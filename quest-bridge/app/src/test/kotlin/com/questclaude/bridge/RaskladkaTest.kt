package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaskladkaTest {
    @Test fun dvaGostyaPoCentruSlevaMezhduTabloIRozoy() {
        val p = Raskladka.plitki(2, 2)
        assertEquals(2, p.size)
        assertEquals(240f, p[0].s, 0f)
        assertEquals(Raskladka.KRAY, p[0].x, 0f)
        assertTrue(p[0].y >= Raskladka.VERH)
        assertTrue(p[1].niz <= Raskladka.NIZ)
        assertEquals(p[0].niz + Raskladka.ZAZOR, p[1].y, 0.01f)
        // столбик по центру промежутка
        assertEquals(p[0].y - Raskladka.VERH, Raskladka.NIZ - p[1].niz, 0.5f)
    }

    @Test fun triMestaMenshe() {
        val p = Raskladka.plitki(3, 3)
        assertEquals(210f, p[0].s, 0f)
        assertTrue(p[0].y >= Raskladka.VERH && p[2].niz <= Raskladka.NIZ)
        // один гость при трёх местах — того же размера, по центру
        val o = Raskladka.plitki(1, 3).single()
        assertEquals(210f, o.s, 0f)
        assertEquals((Raskladka.VERH + Raskladka.NIZ) / 2, o.centrY, 0.5f)
        assertTrue(Raskladka.plitki(0, 2).isEmpty())
    }

    /** Без камеры — строка с именем (ниже квадрата), столбик всё равно по центру; пузырь — по середине строки. */
    @Test fun bezKameryStroka() {
        val p = Raskladka.plitki(listOf(true, false, true), 3)
        assertEquals(listOf(210f, Raskladka.STROKA, 210f), p.map { it.h })
        assertTrue(p.all { it.s == 210f && it.x == Raskladka.KRAY })
        assertEquals(p[0].niz + Raskladka.ZAZOR, p[1].y, 0.01f)
        assertEquals(p[1].niz + Raskladka.ZAZOR, p[2].y, 0.01f)
        assertEquals(p[0].y - Raskladka.VERH, Raskladka.NIZ - p[2].niz, 0.5f)
        assertEquals(p[1].y + Raskladka.STROKA / 2, p[1].centrY, 0f)
        assertTrue(!p[1].video && p[0].video)
        val vse = Raskladka.plitki(listOf(false, false), 2)                     // оба без камер — две строки
        assertEquals(2 * Raskladka.STROKA + Raskladka.ZAZOR, vse[1].niz - vse[0].y, 0.01f)
    }

    @Test fun puzyrSpravaOtPlitki() {
        val p = Raskladka.plitki(1, 2).single()
        assertEquals(p.pravo + Raskladka.PUZYR_OTSTUP, Raskladka.puzyrX(p), 0f)
        assertTrue(Raskladka.puzyrX(p) + Raskladka.PUZYR_SHIR < 960)            // не дальше середины кадра
    }

    @Test fun nizMezhduRozoyIKameroy() {
        // 1080p: роза до 161, окно камеры лёжа с 1363 — центр 762, а не 960
        assertEquals(762, Raskladka.centrNiza(1920, 161, 1363, 1100))
        // без камеры — между розой и правым краем
        assertEquals((161 + 1920) / 2, Raskladka.centrNiza(1920, 161, 1920, 1100))
        // узко — картинка не выходит за левый край
        assertEquals(550, Raskladka.centrNiza(1920, 0, 400, 1100))
    }

    /** 2.7.8: `gosti razmer` — плитка больше; не влезает по высоте — квадраты меньше, но в промежутке. */
    @Test fun razmerPlitki() {
        val p = Raskladka.plitki(listOf(true, true), 2, listOf(1.75f, 1f))
        assertEquals(420f, p[0].s, 0.5f)
        assertEquals(240f, p[1].s, 0.5f)
        assertTrue(p[0].y >= Raskladka.VERH && p[1].niz <= Raskladka.NIZ + 0.5f)
        val b = Raskladka.plitki(listOf(true, true), 2, listOf(2f, 2f))           // 960 + зазор — не влезает
        assertTrue(b[1].niz <= Raskladka.NIZ + 0.5f && b[0].y >= Raskladka.VERH - 0.5f)
        assertEquals(b[0].s, b[1].s, 0.5f)
        assertTrue(b[0].pravo + 14f <= Raskladka.SLOY_LG)                         // свечение влезает в слой рамок
    }
}
