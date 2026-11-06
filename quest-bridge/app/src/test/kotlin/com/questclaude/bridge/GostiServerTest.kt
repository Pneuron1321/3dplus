package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Сервер гостей на обычной JVM: страница, WebSocket (клиент на сокете — в android.jar нет java.net.http), вход, звук и картинка, выгнать. */
class GostiServerTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val kl = Komnata.Klyuchi.iz(null) { }
    private val komnata = Komnata(kl).apply { adres = "https://proba.trycloudflare.com" }

    private class Zvuk(val id: Int, val seq: Int, val d: ByteArray)
    private class Video(val key: Boolean, val ms: Long, val d: ByteArray, val kodek: Char)
    private val zvuki = LinkedBlockingQueue<Zvuk>()
    private val video = LinkedBlockingQueue<Video>()
    @Volatile private var klyuchevyh = 0
    @Volatile private var ushlo = 0
    private val media = object : GostiServer.Media {
        override fun zvuk(u: Komnata.Uchastnik, seq: Int, d: ByteArray, off: Int, len: Int) { zvuki += Zvuk(u.id, seq, d.copyOfRange(off, off + len)) }
        override fun video(u: Komnata.Uchastnik, key: Boolean, ms: Long, d: ByteArray, off: Int, len: Int, kodek: Char) {
            video += Video(key, ms, d.copyOfRange(off, off + len), kodek)
        }
        override fun klyuchevoyEfira() { klyuchevyh++ }
        override fun ushel(u: Komnata.Uchastnik) { ushlo++ }
    }
    private val fayly = mapOf("index.html" to "<!doctype html>гость".toByteArray(), "opus.wasm" to byteArrayOf(0, 97, 115, 109))
    private val s = GostiServer(port, komnata, { fayly[it] }, media, bezVhodaMs = 1500).also { it.start() }

    @After fun stop() = s.stop()

    private fun get(put: String): Triple<Int, String?, ByteArray> {
        val c = URI("http://127.0.0.1:$port$put").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val code = c.responseCode
        return Triple(code, c.contentType, (if (code < 400) c.inputStream else c.errorStream).readBytes())
    }

    /** Клиент WebSocket на сокете: рукопожатие RFC 6455, кадры сервера (без маски) — в очереди, свои — с маской. */
    private inner class Klient(origin: String? = null) {
        val teksty = LinkedBlockingQueue<String>()
        val dvoichnye = LinkedBlockingQueue<ByteArray>()
        @Volatile var zakryt = false
        private val sok = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        private val out = sok.getOutputStream()
        private val inp = BufferedInputStream(sok.getInputStream())

        init {
            val klyuch = Base64.getEncoder().encodeToString(ByteArray(16) { (it * 7).toByte() })
            out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $klyuch\r\nSec-WebSocket-Version: 13\r\n" +
                (if (origin != null) "Origin: $origin\r\n" else "") + "\r\n").toByteArray())
            out.flush()
            val zag = StringBuilder()
            while (!zag.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) break; zag.append(c.toChar()) }
            if (!zag.startsWith("HTTP/1.1 101")) { sok.close(); throw IOException("нет рукопожатия: " + zag.lineSequence().first()) }
            check(zag.contains(Ws.otvetKlyuch(klyuch))) { "чужой ответ рукопожатия" }
            sok.soTimeout = 0
            thread(isDaemon = true) { chitat() }
        }

        private fun chitat() {
            try {
                val d = DataInputStream(inp)
                while (true) {
                    val b0 = d.readUnsignedByte()
                    val b1 = d.readUnsignedByte()
                    check(b1 and 128 == 0) { "сервер замаскировал кадр" }
                    var n = (b1 and 127).toLong()
                    if (n == 126L) n = d.readUnsignedShort().toLong() else if (n == 127L) n = d.readLong()
                    val p = ByteArray(n.toInt()).also { d.readFully(it) }
                    when (b0 and 15) {
                        Ws.TEKST -> teksty += String(p, Charsets.UTF_8)
                        Ws.DVOICHNYY -> dvoichnye += p
                        Ws.ZAKRYT -> { zakryt = true; return }
                    }
                }
            } catch (e: Exception) { zakryt = true }
        }

        fun zhdat(t: String): String {
            val konec = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < konec) {
                val m = teksty.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (m.contains("\"t\":\"$t\"")) return m
            }
            throw AssertionError("не дождались $t")
        }
        fun tekst(s: String) = synchronized(out) { out.write(Ws.kadrKlienta(Ws.TEKST, s.toByteArray())); out.flush() }
        fun bin(b: ByteArray) = synchronized(out) { out.write(Ws.kadrKlienta(Ws.DVOICHNYY, b)); out.flush() }
    }

    @Test fun stranicaIFayly() {
        val (k, t, b) = get("/")
        assertEquals(200, k)
        assertTrue(t!!.startsWith("text/html"))
        assertEquals("<!doctype html>гость", String(b, Charsets.UTF_8))
        assertEquals("application/wasm", get("/opus.wasm").second)
        assertEquals(404, get("/../etc/passwd").first)
        assertEquals(404, get("/net.js").first)
        assertEquals(404, get("/%2e%2e/x.js").first)
    }

    @Test fun vhodZvukKartinka() {
        val k = Klient()
        k.tekst("""{"t":"vhod","k":"${kl.moder}","imya":"Мод","kamera":true,"mikrofon":true,"h264":true}""")
        val p = k.zhdat("privet")
        assertTrue(p.contains("\"rol\":\"moder\"") && p.contains("\"sost\":\"vnutri\""))
        assertTrue(k.zhdat("ssylki").contains("#k=${kl.gost}"))
        k.bin(byteArrayOf(1, 0x01, 0x02, 9, 8, 7))
        val z = zvuki.poll(2, TimeUnit.SECONDS)!!
        assertEquals(0x0102, z.seq)
        assertArrayEquals(byteArrayOf(9, 8, 7), z.d)
        k.bin(byteArrayOf(2, 1, 0, 0, 0x30, 0x39, 0, 0, 0, 1, 0x67))
        val v = video.poll(2, TimeUnit.SECONDS)!!
        assertTrue(v.key); assertEquals('h', v.kodek); assertEquals(12345L, v.ms)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67), v.d)
        k.bin(byteArrayOf(3, 0, 0, 0, 5, -1, -40))
        assertEquals('j', video.poll(2, TimeUnit.SECONDS)!!.kodek)
        k.bin(byteArrayOf(4, 0, 0, 0, 0, 7, 0x31, 0, 0))
        val v8 = video.poll(2, TimeUnit.SECONDS)!!
        assertEquals('v', v8.kodek); assertFalse(v8.key)
        // шлем → гость: звук и картинка эфира
        val u = komnata.vnutri().single()
        u.svyaz.dvoichnoe(GostiServer.kadrZvuka(7, byteArrayOf(5, 5), 0, 2), 'a', false)
        assertArrayEquals(byteArrayOf(11, 0, 7, 5, 5), k.dvoichnye.poll(2, TimeUnit.SECONDS))
        k.tekst("""{"t":"ping","c":42}""")
        assertTrue(k.zhdat("pong").contains("\"c\":42"))
        k.tekst("""{"t":"kluch"}""")
        Thread.sleep(200)
        assertEquals(1, klyuchevyh)
    }

    @Test fun zhdushchiyNeShlyotZvuk() {
        val k = Klient()
        k.tekst("""{"t":"vhod","k":"${kl.gost}","imya":"Ждун","kamera":false,"mikrofon":true}""")
        assertTrue(k.zhdat("privet").contains("\"sost\":\"zhdet\""))
        k.bin(byteArrayOf(1, 0, 1, 1, 2, 3))
        assertEquals(null, zvuki.poll(500, TimeUnit.MILLISECONDS))
        komnata.upr(null, "vpustit", null)
        k.zhdat("privet")
        k.bin(byteArrayOf(1, 0, 2, 1, 2, 3))
        assertEquals(2, zvuki.poll(2, TimeUnit.SECONDS)!!.seq)
        k.tekst("""{"t":"sam","mikrofon":false}""")
        Thread.sleep(200)
        k.bin(byteArrayOf(1, 0, 3, 1, 2, 3))
        assertEquals(null, zvuki.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun vygnatZakryvaet() {
        val k = Klient()
        k.tekst("""{"t":"vhod","k":"${kl.gost}","imya":"Шумный","kamera":true,"mikrofon":true}""")
        k.zhdat("privet")
        val id = komnata.vse().single().id
        komnata.upr(null, "vygnat", id)
        assertTrue(k.zhdat("proch").contains("vygnali"))
        val konec = System.currentTimeMillis() + 3000
        while (!k.zakryt && System.currentTimeMillis() < konec) Thread.sleep(50)
        assertTrue(k.zakryt)
        Thread.sleep(300)
        assertEquals(1, ushlo)
        assertTrue(komnata.vse().isEmpty())
    }

    @Test fun bezKlyuchaZakryvaetSam() {
        val k = Klient()
        val konec = System.currentTimeMillis() + 5000
        while (!k.zakryt && System.currentTimeMillis() < konec) Thread.sleep(50)
        assertTrue(k.zakryt)
    }

    @Test fun chuzhoyIstochnikNelzya() {
        assertTrue(GostiServer.istochnikSvoy("https://abc.trycloudflare.com", "abc.trycloudflare.com"))
        assertFalse(GostiServer.istochnikSvoy("https://evil.example", "abc.trycloudflare.com"))
        assertTrue(GostiServer.istochnikSvoy(null, "x"))
        val oshibka = try { Klient("https://evil.example"); null } catch (e: Exception) { e }
        assertNotNull(oshibka)
        val k = Klient("http://127.0.0.1:$port")
        k.tekst("""{"t":"vhod","k":"${kl.hozyain}"}""")
        assertTrue(k.zhdat("privet").contains("hozyain"))
    }

    @Test fun kadrVideoUpakovka() {
        val d = GostiServer.kadrVideo(true, 0x01020304, byteArrayOf(0, 0, 1, 0x65))
        assertArrayEquals(byteArrayOf(12, 1, 1, 2, 3, 4, 0, 0, 1, 0x65), d)
        assertEquals(0x01020304L, GostiServer.chislo(d, 2))
    }
}
