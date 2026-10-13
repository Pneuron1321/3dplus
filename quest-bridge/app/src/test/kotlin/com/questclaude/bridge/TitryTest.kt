package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Субтитры: два языка, строки живут своё время, видно не больше двух, новые внизу; мусор — прочь. */
class TitryTest {
    private var now = 1_000_000L
    private val t = Titry { now }

    @Test fun zhivutSvoyoVremya() {
        assertTrue(t.dobavit("ya", "Plus, look at the screen"))
        assertEquals(1, t.seychas().size)
        now += (Titry.poDline("Plus, look at the screen") * 1000).toLong() - 1
        assertEquals(1, t.seychas().size)
        now += 2
        assertEquals(0, t.seychas().size)
    }

    @Test fun neBolsheDvuhNovyeVnizu() {
        t.dobavit("ya", "one", 10.0)
        t.dobavit("plus", "two", 10.0)
        t.dobavit("plus", "three", 10.0)
        assertEquals(listOf("two", "three"), t.seychas().map { it.tekst })
        assertEquals("plus", t.seychas().last().kto)
    }

    @Test fun sekVGranicah() {
        t.dobavit("plus", "short", 0.5)
        now += 2400
        assertEquals(1, t.seychas().size)                   // не меньше 2,5 с
        t.dobavit("plus", "long", 999.0)
        now += 15_000
        assertEquals(0, t.seychas().size)                   // не больше 14 с
    }

    @Test fun musorProch() {
        assertFalse(t.dobavit("ya", "   \n\t "))
        assertFalse(t.dobavit("chat", "hello"))             // только ya и plus
        assertEquals("a b c", Titry.chisto("a\u0007 b\n\n c"))
        val d = Titry.chisto("x".repeat(400))
        assertEquals(Titry.MAKS_DLINA, d.length)
        assertTrue(d.endsWith("…"))
    }

    /** Два языка: второй ряд — перевод; тот же текст переводом не дублируется; читать — по длинному. */
    @Test fun dvaYazyka() {
        val ru = "Плюс, посмотри на карту и скажи, где враги"
        val en = "Plus, look at the map and tell me where the enemies are"
        t.upr(mapOf("kto" to "ya", "t" to java.net.URLEncoder.encode(ru, "UTF-8").replace("+", "%20"),
            "t2" to java.net.URLEncoder.encode(en, "UTF-8").replace("+", "%20")))
        val s = t.seychas().single()
        assertEquals(ru, s.tekst)
        assertEquals(en, s.perevod)
        assertEquals(now + (Titry.poDline(en) * 1000).toLong(), s.doMs)    // английский длиннее — по нему
        t.dobavit("plus", "GG", 5.0, "GG")
        assertEquals("", t.seychas().last().perevod)
    }

    @Test fun uprIzZaprosa() {
        val j = t.upr(mapOf("kto" to "plus", "t" to "Hello%2C%20chat%21%20%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82", "sek" to "5"))
        assertTrue(j, j.contains("\"ok\":true"))
        assertEquals("Hello, chat! Привет", t.seychas()[0].tekst)
        t.upr(mapOf("vidny" to "0"))
        assertEquals(0, t.seychas().size)
        t.upr(mapOf("vidny" to "1"))
        assertEquals(1, t.seychas().size)
    }
}
