package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Правила кодовых фраз — те же, что в assets/triggers.json. */
class TriggersTest {
    private val t = Triggers(0.6, mapOf(
        "ru" to mapOf("плюс плюс" to "wake", "зум зум" to "zoom:x2", "зум зум зум" to "zoom:x4",
            "зум зум центр" to "zoom:centr", "плюс тихо" to "tiho"),
        "en" to mapOf("plus plus" to "wake", "zoom zoom" to "zoom:x2"),
    ), mapOf("en" to listOf("please", "plus plus", "please stop")))

    @Test fun grammarHasPhrasesDecoysAndUnk() {
        assertEquals("[\"plus plus\",\"zoom zoom\",\"please\",\"please stop\",\"[unk]\"]", t.grammar("en"))
        assertEquals("[\"[unk]\"]", t.grammar("uk"))
    }

    @Test fun decoyIsNeverAnAction() {
        assertNull(t.match("en", "please stop", listOf(1.0, 1.0)))
        assertNull(t.match("en", "please", listOf(1.0)))
    }

    @Test fun phraseLanguageByMeanConfidence() {
        val ru = Triggers.Heard("ru", "посмотри на экран", listOf(0.9, 0.8, 0.95))
        val en = Triggers.Heard("en", "boss three in the crane", listOf(0.4, 0.5, 0.3, 0.6, 0.5))
        assertEquals("ru", t.bestPhrase(listOf(en, ru))?.lang)
        assertEquals("en", t.bestPhrase(listOf(en, Triggers.Heard("ru", "", emptyList())))?.lang)
        assertNull(t.bestPhrase(listOf(Triggers.Heard("ru", "", emptyList()))))
    }

    @Test fun bonusTipsUkrainianTowardsRussian() {
        val b = Triggers(0.75, emptyMap(), emptyMap(), mapOf("ru" to 0.1))
        val ru = Triggers.Heard("ru", "под ввысь что написано на экране", listOf(0.88))
        val en = Triggers.Heard("en", "but the with shown up", listOf(0.89))
        assertEquals("ru", b.bestPhrase(listOf(en, ru))?.lang)
        val enClear = Triggers.Heard("en", "what is written on the screen", listOf(1.0))
        val ruNoise = Triggers.Heard("ru", "вот из райтон", listOf(0.6))
        assertEquals("en", b.bestPhrase(listOf(ruNoise, enClear))?.lang)
    }

    @Test fun exactPhrasesMap() {
        assertEquals("zoom:x2", t.match("ru", "зум зум", listOf(0.9, 0.95))?.action)
        assertEquals("zoom:x4", t.match("ru", "зум зум зум", listOf(0.9, 0.9, 0.9))?.action)
        assertEquals("zoom:centr", t.match("ru", "зум зум центр", listOf(0.8, 0.8, 0.7))?.action)
        assertEquals("wake", t.match("en", " plus plus ", listOf(1.0, 1.0))?.action)
    }

    @Test fun unsureOrPartialIsIgnored() {
        assertNull(t.match("ru", "плюс плюс", listOf(0.9, 0.4)))       // одно слово неуверенно
        assertNull(t.match("ru", "[unk]", listOf(1.0)))
        assertNull(t.match("ru", "плюс", listOf(1.0)))
        assertNull(t.match("ru", "зум зум", listOf(0.9)))              // слов меньше, чем уверенностей
        assertNull(t.match("en", "зум зум", listOf(1.0, 1.0)))         // фраза чужой модели
    }

    @Test fun chooserPrefersConfidentAndDropsEcho() {
        val c = Triggers.Chooser(1500)
        val ru = Triggers.Hit("wake", "ru", 0.9)
        val en = Triggers.Hit("wake", "en", 0.7)
        assertEquals("ru", c.pick(listOf(en, ru), 1000)?.lang)
        assertNull(c.pick(listOf(en), 1800))                            // та же фраза второй моделью
        assertEquals("zoom:x2", c.pick(listOf(Triggers.Hit("zoom:x2", "ru", 0.8)), 1900)?.action)
        assertEquals("wake", c.pick(listOf(ru), 4000)?.action)          // окно прошло
        assertNull(c.pick(emptyList(), 5000))
    }

    @Test fun stopWordIsTwoInARowSteadyAndLast() {
        val s = Triggers.StopWord(mapOf("ru" to setOf("капи", "копи", "копия"), "en" to setOf("copy")))
        assertEquals(false, s.check("ru", "посмотри на экран копи"))     // одно — мало
        assertEquals(false, s.check("ru", "посмотри на экран копи"))
        assertEquals(false, s.check("ru", "посмотри на экран копи копи"))
        assertEquals(true, s.check("ru", "посмотри на экран копи копия")) // второй кусок подряд, варианты вперемешку
        s.reset()
        assertEquals(false, s.check("ru", "капи капи"))
        assertEquals(false, s.check("ru", "капи капи деньги"))           // мимолётное — сброс
        assertEquals(false, s.check("ru", "капи капи деньги"))
        assertEquals(false, s.check("en", "copy copy"))                   // счётчик у каждой модели свой
        assertEquals(false, s.check("uk", "копи копи"))                   // у языка нет стоп-слов
        assertEquals("посмотри на экран", s.strip("ru", "посмотри на экран Копи копи"))
        assertEquals("посмотри на экран копи", s.strip("ru", "посмотри на экран копи"))
        assertEquals("make a copy", s.strip("en", "make a copy"))
        assertEquals("", s.strip("ru", "копи копи"))
    }

    @Test fun stopPhraseInGrammarEndsWithConfidence() {
        val g = Triggers(0.75, mapOf("ru" to mapOf("плюс плюс" to "wake", "копи копи" to "stop")))
        assertEquals(true, g.endsWithStop("ru", "[unk] [unk] копи копи", listOf(0.3, 0.4, 0.9, 0.8)))
        assertEquals(false, g.endsWithStop("ru", "[unk] копи копи", listOf(0.3, 0.9, 0.6)))   // неуверенно
        assertEquals(false, g.endsWithStop("ru", "копи копи [unk]", listOf(0.9, 0.9, 0.5)))   // не в конце
        assertEquals(false, g.endsWithStop("ru", "копи", listOf(0.99)))
        assertEquals(false, g.endsWithStop("ru", "копи копи", listOf(0.9)))                   // слов ≠ уверенностей
        assertEquals("stop", g.match("ru", "копи копи", listOf(0.9, 0.9))?.action)
    }

    @Test fun fastKopiKopiHeardAsOneWord() {
        val s = Triggers.StopWord(mapOf("ru" to setOf("копи")), one = mapOf("ru" to setOf("копик", "копейку")))
        assertEquals(false, s.check("ru", "как быстро слышно копейку"))
        assertEquals(true, s.check("ru", "как быстро слышно копейку"))
        assertEquals("как быстро слышно", s.strip("ru", "как быстро слышно Копик"))
        assertEquals("дай копейку мне", s.strip("ru", "дай копейку мне"))   // не в конце — не трогаем
    }

    private fun w(vararg x: Pair<String, Double>): List<Triggers.Word> {
        var t = 0.0
        return x.map { (word, conf) -> Triggers.Word(word, t, t + 0.4, conf).also { t += 0.5 } }
    }

    @Test fun checkCheckPlusAtStart() {
        val c = Triggers(0.75, mapOf("ru" to mapOf("плюс плюс" to "wake", "чек чек плюс" to Triggers.CHEK,
            "чек чек" to "plenka:7")))
        // «чек чек плюс кто это» одним дыханием — вызов «чек чек плюс», фраза с конца «плюс» (1,4 с)
        val slova = w("чек" to 0.9, "чек" to 0.9, "плюс" to 0.9, "[unk]" to 0.9)
        assertEquals(1.4, c.wakeAtStart("ru", slova, action = Triggers.CHEK)!!, 1e-9)
        assertNull(c.wakeAtStart("ru", slova))                                   // это не «плюс плюс»
        assertEquals(Triggers.CHEK, c.match("ru", "чек чек плюс", listOf(0.9, 0.9, 0.9))?.action)
        assertEquals("plenka:7", c.match("ru", "чек чек", listOf(0.9, 0.9))?.action)
    }

    @Test fun wakeAtStartOfLongerUtterance() {
        // «плюс плюс посмотри…» одним дыханием: вызов есть, конец его слов — 0,9 с
        assertEquals(0.9, t.wakeAtStart("ru", w("плюс" to 0.9, "плюс" to 0.8, "[unk]" to 0.3))!!, 1e-9)
        assertNull(t.wakeAtStart("ru", w("плюс" to 0.9, "плюс" to 0.8)))                  // ровно вызов — это match()
        assertNull(t.wakeAtStart("ru", w("плюс" to 0.9, "плюс" to 0.5, "[unk]" to 0.9)))   // неуверенно
        assertNull(t.wakeAtStart("ru", w("[unk]" to 0.9, "плюс" to 0.9, "плюс" to 0.9, "[unk]" to 0.9)))  // не в начале
        assertNull(t.wakeAtStart("ru", w("плюс" to 0.9, "тихо" to 0.9, "[unk]" to 0.9)))
        assertEquals(0.9, t.wakeAtStart("en", w("plus" to 0.1, "plus" to 0.1, "look" to 0.1), needConf = false)!!, 1e-9)
    }

    @Test fun earlyWakeNeedsNextWordSteadily() {
        val e = Triggers.EarlyWake(t, chunks = 3)
        val two = w("плюс" to 1.0, "плюс" to 1.0)
        val three = w("плюс" to 1.0, "плюс" to 1.0, "посмотри" to 1.0)
        assertNull(e.check("ru", two))                          // следующего слова ещё нет
        assertNull(e.check("ru", three))
        assertNull(e.check("ru", three))
        assertEquals(0.9, e.check("ru", three)!!, 1e-9)         // третий кусок подряд — будим
        assertNull(e.check("ru", w("плюс" to 1.0, "минус" to 1.0, "один" to 1.0)))
        assertNull(Triggers.EarlyWake(t, chunks = 0).check("ru", three))   // выключен
    }

    @Test fun shortCommandsInsidePhrase() {
        val c = Triggers(0.75, mapOf("ru" to mapOf("плюс плюс" to "wake")), mapOf("ru" to listOf("ухо")),
            commands = mapOf("ru" to mapOf("в ухо" to "rezhim:uho", "компакт" to "compact:prep")))
        assertEquals("rezhim:uho", c.command("ru", "в ухо", listOf(0.9, 0.8))?.action)
        assertNull(c.command("ru", "в ухо", listOf(0.9, 0.5)))
        assertNull(c.command("ru", "в ухо [unk]", listOf(0.9, 0.9, 0.9)))
        assertNull(c.match("ru", "в ухо", listOf(1.0, 1.0)))      // вне фразы команда — не вызов
        assertEquals("[\"плюс плюс\",\"в ухо\",\"компакт\",\"ухо\",\"[unk]\"]", c.grammar("ru"))
    }
}
