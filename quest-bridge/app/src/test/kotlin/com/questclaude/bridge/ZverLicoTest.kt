package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ZverLicoTest {
    @Test fun licoZapominaetsya() {
        val d = Files.createTempDirectory("lico").toFile()
        Oformlenie.zagruzit(d)
        assertEquals("plus", Oformlenie.lico)
        assertTrue(Oformlenie.licoUpr("zver").contains("\"lico\":\"zver\""))
        assertEquals("zver", File(d, "efir-lico").readText())
        Oformlenie.licoUpr("chto-to")                                  // чужое — не меняет
        assertEquals("zver", Oformlenie.lico)
        Oformlenie.zagruzit(d)                                         // после перезапуска — то же
        assertEquals("zver", Oformlenie.lico)
        Oformlenie.licoUpr("plus")
        assertEquals("plus", Oformlenie.lico)
    }

    @Test fun chelyustVPredelah() {
        for (t in 0L..20_000L step 37) {
            val g = ZverMimika.chelyust("govorit", t)
            assertTrue("речь: $g", g in -0.1f..8.6f)
            assertTrue(ZverMimika.chelyust("obychno", t) in -0.9f..0.9f)
        }
        assertTrue(ZverMimika.chelyust("dumaet", 0) < 0)              // думает — пасть прикрыта
        // в речи челюсть правда ходит: за секунду есть и открытая, и почти закрытая
        val za = (0L..1000L step 10).map { ZverMimika.chelyust("govorit", it) }
        assertTrue(za.max() > 6f && za.min() < 2f)
    }

    @Test fun glazaIGreben() {
        assertEquals(0, ZverMimika.svechenie("spit", 123))
        for (t in 0L..10_000L step 53) {
            assertTrue(ZverMimika.svechenie("slushaet", t) in 0..255)
            assertEquals(0f, ZverMimika.kolyhanie(ZverLico.CHELKA, t), 1e-6f)   // у чёлки не двигается
            assertTrue(kotlin.math.abs(ZverMimika.kolyhanie(0f, t)) <= 2.4f)
        }
    }
}
