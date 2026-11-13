package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.8.1: своё окно камеры — края в % кадра; голосом «растяни влево на 10», «сдвинь вниз на 5», «больше на 15 %». */
class OknoSvoeTest {
    private val ar = 16f / 9

    @Test fun ramkaVKadre() {
        val v = Okno.Vid.iz(mapOf("ramka" to "50,10,100,60"), Okno.Vid())
        assertEquals("svoe", v.rezhim)
        // слева 960, сверху 108 → GL: y = 1080 − 108 − 540 = 432
        assertArrayEquals(intArrayOf(960, 432, 960, 540), Okno.mesto(1920, 1080, ar, v))
    }

    @Test fun rastyaniVlevoIzUgla() {
        val ugol = Okno.Vid()                                     // справа внизу, 25 %
        val r0 = Okno.ramkaSeychas(ugol, ar)
        val v = Okno.izmenit(ugol, mapOf("rastyani" to "vlevo:10"), ar)
        assertEquals("svoe", v.rezhim)
        assertEquals(r0[0] - 10f, v.ramka[0], 0.01f)            // левый край на 10 % левее
        assertEquals(r0[2], v.ramka[2], 0.01f)                  // правый — на месте
        assertEquals(r0[1], v.ramka[1], 0.01f)
    }

    @Test fun sdvinNeZaKadr() {
        val v0 = Okno.Vid.iz(mapOf("ramka" to "10,80,40,95"), Okno.Vid())
        val v = Okno.izmenit(v0, mapOf("sdvin" to "vniz:20"), ar)   // до низа — только 5 %
        assertArrayEquals(floatArrayOf(10f, 85f, 40f, 100f), v.ramka, 0.01f)
    }

    @Test fun szhatNeMenshePyati() {
        val v0 = Okno.Vid.iz(mapOf("ramka" to "40,40,60,60"), Okno.Vid())
        val v = Okno.izmenit(v0, mapOf("rastyani" to "vpravo:-30"), ar)  // «сожми справа на 30» — не меньше 5 %
        assertEquals(45f, v.ramka[2], 0.01f)
    }

    @Test fun masshtabOtCentra() {
        val v0 = Okno.Vid.iz(mapOf("ramka" to "40,40,60,60"), Okno.Vid())
        assertArrayEquals(floatArrayOf(35f, 35f, 65f, 65f), Okno.izmenit(v0, mapOf("masshtab" to "1.5"), ar).ramka, 0.01f)
    }

    @Test fun pomnitStrokoy() {
        val v = Okno.Vid.iz(mapOf("ramka" to "50,10,100,60"), Okno.Vid())
        val v2 = Okno.Vid.izStroki(v.toString())
        assertEquals("svoe", v2.rezhim)
        assertArrayEquals(v.ramka, v2.ramka, 0.01f)
        assertEquals("ugol", Okno.Vid.izStroki("ugol pn 0.25 90 1 4.0").rezhim)   // старый файл — как был
    }

    @Test fun obrezkaBezIskazheniya() {
        // окно шире картинки (2:1 против 16:9) — по высоте срезаем: sy = (16/9)/2
        val m = Okno.obrezka(2f, ar)
        assertEquals(1f, m[0], 0.001f)
        assertEquals(ar / 2f, m[5], 0.001f)
        assertEquals((1f - ar / 2f) / 2f, m[13], 0.001f)
    }
}
