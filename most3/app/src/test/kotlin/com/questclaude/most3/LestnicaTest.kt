package com.questclaude.most3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Лестница режимов первого запуска и «вежливый» PROBE (разбор №2). */
class LestnicaTest {
    private fun r(f: Int, k: Int, vid: UvcVid, w: Int, h: Int, fps: Int) =
        UvcRezhim(f, k, vid, w, h, listOf(10_000_000 / fps), razmerKadra(vid, w, h))

    private val op = UvcOpisanie(0x100, 0, 1, 0x82, true, 2, 0xf, listOf(
        r(1, 1, UvcVid.NV12, 1920, 1080, 60), r(1, 2, UvcVid.NV12, 1920, 1080, 30),
        r(2, 1, UvcVid.YUY2, 1920, 1080, 60), r(3, 1, UvcVid.MJPEG, 1920, 1080, 60),
        r(1, 3, UvcVid.NV12, 1280, 720, 60), r(4, 1, UvcVid.P010, 1920, 1080, 30)), null)

    @Test fun poryadok() {
        val l = op.lestnica()
        assertEquals(listOf("NV12 1920×1080", "YUY2 1920×1080", "MJPEG 1920×1080", "NV12 1280×720", "P010 1920×1080"),
            l.map { "${it.vid} ${it.shirina}×${it.vysota}" })
        assertEquals(60.0, l[0].maxFps, 0.01)                    // NV12 1080 — шаг 60, а не 30
        assertEquals(2, op.lestnica(2).size)
        assertEquals(emptyList<UvcRezhim>(), op.copy(rezhimy = emptyList()).lestnica())
    }

    @Test fun vezhlivyjProbe() {
        val tek = ByteArray(26) { (it + 100).toByte() }          // то, что карта сама ответила на GET_CUR
        val n = UvcProbe.nalozhit(tek, op.rezhimy[2], 166_666, 26)
        assertEquals(1, n[0].toInt()); assertEquals(0, n[1].toInt())
        assertEquals(2, n[2].toInt()); assertEquals(1, n[3].toInt())
        assertEquals(166_666, UvcProbe.razobrat(n).interval)
        assertArrayEquals(tek.copyOfRange(8, 26), n.copyOfRange(8, 26))   // остальное — как у карты
        assertArrayEquals(UvcProbe.upakovat(op.rezhimy[2], 166_666, 26), UvcProbe.nalozhit(ByteArray(3), op.rezhimy[2], 166_666, 26))
    }

    @Test fun hex() {
        assertEquals("00 ff 10", Hex.str(byteArrayOf(0, -1, 16)))
        assertEquals("00 ff", Hex.str(byteArrayOf(0, -1, 16), 2))
    }
}

/** Находки проверки 22.10: хвост payload без заголовка и пакеты звука по bInterval. */
class ProverkaTest {
    private fun pay(fid: Int, eof: Boolean, n: Int) =
        byteArrayOf(2, ((fid and 1) or (if (eof) 2 else 0) or 0x80).toByte()) + ByteArray(n) { 7 }

    @Test fun hvostBezZagolovka() {
        val kadry = ArrayList<Int>()
        val s = SborKadra(10, 64) { _, n -> kadry += n }
        val a = pay(0, false, 6)
        assertEquals(true, s.podat(a, 0, a.size))
        val hvost = byteArrayOf(99, 98, 97, 96)                 // «заголовок» негоден: 99 > длины
        assertEquals(false, s.podat(hvost, 0, hvost.size))
        s.prodolzhit(hvost, 0, hvost.size)
        val konec = pay(0, true, 0)
        s.podat(konec, 0, konec.size)
        assertEquals(listOf(10), kadry)
        assertEquals(1L, s.prodolzhenij)
    }

    @Test fun paketyZvuka() {
        fun z(i: Int) = UacOpisanie(3, 1, 0x83, 208, 2, 2, listOf(48000), i)
        assertEquals(1, z(4).paketovVMs)
        assertEquals(8, z(1).paketovVMs)
        assertEquals(1, z(0).paketovVMs)
    }
}

/** Профили режимов (заход 2): режим по задаче; карта как на USB 5 Гбит/с — высокое только MJPEG. */
class ProfilTest {
    private fun r(f: Int, k: Int, vid: UvcVid, w: Int, h: Int, vararg fps: Int) =
        UvcRezhim(f, k, vid, w, h, fps.map { 10_000_000 / it }, razmerKadra(vid, w, h))

    private val op = UvcOpisanie(0x100, 0, 1, 0x82, true, 2, 0xf, listOf(
        r(1, 1, UvcVid.NV12, 1920, 1080, 60, 30), r(2, 1, UvcVid.YUY2, 1920, 1080, 60),
        r(3, 1, UvcVid.MJPEG, 1920, 1080, 120, 60), r(3, 2, UvcVid.MJPEG, 2560, 1440, 60, 30),
        r(1, 2, UvcVid.NV12, 2560, 1440, 30), r(3, 3, UvcVid.MJPEG, 3840, 2160, 60, 30),
        r(4, 1, UvcVid.P010, 1920, 1080, 60)), null)

    private fun opis(l: List<Shag>) = l.map { "${it.rezhim.vid} ${it.rezhim.vysota}" }

    @Test fun efir() = assertEquals("NV12 1080", opis(op.lestnica(Profil.EFIR)).first())

    @Test fun plavno() {
        val l = op.lestnica(Profil.PLAVNO)
        assertEquals("MJPEG 1080", opis(l).first())
        assertEquals(120.0, l[0].rezhim.fps(l[0].rezhim.luchshijInterval(l[0].maxFps)), 0.5)
        assertEquals("NV12 1080", opis(l)[1])                              // запасная — «Эфир»
    }

    @Test fun maks() {
        val l = opis(op.lestnica(Profil.MAKS))
        assertEquals(listOf("MJPEG 1440", "NV12 1440", "NV12 1080"), l.take(3))   // плавный MJPEG раньше несжатого 30
    }

    @Test fun k4() {
        val l = op.lestnica(Profil.K4)
        assertEquals("MJPEG 2160", opis(l).first())
        assertEquals(30.0, l[0].rezhim.fps(l[0].rezhim.luchshijInterval(l[0].maxFps)), 0.5)   // 4К — 30 к/с, разжатие успевает
        assertEquals("MJPEG 1440", opis(l)[1])                             // нет 4К — хотя бы 1440p
    }

    @Test fun hdr() = assertEquals("P010 1080", opis(op.lestnica(Profil.HDR)).first())

    @Test fun zapomnennyjPervym() {
        val z = op.rezhimy[4]                                              // NV12 1440p30
        assertEquals("NV12 1440", opis(op.lestnica(Profil.MAKS, z)).first())
        assertEquals("NV12 1440", opis(op.lestnica(Profil.EFIR, z)).first())
        val chuzhoj = r(9, 9, UvcVid.NV12, 640, 480, 30)                   // не из этой карты — не мешает
        assertEquals("MJPEG 1440", opis(op.lestnica(Profil.MAKS, chuzhoj)).first())
    }

    @Test fun bezPovtorov() {
        for (p in Profil.values()) {
            val l = op.lestnica(p).map { Triple(it.rezhim.vid, it.rezhim.shirina, it.rezhim.vysota) }
            assertEquals(l.toSet().size, l.size)
        }
        assertEquals(Profil.MAKS, Profil.po("maks")); assertEquals(null, Profil.po("nety"))
    }
}
