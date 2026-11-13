package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.7.9: два клипа на одно время — пара ключевых кадров камеры и эфира, общий конец, часы шлема в круге. */
class KlipSinhronTest {
    @Test fun vybiraetTochnuyuParu() {
        // камера — раз в секунду со сдвигом 0,37 с; эфир — раз в 2 с сам и по просьбе камеры (+20 мс)
        val kamera = (0 until 300).map { 370_000L + it * 1_000_000L }
        val efir = (0 until 150).map { it * 2_000_000L } + (0 until 150 step 2).map { 370_000L + it * 1_000_000L + 20_000L }
        val p = KlipSinhron.vybrat(kamera, efir.sorted(), 100_000_000L)
        assertNotNull(p)
        assertTrue("расхождение ${p!!.rashozhdenieMs} мс", p.rashozhdenieMs <= 20)
        assertTrue(kotlin.math.abs(p.kamera - 100_000_000L) <= KlipSinhron.OKNO_US)
    }

    @Test fun bezKlyuchevykhNull() {
        assertNull(KlipSinhron.vybrat(emptyList(), listOf(1L), 0L))
        assertNull(KlipSinhron.vybrat(listOf(50_000_000L), listOf(1L), 0L))
    }

    @Test fun snimokPoChasamShlema() {
        val dir = Files.createTempDirectory("klip-sinhron").toFile()
        var chasy = 0L
        val z = KlipZapis(File(dir, "k"), derzhatMs = 60_000, kuskomMs = 5_000) { chasy }
        z.golova(byteArrayOf(0x67, 1, 2, 3), byteArrayOf(0x68, 1), 1920, 1080)
        val sdvig = 7_000_000L                                    // время кадров телефона — своё; часы шлема впереди на 7 с
        for (i in 0 until 600) {                                  // 20 с, 30 к/с, ключевой раз в секунду
            val pts = i * 33_333L
            chasy = pts + sdvig + (if (i % 7 == 0) 40_000L else 0L) // задержка сети гуляет — берём наименьшую
            z.video(byteArrayOf(0, 0, 0, 1, if (i % 30 == 0) 0x65 else 0x41, i.toByte()), pts, i % 30 == 0)
        }
        val kl = z.klyuchevyeVremya()
        assertEquals(20, kl.size)
        assertEquals(sdvig, kl[0])
        val konec = z.konecVremya()!!
        assertEquals(599 * 33_333L + sdvig, konec)
        val s = z.snimokVremya(kl[5], konec - 3_000_000L)!!
        var pervyy = -1L; var posl = -1L; var n = 0
        s.obrazcy { o -> if (pervyy < 0) pervyy = o.ptsUs; posl = o.ptsUs; n++ }
        assertEquals(kl[5] - sdvig, pervyy)                       // начало — ровно тот ключевой кадр
        assertTrue(posl <= konec - 3_000_000L - sdvig)            // конец — не позже заданного мгновения
        assertTrue("кадров $n", n in 355..365)                     // с 5-й секунды до «конец − 3 с» ≈ 12 с × 30
        assertNull(z.snimokVremya(kl[5] + 500_000L, konec))       // не ключевой — снимка нет
        dir.deleteRecursively()
    }
}
