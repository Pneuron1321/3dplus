package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.8.17–2.8.18: слово клипа — «кс-кс-кс» по умолчанию, кнопкой — «клип клип клип»; правило ушей; журнал ушей. */
class KlipSlovoTest {
    @After fun posle() = KlipSlovo.zanovo()

    @Test fun poUmolchaniyuSksIFajlPomnit() {
        val d = Files.createTempDirectory("slovo").toFile()
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.SKS, KlipSlovo.slovo)
        assertEquals("кс-кс-кс", KlipSlovo.fraza())
        assertTrue(KlipSlovo.beret(Triggers.SKS)); assertFalse(KlipSlovo.beret("klip"))
        assertTrue(KlipSlovo.beret("wake")); assertTrue(KlipSlovo.beret("zoom:x2"))     // другое не трогаем
        assertTrue(KlipSlovo.pereklyuchit())                                           // кнопка
        assertEquals("klip", java.io.File(d, KlipSlovo.FAJL).readText())
        assertTrue(KlipSlovo.beret("klip")); assertFalse(KlipSlovo.beret(Triggers.SKS))
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.KLIP, KlipSlovo.slovo)                                 // после перезапуска — то же
        assertFalse(KlipSlovo.postavit("klip")); assertFalse(KlipSlovo.postavit("chush")); assertFalse(KlipSlovo.postavit(null))
        assertTrue(KlipSlovo.postavit("скскс"))
        assertEquals("{\"slovo\":\"sks\",\"fraza\":\"кс-кс-кс\",\"primanki\":true}", KlipSlovo.json())
        java.io.File(d, KlipSlovo.FAJL).writeText("musor")
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.SKS, KlipSlovo.slovo)                                  // мусор в файле — по умолчанию
        d.deleteRecursively()
    }

    private val t = Triggers(0.75, mapOf("ru" to mapOf("клип клип клип" to "klip")),
        seriya = mapOf("ru" to Triggers.Seriya(setOf("скс", "кс"), setOf("скс"), listOf("скажи", "скорее"), Triggers.SKS)))
    private fun w(s: String, c: Double = 1.0) = Triggers.Word(s, 0.0, 0.1, c)

    @Test fun grammatika() {
        assertEquals("[\"клип клип клип\",\"скс\",\"кс\",\"скажи\",\"скорее\",\"[unk]\"]", t.grammar("ru"))
        assertEquals("[\"клип клип клип\",\"скс\",\"кс\",\"[unk]\"]", t.grammar("ru", sPrimankami = false))
        assertEquals("[\"клип клип клип\",\"[unk]\"]", t.grammar("ru", sSeriej = false))   // слово клипа — «клип»
    }

    @Test fun egoKsKsKs() {                          // его живые итоги 26.11 (2.8.17 их не брал: ждал три слова)
        assertEquals(Triggers.SKS, t.seriya("ru", listOf(w("скс", 0.79)))?.action)
        assertEquals(0.835, t.seriya("ru", listOf(w("скс", 1.0), w("кс", 0.67)))!!.conf, 1e-9)
        assertEquals(Triggers.SKS, t.seriya("ru", listOf(w("скс", 1.0))) ?.action)
        assertEquals(Triggers.SKS, t.seriya("ru", listOf(w("[unk]", 0.4), w("скс", 0.9), w("кс", 0.9), w("кс", 0.8)))?.action)
    }

    @Test fun neKlip() {
        assertNull(t.seriya("ru", listOf(w("кс"))))                     // одно «кс» — английские six, sucks, next
        assertNull(t.seriya("ru", listOf(w("кс"), w("кс"))))
        assertNull(t.seriya("ru", listOf(w("скс", 0.7))))               // неуверенно
        assertNull(t.seriya("ru", listOf(w("скс", 0.9), w("кс", 0.5))))  // средняя 0,7
        assertNull(t.seriya("ru", listOf(w("скс"), w("скажи"))))         // в фразе другое слово
        assertNull(t.seriya("ru", listOf(w("скорее"))))
        assertNull(t.seriya("ru", listOf(w("[unk]"))))
        assertNull(t.seriya("ru", emptyList()))
        assertNull(t.seriya("en", listOf(w("скс"))))
        assertNull(Triggers(0.75, emptyMap()).seriya("ru", listOf(w("скс"))))
    }

    @Test fun primankiIVersiya() {
        val d = Files.createTempDirectory("prim").toFile()
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertTrue(KlipSlovo.primanki)
        val v = KlipSlovo.versiya
        assertTrue(KlipSlovo.postavitPrimanki("vykl"))
        assertTrue(KlipSlovo.versiya > v)                                // уши пересоберут грамматику
        assertFalse(KlipSlovo.postavitPrimanki("0")); assertFalse(KlipSlovo.postavitPrimanki("chush"))
        assertEquals("sks 0", java.io.File(d, KlipSlovo.FAJL).readText())
        assertTrue(KlipSlovo.postavit("klip"))
        assertEquals("klip 0", java.io.File(d, KlipSlovo.FAJL).readText())
        KlipSlovo.zanovo(); KlipSlovo.zagruzit(d)
        assertEquals(KlipSlovo.KLIP, KlipSlovo.slovo); assertFalse(KlipSlovo.primanki)
        assertTrue(KlipSlovo.postavitPrimanki("1"))
        assertEquals("klip", java.io.File(d, KlipSlovo.FAJL).readText())
        d.deleteRecursively()
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
