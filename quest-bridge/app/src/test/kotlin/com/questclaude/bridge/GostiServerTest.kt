package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Сервер гостей на обычной JVM: страница, WebSocket настоящим клиентом Java, вход, звук и картинка, выгнать. */
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

    /** Клиент WebSocket: тексты и двоичные — в очереди. */
    private inner class Klient(origin: String? = null) : WebSocket.Listener {
        val teksty = LinkedBlockingQueue<String>()
        val dvoichnye = LinkedBlockingQueue<ByteArray>()
        @Volatile var zakryt = false
        private val sb = StringBuilder()
        private val bb = java.io.ByteArrayOutputStream()
        val ws: WebSocket = HttpClient.newHttpClient().newWebSocketBuilder().apply { if (origin != null) header("Origin", origin) }
            .buildAsync(URI("ws://127.0.0.1:$port/ws"), this).get(3, TimeUnit.SECONDS)

        override fun onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            sb.append(data); if (last) { teksty += sb.toString(); sb.setLength(0) }
            w.request(1); return null
        }
        override fun onBinary(w: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val a = ByteArray(data.remaining()); data.get(a); bb.write(a)
            if (last) { dvoichnye += bb.toByteArray(); bb.reset() }
            w.request(1); return null
        }
        override fun onClose(w: WebSocket, code: Int, reason: String): CompletionStage<*>? { zakryt = true; return null }

        fun zhdat(t: String): String {
            val konec = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < konec) {
                val m = teksty.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (m.contains("\"t\":\"$t\"")) return m
            }
            throw AssertionError("не дождались $t")
        }
        fun tekst(s: String) { ws.sendText(s, true).get(2, TimeUnit.SECONDS) }
        fun bin(b: ByteArray) { ws.sendBinary(ByteBuffer.wrap(b), true).get(2, TimeUnit.SECONDS) }
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
