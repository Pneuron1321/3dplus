package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Субтитры: два языка, строки живут своё время, видно не больше двух, новые внизу; мусор — прочь. */
class TitryTest {
    private var now = 1_000_000L
    private val t = Titry({ now })

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

    /** 2.5.0: внизу — Александр, Плюс, игра (до двух); у каждого гостя — последняя реплика; или все внизу. */
    @Test fun golosaIGosti() {
        t.dobavit("igra", "Freeze! Police!", 10.0, "Стоять! Полиция!")
        t.dobavit("g1", "Ты видел?", 10.0, "Did you see that?")
        t.dobavit("g1", "Это было круто", 10.0, "That was cool")
        t.dobavit("g2", "Hi all", 10.0, "Всем привет")
        t.dobavit("ya", "Смотри", 10.0, "Look")
        t.dobavit("plus", "Вижу", 10.0, "I see")
        val s = t.seychas()
        assertEquals(listOf("g1", "g2", "ya", "plus"), s.map { it.kto })
        assertEquals("Это было круто", s.first { it.kto == "g1" }.tekst)
        val vnizu = t.seychas(gostiVnizu = true)
        assertEquals(Titry.MAKS_VNIZU, vnizu.size)
        assertEquals(listOf("g2", "ya", "plus"), vnizu.map { it.kto })
        assertFalse(t.dobavit("g4", "нет такого"))
        // g2 убран с экрана (плитки нет) — его слова внизу со всеми, g1 — у плитки
        val bezPlitki = t.seychas(uPlitok = setOf("g1"))
        assertEquals(listOf("g1", "g2", "ya", "plus"), bezPlitki.map { it.kto })
        assertEquals(1, bezPlitki.count { it.kto == "g1" })
    }

    /** Пока sinhron слушает микрофон — фраза ушей (titr ya) не дублируется; от sinhron (ot=s) — да. */
    @Test fun yaNeDvazhdy() {
        var slushaet = true
        val tt = Titry({ now }, { slushaet })
        assertTrue(tt.upr(mapOf("kto" to "ya", "t" to "abc")).contains("\"dubl\":true"))
        assertTrue(tt.upr(mapOf("kto" to "ya", "t" to "abc", "ot" to "s")).contains("\"ok\":true"))
        assertTrue(tt.upr(mapOf("kto" to "plus", "t" to "xyz")).contains("\"ok\":true"))
        slushaet = false
        assertTrue(tt.upr(mapOf("kto" to "ya", "t" to "def")).contains("\"ok\":true"))
        assertEquals(listOf("xyz", "def"), tt.seychas().map { it.tekst })
    }

    /** Черновик растёт на месте, чистовик заменяет и закрепляет; опоздавший черновик его не трогает. */
    @Test fun chernovikChistovik() {
        val tt = Titry({ now })
        tt.upr(mapOf("kto" to "ya", "t" to "привет как", "ot" to "s", "id" to "r1"))
        tt.upr(mapOf("kto" to "ya", "t" to "привет как дела", "ot" to "s", "id" to "r1"))
        assertEquals(listOf("привет как дела"), tt.seychas().map { it.tekst })
        val o = tt.upr(mapOf("kto" to "ya", "t" to "Привет, как дела?", "t2" to "Hi, how are you?", "ot" to "s", "id" to "r1", "tochno" to "1"))
        assertTrue(o.contains("\"id\":true") && o.contains("\"zakreplena\":true"))
        tt.upr(mapOf("kto" to "ya", "t" to "привет как дела ну", "ot" to "s", "id" to "r1"))
        assertEquals(listOf("Привет, как дела?"), tt.seychas().map { it.tekst })
        assertEquals("Hi, how are you?", tt.seychas()[0].perevod)
        tt.upr(mapOf("kto" to "ya", "t" to "вторая реплика", "ot" to "s", "id" to "r2"))
        assertEquals(2, tt.seychas().size)
    }

    /** Уши (полная модель) — чистовик для реплики sinhron, кто бы ни пришёл первым; чужая реплика не трогается. */
    @Test fun ushiChistovik() {
        val tt = Titry({ now }, { true })
        tt.upr(mapOf("kto" to "ya", "t" to "плюс покажи карту", "ot" to "s", "id" to "r1"))
        val o = tt.upr(mapOf("kto" to "ya", "t" to "Покажи карту.", "t2" to "Show the map."))
        assertTrue(o, o.contains("\"chistovik\":true"))
        assertEquals(listOf("Покажи карту."), tt.seychas().map { it.tekst })
        // уши раньше sinhron: ждут реплику и становятся её текстом
        tt.upr(mapOf("kto" to "ya", "t" to "Какая сейчас погода?"))
        tt.upr(mapOf("kto" to "ya", "t" to "какая сейчас пагода", "ot" to "s", "id" to "r2"))
        assertEquals("Какая сейчас погода?", tt.seychas().last().tekst)
        assertTrue(tt.zakreplena("ya", "r2"))
        // непохожая реплика — остаётся своей
        tt.upr(mapOf("kto" to "ya", "t" to "Стреляй левее."))
        tt.upr(mapOf("kto" to "ya", "t" to "вчера смотрели кино до ночи с друзьями и потом гуляли", "ot" to "s", "id" to "r3"))
        assertEquals("вчера смотрели кино до ночи с друзьями и потом гуляли", tt.seychas().last().tekst)
        // старая фраза ушей (больше 12 с) не цепляется
        now += 13_000
        tt.upr(mapOf("kto" to "ya", "t" to "стреляй левее", "ot" to "s", "id" to "r4"))
        assertEquals("стреляй левее", tt.seychas().last().tekst)
    }
}
