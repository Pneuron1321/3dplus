package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Приём камеры без шлема и без айфона: настоящий ffmpeg в роли телефона (как Larix) шлёт
 * H.264 с B-кадрами на наш RtmpPriyom; проверяем заголовок, кадры, время показа, чужой ключ.
 * Нет ffmpeg — проверки с ним пропускаются.
 */
class RtmpPriyomTest {
    private val lo: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val klyuch = "k7m2x9p4qa"
    private var priyom: RtmpPriyom? = null

    private class Lovushka : RtmpPriyom.Priyomnik {
        val nachala = CopyOnWriteArrayList<Triple<ByteArray, ByteArray, Pair<Int, Int>>>()
        val kadry = CopyOnWriteArrayList<Triple<ByteArray, Long, Boolean>>()
        @Volatile var koncov = 0
        override fun nachalo(sps: ByteArray, pps: ByteArray, w: Int, h: Int) { nachala += Triple(sps, pps, w to h) }
        override fun kadr(annexB: ByteArray, ptsUs: Long, key: Boolean) { kadry += Triple(annexB, ptsUs, key) }
        override fun konec() { koncov++ }
    }

    @After fun stop() { priyom?.close() }

    private fun has(p: String) = try {
        ProcessBuilder(p, "-version").redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() == 0 }
    } catch (e: IOException) { false }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun zhdat(t: Long = 5000, u: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + t
        while (System.currentTimeMillis() < end) { if (u()) return true; Thread.sleep(20) }
        return false
    }

    private fun zapusk(l: Lovushka): Int {
        val port = freePort()
        priyom = RtmpPriyom(port, { klyuch }, l, lo).also { it.start() }
        return port
    }

    /** ffmpeg как телефон: 3 с тестовой картинки 320×240 30 к/с, H.264 с B-кадрами, ключевой раз в секунду, и звук. */
    private fun ffmpeg(url: String): Pair<Int, String> {
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "3", "-c:v", "libx264", "-preset", "veryfast", "-g", "30", "-bf", "2", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-f", "flv", url).redirectErrorStream(true).start()
        val out = String(p.inputStream.readBytes())
        assertTrue("ffmpeg не закончил", p.waitFor(40, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    @Test fun ffmpegKakTelefon() {
        assumeTrue("нет ffmpeg", has("ffmpeg"))
        val l = Lovushka()
        val port = zapusk(l)
        val (code, out) = ffmpeg("rtmp://127.0.0.1:$port/kamera/$klyuch")
        assertEquals(out, 0, code)
        assertTrue("конец не пришёл", zhdat { l.koncov == 1 })
        val p = priyom!!
        assertEquals("zhdu", p.state)
        assertTrue("ffmpeg подписывает C1 — подпись должна распознаться", p.skhemaKlienta in 1..2)

        assertEquals(1, l.nachala.size)
        val (sps, pps, razmer) = l.nachala[0]
        assertEquals(7, sps[0].toInt() and 0x1f)
        assertEquals(8, pps[0].toInt() and 0x1f)
        assertEquals(320 to 240, razmer)

        val k = l.kadry
        assertTrue("кадров ${k.size}", k.size in 85..91)
        assertTrue("первый — ключевой", k[0].third)
        for ((a, _, _) in k) assertArrayEquals(byteArrayOf(0, 0, 0, 1), a.copyOfRange(0, 4))
        assertTrue("ключевые раз в секунду", k.count { it.third } in 3..4)
        // B-кадры: время показа приходит не по порядку, но вместе — ровная лесенка через 1/30 с
        val pts = k.map { it.second }
        assertTrue("B-кадры: время показа не монотонно", pts.zipWithNext().any { (a, b) -> b < a })
        val s = pts.sorted()
        assertEquals(k.size, s.distinct().size)
        for ((a, b) in s.zipWithNext()) assertTrue("шаг ${b - a} мкс", b - a in 30_000L..37_000L)
    }

    @Test fun chuzhoyKlyuch() {
        assumeTrue("нет ffmpeg", has("ffmpeg"))
        val l = Lovushka()
        val port = zapusk(l)
        val (code, _) = ffmpeg("rtmp://127.0.0.1:$port/kamera/podbor12345")
        assertTrue("ffmpeg должен получить отказ", code != 0)
        assertEquals(0, l.nachala.size)
        assertEquals(0, l.kadry.size)
        val o = priyom!!.oshibka.orEmpty()
        assertTrue(o, o.contains("неверный ключ"))
        assertFalse(o.contains("podbor12345"))
        assertFalse(o.contains(klyuch))
        assertEquals("zhdu", priyom!!.state)
    }

    /** Наш же отправитель эфира (простое рукопожатие, куски по 4096) — к нашему приёмнику. */
    @Test fun svoyOtpravitel() {
        val l = Lovushka()
        val port = zapusk(l)
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x33, 0x44)
        val idr = ByteArray(9000) { (it % 251).toByte() }.also { it[0] = 0x65 }   // больше куска — проверка склейки
        val p = byteArrayOf(0x41, 0x01, 0x02)
        val r = Rtmp("rtmp://127.0.0.1:$port/kamera", klyuch)
        r.connect()
        assertEquals(0, priyom!!.skhemaKlienta)
        r.meta(640, 360, 30, 2500, 128, 44100)
        r.video(Flv.video(Flv.avcConfig(sps, pps), key = true, config = true), 0)
        r.video(Flv.video(p.let { byteArrayOf(0, 0, 0, it.size.toByte()) + it }, key = false), 10)   // до ключевого — мимо
        r.video(Flv.video(Flv.avcc(byteArrayOf(0, 0, 0, 1) + idr), key = true), 33)
        r.video(Flv.video(Flv.avcc(byteArrayOf(0, 0, 0, 1) + p), key = false), 66)
        assertTrue(zhdat { l.kadry.size == 2 })
        assertEquals("idyot", priyom!!.state)
        assertEquals("127.0.0.1", priyom!!.ot)
        assertArrayEquals(sps, l.nachala[0].first)
        assertArrayEquals(pps, l.nachala[0].second)
        assertEquals(640 to 360, l.nachala[0].third)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + idr, l.kadry[0].first)
        assertEquals(33_000L, l.kadry[0].second)
        assertTrue(l.kadry[0].third)
        assertEquals(66_000L, l.kadry[1].second)
        r.close()
        assertTrue(zhdat { l.koncov == 1 })
    }

    /** Новая камера с верным ключом сменяет прежнюю; приёмник не слышит «конец» от прежней. */
    @Test fun novayaSmenyaetPrezhnyuyu() {
        val l = Lovushka()
        val port = zapusk(l)
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0x33)
        val a = Rtmp("rtmp://127.0.0.1:$port/kamera", klyuch).apply { connect() }
        a.video(Flv.video(Flv.avcConfig(sps, pps), key = true, config = true), 0)
        val b = Rtmp("rtmp://127.0.0.1:$port/kamera", klyuch).apply { connect() }
        b.video(Flv.video(Flv.avcConfig(sps, pps), key = true, config = true), 0)
        assertTrue(zhdat { l.nachala.size == 2 })
        Thread.sleep(300)
        assertEquals(0, l.koncov)
        assertEquals("idyot", priyom!!.state)
        assertEquals(2, priyom!!.podklyucheniy)
        b.close()
        assertTrue(zhdat { l.koncov == 1 })
        a.close()
    }

    /** Наш отправитель кончает эфир по-настоящему: сервер получает «конец трансляции», а не обрыв. */
    @Test fun konecEfiraNeObryv() {
        val l = Lovushka()
        val port = zapusk(l)
        val r = Rtmp("rtmp://127.0.0.1:$port/kamera", klyuch).apply { connect() }
        r.video(Flv.video(Flv.avcConfig(byteArrayOf(0x67, 0x42, 0x00, 0x1f), byteArrayOf(0x68, 0x33)), key = true, config = true), 0)
        assertTrue(zhdat { l.nachala.size == 1 })
        r.zakonchit()
        assertFalse(r.alive)
        assertTrue(zhdat { l.koncov == 1 })
        val o = priyom!!.oshibka.orEmpty()
        assertTrue(o, o.contains("камера закончила"))           // deleteStream, а не оборванный сокет
    }

    /** Система закрыла порт из-под приёмника (08.10: «жду», а Safari — «не удаётся подключиться»): открывается заново. */
    @Test fun sistemaZakrylaPort() {
        val l = Lovushka()
        val port = zapusk(l)
        val p = priyom!!
        assertTrue(p.slushaet)
        p.sistemaZakryla()
        assertTrue("порт не открылся заново", zhdat { p.perezapuskov == 1 && p.slushaet })
        assertEquals("zhdu", p.state)
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0x33)
        val r = Rtmp("rtmp://127.0.0.1:$port/kamera", klyuch).apply { connect() }
        r.video(Flv.video(Flv.avcConfig(sps, pps), key = true, config = true), 0)
        assertTrue(zhdat { l.nachala.size == 1 })
        assertEquals("idyot", p.state)
        assertEquals(1, p.popytok)
        r.close()
    }

    /** Порт занят — приёмник ждёт и открывает его, как только освободится. */
    @Test fun zanyatyyPort() {
        val port = freePort()
        val chuzhoy = ServerSocket(port, 50, lo)
        val p = RtmpPriyom(port, { klyuch }, Lovushka(), lo).also { priyom = it; it.start() }
        assertFalse(p.slushaet)
        assertEquals("vykl", p.state)
        assertTrue(p.oshibka.orEmpty(), p.oshibka.orEmpty().contains("не открылся"))
        chuzhoy.close()
        assertTrue("порт не открылся после освобождения", zhdat(8000) { p.slushaet })
        assertEquals("zhdu", p.state)
        assertEquals(null, p.oshibka)
    }

    /** Постучались не камерой (Safari на порт 1935): видно, что стучались и почему видео нет. */
    @Test fun stuchalisNeKameroy() {
        val port = zapusk(Lovushka())
        java.net.Socket(lo, port).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) ; it.getInputStream().read() }
        val p = priyom!!
        assertTrue(zhdat { p.oshibka != null })
        assertEquals(1, p.popytok)
        assertTrue(p.oshibka!!, p.oshibka!!.contains("видео не пошло"))
        assertEquals("zhdu", p.state)
    }

    @Test fun avcZagolovokTudaObratno() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0x11)
        val pps = byteArrayOf(0x68, 0x22, 0x33)
        val c = Flv.avcConfigParse(Flv.avcConfig(sps, pps))!!
        assertArrayEquals(sps, c.sps)
        assertArrayEquals(pps, c.pps)
        assertEquals(4, c.lenSize)
        assertEquals(null, Flv.avcConfigParse(byteArrayOf(1, 2, 3)))
        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x01, 0, 0, 0, 1, 0x06, 0, 0, 0, 99, 1)   // последняя длина битая
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x06), Flv.annexB(avcc, 0, 4))
    }

    /** Подпись рукопожатия — так её проверяет ffmpeg-проигрыватель (rtmpproto.c). */
    @Test fun podpisRukopozhatiya() {
        val h = RtmpPriyom.Rukopozhatie
        val c1 = ByteArray(1536) { (it * 7 + 3).toByte() }
        assertEquals(0, h.skhema(c1))
        val prostoy = h.otvet(c1)
        assertArrayEquals(c1 + c1, prostoy)
        // клиент «как Flash», схема 1: подпись HMAC(ключ игрока) на месте из байт 8..11
        val at = h.mesto(c1, 1)
        h.hmac(c1.copyOfRange(0, at) + c1.copyOfRange(at + 32, 1536), "Genuine Adobe Flash Player 001".toByteArray()).copyInto(c1, at)
        assertEquals(1, h.skhema(c1))
        val o = h.otvet(c1)
        val s1 = o.copyOfRange(0, 1536)
        val s2 = o.copyOfRange(1536, 3072)
        val sa = h.mesto(s1, 1)
        assertArrayEquals(h.hmac(s1.copyOfRange(0, sa) + s1.copyOfRange(sa + 32, 1536),
            "Genuine Adobe Flash Media Server 001".toByteArray()), s1.copyOfRange(sa, sa + 32))
        val crud = intArrayOf(0xf0, 0xee, 0xc2, 0x4a, 0x80, 0x68, 0xbe, 0xe8, 0x2e, 0x00, 0xd0, 0xd1, 0x02, 0x9e,
            0x7e, 0x57, 0x6e, 0xec, 0x5d, 0x2d, 0x29, 0x80, 0x6f, 0xab, 0x93, 0xb8, 0xe6, 0x36, 0xcf, 0xeb, 0x31, 0xae)
            .map { it.toByte() }.toByteArray()
        val digest = h.hmac(c1.copyOfRange(at, at + 32), "Genuine Adobe Flash Media Server 001".toByteArray() + crud)
        assertArrayEquals(h.hmac(s2.copyOfRange(0, 1504), digest), s2.copyOfRange(1504, 1536))
    }
}
