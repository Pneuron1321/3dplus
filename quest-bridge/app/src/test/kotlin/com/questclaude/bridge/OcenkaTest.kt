package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.random.Random

/** 2.8.15 «свой глаз»: SSIM/PSNR, подгонка диапазона яркости, переворот строк glReadPixels, пары по времени. */
class OcenkaTest {
    private val w = 64; private val h = 48

    private fun kartinka(): FloatArray = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        (128 + 80 * kotlin.math.sin(x / 5.0) * kotlin.math.cos(y / 7.0) + (if ((x / 8 + y / 8) % 2 == 0) 20 else -20)).toFloat()
    }

    @Test fun odinakovye() {
        val a = kartinka()
        assertEquals(1.0, Ocenka.ssim(a, a, w, h), 1e-9)
        assertEquals(99.0, Ocenka.psnr(a, a), 1e-9)
        val (s, p) = Ocenka.sravnit(a, a.copyOf(), w, h)
        assertEquals(1.0, s, 1e-6); assertTrue(p > 90)
    }

    /** Декодер отдаёт яркость в 16–235 (а эталон — 0..255) — после подгонки это не потеря. */
    @Test fun diapazonNePoterya() {
        val a = kartinka()
        val ogr = FloatArray(a.size) { 16 + a[it] * 219f / 255f }
        assertTrue(Ocenka.ssim(a, ogr, w, h) < 0.999)
        val (s, p) = Ocenka.sravnit(a, ogr, w, h)
        assertEquals(1.0, s, 1e-4); assertTrue(p > 60)
    }

    /** Шум σ=5 — PSNR ≈ 20·log10(255/5) ≈ 34,2 дБ; SSIM заметно меньше 1, но выше 0,5. */
    @Test fun shum() {
        val r = Random(7)
        val a = kartinka()
        val b = FloatArray(a.size) { a[it] + (r.nextDouble() * 2 - 1).toFloat() * 5f * 1.7320508f }   // равномерный, σ = 5
        val (s, p) = Ocenka.sravnit(a, b, w, h)
        assertEquals(20 * log10(255.0 / 5), p, 0.4)
        assertTrue("ssim $s", s in 0.5..0.98)
        val b2 = FloatArray(a.size) { a[it] + (r.nextDouble() * 2 - 1).toFloat() * 2f }               // шум меньше — SSIM выше
        assertTrue(Ocenka.sravnit(a, b2, w, h).first > s)
    }

    /** glReadPixels отдаёт строки снизу вверх: первая строка буфера — нижняя строка куска. */
    @Test fun perevorotStrok() {
        val ww = 2; val hh = 2
        val rgba = byteArrayOf(10, 10, 10, -1, 20, 20, 20, -1,      // нижняя строка
                               30, 30, 30, -1, 40, 40, 40, -1)      // верхняя
        val y = Ocenka.yarkostGl(rgba, ww, hh)
        assertEquals(30f, y[0], 0.01f); assertEquals(40f, y[1], 0.01f); assertEquals(10f, y[2], 0.01f)
    }

    @Test fun kusokY() {
        val plane = ByteArray(8 * 4) { it.toByte() }                // 8 в строке, шаг точки 1
        val k = Ocenka.kusokY(plane, 8, 1, 2, 1, 3, 2)
        assertEquals(listOf(10f, 11f, 12f, 18f, 19f, 20f), k.toList())
    }

    @Test fun paryPoVremeni() {
        val k = listOf(1_000_000L, 1_500_000L, 2_000_000L)
        assertEquals(1_500_000L, Ocenka.blizhajshij(k, 1_500_400L))
        assertNull(Ocenka.blizhajshij(k, 1_250_000L))
    }

    @Test fun itog() {
        assertTrue(Ocenka.itog(emptyList(), 10, "1920x1080@30", 6000).contains("oshibka"))
        val j = Ocenka.itog(listOf(0.95 to 36.0, 0.91 to 33.0), 10, "1920x1080@30", 6000)
        assertTrue(j, j.contains("\"ssim\":0.9300,\"ssim_min\":0.9100,\"psnr\":34.50"))
        assertTrue(j.contains("\"par\":2"))
    }
}
