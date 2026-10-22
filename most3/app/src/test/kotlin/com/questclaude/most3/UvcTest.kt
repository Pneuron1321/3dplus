package com.questclaude.most3

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class UvcTest {

    // ---------- фикстуры ----------

    /**
     * Elgato 4K X в режиме USB 2 (PID 0x009D): дескриптор устройства + конфигурация (1301 байт),
     * восстановлены по полям из UsbTreeView (log/elgato.txt). Отчёт не показывает
     * dwMaxVideoFrameBufferSize — подставлено w*h*bpp/8 (MJPEG: w*h*2).
     */
    private val usb2: ByteArray = hex(
            "12010002EF020140D90F9D0030010607030109021505040104C0FA080B00020E03000509040000010E0100050D240100015000C0FC9B01010112240201010200" +
            "00000000000000030000000C240502010040030F0000000924030301010004001C240604C7731096F749F244AB42E940405940C2110102030FFE010007058503" +
            "100008063000000000052503100009040100010E020006132401060D04820003000000010004000000001B240401035955593200001000800000AA00389B7110" +
            "02000000002A240501008002E001FFBFC70800009411006009000A8B0200040A8B0200B18B020015160500631705002A24050200D002E001FFB7E0090080C613" +
            "008C0A000A8B0200040A8B0200B18B020015160500631705002224050300D00240026676DA0B0080C61300A80C00400D030002400D03006317050006240D0101" +
            "010B240602050005000000002A240701008002E001FFBFC70800009411006009000A8B0200040A8B0200B18B020015160500631705002A24070200D002E001FF" +
            "B7E0090080C613008C0A000A8B0200040A8B0200B18B020015160500631705002224070300D00240026676DA0B0080C61300A80C00400D030002400D03006317" +
            "050032240704000005D002FF3F571A0000786900201C008545010006854501000A8B0200B18B0200400D03001516050063170500322407050080073804FF4F44" +
            "3B00004EED00483F008545010006854501000A8B0200B18B0200400D0300151605006317050006240D0404041B240403044E56313200001000800000AA00389B" +
            "710C04000000002A240501008002E001FFCF950600002F0D000807000A8B0200040A8B0200B18B020015160500631705002A24050200D002E001FF89680700E0" +
            "D40E00E907000A8B0200040A8B0200B18B020015160500631705002224050300D0024002CCD8E30800E0D40E007E0900400D030002400D030063170500222405" +
            "04000005D002FF6FC1130080C613001815001516050002151605006317050006240D0101011B240404044934323000001000800000AA00389B710C0400000000" +
            "2A240501008002E001FFCF950600002F0D000807000A8B0200040A8B0200B18B020015160500631705002A24050200D002E001FF89680700E0D40E00E907000A" +
            "8B0200040A8B0200B18B020015160500631705002224050300D0024002CCD8E30800E0D40E007E0900400D030002400D03006317050022240504000005D002FF" +
            "6FC1130080C613001815001516050002151605006317050006240D0101011B240405037DEB36E44F52CE119F530020AF0BA77018020000000022240501008002" +
            "E001FF9F2B0D00002F0D00100E00151605000215160500631705002224050200D002E001FF13D10E00E0D40E00D20F00151605000215160500631705001E2405" +
            "0300D002400299B1C71199B1C71100FC120063170500016317050006240D0101011B240407035030313000001000800000AA00389B7118020000000022240501" +
            "008002E001FF9F2B0D00002F0D00100E00151605000215160500631705002224050200D002E001FF13D10E00E0D40E00D20F0015160500021516050063170500" +
            "1E24050300D002400299B1C71199B1C71100FC120063170500016317050006240D0101010705820200020106300F000000080B02020101000809040200000101" +
            "000809240100012D0001030C240201020600020300000A09240302010100030008240603040101000724050401010009040300000102000A0904030101010200" +
            "0A072401020101000B2402010202100180BB0007058305D0000406300000000007250101000000"
    )

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun w16(v: Int) = listOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun w24(v: Int) = w16(v) + listOf((v shr 16) and 0xFF)
    private fun w32(v: Int) = w16(v) + w16(v ushr 16)
    private fun guid(cc: String) = cc.map { it.code } + listOf(0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71)

    /** Сборщик: d(тип, поля…) — bLength считается сам. */
    private class Sb {
        val o = ByteArrayOutputStream()
        fun d(vararg x: Any) {
            val t = ArrayList<Int>()
            for (e in x) when (e) { is Int -> t += e; is List<*> -> e.forEach { t += it as Int } }
            o.write(t.size + 1); t.forEach { o.write(it) }
        }
    }

    private fun Sb.kadr(sub: Int, idx: Int, w: Int, h: Int, buf: Int, iv: List<Int>, nepr: Boolean = false) =
        d(0x24, sub, idx, 0, w16(w), w16(h), w32(1_000_000), w32(2_000_000_000), w32(buf), w32(iv[0]),
            if (nepr) 0 else iv.size, iv.flatMap { w32(it) })

    /** Синтетика «как SuperSpeed»: UVC 1.10, NV12/YUY2 1080p60, MJPEG 1080p120 + 4K30, I420 720p (непрерывный), UAC1. */
    private fun ss(formaty: Set<String> = setOf("NV12", "YUY2", "MJPG", "I420")): ByteArray {
        val s = Sb()
        s.d(1, w16(0x0320), 0xEF, 2, 1, 9, w16(0x0FD9), w16(0x009C), w16(0x0130), 1, 2, 3, 1)
        s.d(2, w16(0), 4, 1, 0, 0xC0, 0x32)
        s.d(0x0B, 0, 2, 0x0E, 3, 0, 0)
        s.d(4, 0, 0, 1, 0x0E, 1, 0, 0)
        s.d(0x24, 1, w16(0x0110), w16(0x4E), w32(27_000_000), 1, 1)
        s.d(0x24, 2, 1, w16(0x0201), 0, 0, w16(0), w16(0), w16(0), 3, 0, 0, 0)
        s.d(0x24, 5, 2, 1, w16(0), 2, 0x0F, 0x00, 0, 0) // PU 1.1: яркость/контраст/оттенок/насыщенность
        s.d(0x24, 3, 3, w16(0x0101), 0, 2, 0)
        s.d(5, 0x85, 3, w16(16), 8); s.d(0x30, 0, 0, w16(0)); s.d(0x25, 3, w16(16))
        s.d(4, 1, 0, 1, 0x0E, 2, 0, 0)
        s.d(0x24, 1, formaty.size, w16(0), 0x82, 0, 3, 0, 0, 0, 1, List(formaty.size) { 0 })
        if ("NV12" in formaty) {
            s.d(0x24, 4, 1, 1, guid("NV12"), 12, 1, 0, 0, 0, 0)
            s.kadr(5, 1, 1920, 1080, 1920 * 1080 * 3 / 2, listOf(166666, 333333))
            s.d(0x24, 0x0D, 1, 1, 4)
        }
        if ("YUY2" in formaty) {
            s.d(0x24, 4, 2, 1, guid("YUY2"), 16, 1, 0, 0, 0, 0)
            s.kadr(5, 1, 1920, 1080, 1920 * 1080 * 2, listOf(166666, 333333))
        }
        if ("MJPG" in formaty) {
            s.d(0x24, 6, 3, 2, 0, 1, 0, 0, 0, 0)
            s.kadr(7, 1, 1920, 1080, 4_000_000, listOf(83333, 166666, 333333))
            s.kadr(7, 2, 3840, 2160, 16_000_000, listOf(333333))
        }
        if ("I420" in formaty) {
            s.d(0x24, 4, 4, 1, guid("I420"), 12, 1, 0, 0, 0, 0)
            s.kadr(5, 1, 1280, 720, 1280 * 720 * 3 / 2, listOf(166666, 333333, 166667), nepr = true)
        }
        s.d(5, 0x82, 2, w16(1024), 0); s.d(0x30, 15, 0, w16(0))
        // звук
        s.d(0x0B, 2, 2, 1, 1, 0, 0)
        s.d(4, 2, 0, 0, 1, 1, 0, 0)
        s.d(0x24, 1, w16(0x100), w16(0x1E), 1, 3)
        s.d(4, 3, 0, 0, 1, 2, 0, 0)
        s.d(4, 3, 1, 1, 1, 2, 0, 0)
        s.d(0x24, 1, 2, 1, w16(1))
        s.d(0x24, 2, 1, 2, 2, 16, 1, w24(48000))
        s.d(5, 0x83, 5, w16(208), 4); s.d(0x30, 0, 0, w16(208)); s.d(0x25, 1, 1, 0, w16(0))
        return s.o.toByteArray()
    }

    // ---------- разбор: USB2 Elgato ----------

    @Test fun usb2FiksturaCelaya() {
        assertEquals(18 + 0x515, usb2.size)
        assertEquals(0x515, (usb2[18 + 2].toInt() and 0xFF) or ((usb2[18 + 3].toInt() and 0xFF) shl 8))
    }

    @Test fun usb2Osnovnoe() {
        val o = UvcRazbor.razobrat(usb2)
        assertEquals(0x0100, o.bcdUvc)
        assertEquals(0, o.vcInterfejs)
        assertEquals(1, o.vsInterfejs)
        assertEquals(0x82, o.tochka)
        assertTrue(o.bulk)
        assertEquals(2, o.puId)
        assertEquals(0x0F, o.puUpravlenie)
        assertEquals(26, UvcProbe.dlina(o.bcdUvc))
    }

    @Test fun usb2Rezhimy() {
        val o = UvcRazbor.razobrat(usb2)
        assertEquals(22, o.rezhimy.size)
        assertEquals(mapOf(1 to 3, 2 to 5, 3 to 4, 4 to 4, 5 to 3, 7 to 3), o.rezhimy.groupingBy { it.formatIndex }.eachCount())
        assertEquals(
            mapOf(1 to UvcVid.YUY2, 2 to UvcVid.MJPEG, 3 to UvcVid.NV12, 4 to UvcVid.I420, 5 to UvcVid.DRUGOE, 7 to UvcVid.P010),
            o.rezhimy.associate { it.formatIndex to it.vid })
        // «Default Video Modes» из отчёта (???? = RGB24 → DRUGOE)
        val V = UvcVid.values().associateBy { it.name }
        fun s(vararg n: String) = n.map { V.getValue(it) }.toSet()
        val ozhid = mapOf(
            "1920×1080 120 к/с" to s("MJPEG"),
            "1280×720 120 к/с" to s("MJPEG"),
            "1280×720 30 к/с" to s("I420", "NV12"),
            "720×576 50 к/с" to s("I420", "MJPEG", "NV12", "YUY2"),
            "720×576 29.97 к/с" to s("DRUGOE", "P010"),
            "720×480 60 к/с" to s("I420", "MJPEG", "NV12", "YUY2"),
            "720×480 30 к/с" to s("DRUGOE", "P010"),
            "640×480 60 к/с" to s("I420", "MJPEG", "NV12", "YUY2"),
            "640×480 30 к/с" to s("DRUGOE", "P010"),
        )
        val fakt = o.rezhimy.groupBy({ it.opisanie().substringAfter(' ') }, { it.vid }).mapValues { it.value.toSet() }
        assertEquals(ozhid, fakt)

        val mj = o.rezhimy.single { it.formatIndex == 2 && it.frameIndex == 5 }
        assertEquals(1920, mj.shirina); assertEquals(1080, mj.vysota)
        assertEquals(listOf(83333, 166666, 166833, 200000, 333333, 333667), mj.intervaly)
        assertEquals("MJPEG 1920×1080 120 к/с", mj.opisanie())
        val y = o.rezhimy.single { it.formatIndex == 1 && it.frameIndex == 1 }
        assertEquals(640 * 480 * 2, y.maxBuf)
        assertFalse(y.nepreryvno)
    }

    @Test fun usb2Zvuk() {
        val z = UvcRazbor.razobrat(usb2).zvuk!!
        assertEquals(UacOpisanie(3, 1, 0x83, 208, 2, 2, listOf(48000)), z)
    }

    @Test fun usb2Vybor() {
        val o = UvcRazbor.razobrat(usb2)
        val r = o.vybrat()!!
        assertEquals(UvcVid.MJPEG, r.vid)
        assertEquals(1920, r.shirina); assertEquals(1080, r.vysota)
        assertEquals(166666, r.luchshijInterval())
        assertEquals(83333, r.luchshijInterval(120))
    }

    // ---------- разбор: синтетика SuperSpeed ----------

    @Test fun ssOsnovnoe() {
        val o = UvcRazbor.razobrat(ss())
        assertEquals(0x0110, o.bcdUvc)
        assertEquals(34, UvcProbe.dlina(o.bcdUvc))
        assertEquals(0, o.vcInterfejs); assertEquals(1, o.vsInterfejs)
        assertEquals(0x82, o.tochka); assertTrue(o.bulk)
        assertEquals(2, o.puId); assertEquals(0x0F, o.puUpravlenie)
        assertEquals(5, o.rezhimy.size)
        assertEquals(UacOpisanie(3, 1, 0x83, 208, 2, 2, listOf(48000)), o.zvuk)
        val k4 = o.rezhimy.single { it.shirina == 3840 }
        assertEquals(UvcRezhim(3, 2, UvcVid.MJPEG, 3840, 2160, listOf(333333), 16_000_000), k4)
        val i420 = o.rezhimy.single { it.vid == UvcVid.I420 }
        assertTrue(i420.nepreryvno)
        assertEquals(listOf(166666, 333333), i420.intervaly)
        assertEquals("I420 1280×720 60 к/с", i420.opisanie())
    }

    @Test fun ssVyborNv12() {
        val r = UvcRazbor.razobrat(ss()).vybrat()!!
        assertEquals(UvcVid.NV12, r.vid)
        assertEquals(1 to 1, r.formatIndex to r.frameIndex)
        assertEquals("NV12 1920×1080 60 к/с", r.opisanie())
        assertEquals(166666, r.luchshijInterval())
        assertEquals(razmerKadra(UvcVid.NV12, 1920, 1080), r.maxBuf)
    }

    @Test fun ssVyborBezNv12() {
        val r = UvcRazbor.razobrat(ss(setOf("YUY2", "MJPG", "I420"))).vybrat()!!
        assertEquals(UvcVid.YUY2, r.vid); assertEquals(2, r.formatIndex)
    }

    @Test fun ssVyborTolkoMjpeg() {
        val r = UvcRazbor.razobrat(ss(setOf("MJPG"))).vybrat()!!
        assertEquals(UvcVid.MJPEG, r.vid)
        assertEquals(1920 to 1080, r.shirina to r.vysota)
        assertEquals(166666, r.luchshijInterval())
    }

    @Test fun ssVyborTolkoI420() {
        // нет 1080p — самый большой несжатый
        val r = UvcRazbor.razobrat(ss(setOf("I420"))).vybrat()!!
        assertEquals(UvcVid.I420, r.vid)
        assertEquals(166667, r.luchshijInterval()) // непрерывный: ceil(1e7/60)
    }

    // ---------- выбор и интервалы на ручных данных ----------

    private fun rz(vid: UvcVid, w: Int, h: Int, vararg iv: Int, fi: Int = 1) =
        UvcRezhim(fi, 1, vid, w, h, iv.toList(), razmerKadra(vid, w, h))

    private fun op(vararg r: UvcRezhim) = UvcOpisanie(0x110, 0, 1, 0x82, true, 2, 0xF, r.toList(), null)

    @Test fun vyborUrovni() {
        // несжатый 1080p30 лучше MJPEG 1080p60
        val y30 = rz(UvcVid.YUY2, 1920, 1080, 333333)
        val mj = rz(UvcVid.MJPEG, 1920, 1080, 166666, fi = 2)
        assertSame(y30, op(mj, y30).vybrat())
        // 1080p60 I420 лучше 1080p30 NV12 (первое правило — частота ≥59)
        val n30 = rz(UvcVid.NV12, 1920, 1080, 333333, fi = 3)
        val i60 = rz(UvcVid.I420, 1920, 1080, 166666, fi = 4)
        assertSame(i60, op(n30, i60).vybrat())
        // 1080p25 несжатый не годится в п.2 → MJPEG 1080p
        val y25 = rz(UvcVid.YUY2, 1920, 1080, 400000)
        assertSame(mj, op(y25, mj).vybrat())
        // нет 1080p вовсе: самый большой несжатый ≤1080p, 4K несжатый и P010 мимо
        val n720 = rz(UvcVid.NV12, 1280, 720, 166666)
        val n4k = rz(UvcVid.NV12, 3840, 2160, 333333)
        val p = rz(UvcVid.P010, 1280, 720, 166666)
        assertSame(n720, op(n4k, p, n720, rz(UvcVid.YUY2, 640, 480, 166666)).vybrat())
        // только экзотика — хоть что-то
        val d = rz(UvcVid.DRUGOE, 720, 480, 333333)
        assertSame(p, op(d, p).vybrat())
        assertNull(op().vybrat())
    }

    @Test fun luchshijInterval() {
        val r = rz(UvcVid.MJPEG, 1920, 1080, 83333, 166666, 166833, 333333)
        assertEquals(166666, r.luchshijInterval(60))
        assertEquals(333333, r.luchshijInterval(30))
        assertEquals(83333, r.luchshijInterval(120))
        assertEquals(83333, r.luchshijInterval(240))
        assertEquals(83333, rz(UvcVid.MJPEG, 1920, 1080, 83333).luchshijInterval(60)) // только 120 — берём его
        assertEquals(333333, rz(UvcVid.MJPEG, 1920, 1080, 83333, 333333).luchshijInterval(50))
        assertEquals(0, rz(UvcVid.NV12, 1, 1).luchshijInterval())
        val n = UvcRezhim(1, 1, UvcVid.NV12, 1280, 720, listOf(166666, 333333), 0, nepreryvno = true)
        assertEquals(166666, n.luchshijInterval(120))
        assertEquals(200000, n.luchshijInterval(50))
        assertEquals(333333, n.luchshijInterval(25))
        assertEquals(60.0, n.maxFps, 0.01)
        assertEquals("NV12 1920×1080 59.94 к/с", rz(UvcVid.NV12, 1920, 1080, 166833).opisanie())
    }

    @Test fun razmery() {
        assertEquals(4147200, razmerKadra(UvcVid.YUY2, 1920, 1080))
        assertEquals(3110400, razmerKadra(UvcVid.NV12, 1920, 1080))
        assertEquals(3110400, razmerKadra(UvcVid.I420, 1920, 1080))
        assertEquals(6220800, razmerKadra(UvcVid.P010, 1920, 1080))
        assertEquals(0, razmerKadra(UvcVid.MJPEG, 1920, 1080))
    }

    @Test fun guid() {
        val g = guid("YUY2").map { it.toByte() }.toByteArray()
        assertEquals(UvcVid.YUY2, UvcRazbor.vidPoGuid(g, 0))
        g[15] = 0
        assertEquals(UvcVid.DRUGOE, UvcRazbor.vidPoGuid(g, 0))
        assertEquals(UvcVid.DRUGOE, UvcRazbor.vidPoGuid(g, 5))
    }

    // ---------- живучесть ----------

    @Test fun obrezannoe() {
        for (f in listOf(usb2, ss())) {
            var bylo = 0
            for (n in 0..f.size) {
                val o = UvcRazbor.razobrat(f.copyOf(n))
                assertTrue(o.rezhimy.size >= bylo) // чем длиннее, тем не меньше режимов
                bylo = o.rezhimy.size
            }
            assertEquals(UvcRazbor.razobrat(f), UvcRazbor.razobrat(f.copyOf(f.size)))
        }
        assertTrue(UvcRazbor.razobrat(ByteArray(0)).rezhimy.isEmpty())
        assertEquals(-1, UvcRazbor.razobrat(ByteArray(1)).vsInterfejs)
    }

    @Test fun nulevayaDlina() {
        assertTrue(UvcRazbor.razobrat(ByteArray(100)).rezhimy.isEmpty())
        val o = UvcRazbor.razobrat(byteArrayOf(1, 1) + ByteArray(50) { 1 })
        assertEquals(-1, o.vcInterfejs)
        // ноль посреди: всё до него разобрано, дальше — стоп
        val f = ss()
        val tochka = f.size - 60
        val slomano = f.copyOf().also { it[poziciyaDeskriptora(it, tochka)] = 0 }
        val r = UvcRazbor.razobrat(slomano)
        assertEquals(5, r.rezhimy.size)
        assertNull(r.zvuk)
    }

    private fun poziciyaDeskriptora(b: ByteArray, ne_ranshe: Int): Int {
        var i = 0
        while (i < ne_ranshe) i += b[i].toInt() and 0xFF
        return i
    }

    @Test fun musor() {
        val rnd = Random(12345)
        repeat(3000) {
            val b = ByteArray(rnd.nextInt(700))
            rnd.nextBytes(b)
            // чаще порождать «похожее»: интерфейсы UVC и class-specific
            if (b.size > 20 && rnd.nextBoolean()) {
                b[0] = 9; b[1] = 4; b[5] = 14; b[6] = (1 + rnd.nextInt(2)).toByte()
                b[9] = rnd.nextInt(60).toByte(); b[10] = 0x24; b[11] = (1 + rnd.nextInt(17)).toByte()
            }
            UvcRazbor.razobrat(b).vybrat()
        }
        for (f in listOf(usb2, ss())) repeat(3000) {
            val b = f.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val o = UvcRazbor.razobrat(b)
            o.vybrat()?.luchshijInterval()
            o.rezhimy.forEach { it.opisanie() }
        }
    }

    // ---------- probe/commit ----------

    @Test fun probeDlina() {
        assertEquals(26, UvcProbe.dlina(0x0100))
        assertEquals(34, UvcProbe.dlina(0x0110))
        assertEquals(48, UvcProbe.dlina(0x0150))
        assertEquals(48, UvcProbe.dlina(0x0200))
    }

    @Test fun probeUpakovka() {
        val r = rz(UvcVid.NV12, 1920, 1080, 166666, fi = 3).copy(frameIndex = 5)
        val b = UvcProbe.upakovat(r, 166666, 26)
        assertArrayEquals(hex("0100" + "03" + "05" + "0A8B0200" + "00".repeat(18)), b)
        assertEquals(34, UvcProbe.upakovat(r, 166666, 34).size)
        assertEquals(48, UvcProbe.upakovat(r, 166666, 48).size)
        assertEquals(26, UvcProbe.upakovat(r, 166666, 3).size)
    }

    @Test fun probeRazbor() {
        // формат 3, кадр 5, 333333, ключевые/P/качество/окно/задержка, кадр 3110400, payload 32768
        val b = hex("0100" + "03" + "05" + "15160500" + "0000" + "0000" + "0000" + "0000" + "2000" +
            "00762F00" + "00800000")
        assertEquals(26, b.size)
        assertEquals(ProbeOtvet(3, 5, 333333, 3110400, 32768), UvcProbe.razobrat(b))
        val b48 = b + ByteArray(22) { 0x77 }
        assertEquals(ProbeOtvet(3, 5, 333333, 3110400, 32768), UvcProbe.razobrat(b48))
        // короткие ответы не ломают
        assertEquals(ProbeOtvet(0, 0, 0, 0, 0), UvcProbe.razobrat(ByteArray(0)))
        assertEquals(ProbeOtvet(3, 5, 333333, 0, 0), UvcProbe.razobrat(b.copyOf(10)))
    }

    @Test fun probeKrug() {
        val r = UvcRezhim(7, 9, UvcVid.MJPEG, 1920, 1080, listOf(83333), 0)
        for (n in listOf(26, 34, 48)) {
            val b = UvcProbe.upakovat(r, 83333, n)
            assertEquals(n, b.size)
            assertEquals(ProbeOtvet(7, 9, 83333, 0, 0), UvcProbe.razobrat(b))
            // имитация ответа устройства: заполнены размеры
            b[18] = 0x10; b[22] = 0x40; b[23] = 0x01
            val o = UvcProbe.razobrat(b)
            assertEquals(0x10, o.maxKadr); assertEquals(0x140, o.maxPaket)
        }
    }

    // ---------- сборка кадров ----------

    private fun pay(fid: Int, eof: Boolean, data: ByteArray = ByteArray(0), err: Boolean = false, ptsScr: Boolean = false): ByteArray {
        val hl = if (ptsScr) 12 else 2
        val info = 0x80 or fid or (if (eof) 2 else 0) or (if (err) 0x40 else 0) or (if (ptsScr) 0x0C else 0)
        return byteArrayOf(hl.toByte(), info.toByte()) + ByteArray(hl - 2) { 0x55 } + data
    }

    private fun dannye(n: Int, s: Int = 0) = ByteArray(n) { (it + s).toByte() }

    private class Priem {
        val kadry = ArrayList<ByteArray>()
        val bufy = ArrayList<ByteArray>()
        val f: (ByteArray, Int) -> Unit = { b, n -> kadry += b.copyOf(n); bufy += b }
    }

    private fun SborKadra.p(b: ByteArray) = podat(b, 0, b.size)

    @Test fun sborEof() {
        val pr = Priem()
        val s = SborKadra(100, 200, pr.f)
        val d = dannye(100)
        for (k in 0 until 4) s.p(pay(0, k == 3, d.copyOfRange(25 * k, 25 * k + 25)))
        assertEquals(1, pr.kadry.size)
        assertArrayEquals(d, pr.kadry[0])
        // следующий кадр, FID сменён, с PTS/SCR-заголовком
        s.p(pay(1, false, d.copyOfRange(0, 60), ptsScr = true)); s.p(pay(1, true, d.copyOfRange(60, 100), ptsScr = true))
        assertEquals(2, s.kadrov.toInt())
        assertArrayEquals(d, pr.kadry[1])
        assertEquals(100, s.poslednijRazmer)
        assertEquals(0, s.broshenyh.toInt())
    }

    @Test fun sborFidBezEof() {
        val pr = Priem()
        val s = SborKadra(100, 200, pr.f)
        s.p(pay(0, false, dannye(50))); s.p(pay(0, false, dannye(50, 50)))
        assertEquals(0, pr.kadry.size)
        s.p(pay(1, false, dannye(50, 7)))
        assertEquals(1, pr.kadry.size)
        assertArrayEquals(dannye(100), pr.kadry[0])
        s.p(pay(1, false, dannye(50, 57)))
        s.p(pay(0, false, dannye(10)))
        assertEquals(2, pr.kadry.size)
        assertArrayEquals(dannye(100, 7), pr.kadry[1])
    }

    @Test fun sborOshibka() {
        val pr = Priem()
        val s = SborKadra(100, 200, pr.f)
        s.p(pay(0, false, dannye(50))); s.p(pay(0, false, dannye(50), err = true)); s.p(pay(0, true))
        assertEquals(0, pr.kadry.size)
        assertEquals(1, s.oshibok.toInt()); assertEquals(1, s.broshenyh.toInt())
        s.p(pay(1, false, dannye(50))); s.p(pay(1, true, dannye(50, 50)))
        assertEquals(1, pr.kadry.size)
        // ERR в заголовочном пакете с EOF тоже губит кадр
        s.p(pay(0, false, dannye(100))); s.p(pay(0, true, err = true))
        assertEquals(1, pr.kadry.size); assertEquals(2, s.broshenyh.toInt())
    }

    @Test fun sborNeTotRazmer() {
        val pr = Priem()
        val s = SborKadra(100, 200, pr.f)
        s.p(pay(0, true, dannye(99)))
        assertEquals(0, pr.kadry.size); assertEquals(1, s.broshenyh.toInt())
        // больше ожидаемого — хвост выравнивания (например, высота 1088): кадр годен, показ берёт начало
        s.p(pay(1, true, dannye(101)))
        assertEquals(1, pr.kadry.size); assertEquals(101, s.poslednijRazmer)
        // первый кадр, пойманный с середины, отбрасывается по размеру
        s.p(pay(0, false, dannye(30))); s.p(pay(1, false, dannye(100))); s.p(pay(1, true))
        assertEquals(2, pr.kadry.size); assertEquals(2, s.broshenyh.toInt())
    }

    @Test fun sborPerepolnenie() {
        val pr = Priem()
        val s = SborKadra(0, 100, pr.f)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + dannye(70)
        s.p(pay(0, false, jpg)); s.p(pay(0, false, jpg)); s.p(pay(0, false, jpg)); s.p(pay(0, true, jpg))
        assertEquals(0, pr.kadry.size); assertEquals(1, s.broshenyh.toInt())
        s.p(pay(1, true, jpg))
        assertEquals(1, pr.kadry.size); assertArrayEquals(jpg, pr.kadry[0])
        // ровно maxKadr — можно
        val polnyj = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + dannye(98)
        s.p(pay(0, true, polnyj))
        assertArrayEquals(polnyj, pr.kadry[1])
        // один огромный payload
        s.p(pay(1, true, ByteArray(5000)))
        assertEquals(2, pr.kadry.size)
    }

    @Test fun sborMjpeg() {
        val pr = Priem()
        val s = SborKadra(0, 10_000, pr.f)
        val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + dannye(35) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + dannye(1230, 3) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        s.p(pay(0, true, a))
        s.p(pay(1, false, b.copyOfRange(0, 1000))); s.p(pay(1, true, b.copyOfRange(1000, b.size)))
        s.p(pay(0, true, dannye(500))) // не JPEG
        assertEquals(2, pr.kadry.size)
        assertArrayEquals(a, pr.kadry[0]); assertArrayEquals(b, pr.kadry[1])
        assertEquals(1, s.broshenyh.toInt())
        assertEquals(500, s.poslednijRazmer)
    }

    @Test fun sborPustyeIZagolovki() {
        val pr = Priem()
        val s = SborKadra(100, 200, pr.f)
        s.podat(ByteArray(10), 0, 0) // пустая передача
        s.p(pay(0, false)); s.p(pay(0, false, dannye(40))); s.p(pay(0, false)); s.p(pay(0, false, dannye(60, 40)))
        s.p(pay(0, true)) // EOF отдельным заголовком
        s.p(pay(0, true)); s.p(pay(1, true)) // пустые кадры не считаются ни отданными, ни брошенными
        assertEquals(1, pr.kadry.size); assertArrayEquals(dannye(100), pr.kadry[0])
        assertEquals(0, s.broshenyh.toInt())
        // негодные заголовки игнорируются и считаются, кадр собирается дальше
        s.p(pay(1, false, dannye(50)))
        s.p(byteArrayOf(0, 0x80.toByte(), 1, 2))           // hl=0
        s.p(byteArrayOf(1, 0x80.toByte()))                  // hl=1
        s.p(byteArrayOf(9, 0x82.toByte(), 1, 2))            // hl>len
        s.p(byteArrayOf(2, 0x84.toByte(), 1, 2, 3, 4))      // PTS при hl=2
        s.p(byteArrayOf(7))                                 // 1 байт
        assertEquals(5, s.plohihZagolovkov.toInt())
        s.p(pay(1, true, dannye(50, 50)))
        assertEquals(2, pr.kadry.size); assertArrayEquals(dannye(100), pr.kadry[1])
    }

    @Test fun sborSmeshchenie() {
        val pr = Priem()
        val s = SborKadra(4, 4, pr.f)
        val big = ByteArray(32) { 0x11 }
        val p = pay(0, true, byteArrayOf(1, 2, 3, 4))
        System.arraycopy(p, 0, big, 10, p.size)
        s.podat(big, 10, p.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), pr.kadry.single())
        s.podat(big, 30, 5) // за краем буфера
        assertEquals(1, s.plohihZagolovkov.toInt())
    }

    @Test fun sborBufery() {
        // буфер доставки k не меняется до конца доставки k+2
        val bufy = ArrayList<ByteArray>()
        val kopii = ArrayList<ByteArray>()
        var narushenij = 0
        lateinit var s: SborKadra
        s = SborKadra(64, 64) { b, n ->
            val k = bufy.size
            for (j in maxOf(0, k - 2) until k) if (!bufy[j].copyOf(64).contentEquals(kopii[j])) narushenij++
            bufy += b; kopii += b.copyOf(n)
        }
        for (k in 0 until 9) {
            s.p(pay(k and 1, false, dannye(32, k * 10)))
            s.p(pay(k and 1, true, dannye(32, k * 10 + 32)))
            if (k == 4) s.p(pay(1, true, dannye(7))) // брошенный кадр не сдвигает круг
        }
        assertEquals(0, narushenij)
        assertEquals(9, bufy.size)
        assertNotSame(bufy[0], bufy[1]); assertNotSame(bufy[1], bufy[2]); assertNotSame(bufy[0], bufy[2])
        for (k in 3 until 9) assertSame(bufy[k - 3], bufy[k])
        assertArrayEquals(dannye(64, 80), kopii[8])
    }
}
