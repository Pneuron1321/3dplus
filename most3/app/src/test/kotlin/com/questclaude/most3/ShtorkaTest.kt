package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.3.2: шторка ручек — помнит открытые ручки; курсор на ней — не прячется; ушёл — через 2,5 с; без курсора — 5 с. */
class ShtorkaTest {
    @Test fun bezNavedeniyaKakBylo() {
        val s = Shtorka()
        assertEquals(Shtorka.OBYCHNO_MS, s.pryatatCherez())
        s.kasanie(vnutri = true)                         // курсора не видно — касание шторки не держит её вечно
        assertFalse(s.kursorNaShtorke)
        assertEquals(5000L, s.pryatatCherez())
    }

    @Test fun kursorNaShtorkeNePryachet() {
        val s = Shtorka()
        assertTrue(s.navedenie(vnutri = true))
        assertNull(s.pryatatCherez())
        assertFalse(s.navedenie(vnutri = true))          // водит по шторке — ничего не меняется
        assertTrue(s.navedenie(vnutri = false))          // ушёл на картинку — время заново, вдвое быстрее
        assertEquals(Shtorka.BYSTRO_MS, s.pryatatCherez())
        assertEquals(2 * Shtorka.BYSTRO_MS, Shtorka.OBYCHNO_MS)
        assertFalse(s.navedenie(vnutri = false))         // водит по картинке — время не продлевается
    }

    @Test fun nazhatiePoShtorke() {
        val s = Shtorka()
        s.navedenie(vnutri = true)
        s.ushyol()                                       // перед нажатием система шлёт «ушёл»…
        assertEquals(Shtorka.BYSTRO_MS, s.pryatatCherez())
        s.kasanie(vnutri = true)                         // …и сразу касание по шторке — снова не прятать
        assertNull(s.pryatatCherez())
        s.kasanie(vnutri = false)                        // ткнул в картинку — шторка видна 2,5 с
        assertEquals(Shtorka.BYSTRO_MS, s.pryatatCherez())
        s.navedenie(vnutri = true); s.ushyol()          // ушёл из окна совсем
        assertEquals(Shtorka.BYSTRO_MS, s.pryatatCherez())
    }

    @Test fun ruchkiPomnit() {
        val s = Shtorka()
        assertFalse(s.ruchki)
        assertTrue(s.pereklyuchitRuchki()); assertTrue(s.ruchki)
        assertFalse(s.pereklyuchitRuchki()); assertFalse(s.ruchki)
    }
}
