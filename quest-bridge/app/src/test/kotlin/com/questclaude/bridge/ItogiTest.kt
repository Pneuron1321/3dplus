package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Итоги вечера: строки от Плюса, показ, цифры по-английски, новый эфир — с нуля, конец эфира — убрать. */
class ItogiTest {
    @Test fun strokiIPokaz() {
        val i = Itogi()
        i.upr(mapOf("stroki" to "Clutch%201v3%20on%20Bridge%0A%0AChat%20named%20the%20robot%20Bolt", "pokaz" to "1"))
        assertTrue(i.vidny)
        assertEquals(listOf("Clutch 1v3 on Bridge", "Chat named the robot Bolt"), i.stroki)
        i.upr(mapOf("stroki" to (1..10).joinToString("%0A") { "line$it" }))
        assertEquals(Itogi.MAKS_STROK, i.stroki.size)
        i.upr(mapOf("pokaz" to "0"))
        assertFalse(i.vidny)
        i.vyzovov = 7
        i.upr(mapOf("pokaz" to "1"))
        i.novyEfir()
        assertTrue(i.vidny)                                  // включили до эфира — так и начнётся
        assertEquals(0, i.vyzovov)
        assertEquals(emptyList<String>(), i.stroki)
        i.konecEfira()
        assertFalse(i.vidny)                                 // следующий эфир — не с тёмного экрана
    }

    @Test fun ryady() {
        assertEquals("12 min", Itogi.vremya(12 * 60_000L + 5000))
        assertEquals("1:52:10", Itogi.vremya(((1 * 60 + 52) * 60 + 10) * 1000L))
        val c = Cel({ "100.0\t23.0\t€\t1\tNew mic" }, { })
        val r = Itogi.ryady(3_600_000L, 3600, 60 * 1234, 14, c)
        assertEquals("On air" to "1:00:00", r[0])
        assertEquals("Airtime coins" to "+60.00  (total 1,234.00)", r[1])
        assertEquals("Questions to Plus" to "14", r[2])
        assertEquals("Goal" to "New mic · €23 / €100", r[3])
        assertEquals(3, Itogi.ryady(0, 0, 0, 0, Cel({ null }, { })).size)   // без цели — без ряда
    }
}
