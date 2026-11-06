package com.questclaude.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Приём камеры — свой маленький сервер RTMP в шлеме. Телефон (Moblin или Larix на iPhone) шлёт
 * видео по домашней Wi-Fi на rtmp://<шлем>:1935/kamera/<ключ>; сервер проверяет ключ и отдаёт
 * кадры H.264 (Annex-B, время показа с учётом B-кадров) приёмнику — декодеру в Kamera.
 * Звук телефона не нужен (голос — из ушей шлема), он отбрасывается. Обычная JVM — проверяется
 * с настоящим ffmpeg (RtmpPriyomTest). Ключ — секрет: в состояние и ошибки не попадает.
 * Камера одна: новая с верным ключом сменяет прежнюю (телефон переподключился после обрыва Wi-Fi).
 */
class RtmpPriyom(
    private val port: Int,
    private val klyuch: () -> String,
    private val priyomnik: Priyomnik,
    private val bind: InetAddress? = null,     // null — все сети шлема (Wi-Fi); в тестах — 127.0.0.1
) : Closeable {
    interface Priyomnik {
        /** Заголовок видео (камера подключилась или сменила размер): перезапустить декодер. */
        fun nachalo(sps: ByteArray, pps: ByteArray, w: Int, h: Int)
        /** Кадр Annex-B; ptsUs — время показа; key — ключевой. Первый после nachalo — всегда ключевой. */
        fun kadr(annexB: ByteArray, ptsUs: Long, key: Boolean)
        /** Камера отключилась. */
        fun konec()
        /** Звук телефона (2.5.9, для клипа): заголовок AAC (AudioSpecificConfig) и кадры AAC без обёртки. */
        fun zvukNachalo(asc: ByteArray) {}
        fun zvuk(aac: ByteArray, ptsUs: Long) {}
    }

    /** vykl — сервер не слушает; zhdu — ждёт камеру; idyot — камера шлёт видео. */
    @Volatile var state = "vykl"
        private set
    /** Адрес телефона (в домашней сети). */
    @Volatile var ot: String? = null
        private set
    @Volatile var shirina = 0
        private set
    @Volatile var vysota = 0
        private set
    @Volatile var fps = 0
        private set
    @Volatile var kbps = 0
        private set
    @Volatile var kadrov = 0L
        private set
    @Volatile var podklyucheniy = 0
        private set
    /** Последняя беда (без ключа): чужой ключ, не H.264, обрыв. */
    @Volatile var oshibka: String? = null
        private set
    /** Рукопожатие последнего клиента: 0 — простое, 1 или 2 — с подписью (для проверки). */
    @Volatile var skhemaKlienta = -1
        private set
    /** Порт открыт и слушает. */
    @Volatile var slushaet = false
        private set
    /** Сколько раз в порт стучались (телефон, Safari — кто угодно): «стучались ли вообще». */
    @Volatile var popytok = 0
        private set
    /** Сколько раз порт пришлось открывать заново (система закрыла или был занят). */
    @Volatile var perezapuskov = 0
        private set

    private var server: ServerSocket? = null
    private val lock = Any()
    @Volatile private var tekushchaya: Sessiya? = null
    private val sessii = mutableSetOf<Sessiya>()
    @Volatile private var zakryto = false
    @Volatile private var potok: Thread? = null

    /**
     * Открыть порт и слушать. Порт занят — ошибка в oshibka, новые попытки в фоне. Android сам
     * рвёт сокеты приложения (шлем заснул, Wi-Fi переподключился) — так 08.10 приёмник «ждал»,
     * а порт был закрыт; теперь accept падает — и порт открывается заново через секунду.
     */
    fun start() {
        zakryto = false
        val s = try { otkryt() } catch (e: IOException) { oshibka = "порт $port не открылся: ${e.message}"; null }
        potok = thread(name = "kamera-priyom", isDaemon = true) { slushat(s) }
    }

    private fun otkryt(): ServerSocket {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.receiveBufferSize = 512 * 1024
            s.bind(InetSocketAddress(bind, port))
        } catch (e: IOException) {
            try { s.close() } catch (e2: IOException) { }
            throw e
        }
        synchronized(lock) {
            server = s
            slushaet = true
            if (tekushchaya == null) state = "zhdu"
        }
        return s
    }

    private fun slushat(pervyy: ServerSocket?) {
        var s = pervyy
        var pauza = PAUZA_MS
        while (!zakryto) {
            if (s == null) {
                try { Thread.sleep(pauza) } catch (e: InterruptedException) { break }
                if (zakryto) break
                s = try {
                    otkryt().also { perezapuskov++; oshibka = null }
                } catch (e: IOException) {
                    oshibka = "порт $port не открылся: ${e.message}"
                    pauza = minOf(pauza * 2, PAUZA_MAKS_MS)
                    null
                }
                continue
            }
            pauza = PAUZA_MS
            val c = try { s.accept() } catch (e: IOException) {
                if (zakryto) break
                synchronized(lock) {
                    if (server === s) server = null
                    slushaet = false
                    if (tekushchaya == null) state = "vykl"
                }
                try { s.close() } catch (e2: IOException) { }
                oshibka = "порт $port закрыла система (${e.message ?: e.javaClass.simpleName}) — открываю заново"
                s = null
                continue
            }
            popytok++
            val ses = synchronized(lock) {
                if (sessii.size >= MAKS_SESSIY) null else Sessiya(c).also { sessii += it }
            }
            if (ses == null) { try { c.close() } catch (e: IOException) { } }
            else thread(name = "kamera-sessiya", isDaemon = true) { ses.run() }
        }
        try { s?.close() } catch (e: IOException) { }      // close() пришёл, пока порт открывался заново
    }

    /** Для проверки на столе: закрыть порт из-под приёмника — как это делает система. */
    internal fun sistemaZakryla() {
        val s = synchronized(lock) { server }
        try { s?.close() } catch (e: IOException) { }
    }

    /** Декодер потерял кадр или только что встал: до следующего ключевого кадры не нужны. */
    fun zhdatKlyuchevoy() {
        tekushchaya?.zhduKlyuchevoy = true
    }

    override fun close() {
        zakryto = true
        val s = synchronized(lock) { server.also { server = null; slushaet = false } }
        try { s?.close() } catch (e: IOException) { }
        potok?.takeIf { it !== Thread.currentThread() }?.let { it.interrupt(); it.join(1000) }   // порт свободен — поток проснулся
        potok = null
        val vse = synchronized(lock) { sessii.toList() }
        vse.forEach { it.zakryt() }
        state = "vykl"
    }

    private inner class Sessiya(val sock: Socket) {
        private val counter = Counter(sock.getInputStream())
        private val inp = DataInputStream(BufferedInputStream(counter, 64 * 1024))
        private val out = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
        private val wlock = Any()
        private val ip = sock.inetAddress?.hostAddress ?: "?"
        private var inChunk = 128
        private var outChunk = 128
        private var ackWindow = 2_500_000L
        private var lastAck = 0L
        private var app = ""
        private var cfg: Flv.AvcConfig? = null
        @Volatile var zhduKlyuchevoy = true
        private var opublikovana = false     // была камерой (её могла сменить новая — это не беда)
        private var otkaz = false            // отказали сами (чужой ключ) — причина уже в oshibka
        private var oknoMs = System.currentTimeMillis()
        private var oknoKadrov = 0
        private var oknoBait = 0L

        fun run() {
            try {
                sock.soTimeout = TIMEOUT_MS
                sock.tcpNoDelay = true
                rukopozhatie()
                chitat()
            } catch (e: Exception) {
                val chto = e.message ?: e.javaClass.simpleName
                if (tekushchaya === this) oshibka = "камера отключилась ($chto)"
                else if (!opublikovana && !otkaz) oshibka = "с $ip подключились, но видео не пошло ($chto)"
            } finally {
                zakryt()
            }
        }

        fun zakryt() {
            try { sock.close() } catch (e: IOException) { }
            val bylaTekushchey = synchronized(lock) {
                sessii -= this
                if (tekushchaya === this) {
                    tekushchaya = null
                    state = if (server?.isClosed == false) "zhdu" else "vykl"
                    ot = null; fps = 0; kbps = 0
                    true
                } else false
            }
            if (bylaTekushchey) priyomnik.konec()
        }

        // ---------- рукопожатие ----------

        private fun rukopozhatie() {
            val v = inp.readUnsignedByte()
            if (v != 3) throw IOException("не RTMP (первый байт $v)")
            val c1 = ByteArray(SIG)
            inp.readFully(c1)
            skhemaKlienta = Rukopozhatie.skhema(c1)
            out.write(3)
            out.write(Rukopozhatie.otvet(c1))
            out.flush()
            inp.readFully(ByteArray(SIG))                       // C2
        }

        // ---------- куски и сообщения ----------

        private inner class Cs {
            var ts = 0L; var tsField = 0L; var len = 0; var type = 0; var sid = 0; var ext = false
            var buf: ByteArrayOutputStream? = null
        }

        private fun chitat() {
            val cs = HashMap<Int, Cs>()
            while (true) {
                val b0 = inp.readUnsignedByte()
                val fmt = b0 ushr 6
                var id = b0 and 0x3f
                if (id == 0) id = 64 + inp.readUnsignedByte()
                else if (id == 1) id = 64 + inp.readUnsignedByte() + inp.readUnsignedByte() * 256
                val c = cs.getOrPut(id) { Cs() }
                val novoe = c.buf == null
                if (fmt <= 2) {
                    var t = u24().toLong()
                    if (fmt <= 1) { c.len = u24(); c.type = inp.readUnsignedByte() }
                    if (fmt == 0) c.sid = Integer.reverseBytes(inp.readInt())
                    c.ext = t == 0xffffffL
                    if (c.ext) t = inp.readInt().toLong() and 0xffffffffL
                    c.tsField = t
                    if (fmt == 0) c.ts = t else c.ts += t           // fmt 1, 2 — разница с прошлым
                } else {
                    if (c.ext) inp.readInt()
                    if (novoe) c.ts += c.tsField                    // fmt 3 на новое сообщение — та же разница (как в ffmpeg)
                }
                if (c.len > MAKS_SOOBSHCHENIE) throw IOException("сообщение ${c.len} байт — слишком большое")
                val b = c.buf ?: ByteArrayOutputStream(maxOf(c.len, 16)).also { c.buf = it }
                val n = minOf(inChunk, c.len - b.size())
                if (n > 0) {
                    val chunk = ByteArray(n)
                    inp.readFully(chunk)
                    b.write(chunk)
                }
                if (b.size() >= c.len) {
                    c.buf = null
                    soobshchenie(c.type, c.ts, b.toByteArray())
                }
                if (counter.count - lastAck >= ackWindow) {      // подтверждение полученного
                    lastAck = counter.count
                    send(2, 3, 0, int32(counter.count))
                }
            }
        }

        private fun soobshchenie(type: Int, ts: Long, m: ByteArray) {
            when (type) {
                1 -> if (m.size >= 4) inChunk = (int(m, 0) and 0x7fffffff).coerceIn(1, 1 shl 24)
                5 -> if (m.size >= 4) ackWindow = (int(m, 0).toLong() and 0xffffffffL).coerceAtLeast(4096)
                20 -> komanda(Flv.amf0(m))
                17 -> if (m.isNotEmpty()) komanda(Flv.amf0(m.copyOfRange(1, m.size)))   // AMF3-обёртка над AMF0
                18 -> meta(Flv.amf0(m))
                9 -> if (tekushchaya === this) video(m, ts)
                8 -> if (tekushchaya === this) zvuk(m, ts)
            }
        }

        private fun komanda(a: List<Any?>) {
            val imya = a.getOrNull(0) as? String ?: return
            val txn = a.getOrNull(1) as? Double ?: 0.0
            when (imya) {
                "connect" -> {
                    app = ((a.getOrNull(2) as? Map<*, *>)?.get("app") as? String).orEmpty()
                    send(2, 5, 0, int32(2_500_000))                                 // окно подтверждений
                    send(2, 6, 0, int32(2_500_000) + byteArrayOf(2))                // ширина канала
                    send(2, 1, 0, int32(OUT_CHUNK.toLong()))
                    outChunk = OUT_CHUNK
                    send(3, 20, 0, Flv.Amf0().str("_result").num(txn)
                        .obj(linkedMapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31))
                        .obj(linkedMapOf("level" to "status", "code" to "NetConnection.Connect.Success",
                            "description" to "Connection succeeded.", "objectEncoding" to 0)).bytes())
                }
                "createStream" -> send(3, 20, 0, Flv.Amf0().str("_result").num(txn).nul().num(1.0).bytes())
                "publish" -> publish(a.getOrNull(3) as? String ?: "")
                "FCUnpublish", "deleteStream", "closeStream" -> if (tekushchaya === this) throw IOException("камера закончила")
                else -> if (txn > 0) send(3, 20, 0, Flv.Amf0().str("_result").num(txn).nul().bytes())   // releaseStream, FCPublish
            }
        }

        private fun publish(imya: String) {
            val k = klyuch()
            // Larix и ffmpeg кладут ключ последним кусочком адреса — либо в имя потока, либо в приложение
            val kandidaty = listOf(imya.substringBefore('?'), app.substringBefore('?').substringAfterLast('/'))
            val verno = k.length >= 6 && kandidaty.any { MessageDigest.isEqual(it.toByteArray(), k.toByteArray()) }
            if (!verno) {
                otkaz = true
                oshibka = "камера с $ip: неверный ключ"
                send(5, 20, 1, Flv.Amf0().str("onStatus").num(0.0).nul().obj(linkedMapOf(
                    "level" to "error", "code" to "NetStream.Publish.BadName", "description" to "wrong key")).bytes())
                Thread.sleep(1000)                                 // подбирать ключ — медленно
                throw IOException("неверный ключ")
            }
            val prezhnyaya = synchronized(lock) {
                val p = tekushchaya
                tekushchaya = this
                state = "idyot"; ot = ip; oshibka = null; podklyucheniy++
                opublikovana = true
                p
            }
            prezhnyaya?.let { thread(isDaemon = true) { it.zakrytTiho() } }
            send(2, 4, 0, byteArrayOf(0, 0, 0, 0, 0, 1))          // StreamBegin, поток 1
            send(5, 20, 1, Flv.Amf0().str("onStatus").num(0.0).nul().obj(linkedMapOf(
                "level" to "status", "code" to "NetStream.Publish.Start", "description" to "Start publishing")).bytes())
        }

        /** Сменили на новую — закрыть без «камера отключилась». */
        fun zakrytTiho() {
            try { sock.close() } catch (e: IOException) { }
        }

        private fun meta(a: List<Any?>) {
            val m = a.firstOrNull { it is Map<*, *> } as? Map<*, *> ?: return
            (m["width"] as? Double)?.let { shirina = it.toInt() }
            (m["height"] as? Double)?.let { vysota = it.toInt() }
        }

        /** Звук FLV (тип 8): только AAC (формат 10) — заголовок и кадры; другой звук клипу не нужен. */
        private fun zvuk(m: ByteArray, ts: Long) {
            if (m.size < 3 || (m[0].toInt() and 0xff) ushr 4 != 10) return
            val d = m.copyOfRange(2, m.size)
            if (m[1].toInt() == 0) priyomnik.zvukNachalo(d) else priyomnik.zvuk(d, ts * 1000)
        }

        private fun video(m: ByteArray, ts: Long) {
            if (m.size < 5) return
            val b0 = m[0].toInt() and 0xff
            if (b0 and 0x80 != 0) { oshibka = "камера шлёт не H.264 (HEVC?) — в Moblin/Larix: Video → Codec → H.264"; return }
            val tip = b0 ushr 4
            if (b0 and 0x0f != 7) { oshibka = "камера шлёт не H.264 — в Moblin/Larix: Video → Codec → H.264"; return }
            if (tip == 5) return                                   // служебный кадр
            when (m[1].toInt()) {
                0 -> {
                    val c = Flv.avcConfigParse(m, 5) ?: return
                    val prezhniy = cfg
                    cfg = c
                    if (prezhniy == null || !prezhniy.sps.contentEquals(c.sps) || !prezhniy.pps.contentEquals(c.pps)) {
                        zhduKlyuchevoy = true
                        priyomnik.nachalo(c.sps, c.pps, shirina, vysota)
                    }
                }
                1 -> {
                    val c = cfg ?: return
                    var cts = ((m[2].toInt() and 0xff) shl 16) or ((m[3].toInt() and 0xff) shl 8) or (m[4].toInt() and 0xff)
                    if (cts and 0x800000 != 0) cts = cts or 0xff000000.toInt()
                    val a = Flv.annexB(m, 5, c.lenSize)
                    if (a.isEmpty()) return
                    val key = tip == 1 || (zhduKlyuchevoy && Flv.nals(a).any { Flv.nalType(a, it.first) == 5 })
                    if (zhduKlyuchevoy && !key) return
                    zhduKlyuchevoy = false
                    kadrov++
                    oknoKadrov++
                    priyomnik.kadr(a, (ts + cts) * 1000, key)
                }
            }
            val now = System.currentTimeMillis()
            if (now - oknoMs >= 1000) {
                fps = (oknoKadrov * 1000 / (now - oknoMs)).toInt()
                kbps = ((counter.count - oknoBait) * 8 / (now - oknoMs)).toInt()
                oknoMs = now; oknoKadrov = 0; oknoBait = counter.count
            }
        }

        private fun send(csid: Int, type: Int, sid: Int, p: ByteArray) {
            synchronized(wlock) {
                val h = ByteArray(12)
                h[0] = csid.toByte()
                h[4] = (p.size ushr 16).toByte(); h[5] = (p.size ushr 8).toByte(); h[6] = p.size.toByte()
                h[7] = type.toByte()
                h[8] = sid.toByte(); h[9] = (sid ushr 8).toByte(); h[10] = (sid ushr 16).toByte(); h[11] = (sid ushr 24).toByte()
                out.write(h)
                var off = 0
                while (off < p.size) {
                    val len = minOf(outChunk, p.size - off)
                    if (off > 0) out.write(0xc0 or csid)
                    out.write(p, off, len)
                    off += len
                }
                out.flush()
            }
        }

        private fun u24(): Int = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
    }

    private class Counter(i: InputStream) : FilterInputStream(i) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Ответ на рукопожатие (S1 и S2). Простой клиент (C1 без подписи) получает эхо; клиент «как Flash»
     * (подпись HMAC в C1 — так делают ffmpeg и многие мобильные) — подписанные S1 и S2: иные такие
     * клиенты проверяют подпись сервера и без неё рвут связь. Схема — как у nginx-rtmp и node-media-server.
     */
    object Rukopozhatie {
        private val CRUD = intArrayOf(0xf0, 0xee, 0xc2, 0x4a, 0x80, 0x68, 0xbe, 0xe8, 0x2e, 0x00, 0xd0, 0xd1, 0x02, 0x9e,
            0x7e, 0x57, 0x6e, 0xec, 0x5d, 0x2d, 0x29, 0x80, 0x6f, 0xab, 0x93, 0xb8, 0xe6, 0x36, 0xcf, 0xeb, 0x31, 0xae)
            .map { it.toByte() }.toByteArray()
        private val FP = "Genuine Adobe Flash Player 001".toByteArray()
        private val FMS = "Genuine Adobe Flash Media Server 001".toByteArray()
        private val FMS_CRUD = FMS + CRUD
        private val rnd = SecureRandom()

        fun hmac(data: ByteArray, key: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** Где подпись: схема 1 — смещение из байт 8..11, схема 2 — из 772..775. */
        fun mesto(c: ByteArray, skhema: Int): Int {
            val at = if (skhema == 1) 8 else 772
            val s = (0 until 4).sumOf { c[at + it].toInt() and 0xff }
            return s % 728 + (if (skhema == 1) 12 else 776)
        }

        private fun bezPodpisi(c: ByteArray, at: Int) = c.copyOfRange(0, at) + c.copyOfRange(at + 32, c.size)

        /** 0 — простой клиент; 1 или 2 — схема подписи клиента. */
        fun skhema(c1: ByteArray): Int {
            for (s in intArrayOf(2, 1)) {
                val at = mesto(c1, s)
                if (MessageDigest.isEqual(hmac(bezPodpisi(c1, at), FP), c1.copyOfRange(at, at + 32))) return s
            }
            return 0
        }

        fun otvet(c1: ByteArray): ByteArray {
            val s = skhema(c1)
            if (s == 0) return c1 + c1
            val s1 = ByteArray(SIG).also { rnd.nextBytes(it) }
            byteArrayOf(0, 0, 0, 0, 1, 2, 3, 4).copyInto(s1)
            val at = mesto(s1, s)
            hmac(bezPodpisi(s1, at), FMS).copyInto(s1, at)
            val ca = mesto(c1, s)
            val sluch = ByteArray(SIG - 32).also { rnd.nextBytes(it) }
            val s2 = sluch + hmac(sluch, hmac(c1.copyOfRange(ca, ca + 32), FMS_CRUD))
            return s1 + s2
        }
    }

    companion object {
        const val PORT = 1935
        const val SIG = 1536
        const val OUT_CHUNK = 4096
        const val TIMEOUT_MS = 10_000
        const val MAKS_SESSIY = 4
        const val MAKS_SOOBSHCHENIE = 4_000_000
        const val PAUZA_MS = 1000L
        const val PAUZA_MAKS_MS = 10_000L
        private fun int32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun int32(v: Int) = int32(v.toLong())
        private fun int(a: ByteArray, i: Int) = ((a[i].toInt() and 0xff) shl 24) or ((a[i + 1].toInt() and 0xff) shl 16) or
            ((a[i + 2].toInt() and 0xff) shl 8) or (a[i + 3].toInt() and 0xff)
    }
}
