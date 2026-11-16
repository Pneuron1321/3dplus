package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DosluhTest {
    private val tiho = 70
    private val golos = 25

    /** Куски по 0,1 с с [ot] мс: громкости [db] по очереди; номер куска, где заговорил, или -1. */
    private fun Dosluh.slushat(ot: Long, vararg db: Int): Int {
        db.forEachIndexed { i, d -> if (kusok(ot + (i + 1) * 100L, d)) return i }
        return -1
    }

    @Test fun zagovorilVSekundu() {
        val d = Dosluh()
        d.otkryt(1000)
        assertEquals(4, d.slushat(1000, tiho, tiho, tiho, golos, golos))     // 0,4 с тишины — и заговорил
        assertEquals(1, d.zagovoril)
        assertEquals(-1, d.slushat(1500, golos, golos, golos))                // окно закрыто: дальше — по «плюс плюс»
    }

    @Test fun molchit() {
        val d = Dosluh()
        d.otkryt(1000)
        assertEquals(-1, d.slushat(1000, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho))
        assertEquals(-1, d.slushat(2100, golos, golos, golos))                // секунда прошла — отдыхает
        assertEquals(0, d.zagovoril)
        assertEquals(1, d.otkryto)
    }

    @Test fun nachalVKonceSekundy() {
        val d = Dosluh()
        d.otkryt(1000)
        assertEquals(10, d.slushat(1000, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho, tiho, golos, golos))
    }

    @Test fun odinShchelchokNeRech() {
        val d = Dosluh()
        d.otkryt(1000)
        assertEquals(-1, d.slushat(1000, tiho, golos, tiho, golos, tiho, tiho, tiho, tiho, tiho, tiho, tiho, golos, golos))
    }

    @Test fun hvostEyoGolosaNeSchitaetsya() {
        val d = Dosluh()
        d.otkryt(1000)
        assertFalse(d.kusok(1100, golos))                                   // первые 0,2 с — её же голос
        assertFalse(d.kusok(1200, golos))
        assertFalse(d.kusok(1300, golos))
        assertTrue(d.kusok(1400, golos))
    }

    @Test fun otstavanieRaspoznavaniya() {
        // распознавание отстало: «договорила» пришла, а куски ещё из её речи — по времени чтения
        val d = Dosluh()
        d.otkryt(5000)
        assertFalse(d.kusok(4800, golos))
        assertFalse(d.kusok(4900, golos))
        assertFalse(d.kusok(5000, golos))
        assertEquals(-1, d.slushat(5000, tiho, tiho, tiho))
    }

    @Test fun avaSnovaZagovorila() {
        val d = Dosluh()
        d.otkryt(1000)
        d.zakryt()
        assertEquals(-1, d.slushat(1200, golos, golos, golos))
    }

    @Test fun porogIVyklyucheno() {
        val d = Dosluh()
        d.otkryt(1000)
        assertEquals(-1, d.slushat(1200, 45, 45, 45))                         // игра из динамиков тише порога
        d.set(0f, null)
        d.otkryt(3000)
        assertEquals(-1, d.slushat(3200, golos, golos, golos))
        assertEquals(1, d.otkryto)
        d.set(1.5f, 50)
        d.otkryt(5000)
        assertEquals(1, d.slushat(5200, 45, 45))
        assertEquals("{\"okno\":1.5,\"db\":50,\"otkryto\":2,\"zagovoril\":1}", d.status())
    }
}
