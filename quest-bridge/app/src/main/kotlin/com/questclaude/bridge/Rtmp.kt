package com.questclaude.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Свой отправитель RTMP (публикация эфира на Twitch): рукопожатие, connect, createStream,
 * publish, дальше видео и звук кусками. Без библиотек — обычная JVM, проверяется с настоящим
 * ffmpeg (RtmpTest). Ключ эфира — секрет: в тексты ошибок не попадает (заменяется на ***).
 *
 * Все сообщения — с полным заголовком (fmt 0, своё время): проще и для сервера законно.
 * Отправлять можно с разных потоков — запись под замком.
 */
class Rtmp(private val url: String, private val key: String, private val timeoutMs: Int = 8000) : Closeable, Otpravka.Kanal {
    private var sock: Socket? = null
    private lateinit var out: OutputStream
    private lateinit var inp: DataInputStream
    private val wlock = Any()
    private var inChunk = 128
    private var streamId = 1
    private var ackWindow = 2_500_000L
    private var lastAck = 0L
    private val replies = LinkedBlockingQueue<List<Any?>>()
    private var counter: Counter? = null

    /** Сколько байт ушло на сервер — для скорости в статусе. */
    @Volatile override var sent = 0L
        private set
    @Volatile override var alive = false
        private set
    /** Почему соединение кончилось (без ключа). */
    @Volatile var error: String? = null
        private set

    fun connect() {
        val u = URI(url)
        val tls = u.scheme == "rtmps"
        val host = u.host ?: throw IOException("в адресе нет сервера")
        val port = if (u.port > 0) u.port else if (tls) 443 else 1935
        val app = u.path.trim('/')
        val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        sock = s
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            s.sendBufferSize = 512 * 1024
            out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val c = Counter(s.getInputStream())
            counter = c
            inp = DataInputStream(BufferedInputStream(c))
            handshake()
            s.soTimeout = 0                       // дальше ответы ждём по очереди, не по сокету
            send(2, 1, 0, int32(OUT_CHUNK.toLong()))
            val tcUrl = "${u.scheme}://$host${if (u.port > 0) ":$port" else ""}/$app"
            startReader()
            send(3, 20, 0, Flv.Amf0().str("connect").num(1.0).obj(linkedMapOf(
                "app" to app, "type" to "nonprivate", "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)",
                "swfUrl" to tcUrl, "tcUrl" to tcUrl)).bytes())
            reply(1.0, "connect")
            send(3, 20, 0, Flv.Amf0().str("releaseStream").num(2.0).nul().str(key).bytes())
            send(3, 20, 0, Flv.Amf0().str("FCPublish").num(3.0).nul().str(key).bytes())
            send(3, 20, 0, Flv.Amf0().str("createStream").num(4.0).nul().bytes())
            streamId = (reply(4.0, "createStream").getOrNull(3) as? Double)?.toInt() ?: 1
            send(4, 20, streamId, Flv.Amf0().str("publish").num(5.0).nul().str(key).str("live").bytes())
            publishStatus()
            alive = true
        } catch (e: Exception) {
            close()
            throw IOException(chisto(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Описание потока для зрителей и сервера (onMetaData). */
    fun meta(w: Int, h: Int, fps: Int, videoKbps: Int, audioKbps: Int, rate: Int) =
        send(5, 18, streamId, Flv.Amf0().str("@setDataFrame").str("onMetaData").ecma(linkedMapOf(
            "width" to w, "height" to h, "framerate" to fps, "videocodecid" to 7, "videodatarate" to videoKbps,
            "audiocodecid" to 10, "audiodatarate" to audioKbps, "audiosamplerate" to rate,
            "audiosamplesize" to 16, "stereo" to true, "encoder" to "Quest Claude Bridge 2")).bytes())

    /** Видео (Flv.video) со временем в мс от начала эфира. */
    override fun video(payload: ByteArray, ms: Long) = send(6, 9, streamId, payload, ms)

    /** Звук (Flv.audio) со временем в мс от начала эфира. */
    override fun audio(payload: ByteArray, ms: Long) = send(7, 8, streamId, payload, ms)

    /**
     * Закончить эфир по-настоящему, как «Остановить трансляцию» в OBS: FCUnpublish и deleteStream —
     * Twitch сразу завершает трансляцию, а не держит её «с разрывом связи», ожидая оборвавшийся
     * поток. Потом закрыть соединение.
     */
    override fun zakonchit() {
        if (alive) try {
            send(3, 20, 0, Flv.Amf0().str("FCUnpublish").num(6.0).nul().str(key).bytes())
            send(3, 20, 0, Flv.Amf0().str("deleteStream").num(7.0).nul().num(streamId.toDouble()).bytes())
            Thread.sleep(ZAKONCHIT_MS)                      // сервер успевает прочесть до закрытия сокета
        } catch (e: Exception) { }
        close()
    }

    override fun close() {
        alive = false
        try { sock?.close() } catch (e: IOException) { }
    }

    // ---------- запись ----------

    private fun send(csid: Int, type: Int, sid: Int, p: ByteArray, ms: Long = 0) {
        synchronized(wlock) {
            val ext = ms >= 0xffffff
            val t = if (ext) 0xffffff else ms.toInt()
            val h = ByteArray(12)
            h[0] = csid.toByte()                               // fmt 0, номер потока кусков < 64
            h[1] = (t ushr 16).toByte(); h[2] = (t ushr 8).toByte(); h[3] = t.toByte()
            h[4] = (p.size ushr 16).toByte(); h[5] = (p.size ushr 8).toByte(); h[6] = p.size.toByte()
            h[7] = type.toByte()
            h[8] = sid.toByte(); h[9] = (sid ushr 8).toByte(); h[10] = (sid ushr 16).toByte(); h[11] = (sid ushr 24).toByte()
            out.write(h)
            var n = 12L
            if (ext) { out.write(int32(ms)); n += 4 }
            var off = 0
            while (off < p.size) {
                val len = minOf(OUT_CHUNK, p.size - off)
                if (off > 0) {
                    out.write(0xc0 or csid); n++
                    if (ext) { out.write(int32(ms)); n += 4 }
                }
                out.write(p, off, len)
                off += len
                n += len
            }
            out.flush()
            sent += n
        }
    }

    private fun handshake() {
        val c1 = ByteArray(1536)
        java.util.Random().nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0                         // время 0, нули — простое рукопожатие
        out.write(3); out.write(c1); out.flush()
        val v = inp.readUnsignedByte()
        if (v != 3) throw IOException("сервер ответил не как RTMP (версия $v)")
        val s1 = ByteArray(1536)
        inp.readFully(s1)
        out.write(s1); out.flush()                             // C2 — эхо S1
        inp.readFully(ByteArray(1536))                         // S2
    }

    // ---------- чтение ответов сервера ----------

    private class Cs { var len = 0; var type = 0; var ext = false; var buf: ByteArrayOutputStream? = null }

    private fun startReader() {
        Thread({
            try {
                readLoop()
            } catch (e: Exception) {
                if (error == null) error = if (alive) "сервер закрыл соединение" else chisto(e.message ?: "обрыв")
            } finally {
                alive = false
                replies.offer(listOf(CLOSED))
            }
        }, "rtmp-chtenie").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val cs = HashMap<Int, Cs>()
        while (true) {
            val b0 = inp.readUnsignedByte()
            val fmt = b0 ushr 6
            var id = b0 and 0x3f
            if (id == 0) id = 64 + inp.readUnsignedByte()
            else if (id == 1) id = 64 + inp.readUnsignedByte() + inp.readUnsignedByte() * 256
            val c = cs.getOrPut(id) { Cs() }
            if (fmt <= 2) {
                val ts = u24()
                if (fmt <= 1) { c.len = u24(); c.type = inp.readUnsignedByte() }
                if (fmt == 0) inp.readInt()                   // номер потока сообщений
                c.ext = ts == 0xffffff
                if (c.ext) inp.readInt()
            } else if (c.ext) inp.readInt()
            val b = c.buf ?: ByteArrayOutputStream(maxOf(c.len, 16)).also { c.buf = it }
            val n = minOf(inChunk, c.len - b.size())
            if (n > 0) {
                val chunk = ByteArray(n)
                inp.readFully(chunk)
                b.write(chunk)
            }
            if (b.size() >= c.len) {
                c.buf = null
                handle(c.type, b.toByteArray())
            }
            val got = counter?.count ?: 0
            if (got - lastAck >= ackWindow) {                  // подтверждение полученного
                lastAck = got
                send(2, 3, 0, int32(got))
            }
        }
    }

    private fun handle(type: Int, m: ByteArray) {
        when (type) {
            1 -> if (m.size >= 4) inChunk = (int(m, 0) and 0x7fffffff).coerceIn(1, 1 shl 24)
            5 -> if (m.size >= 4) ackWindow = int(m, 0).toLong() and 0xffffffffL
            6 -> if (m.size >= 4) send(2, 5, 0, m.copyOfRange(0, 4))   // ответ на ширину канала
            4 -> if (m.size >= 6 && m[0].toInt() == 0 && m[1].toInt() == 6)  // пинг сервера
                send(2, 4, 0, byteArrayOf(0, 7, m[2], m[3], m[4], m[5]))
            20 -> replies.offer(Flv.amf0(m))
        }
    }

    /** Ответ сервера на команду с номером txn. */
    private fun reply(txn: Double, what: String): List<Any?> {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val m = replies.poll(maxOf(1L, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                ?: throw IOException("сервер не ответил на $what")
            if (m.firstOrNull() == CLOSED) throw IOException("сервер закрыл соединение на $what")
            if (m.firstOrNull() == "onStatus") oshibka(m)?.let { throw IOException(it) }
            if (m.getOrNull(1) == txn) {
                if (m[0] == "_error") throw IOException("сервер отказал на $what${opisanie(m)}")
                if (m[0] == "_result") return m
            }
        }
    }

    /** onStatus после publish: «Publish.Start» — эфир принят; ошибка — бросаем. Нет ответа 5 с — считаем, что принят. */
    private fun publishStatus() {
        val until = System.currentTimeMillis() + minOf(timeoutMs, 5000)
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return
            val m = replies.poll(left, TimeUnit.MILLISECONDS) ?: return
            if (m.firstOrNull() == CLOSED) throw IOException("сервер закрыл соединение после publish (ключ?)")
            if (m.firstOrNull() == "onStatus") {
                oshibka(m)?.let { throw IOException(it) }
                if ((m.getOrNull(3) as? Map<*, *>)?.get("code") == "NetStream.Publish.Start") return
            }
        }
    }

    private fun oshibka(m: List<Any?>): String? {
        val info = m.getOrNull(3) as? Map<*, *> ?: return null
        val code = info["code"]?.toString().orEmpty()
        return if (info["level"] == "error" || Regex("Fail|BadName|Reject|Denied").containsMatchIn(code))
            "сервер отказал: $code${opisanie(m)}" else null
    }

    private fun opisanie(m: List<Any?>): String {
        val d = (m.getOrNull(3) as? Map<*, *>)?.get("description")?.toString() ?: return ""
        return " — " + chisto(d)
    }

    /** Текст без ключа эфира. */
    private fun chisto(s: String) = if (key.length >= 4) s.replace(key, "***") else s

    private fun u24(): Int = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()

    private class Counter(i: InputStream) : FilterInputStream(i) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        const val OUT_CHUNK = 4096
        /** Сколько ждать после «конец эфира», прежде чем закрыть сокет. */
        const val ZAKONCHIT_MS = 300L
        private const val CLOSED = "\u0000closed"
        private fun int32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun int(a: ByteArray, i: Int) = ((a[i].toInt() and 0xff) shl 24) or ((a[i + 1].toInt() and 0xff) shl 16) or
            ((a[i + 2].toInt() and 0xff) shl 8) or (a[i + 3].toInt() and 0xff)
    }
}
