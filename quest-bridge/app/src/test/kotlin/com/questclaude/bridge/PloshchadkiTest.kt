package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Мульти-эфир (2.7.0) без шлема: один «кодировщик» (кадры из теста), две площадки — два поддельных сервера
 * RTMP (наш же приёмник камеры RtmpPriyom), свой ключ у каждого. Оба получают одни и те же кадры; один
 * умирает — второй идёт дальше; медленная площадка не тормозит другую и не копит память; ключ — ни в одном
 * статусе и ни в одной строке вывода.
 */
class PloshchadkiTest {
    private val lo: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val kluchTw = "test-key-123"
    private val kluchKick = "test-key-456"
    private val servery = mutableListOf<RtmpPriyom>()
    private var pl: Ploshchadki? = null

    @After fun stop() {
        pl?.stop()
        servery.forEach { it.close() }
    }

    private class Lovushka : RtmpPriyom.Priyomnik {
        val kadry = CopyOnWriteArrayList<Triple<ByteArray, Long, Boolean>>()
        val zvuki = CopyOnWriteArrayList<Pair<ByteArray, Long>>()
        @Volatile var nachal = 0
        override fun nachalo(sps: ByteArray, pps: ByteArray, w: Int, h: Int) { nachal++ }
        override fun kadr(annexB: ByteArray, ptsUs: Long, key: Boolean) { kadry += Triple(annexB, ptsUs, key) }
        override fun konec() {}
        override fun zvuk(aac: ByteArray, ptsUs: Long) { zvuki += aac to ptsUs }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun zhdat(t: Long = 8000, u: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + t
        while (System.currentTimeMillis() < end) { if (u()) return true; Thread.sleep(20) }
        return false
    }

    private fun server(port: Int, klyuch: String, l: Lovushka) =
        RtmpPriyom(port, { klyuch }, l, lo).also { it.start(); servery += it }

    // H.264 «как от кодировщика»: SPS/PPS в заголовке, кадры Annex-B (ключевой — IDR)
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xac.toByte(), 0x2b, 0x40)
    private val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
    private fun kadr(n: Int, key: Boolean) =
        byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 0x88.toByte(), n.toByte(), (n shr 8).toByte(), 0x11, 0x22)

    /** Кадр — один раз «сжат», дальше одни и те же байты всем площадкам. */
    private fun dat(p: Ploshchadki, n: Int, key: Boolean, ptsUs: Long) {
        p.video(Flv.video(Flv.avcc(kadr(n, key)), key), ptsUs, key)
        p.audio(Flv.audio(byteArrayOf(0x21, 0x10, n.toByte())), ptsUs + 5_000)
    }

    private fun rtmp(pl: String, url: String, key: String): Otpravka.Kanal = Rtmp(url, key, 3000).also { it.connect() }

    private fun ploshchadki(pauzaMs: Long = 200) = Ploshchadki(::rtmp, pauzaMs = pauzaMs).also {
        it.videoConfig = Flv.video(Flv.avcConfig(sps, pps), key = true, config = true)
        it.audioConfig = Flv.audio(byteArrayOf(0x11, 0x90.toByte()), config = true)
        pl = it
    }

    /** Весь вывод (System.out/err) за время блока — ключ туда не должен попасть. */
    private fun <T> vyvod(block: () -> T): Pair<T, String> {
        val b = ByteArrayOutputStream()
        val o = System.out
        val e = System.err
        val ps = PrintStream(b, true, "UTF-8")
        System.setOut(ps); System.setErr(ps)
        try { return block() to b.toString("UTF-8") } finally { System.setOut(o); System.setErr(e) }
    }

    private fun bezKlyuchey(s: String) {
        for (k in listOf(kluchTw, kluchKick)) assertFalse("ключ в тексте: $s", s.contains(k))
    }

    @Test fun dvePloshchadkiOdniITeZheKadry() {
        val l1 = Lovushka(); val l2 = Lovushka()
        val p1 = freePort(); val p2 = freePort()
        server(p1, kluchTw, l1); server(p2, kluchKick, l2)
        val p = ploshchadki()
        val (_, log) = vyvod {
            p.start(listOf(
                Ploshchadki.Nastroyka("twitch", listOf("rtmp://127.0.0.1:$p1/kamera"), kluchTw),
                Ploshchadki.Nastroyka("kick", listOf("rtmp://127.0.0.1:$p2/kamera"), kluchKick)))
            assertTrue("обе площадки в эфире", zhdat { p.spisok.all { it.sostoyanie == "idyot" } })
            assertEquals("efir", p.state)
            for (i in 0 until 60) dat(p, i, i % 30 == 0, 1_000_000L + i * 33_333L)
            assertTrue("кадры дошли до обоих", zhdat { l1.kadry.size >= 60 && l2.kadry.size >= 60 })
            assertTrue(zhdat { l1.zvuki.size >= 59 && l2.zvuki.size >= 59 })
            p.tik(System.currentTimeMillis())
            p.json()
        }
        assertEquals(60, l1.kadry.size)
        assertEquals(l1.kadry.size, l2.kadry.size)
        for (i in l1.kadry.indices) {                                  // те же байты, то же время, тот же «ключевой»
            assertArrayEquals(l1.kadry[i].first, l2.kadry[i].first)
            assertEquals(l1.kadry[i].second, l2.kadry[i].second)
            assertEquals(l1.kadry[i].third, l2.kadry[i].third)
        }
        assertArrayEquals(kadr(0, true), l1.kadry[0].first)
        assertEquals(0L, l1.kadry[0].second)                           // время эфира — с первого ключевого
        assertEquals(1, l1.nachal); assertEquals(1, l2.nachal)
        val s = p.json()
        assertTrue(s, s.contains("\"pl\":\"twitch\",\"sostoyanie\":\"idyot\""))
        assertTrue(s, s.contains("\"pl\":\"kick\",\"sostoyanie\":\"idyot\""))
        assertTrue(s, s.contains("\"vybor\":[\"twitch\",\"kick\"]"))
        assertTrue(s, s.contains("\"tesno_set\":false"))
        bezKlyuchey(s + log)
    }

    @Test fun odinUmerVtoroyIdyotPervyyVernulsya() {
        val l1 = Lovushka(); val l2 = Lovushka()
        val p1 = freePort(); val p2 = freePort()
        server(p1, kluchTw, l1)
        val s2 = server(p2, kluchKick, l2)
        val p = ploshchadki()
        val (_, log) = vyvod {
            p.start(listOf(
                Ploshchadki.Nastroyka("twitch", listOf("rtmp://127.0.0.1:$p1/kamera"), kluchTw),
                Ploshchadki.Nastroyka("kick", listOf("rtmp://127.0.0.1:$p2/kamera"), kluchKick)))
            assertTrue(zhdat { p.spisok.all { it.sostoyanie == "idyot" } })
            var t = 0L
            var n = 0
            fun sek(sekund: Int) = repeat(sekund * 30) { dat(p, n, n % 30 == 0, t); n++; t += 33_333; Thread.sleep(2) }
            sek(1)
            assertTrue(zhdat { l2.kadry.size >= 30 })
            s2.close()                                                 // Kick умер
            servery.remove(s2)
            val kick = p.spisok.first { it.pl == "kick" }
            sek(1)
            assertTrue("Kick заметил обрыв", zhdat { kick.sostoyanie != "idyot" })
            val doTw = l1.kadry.size
            sek(2)
            assertTrue("Twitch идёт дальше", zhdat { l1.kadry.size >= doTw + 60 })
            assertEquals("idyot", p.spisok.first { it.pl == "twitch" }.sostoyanie)
            assertEquals("efir", p.state)                              // общий эфир идёт
            assertTrue(p.json(), p.json().contains("\"pl\":\"kick\",\"sostoyanie\":\"" + kick.sostoyanie))
            val l3 = Lovushka()
            server(p2, kluchKick, l3)                                  // Kick снова принимает — переподключились сами
            assertTrue("Kick вернулся", zhdat(10_000) { sek(1); kick.sostoyanie == "idyot" && l3.kadry.size > 0 })
            assertTrue(l3.kadry[0].third)                              // после переподключения — с ключевого кадра
            assertTrue(kick.otpravka!!.popytok >= 2)
            assertEquals(1, p.spisok.first { it.pl == "twitch" }.otpravka!!.podklyucheniy)   // Twitch не дёргали
            p.json()
        }
        bezKlyuchey(p.json() + log + (p.oshibka ?: ""))
    }

    @Test fun chuzhoyKlyuchOtkazBezKlyuchaVOshibke() {
        val l = Lovushka()
        val port = freePort()
        server(port, "drugoj-klyuch-789", l)
        val p = ploshchadki()
        val (_, log) = vyvod {
            p.start(listOf(Ploshchadki.Nastroyka("youtube", listOf("rtmp://127.0.0.1:$port/kamera"), kluchTw)))
            assertTrue(zhdat { p.oshibka != null })
        }
        val s = p.json()
        assertTrue(s, s.contains("\"sostoyanie\":\"podklyuchayus\""))
        assertTrue(s, p.oshibka!!.contains("отказал") || p.oshibka!!.contains("закрыл"))
        bezKlyuchey(s + log + p.oshibka)
    }

    /** Подделка медленной сети: каждая отправка ждёт; и быстрая — сразу. */
    private class Kanal(val zhdatMs: Long = 0) : Otpravka.Kanal {
        val got = CopyOnWriteArrayList<String>()
        override var alive = true
        override var sent = 0L
        override fun video(payload: ByteArray, ms: Long) = put("v${payload[0]}@$ms", payload.size)
        override fun audio(payload: ByteArray, ms: Long) = put("a${payload[0]}@$ms", payload.size)
        private fun put(s: String, n: Int) {
            if (zhdatMs > 0) Thread.sleep(zhdatMs)
            got += s; sent += n
        }
        override fun close() { alive = false }
    }

    @Test fun medlennayaNeTormozitINeKopit() {
        val bystro = Kanal()
        val medlenno = Kanal(zhdatMs = 30)
        val p = Ploshchadki({ pl, _, _ -> if (pl == "twitch") bystro else medlenno }, limit = 50)
        pl = p
        p.videoConfig = byteArrayOf(9)
        p.start(listOf(Ploshchadki.Nastroyka("twitch", listOf("rtmp://127.0.0.1/app"), kluchTw),
            Ploshchadki.Nastroyka("kick", listOf("rtmps://x.global-contribute.live-video.net/app"), kluchKick)))
        assertTrue(zhdat { p.spisok.all { it.sostoyanie == "idyot" } })
        var maxOchered = 0
        val nachalo = System.currentTimeMillis()
        for (i in 0 until 600) {
            p.video(byteArrayOf((i % 100).toByte()), i * 10_000L, key = i % 60 == 0)
            maxOchered = maxOf(maxOchered, p.spisok[1].otpravka!!.ochered)
            if (i % 50 == 0) p.tik(System.currentTimeMillis())
            Thread.sleep(1)                                           // как кодировщик: кадры идут потоком, а не разом
        }
        val vremya = System.currentTimeMillis() - nachalo
        assertTrue("кодировщик не ждал медленную площадку: $vremya мс", vremya < 4000)
        assertTrue("быстрая получила всё", zhdat { bystro.got.size >= 601 })
        assertTrue("очередь медленной не растёт без предела: $maxOchered", maxOchered <= 50)
        val kick = p.spisok[1]
        assertTrue("медленная сбрасывала", kick.otpravka!!.drops > 0)
        p.tik(System.currentTimeMillis())
        assertTrue(kick.tesno)
        assertTrue(p.tesnoSet)
        val s = p.json()
        assertTrue(s, s.contains("\"tesno_set\":true"))
        assertTrue(s, s.contains("оставь одну площадку (efir na twitch)"))
        assertEquals(0L, p.spisok[0].otpravka!!.drops)
        bezKlyuchey(s)
    }

    @Test fun zapasnoyAdresYoutube() {
        val adresa = CopyOnWriteArrayList<String>()
        val k = Kanal()
        val p = Ploshchadki({ _, url, _ ->
            adresa += url
            if (url.startsWith("rtmps")) throw IOException("TLS не прошёл") else k
        }, pauzaMs = 20)
        pl = p
        p.videoConfig = byteArrayOf(9)
        p.start(listOf(Ploshchadki.Nastroyka("youtube", Ploshchadki.adresa("youtube"), kluchTw)))
        assertTrue(zhdat { p.spisok[0].sostoyanie == "idyot" })
        assertEquals(listOf(Ploshchadki.YOUTUBE, Ploshchadki.YOUTUBE, Ploshchadki.YOUTUBE_ZAPAS), adresa.toList())
        assertTrue(p.spisok[0].zapasnoy)
        assertTrue(p.json().contains("\"zapasnoy\":true"))
    }

    @Test fun klyuchTolkoNaSvoyuPloshchadku() {
        var zvali = 0
        val p = Ploshchadki({ _, _, _ -> zvali++; Kanal() }, pauzaMs = 20)
        pl = p
        p.start(listOf(Ploshchadki.Nastroyka("kick", listOf("rtmps://evil.example.com/app"), kluchKick),
            Ploshchadki.Nastroyka("youtube", listOf("rtmp://live.twitch.tv/app"), kluchTw)))
        assertTrue(zhdat { p.spisok.all { it.otpravka!!.popytok >= 2 } })
        assertEquals(0, zvali)                                        // до сервера дело не дошло
        assertTrue(p.oshibka!!.contains("адрес не разрешён"))

        assertTrue(Ploshchadki.razreshyon("twitch", "rtmp://live.twitch.tv/app"))
        assertTrue(Ploshchadki.razreshyon("kick", "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app"))
        assertFalse(Ploshchadki.razreshyon("kick", "rtmp://fa723fc1b171.global-contribute.live-video.net/app"))   // только TLS
        assertFalse(Ploshchadki.razreshyon("kick", "rtmp://live.twitch.tv/app"))
        assertTrue(Ploshchadki.razreshyon("youtube", "rtmps://a.rtmps.youtube.com:443/live2"))
        assertTrue(Ploshchadki.razreshyon("youtube", "rtmp://a.rtmp.youtube.com/live2"))
        assertFalse(Ploshchadki.razreshyon("youtube", "rtmps://a.rtmps.youtube.com.evil.com/live2"))
        assertFalse(Ploshchadki.razreshyon("youtube", "rtmp://b.rtmp.youtube.com/live2"))
        assertFalse(Ploshchadki.razreshyon("twitch", "rtmps://a.rtmps.youtube.com/live2"))
        assertFalse(Ploshchadki.razreshyon("tiktok", "rtmp://127.0.0.1/app"))
        assertFalse(Ploshchadki.razreshyon("kick", "https://x.global-contribute.live-video.net/app"))
        assertTrue(Otpravka.adresRazreshyon("rtmps://a.rtmps.youtube.com:443/live2"))
        assertFalse(Otpravka.adresRazreshyon("rtmp://evil.example.com/app"))

        assertEquals("rtmps://fa723fc1b171.global-contribute.live-video.net:443/app",
            Ploshchadki.adresKick(" rtmps://fa723fc1b171.global-contribute.live-video.net/app/ "))
        assertNull(Ploshchadki.adresKick("rtmps://fa723fc1b171.global-contribute.live-video.net/app/sk_us-west-2_xyz"))
        assertNull(Ploshchadki.adresKick("rtmp://fa723fc1b171.global-contribute.live-video.net/app"))
        assertNull(Ploshchadki.adresKick("rtmps://evil.com/app"))
        assertNull(Ploshchadki.adresKick("rtmps://fa723fc1b171.global-contribute.live-video.net/app?k=1"))
        assertNull(Ploshchadki.adresKick(null))

        assertEquals(listOf("twitch", "kick"), Ploshchadki.razobrat("twitch,kick"))
        assertEquals(listOf("twitch", "kick"), Ploshchadki.razobrat("Twitch, kick, twitch"))
        assertEquals(listOf("kick", "youtube"), Ploshchadki.razobrat("кик и ютуб"))
        assertEquals(listOf("twitch", "kick", "youtube"), Ploshchadki.razobrat("twitch+kick+youtube"))
        assertNull(Ploshchadki.razobrat("twitch,tiktok"))
        assertNull(Ploshchadki.razobrat(""))
        assertNull(Ploshchadki.razobrat(null))
        assertEquals("{\"twitch\":true,\"kick\":false,\"youtube\":false}", Ploshchadki.kluchiJson { it == "twitch" })
    }

    @Test fun bezKlyuchaVSpiskeNoNeShlyot() {
        val p = Ploshchadki({ _, _, _ -> Kanal() }, pauzaMs = 20)
        pl = p
        p.start(listOf(Ploshchadki.Nastroyka("twitch", listOf("rtmp://127.0.0.1/app"), kluchTw),
            Ploshchadki.Nastroyka("youtube", Ploshchadki.adresa("youtube"), null),
            Ploshchadki.Nastroyka("kick", emptyList(), kluchKick)))
        assertTrue(zhdat { p.state == "efir" })
        val s = p.json()
        assertTrue(s, s.contains("\"pl\":\"youtube\",\"sostoyanie\":\"net_klyucha\""))
        assertTrue(s, s.contains("нет ключа: kluch youtube"))
        assertTrue(s, s.contains("\"pl\":\"kick\",\"sostoyanie\":\"net_klyucha\""))
        assertTrue(s, s.contains("нет адреса: kluch kick"))
        assertTrue(s, s.contains("\"est_kluch\":false"))
        bezKlyuchey(s)
    }

    @Test fun naborMenyaetsyaNaHodu() {
        val tw = Kanal(); val kick = Kanal()
        val p = Ploshchadki({ pl, _, _ -> if (pl == "twitch") tw else kick }, pauzaMs = 20)
        pl = p
        p.videoConfig = byteArrayOf(9)
        val nTw = Ploshchadki.Nastroyka("twitch", listOf("rtmp://127.0.0.1/app"), kluchTw)
        p.start(listOf(nTw))
        assertTrue(zhdat { p.state == "efir" })
        val twitch = p.spisok[0]
        p.ustanovit(listOf(nTw, Ploshchadki.Nastroyka("kick", listOf("rtmps://x.global-contribute.live-video.net/app"), kluchKick)))
        assertSame(twitch, p.spisok[0])                                  // Twitch не трогали
        assertTrue(zhdat { p.spisok.size == 2 && p.spisok[1].sostoyanie == "idyot" })
        p.video(byteArrayOf(1), 0, key = true)
        assertTrue(zhdat { tw.got.size >= 2 && kick.got.size >= 2 })
        assertEquals(listOf("v9@0", "v1@0"), kick.got.toList())          // новая — с заголовка и ключевого кадра
        p.ustanovit(listOf(Ploshchadki.Nastroyka("kick", listOf("rtmps://x.global-contribute.live-video.net/app"), kluchKick)))
        assertEquals(listOf("kick"), p.spisok.map { it.pl })
        assertTrue("Twitch закончил эфир", zhdat { twitch.otpravka!!.state == "off" && !tw.alive })
        assertEquals(1, twitch.otpravka!!.podklyucheniy)
        assertNotNull(p.spisok[0].otpravka)
        p.stop()
        assertEquals("off", p.state)
    }

    // ---------- RTMPS: TLS с проверкой имени сервера ----------

    private fun keytool(dir: File, imya: String, san: String): File {
        val f = File(dir, "$imya.p12")
        val pr = ProcessBuilder("keytool", "-genkeypair", "-alias", imya, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=$imya", "-ext", "SAN=$san", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", f.path, "-storepass", "parol-test", "-keypass", "parol-test").redirectErrorStream(true).start()
        pr.inputStream.readBytes()
        assertTrue(pr.waitFor(60, TimeUnit.SECONDS) && pr.exitValue() == 0)
        return f
    }

    private fun ks(f: File) = KeyStore.getInstance("PKCS12").apply { FileInputStream(f).use { load(it, "parol-test".toCharArray()) } }

    /** TLS-прослойка перед приёмником RTMP: как настоящий rtmps-сервер. */
    private fun tlsServer(f: File, dalshe: Int): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks(f), "parol-test".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, lo) as SSLServerSocket
        thread(isDaemon = true) {
            while (true) {
                val c = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) {
                    try {
                        val d = Socket(lo, dalshe)
                        thread(isDaemon = true) { try { c.getInputStream().copyTo(d.getOutputStream()) } catch (e: IOException) { }; d.close() }
                        try { d.getInputStream().copyTo(c.getOutputStream()) } catch (e: IOException) { }
                        c.close()
                    } catch (e: IOException) { try { c.close() } catch (e2: IOException) { } }
                }
            }
        }
        return ss.localPort
    }

    private fun has(p: String) = try {
        ProcessBuilder(p, "-help").redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor(); true }
    } catch (e: IOException) { false }

    @Test fun rtmpsSProverkoyImeni() {
        assumeTrue("нет keytool", has("keytool"))
        val dir = Files.createTempDirectory("rtmps").toFile()
        val svoy = keytool(dir, "localhost", "dns:localhost")
        val chuzhoy = keytool(dir, "evil", "dns:evil.example.com")
        val l = Lovushka()
        val port = freePort()
        server(port, kluchTw, l)
        val doverie = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("svoy", ks(svoy).getCertificate("localhost"))
            setCertificateEntry("chuzhoy", ks(chuzhoy).getCertificate("evil"))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(doverie) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory

        val t1 = tlsServer(svoy, port)
        val r = Rtmp("rtmps://localhost:$t1/kamera", kluchTw, 3000, tls)
        r.connect()
        assertTrue(r.alive)
        r.video(Flv.video(Flv.avcConfig(sps, pps), key = true, config = true), 0)
        r.video(Flv.video(Flv.avcc(kadr(1, true)), true), 0)
        assertTrue("кадр прошёл через TLS", zhdat { l.kadry.size == 1 })
        r.zakonchit()

        // сертификат доверенный, но на чужое имя — ключ не уходит
        val t2 = tlsServer(chuzhoy, port)
        val (oshibka, log) = vyvod {
            try { Rtmp("rtmps://localhost:$t2/kamera", kluchTw, 3000, tls).connect(); null } catch (e: IOException) { e.message }
        }
        assertNotNull("чужое имя в сертификате — отказ", oshibka)
        assertEquals(1, l.kadry.size)
        bezKlyuchey(oshibka + log)
        dir.deleteRecursively()
    }
}
