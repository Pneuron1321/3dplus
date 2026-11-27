package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Топ-донатор: задать, сумма и валюта, файл, скрыть, сброс, чужой ник. */
class TopTest {
    @Test fun zadatIFayl() {
        var fayl = ""
        val t = Top({ null }, { fayl = it })
        assertFalse(t.vidna)                                 // никого — не видна
        t.upr(mapOf("imya" to "Rex_77", "summa" to "25"))
        assertTrue(t.vidna)
        assertEquals("$25", t.skolko())
        t.upr(mapOf("summa" to "12,5"))
        assertEquals("$12.50", t.skolko())
        assertEquals("12.5\t$\t1\tRex_77", fayl)
        val t2 = Top({ fayl }, { })                          // после перезапуска — тот же
        assertEquals("Rex_77", t2.imya)
        assertEquals("$12.50", t2.skolko())
    }

    @Test fun valyutaSkrytSbros() {
        val t = Top({ "320.0\tUAH\t1\tОлег" }, { })
        assertEquals("320 UAH", t.skolko())
        assertEquals("Олег", t.imya)
        t.upr(mapOf("pokaz" to "0"))
        assertFalse(t.vidna)
        t.upr(mapOf("pokaz" to "1"))
        assertTrue(t.vidna)
        t.upr(mapOf("sbros" to "1"))
        assertFalse(t.vidna)
        assertEquals("", t.imya)
    }

    @Test fun chuzhoyNik() {
        val t = Top({ null }, { })
        t.upr(mapOf("imya" to "Evil%20%3Cb%3E%0Aname%2F..%2Fx", "summa" to "5"))
        assertEquals("Evilbnamex", t.imya)                  // только буквы, цифры, _ и -
        t.upr(mapOf("imya" to "a".repeat(40)))
        assertEquals(25, t.imya.length)
        assertTrue(t.status().contains("\"tekst\":\"$5\""))
    }
}
