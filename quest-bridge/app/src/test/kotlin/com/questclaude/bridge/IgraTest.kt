package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Табло соведущего: пусто — не видно; попаданий не больше ставок; вызов по-английски, коротко. */
class IgraTest {
    @Test fun tablo() {
        val g = Igra()
        assertFalse(g.vidna)
        g.upr(mapOf("popal" to "7", "stavok" to "5"))
        assertEquals(5, g.popal)                             // сбылось не больше, чем поставлено
        assertTrue(g.vidna)
        g.upr(mapOf("vyzov" to "Win%201v3%20at%20the%20bridge%20" + "x".repeat(80), "vyzovov" to "3", "sdelano" to "2"))
        assertEquals(Igra.MAKS_VYZOV, g.vyzov.length)
        assertEquals(2, g.sdelano)
        g.upr(mapOf("pokaz" to "0"))
        assertFalse(g.vidna)
        g.upr(mapOf("pokaz" to "1", "sbros" to "1"))
        assertFalse(g.vidna)
        assertEquals(0, g.stavok)
    }

    @Test fun vItogah() {
        val g = Igra().apply { upr(mapOf("popal" to "3", "stavok" to "5", "vyzovov" to "2", "sdelano" to "1")) }
        val r = Itogi.ryady(60_000, 60, 60, 2, null, g)
        assertEquals("Plus calls" to "3/5", r[3])
        assertEquals("Challenges" to "1/2", r[4])
    }
}
