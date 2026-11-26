package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.8.17: слово клипа — «скскс» ×3 по умолчанию, кнопкой — «клип клип клип»; серия скс/кс в ушах; журнал ушей. */
class KlipSlovoTest {
    @After fun posle() = KlipSlovo.zanovo()

    @Test fun poUmolchaniyuSksIFajlPomnit() {
        val d = Files.createTempDirectory("slovo").toFile()
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.SKS, KlipSlovo.slovo)
        assertEquals("скскс скскс скскс", KlipSlovo.fraza())
        assertTrue(KlipSlovo.beret(Triggers.SKS)); assertFalse(KlipSlovo.beret("klip"))
        assertTrue(KlipSlovo.beret("wake")); assertTrue(KlipSlovo.beret("zoom:x2"))     // другое не трогаем
        assertTrue(KlipSlovo.pereklyuchit())                                           // кнопка
        assertEquals("klip", java.io.File(d, KlipSlovo.FAJL).readText())
        assertTrue(KlipSlovo.beret("klip")); assertFalse(KlipSlovo.beret(Triggers.SKS))
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.KLIP, KlipSlovo.slovo)                                 // после перезапуска — то же
        assertFalse(KlipSlovo.postavit("klip")); assertFalse(KlipSlovo.postavit("chush")); assertFalse(KlipSlovo.postavit(null))
        assertTrue(KlipSlovo.postavit("скскс"))
        assertEquals("{\"slovo\":\"sks\",\"fraza\":\"скскс скскс скскс\"}", KlipSlovo.json())
        java.io.File(d, KlipSlovo.FAJL).writeText("musor")
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.SKS, KlipSlovo.slovo)                                  // мусор в файле — по умолчанию
        d.deleteRecursively()
    }

    private val t = Triggers(0.75, mapOf("ru" to mapOf("клип клип клип" to "klip")),
        seriya = mapOf("ru" to Triggers.Seriya(setOf("скс", "кс"), 3, 1.5, Triggers.SKS)))
    private fun w(s: String, a: Double, b: Double, c: Double = 1.0) = Triggers.Word(s, a, b, c)

    @Test fun grammatikaZnaetSlovaSerii() =
        assertEquals("[\"клип клип клип\",\"скс\",\"кс\",\"[unk]\"]", t.grammar("ru"))

    @Test fun seriyaOdnimItogomIRaznymi() {
        val s = Triggers.SeriyaSlov(t)
        val h = s.check("ru", listOf(w("скс", 0.1, 0.4), w("скс", 0.6, 0.9, 0.8), w("кс", 1.1, 1.3)), 0.0)
        assertEquals(Triggers.SKS, h?.action); assertEquals(0.8, h!!.conf, 1e-9)       // худшее слово серии
        assertNull(s.check("ru", listOf(w("скс", 2.0, 2.3)), 0.0))                   // счёт заново: одно — не клип
        // пауза между словами — конец итога: «скс | скс кс» — одна серия
        val s2 = Triggers.SeriyaSlov(t)
        assertNull(s2.check("ru", listOf(w("скс", 0.1, 0.4)), 10.0))
        assertEquals(Triggers.SKS, s2.check("ru", listOf(w("скс", 1.2, 1.5), w("кс", 1.7, 2.0)), 10.0)?.action)
        // время слов — от создания распознавателя (base): после «fresh» серия продолжается по общим часам
        val s3 = Triggers.SeriyaSlov(t)
        assertNull(s3.check("ru", listOf(w("скс", 4.0, 4.3), w("кс", 4.5, 4.8)), 0.0))
        assertEquals(Triggers.SKS, s3.check("ru", listOf(w("кс", 0.2, 0.5)), 5.0)?.action)
    }

    @Test fun seriyuRvutDrugoeSlovoPauzaINeuverennost() {
        val s = Triggers.SeriyaSlov(t)
        assertNull(s.check("ru", listOf(w("скс", 0.1, 0.4), w("кс", 0.6, 0.9), w("клип", 1.0, 1.2), w("скс", 1.3, 1.5)), 0.0))
        assertNull(s.check("ru", listOf(w("скс", 3.1, 3.3), w("кс", 3.4, 3.6)), 0.0))     // 1,6 с после «скс» — заново
        assertNull(s.check("ru", listOf(w("скс", 5.2, 5.5)), 0.0))                     // 1,6 с тишины — заново
        assertNull(s.check("ru", listOf(w("кс", 5.7, 5.9, 0.6)), 0.0))                 // неуверенное — рвёт
        assertNull(s.check("ru", listOf(w("скс", 6.0, 6.2), w("[unk]", 6.3, 6.4), w("кс", 6.5, 6.7)), 0.0))
        assertEquals(Triggers.SKS, s.check("ru", listOf(w("скс", 6.9, 7.1)), 0.0)?.action)   // [unk] не рвёт
        assertNull(Triggers.SeriyaSlov(t).check("en", listOf(w("скс", 0.0, 0.1), w("скс", 0.2, 0.3), w("скс", 0.4, 0.5)), 0.0))
        assertNull(Triggers.SeriyaSlov(Triggers(0.75, emptyMap())).check("ru", listOf(w("скс", 0.0, 0.1)), 0.0))
    }

    @Test fun zhurnalUshej() {
        val e = Ear()
        e.zapomnit("скс кс", listOf(1.0, 0.784), ms = 5)
        e.zapomnit("[unk]", listOf(0.5)); e.zapomnit(" ", emptyList())                  // пустое и [unk] — нет
        e.zapomnit("плюс \"плюс\"", listOf(0.9, 0.9), ms = 6)
        assertEquals("[{\"ms\":5,\"t\":\"скс кс\",\"c\":[1.00,0.78]},{\"ms\":6,\"t\":\"плюс плюс\",\"c\":[0.90,0.90]}]", e.slyshalJson())
        repeat(Ear.SLYSHAL + 5) { e.zapomnit("кс", listOf(1.0), ms = 100L + it) }
        assertEquals(Ear.SLYSHAL, e.slyshalJson().split("\"t\"").size - 1)
        assertTrue(e.slyshalJson().endsWith("{\"ms\":134,\"t\":\"кс\",\"c\":[1.00]}]"))
    }
}
