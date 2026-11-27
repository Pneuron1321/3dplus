package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.8.15 (К3): порядок кадров с B-кадрами — dts только растёт, dts ≤ pts, кадры не теряются и не меняются местами. */
class PoryadokTest {
    private val t = 33_333L

    private fun progon(pts: List<Long>, key: (Int) -> Boolean = { it == 0 }): List<Poryadok.Gotov> {
        val p = Poryadok()
        val out = ArrayList<Poryadok.Gotov>()
        pts.forEachIndexed { i, v -> out += p.polozhit(byteArrayOf(i.toByte()), v, key(i)) }
        out += p.ostatok()
        return out
    }

    private fun proverit(pts: List<Long>, out: List<Poryadok.Gotov>) {
        assertEquals(pts.size, out.size)
        out.forEachIndexed { i, g ->
            assertEquals("порядок декодирования сохранён", i.toByte(), g.data[0])
            assertTrue("dts ≤ pts у кадра $i", g.dtsUs <= pts[i])
            assertEquals("pts = dts + cts", pts[i], g.dtsUs + g.ctsUs)
            if (i > 0) assertTrue("dts растёт: ${out[i - 1].dtsUs} → ${g.dtsUs}", g.dtsUs > out[i - 1].dtsUs)
        }
    }

    @Test fun ibbp() {
        // I0 P3 B1 B2 P6 B4 B5 I9 B7 B8 … — порядок, в котором отдаёт кодировщик с 2 B-кадрами
        val gop = listOf(0, 3, 1, 2, 6, 4, 5, 9, 7, 8, 12, 10, 11)
        val pts = (0 until 4).flatMap { g -> gop.map { (it + g * 13) * t } }
        val out = progon(pts)
        proverit(pts, out)
        assertTrue(out.all { it.ctsUs <= Poryadok.SDVIG_US + 3 * t })
        val p = Poryadok(); pts.forEach { p.polozhit(byteArrayOf(0), it, false) }
        assertTrue(p.estB)
    }

    @Test fun bezB() {
        val pts = (0 until 50).map { it * t }
        val out = progon(pts)
        proverit(pts, out)
        assertTrue(out.all { it.ctsUs == Poryadok.SDVIG_US })   // ровный сдвиг: заголовок видео и звук в лад
        val p = Poryadok(); pts.forEach { p.polozhit(byteArrayOf(0), it, false) }
        assertFalse(p.estB)
    }

    @Test fun nerovnoeVremyaIPovtory() {
        // кадры развилки идут неровно (60 → 30 к/с, повтор кадра через 100 мс) — всё равно без нарушений
        val pts = listOf(0L, 50_000L, 16_000L, 33_000L, 150_000L, 66_000L, 100_000L, 250_000L, 183_000L, 216_000L)
        proverit(pts, progon(pts))
    }

    @Test fun flvCts() {
        val v = Flv.video(byteArrayOf(9, 9), key = false, ctsMs = 0x012345)
        assertEquals(0x27.toByte(), v[0]); assertEquals(1.toByte(), v[1])
        assertEquals(listOf(0x01, 0x23, 0x45), listOf(v[2], v[3], v[4]).map { it.toInt() and 0xFF })
        val bez = Flv.video(byteArrayOf(9), key = true)
        assertEquals(listOf(0, 0, 0), listOf(bez[2], bez[3], bez[4]).map { it.toInt() })
    }
}
