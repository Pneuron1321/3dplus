package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    private var fayl: String? = null
    private fun chat() = Chat({ fayl }, { fayl = it })

    @Test fun novyeVnizuIGasnut() {
        val c = chat()
        c.upr(mapOf("kto" to "Lena", "login" to "lena", "tekst" to "привет%20всем", "cvet" to "%2300FF7F", "id" to "a1"), now = 1000)
        c.upr(mapOf("kto" to "Rex_77", "tekst" to "gg"), now = 2000)
        val v = c.vidimye(3000)
        assertEquals(listOf("Lena", "Rex_77"), v.map { it.kto })           // старые первыми — новые внизу
        assertEquals("привет всем", v[0].tekst)
        assertEquals(0xFF00FF7F.toInt(), v[0].cvet)
        assertEquals("rex_77", v[1].login)
        assertEquals(1f, c.yarkost(v[0], 3000))
        assertEquals(0.5f, c.yarkost(v[0], 1000 + Chat.ZHIZN_MS - 5000), 0.01f)   // гаснет за последние 10 с
        assertEquals(listOf("Rex_77"), c.vidimye(1000 + Chat.ZHIZN_MS).map { it.kto })   // прожило 90 с — нет
    }

    @Test fun modUbiraet() {
        val c = chat()
        c.upr(mapOf("kto" to "Troll", "login" to "troll", "tekst" to "плохое", "id" to "x1"), now = 1000)
        c.upr(mapOf("kto" to "Troll", "login" to "troll", "tekst" to "ещё", "id" to "x2"), now = 1100)
        c.upr(mapOf("kto" to "Ann", "tekst" to "норм", "id" to "y1"), now = 1200)
        c.upr(mapOf("ubrat" to "x1"), now = 1300)                            // CLEARMSG
        assertEquals(listOf("ещё", "норм"), c.vidimye(1400).map { it.tekst })
        c.upr(mapOf("ubrat_kto" to "TROLL"), now = 1500)                     // бан/тайм-аут
        assertEquals(listOf("норм"), c.vidimye(1600).map { it.tekst })
        c.upr(mapOf("ochistit" to "1"), now = 1700)
        assertTrue(c.vidimye(1800).isEmpty())
    }

    @Test fun chuzhieSlovaTolkoPokazat() {
        val c = chat()
        c.upr(mapOf("kto" to "<script>Evil", "tekst" to "a%0D%0Ab%07c" + "x".repeat(300)), now = 1000)
        val s = c.vidimye(1000).single()
        assertEquals("scriptEvil", s.kto)                                    // ник — только буквы, цифры, _ и -
        assertTrue(s.tekst.startsWith("a b c"))                              // без переводов строк и управляющих
        assertEquals(Chat.DLINA + 1, s.tekst.length)                         // коротко, с «…»
        c.upr(mapOf("kto" to "Pustoy", "tekst" to "%20%20"), now = 1000)
        assertEquals(1, c.vidimye(1000).size)                                // пустое — не сообщение
    }

    @Test fun pokazZapominaetIMnogoNeHranit() {
        val c = chat()
        repeat(50) { c.upr(mapOf("kto" to "u$it", "tekst" to "m$it"), now = 1000L + it) }
        assertEquals(Chat.MAKS, c.vidimye(2000).size)
        assertEquals("m49", c.vidimye(2000).last().tekst)
        c.upr(mapOf("pokaz" to "0"))
        assertEquals("0", fayl)
        assertTrue(c.vidimye(2000).isEmpty())
        assertEquals(false, chat().pokaz)                                    // после перезапуска — тоже скрыт
        assertTrue(c.status().contains("\"pokaz\":false"))
    }

    @Test fun tyomnyNikSvetlee() {
        assertEquals(0xFFFF4500.toInt(), Chat.cvet("#FF4500", "x"))
        val tyomny = Chat.cvet("#000080", "x")                               // тёмно-синий на тёмной подложке
        assertTrue((tyomny and 0xFF) > 0x80)
        assertEquals(Chat.cvet("", "lena"), Chat.cvet("", "lena"))           // без цвета — свой по нику, всегда тот же
    }

    @Test fun pachkaOdnaVersiya() {
        val c = chat()
        val v0 = c.versiya
        c.pachka(listOf(mapOf("kto" to "A", "tekst" to "раз", "id" to "1"), mapOf("kto" to "B", "tekst" to "два", "id" to "2"),
            mapOf("ubrat" to "1")), now = 1000)
        assertEquals(listOf("два"), c.vidimye(1100).map { it.tekst })
        assertEquals(v0 + 1, c.versiya)                                   // перерисовка — раз на пачку
        c.pachka(emptyList(), now = 1200)
        assertEquals(v0 + 1, c.versiya)
    }

    @Test fun vazhnoeISkorost() {
        val c = chat()
        c.upr(mapOf("kto" to "A", "tekst" to "Плюс, привет"), now = 1000)
        c.upr(mapOf("kto" to "B", "tekst" to "плюсы и минусы"), now = 1000)          // не обращение
        c.upr(mapOf("kto" to "C", "tekst" to "100 bits", "vazhno" to "1"), now = 1000)  // донат — уши пометили
        c.upr(mapOf("kto" to "D", "tekst" to "gg plus"), now = 1000)
        assertEquals(listOf(true, false, true, true), c.vidimye(1100).map { it.vazhno })
        assertEquals(4f / 5, c.skorost(1100), 0.01f)                      // 4 сообщения за 5 с
        assertEquals(0f, c.skorost(1000 + Chat.OKNO_MS + 1), 0.01f)
    }

    @Test fun bystryyVyborVazhnyePervymi() {
        val ss = (1..12).map { Chat.Soob("$it", "u$it", "U$it", "t$it", 0, it.toLong(), vazhno = it == 2 || it == 5) }
        val v = Chat.bystryyVybor(ss)
        assertEquals(listOf("2", "5", "10", "11", "12"), v.map { it.id })   // важные + самые новые, по порядку прихода
        assertEquals(ss.take(3), Chat.bystryyVybor(ss.take(3)))
    }
}
