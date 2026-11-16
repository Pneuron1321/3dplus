package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Тесно / свободно» (2.3.9): игра просела, эфир теряет кадры, шлем греется, чат кипит. */
class DispetcherTest {
    private fun rovno(d: Dispetcher, sek: Int, fps: Float = 72f, s0: Long = 0) {
        for (i in 0 until sek) d.shag(s0 + i * 1000L, fps, 0, 0, 0f)
    }

    @Test fun igraProselaDveSekundy() {
        val d = Dispetcher(otpuskMs = 5_000)
        rovno(d, 20)
        assertEquals(72f, d.norma())
        d.shag(20_000, 50f, 0, 0, 0f)
        assertFalse(d.tesno)                                  // одна секунда — ещё не тесно
        d.shag(21_000, 48f, 0, 0, 0f)
        assertTrue(d.tesno)
        assertTrue(d.igre)                                    // тесно самой игре — бридж сбрасывает своё
        assertEquals("игра 48 к/с из 72", d.pochemu)
        assertEquals(72f, d.norma())                          // просевшие секунды в норму не идут
        rovno(d, 4, s0 = 22_000)
        assertTrue(d.tesno)                                   // свободно — только после 5 с без причин
        rovno(d, 3, s0 = 26_000)
        assertFalse(d.tesno)
        assertFalse(d.igre)
        assertEquals("", d.pochemu)
    }

    @Test fun efirTeryaetKadryIChatKipit() {
        val d = Dispetcher(otpuskMs = 2_000)
        d.shag(0, 72f, 10, 0, 0f)
        assertFalse(d.tesno)                                  // первое число потерь — отсчёт, не причина
        d.shag(1_000, 72f, 13, 0, 3.5f)
        assertTrue(d.tesno)
        assertFalse(d.igre)                                   // игре не тесно — украшения бриджа не трогаем
        assertEquals("эфир теряет кадры, чат кипит", d.pochemu)
    }

    @Test fun shlemGreetsya() {
        val d = Dispetcher()
        d.shag(0, 0f, 0, 1, 0f)                               // экран стоит — кадры не считаем
        assertFalse(d.tesno)
        d.shag(1_000, 0f, 0, Dispetcher.TEPLO, 0f)
        assertTrue(d.tesno && d.igre)
        assertEquals("шлем греется", d.pochemu)
    }

    @Test fun bezNormyNeSudim() {
        val d = Dispetcher()
        rovno(d, 5)                                           // меньше 10 ровных секунд — уровня ещё нет
        d.shag(5_000, 20f, 0, 0, 0f)
        d.shag(6_000, 20f, 0, 0, 0f)
        assertFalse(d.tesno)
    }
}
