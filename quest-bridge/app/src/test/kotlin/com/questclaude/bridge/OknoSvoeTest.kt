package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // 2.8.16: Плюс путался («верни» — «уже полная», а не полная): картинку режет и форма своего окна
    @Test fun kartinkaUglaCelikom() {
        val k = Okno.kartinka(Okno.Vid(), ar, null)
        for (i in 0..3) assertEquals(0f, k[i], 0.01f)
    }

    @Test fun kartinkaUzkoeOknoSrezaetBoka() {
        val v = Okno.Vid.iz(mapOf("ramka" to "50,0,100,100"), Okno.Vid())   // 960×1080 при картинке 16:9
        val k = Okno.kartinka(v, ar, null)
        assertEquals(25f, k[0], 0.1f); assertEquals(25f, k[2], 0.1f)        // половина ширины картинки — по 25 % с боков
        assertEquals(0f, k[1], 0.1f); assertEquals(0f, k[3], 0.1f)
        assertEquals(25f, k[4], 0.1f)                                         // это — заполнение
        val sKnopkoj = Okno.kartinka(v, ar, floatArrayOf(0.1f, 0f, 0f, 0.2f)) // срез: 10 % окна слева, 20 % снизу
        assertEquals(30f, sKnopkoj[0], 0.1f)                                  // 25 + 10 % окна = 5 % картинки
        assertEquals(20f, sKnopkoj[3], 0.1f)                                  // по высоте окно = картинка
        assertEquals(25f, sKnopkoj[2], 0.1f)
    }

    @Test fun polnyjVozvrashchaetFormu() {
        val v = Okno.Vid.iz(mapOf("ramka" to "50,0,100,100"), Okno.Vid())
        val p = Okno.polnyj(v, ar)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 100f), p.ramka, 0.1f)  // шире — во весь кадр (влезло)
        for (i in 0..3) assertEquals(0f, Okno.kartinka(p, ar, null)[i], 0.2f)
    }

    @Test fun polnyjPrizhatKNizuIOtSerediny() {
        val v = Okno.Vid.iz(mapOf("ramka" to "60,70,90,100"), Okno.Vid())   // 576×324 = 16:9 — уже целиком
        assertArrayEquals(v.ramka, Okno.polnyj(v, ar).ramka, 0.01f)
        val niz = Okno.Vid.iz(mapOf("ramka" to "60,80,90,100"), Okno.Vid())  // 576×216 — шире картинки
        val p = Okno.polnyj(niz, ar)
        assertEquals(100f, p.ramka[3], 0.1f)                                  // касалось низа — от низа
        assertEquals(70f, p.ramka[1], 0.1f)                                   // высота 324 точки = 30 %
        assertEquals(60f, p.ramka[0], 0.1f); assertEquals(90f, p.ramka[2], 0.1f)
        val seredina = Okno.Vid.iz(mapOf("ramka" to "60,70,90,90"), Okno.Vid())
        val q = Okno.polnyj(seredina, ar)
        assertEquals(65f, q.ramka[1], 0.1f); assertEquals(95f, q.ramka[3], 0.1f)  // от середины 80 %
        for (i in 0..3) assertEquals(0f, Okno.kartinka(q, ar, null)[i], 0.2f)
    }

    @Test fun kartinkaVertikalnayaVShirokom() {
        val v = Okno.Vid.iz(mapOf("ramka" to "70,70,100,100"), Okno.Vid())   // 576×324, а картинка телефона стоя 9:16
        val k = Okno.kartinka(v, 9f / 16, null)
        assertTrue(k[1] > 30f && k[3] > 30f)                                   // режет сверху и снизу поровну
        assertEquals(k[1], k[3], 0.01f); assertEquals(0f, k[0], 0.01f)
        val p = Okno.polnyj(v, 9f / 16)                                         // целиком — окно выше, прижато к углу
        assertEquals(100f, p.ramka[3], 0.1f); assertEquals(100f, p.ramka[2], 0.1f)
        for (i in 0..3) assertEquals(0f, Okno.kartinka(p, 9f / 16, null)[i], 0.3f)
    }
}
