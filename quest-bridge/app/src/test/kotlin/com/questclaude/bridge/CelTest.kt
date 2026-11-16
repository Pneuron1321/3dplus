package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Цель доната: задать, прибавить, валюта, файл, скрыть. */
class CelTest {
    @Test fun zadatIPribavit() {
        var fayl = ""
        val c = Cel({ null }, { fayl = it })
        assertFalse(c.vidna)                                 // без цели — не видна
        c.upr(mapOf("summa" to "100", "imya" to "New%20mic"))
        assertTrue(c.vidna)
        assertEquals("€0 / €100", c.tekst())
        c.upr(mapOf("plus" to "23.5"))
        assertEquals("€23.50 / €100", c.tekst())
        assertEquals(0.235f, c.dolya(), 1e-4f)
        c.upr(mapOf("plus" to "-50"))
        assertEquals(0.0, c.sobrano, 0.0)                    // не меньше нуля
        c.upr(mapOf("sobrano" to "250"))
        assertEquals(1f, c.dolya(), 0f)                      // полоса не длиннее цели
        assertEquals("€250 / €100", c.tekst())
        assertEquals("100.0\t250.0\t€\t1\tNew mic", fayl)
    }

    @Test fun izFaylaIValyuta() {
        val c = Cel({ "1500.0\t320.0\tUAH\t1\tКамера для стрима" }, { })
        assertEquals("Камера для стрима", c.imya)
        assertEquals("320 / 1,500 UAH", c.tekst())
        c.upr(mapOf("valyuta" to "%24"))
        assertEquals("$320 / $1,500", c.tekst())
        assertEquals(null, Cel.chislo("abc"))
        assertEquals(12.5, Cel.chislo("12,5")!!, 0.0)
    }

    @Test fun skrytISbros() {
        val c = Cel({ null }, { })
        c.upr(mapOf("summa" to "50"))
        c.upr(mapOf("pokaz" to "0"))
        assertFalse(c.vidna)
        c.upr(mapOf("pokaz" to "1"))
        assertTrue(c.vidna)
        val j = c.upr(mapOf("sbros" to "1"))
        assertFalse(c.vidna)
        assertTrue(j, j.contains("\"summa\":0.0"))
        assertEquals(0, Cel({ "битый" }, { }).summa.toInt())   // битый файл — без цели
    }
}
