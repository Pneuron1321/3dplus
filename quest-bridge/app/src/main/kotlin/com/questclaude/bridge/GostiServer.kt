package com.questclaude.bridge

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import com.questclaude.bridge.Json.bool
import com.questclaude.bridge.Json.int
import com.questclaude.bridge.Json.num
import com.questclaude.bridge.Json.str

/**
 * Сервер гостей эфира (2.5.0): страница гостя (файлы из assets/gosti) и WebSocket /ws на
 * 127.0.0.1:[port]. Снаружи к нему ведёт туннель Cloudflare (cloudflared в Ubuntu, `gosti start`),
 * в Wi-Fi порт не открыт. По одному соединению на участника: управление — текстом (JSON), звук
 * (Opus 20 мс) и картинка (H.264 / JPEG) — двоичными кадрами:
 *   гость → шлем: [1][seq:2] Opus · [2][флаги:1][мс:4] H.264 Annex B · [3][мс:4] JPEG · [4][флаги:1][мс:4] VP8
 *   шлем → гость: [11][seq:2] Opus (его смесь) · [12][флаги:1][мс:4] H.264 — картинка эфира
 * Отправка — через очередь на соединение: звук и управление всегда, картинка — пока очередь не
 * длинная; отстали — кадры до ключевого выбрасываются (и просим ключевой). Обычная JVM — GostiServerTest.
 */
class GostiServer(
    private val port: Int,
    private val komnata: Komnata,
    private val fayl: (String) -> ByteArray?,
    private val media: Media = object : Media {},
    /** Сколько ждать сообщения со входом (ключом), мс; в тестах — меньше. */
    private val bezVhodaMs: Long = BEZ_VHODA_MS,
) {
    /** Звук и картинка гостей — движку (GostiZvuk, GostiVideo); в тестах — подделка. */
    interface Media {
        fun zvuk(u: Komnata.Uchastnik, seq: Int, d: ByteArray, off: Int, len: Int) {}
        /** kodek: 'h' — H.264 Annex B, 'v' — VP8, 'j' — JPEG. */
        fun video(u: Komnata.Uchastnik, key: Boolean, ms: Long, d: ByteArray, off: Int, len: Int, kodek: Char) {}
        /** Кому-то из гостей нужен ключевой кадр картинки эфира. */
        fun klyuchevoyEfira() {}
        /** Участник ушёл (соединение закрыто) — освободить его декодеры. */
        fun ushel(u: Komnata.Uchastnik) {}
    }

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var priyom: Thread? = null
    private val soedineniy = AtomicInteger()
    /** Принято байт от гостей и отправлено им — для /gosti. */
    val prinyato = AtomicLong()
    val otpravleno = AtomicLong()
    @Volatile var vybrosheno = 0L
        private set

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(FrameServer.LOOPBACK, port))
        socket = s
        priyom = thread(name = "gosti-server", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: SocketException) { break }
                if (soedineniy.incrementAndGet() > MAKS_SOEDINENIY) {
                    soedineniy.decrementAndGet()
                    try { c.close() } catch (e: IOException) { }
                    continue
                }
                thread(name = "gost", isDaemon = true) {
                    try { obsluzhit(c) } catch (e: Exception) { } finally {
                        soedineniy.decrementAndGet()
                        try { c.close() } catch (e: IOException) { }
                    }
                }
            }
        }
    }

    fun stop() {
        komnata.zakryt()
        socket?.close()
        priyom?.join(2000)
        socket = null
        priyom = null
    }

    val port0: Int get() = socket?.localPort ?: 0

    // ---------- HTTP ----------

    private fun obsluzhit(c: Socket) {
        c.soTimeout = 10_000
        c.tcpNoDelay = true
        val inp = c.getInputStream()
        val zagolovki = chitatZagolovki(inp) ?: return
        val stroka = zagolovki.first()
        val chasti = stroka.split(' ')
        if (chasti.size < 3) return
        val metod = chasti[0]
        val put = chasti[1].substringBefore('?')
        val h = HashMap<String, String>()
        for (l in zagolovki.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val out = c.getOutputStream()
        if (put == "/ws" && h["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
            val kl = h["sec-websocket-key"]
            if (metod != "GET" || kl == null || !istochnikSvoy(h["origin"], h["host"])) {
                otvet(out, 403, "text/plain", "forbidden".toByteArray()); return
            }
            out.write(Ws.rukopozhatie(kl))
            out.flush()
            Soedinenie(c, inp, out).zhit()
            return
        }
        if (metod != "GET" && metod != "HEAD") { otvet(out, 405, "text/plain", "GET only".toByteArray()); return }
        val imya = if (put == "/" || put == "") "index.html" else put.removePrefix("/")
        val telo = if (IMYA_FAYLA.matches(imya)) fayl(imya) else null
        if (telo == null) { otvet(out, 404, "text/plain", "not found".toByteArray()); return }
        otvet(out, 200, tip(imya), if (metod == "HEAD") ByteArray(0) else telo, dlina = telo.size)
    }

    /** Заголовки до пустой строки; длиннее 16 КБ — нет. */
    private fun chitatZagolovki(inp: InputStream): List<String>? {
        val stroki = ArrayList<String>()
        val b = StringBuilder()
        var vsego = 0
        while (true) {
            val x = inp.read()
            if (x < 0) return null
            if (++vsego > 16_384) return null
            if (x == '\n'.code) {
                val l = b.toString().trimEnd('\r')
                b.setLength(0)
                if (l.isEmpty()) return if (stroki.isEmpty()) null else stroki
                stroki += l
            } else b.append(x.toChar())
        }
    }

    private fun otvet(out: OutputStream, kod: Int, tip: String, telo: ByteArray, dlina: Int = telo.size) {
        val prichina = when (kod) { 200 -> "OK"; 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" }
        val kesh = if (kod == 200 && tip.startsWith("application/wasm")) "public, max-age=86400" else "no-store"
        out.write(("HTTP/1.1 $kod $prichina\r\nContent-Type: $tip\r\nContent-Length: $dlina\r\nCache-Control: $kesh\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n" +
            "Permissions-Policy: camera=(self), microphone=(self)\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self' wss: ws:; " +
            "img-src 'self' blob: data:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; frame-ancestors 'none'\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(telo)
        out.flush()
    }

    // ---------- соединение участника ----------

    private class Posylka(val op: Int, val d: ByteArray, val vid: Char, val key: Boolean = false)

    private inner class Soedinenie(val c: Socket, val inp: InputStream, out0: OutputStream) : Komnata.Svyaz {
        private val out = BufferedOutputStream(out0, 65536)
        private val ochered = LinkedBlockingDeque<Posylka>()
        private val vOcheredi = AtomicLong()
        @Volatile private var zhivo = true
        @Volatile private var prichina: String? = null
        @Volatile private var zhdatKlyuchevoy = false
        @Volatile private var poslednyayaZhizn = System.currentTimeMillis()
        private var sam = false
        private var u: Komnata.Uchastnik? = null
        private var korzina = MAKS_BAYT_SEK.toDouble()
        private var korzinaMs = System.currentTimeMillis()

        fun zhit() {
            val pisatel = thread(name = "gost-pishet", isDaemon = true) { pisat() }
            try {
                c.soTimeout = 1000
                val ch = Ws.Chitatel(inp, MAKS_SOOBSHCHENIE)
                val nachalo = System.currentTimeMillis()
                while (zhivo) {
                    val m = try { ch.sleduyushee() } catch (e: java.net.SocketTimeoutException) {
                        val now = System.currentTimeMillis()
                        if (u == null && now - nachalo > bezVhodaMs) break           // ключа нет — до свидания
                        if (now - poslednyayaZhizn > TISHINA_MS) break
                        continue
                    } ?: break
                    poslednyayaZhizn = System.currentTimeMillis()
                    if (!schitat(m.dannye.size)) { zakryt("bystro"); break }
                    when (m.op) {
                        Ws.TEKST -> if (tekstPrishel(m.tekst)) { sam = true; break }
                        Ws.DVOICHNYY -> dvoichnoe(m.dannye)
                        Ws.PING -> polozhit(Posylka(Ws.PONG, m.dannye, 'k'))
                        Ws.ZAKRYT -> break
                    }
                }
            } catch (e: IOException) {
            } finally {
                zhivo = false
                u?.let { komnata.uhod(it, sam); media.ushel(it) }
                ochered.offer(Posylka(-1, ByteArray(0), 'k'))            // после «proch» и закрытия, если они есть
                pisatel.join(2000)
            }
        }

        /** Не больше MAKS_БАЙТ в секунду в среднем (корзина на 2 секунды). */
        private fun schitat(n: Int): Boolean {
            val now = System.currentTimeMillis()
            korzina = minOf(2.0 * MAKS_BAYT_SEK, korzina + (now - korzinaMs) * MAKS_BAYT_SEK / 1000.0)
            korzinaMs = now
            korzina -= n
            prinyato.addAndGet(n.toLong())
            return korzina > -MAKS_BAYT_SEK
        }

        /** true — участник сам уходит («uhozhu»): возврата по жетону не будет. */
        private fun tekstPrishel(s: String): Boolean {
            val m = Json.obekt(s) ?: return false
            val t = m.str("t") ?: return false
            val ya = u
            if (ya == null) {
                if (t != "vhod") return false
                u = komnata.vhod(this, m.str("k") ?: "", m.str("imya"), m.bool("kamera") ?: false,
                    m.bool("mikrofon") ?: false, m.bool("h264") ?: false, m.str("zheton"))
                return false
            }
            when (t) {
                "uhozhu" -> return true
                "sam" -> komnata.sam(ya, m.bool("kamera"), m.bool("mikrofon"), m.num("igra"), m.bool("prevyu"))
                "upr" -> tekst("{\"t\":\"upr_otvet\",\"chto\":${Komnata.js(m.str("chto"))},\"otvet\":" +
                    komnata.upr(ya, m.str("chto") ?: "", m.int("id"), m.str("dop")) + "}")
                "ping" -> tekst("{\"t\":\"pong\",\"c\":${m.num("c")?.toLong() ?: 0},\"s\":${System.currentTimeMillis()}}")
                "kluch" -> if (ya.vnutri) media.klyuchevoyEfira()
            }
            return false
        }

        private fun dvoichnoe(d: ByteArray) {
            val ya = u ?: return
            if (!ya.vnutri || ya.rol == "hozyain" || ya.cvet < 0 || d.isEmpty()) return
            when (d[0].toInt()) {
                1 -> if (d.size > 3 && ya.mikrofon && !ya.zaglushen)
                    media.zvuk(ya, ((d[1].toInt() and 0xff) shl 8) or (d[2].toInt() and 0xff), d, 3, d.size - 3)
                2 -> if (d.size > 6 && ya.sVideo) media.video(ya, d[1].toInt() and 1 != 0, chislo(d, 2), d, 6, d.size - 6, 'h')
                3 -> if (d.size > 5 && ya.sVideo) media.video(ya, true, chislo(d, 1), d, 5, d.size - 5, 'j')
                4 -> if (d.size > 6 && ya.sVideo) media.video(ya, d[1].toInt() and 1 != 0, chislo(d, 2), d, 6, d.size - 6, 'v')
            }
        }

        // ---------- Komnata.Svyaz ----------

        override fun tekst(json: String) = polozhit(Posylka(Ws.TEKST, json.toByteArray(Charsets.UTF_8), 'k'))

        override fun dvoichnoe(d: ByteArray, vid: Char, key: Boolean) {
            if (!zhivo) return
            if (vid == 'v') {
                if (zhdatKlyuchevoy && !key) { vybrosheno++; return }
                if (vOcheredi.get() > MAKS_OCHERED_VIDEO) {
                    zhdatKlyuchevoy = true; vybrosheno++
                    media.klyuchevoyEfira()
                    return
                }
                zhdatKlyuchevoy = false
            } else if (vid == 'a' && vOcheredi.get() > MAKS_OCHERED) {
                vybrosheno++; return                                   // совсем отстали — звук тоже мимо
            }
            polozhit(Posylka(Ws.DVOICHNYY, d, vid, key))
        }

        override fun zakryt(prichina: String) {
            if (!zhivo) return
            this.prichina = prichina
            tekst("{\"t\":\"proch\",\"prichina\":${FrameServer.json(prichina)}}")
            polozhit(Posylka(Ws.ZAKRYT, byteArrayOf(0x03, 0xe8.toByte()), 'k'))
            zhivo = false
        }

        private fun polozhit(p: Posylka) {
            vOcheredi.addAndGet(p.d.size.toLong())
            ochered.offer(p)
        }

        private fun pisat() {
            var pingMs = System.currentTimeMillis()
            try {
                while (true) {
                    val p = ochered.poll(1, TimeUnit.SECONDS)
                    val now = System.currentTimeMillis()
                    if (p == null) {
                        if (!zhivo && prichina == null) break
                        if (now - pingMs > PING_MS) { Ws.zapisat(out, Ws.PING, ByteArray(0)); out.flush(); pingMs = now }
                        continue
                    }
                    if (p.op < 0) break
                    vOcheredi.addAndGet(-p.d.size.toLong())
                    Ws.zapisat(out, p.op, p.d)
                    otpravleno.addAndGet(p.d.size.toLong())
                    if (ochered.isEmpty()) out.flush()
                    if (p.op == Ws.ZAKRYT) { out.flush(); break }
                }
            } catch (e: IOException) {
            } finally {
                zhivo = false
                try { c.close() } catch (e: IOException) { }
            }
        }
    }

    companion object {
        const val PORT = 8771
        const val MAKS_SOEDINENIY = 10
        const val MAKS_SOOBSHCHENIE = 512 * 1024
        /** Больше — гость шлёт слишком много (видео 360×360 — ≈40 КБ/с, звук — 6 КБ/с). */
        const val MAKS_BAYT_SEK = 400_000
        const val BEZ_VHODA_MS = 10_000L
        const val TISHINA_MS = 30_000L
        const val PING_MS = 10_000L
        /** Картинке — пока в очереди меньше 256 КБ (≈3 с картинки эфира); всему — до 1 МБ. */
        const val MAKS_OCHERED_VIDEO = 256 * 1024L
        const val MAKS_OCHERED = 1024 * 1024L
        private val IMYA_FAYLA = Regex("[a-z0-9][a-z0-9.-]{0,40}\\.(html|js|wasm|css|png|svg)")

        fun tip(imya: String) = when (imya.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "wasm" -> "application/wasm"
            "css" -> "text/css; charset=utf-8"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        /** WebSocket — только со страницы того же адреса (Origin = https://Host); без Origin (не браузер) — можно. */
        fun istochnikSvoy(origin: String?, host: String?): Boolean {
            if (origin == null) return true
            if (host == null) return false
            val o = origin.substringAfter("://", "")
            return o.equals(host, ignoreCase = true)
        }

        fun chislo(d: ByteArray, i: Int): Long =
            ((d[i].toLong() and 0xff) shl 24) or ((d[i + 1].toLong() and 0xff) shl 16) or
                ((d[i + 2].toLong() and 0xff) shl 8) or (d[i + 3].toLong() and 0xff)

        /** Двоичный кадр звука шлем → гость: [11][seq:2] Opus. */
        fun kadrZvuka(seq: Int, opus: ByteArray, off: Int, len: Int): ByteArray {
            val d = ByteArray(3 + len)
            d[0] = 11; d[1] = (seq shr 8).toByte(); d[2] = seq.toByte()
            System.arraycopy(opus, off, d, 3, len)
            return d
        }

        /** Двоичный кадр картинки эфира шлем → гость: [12][флаги][мс:4] H.264 Annex B. */
        fun kadrVideo(key: Boolean, ms: Long, h264: ByteArray): ByteArray {
            val d = ByteArray(6 + h264.size)
            d[0] = 12; d[1] = if (key) 1 else 0
            for (j in 0 until 4) d[2 + j] = (ms shr (8 * (3 - j))).toByte()
            System.arraycopy(h264, 0, d, 6, h264.size)
            return d
        }
    }
}
