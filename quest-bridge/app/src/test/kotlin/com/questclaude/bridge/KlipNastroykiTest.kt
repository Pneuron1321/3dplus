package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.8.13: что сохраняет «клип клип клип» — по умолчанию качество и игра Моста 3, камера выкл; файл помнит. */
class KlipNastroykiTest {
    @After fun posle() = KlipNastroyki.zanovo()

    @Test fun poUmolchaniyuIFajl() {
        val d = Files.createTempDirectory("klip").toFile()
        KlipNastroyki.zanovo()
        KlipNastroyki.zagruzit(d)
        assertTrue(KlipNastroyki.kachestvo); assertTrue(KlipNastroyki.most3); assertFalse(KlipNastroyki.kamera)
        assertTrue(KlipNastroyki.upr(mapOf("kachestvo" to "vykl", "kamera" to "1")))
        assertFalse(KlipNastroyki.upr(mapOf("kachestvo" to "0")))                 // ничего не поменялось
        assertFalse(KlipNastroyki.upr(mapOf("kachestvo" to "chush")))
        assertEquals("0 1 1", java.io.File(d, KlipNastroyki.FAJL).readText())
        KlipNastroyki.zanovo(); KlipNastroyki.zagruzit(d)
        assertFalse(KlipNastroyki.kachestvo); assertTrue(KlipNastroyki.kamera)
        assertTrue(KlipNastroyki.postavit(kachestvo = true))                      // одним нажатием — обратно
        assertEquals("\"kachestvo\":true,\"most3\":true,\"kamera\":true", KlipNastroyki.json())
        d.deleteRecursively()
    }
}
