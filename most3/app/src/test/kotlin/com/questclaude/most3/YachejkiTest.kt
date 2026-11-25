package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 3.2.6: ячейки ручек 1–5 — сохранить, загрузить, изменено ли, вернуть перезаписанное, помнит после перезапуска. */
class YachejkiTest {
    @Test fun sohranitZagruzitVernut() {
        val d = Files.createTempDirectory("yach").toFile()
        val y = Yachejki(d)
        assertEquals(0, y.aktivnaya)
        assertNull(y.zagruzit(1))                                   // пустая
        assertFalse(y.sohranit(6, Nastrojki()))                     // нет такой
        val a = Nastrojki(kontrast = 1.3f, temperatura = -20f, vyderzhka = 0.5f, sila = 1.4f)
        assertTrue(y.sohranit(2, a))
        assertEquals(2, y.aktivnaya)
        assertEquals(a, y.zagruzit(2))
        assertFalse(y.izmeneno(a))
        assertTrue(y.izmeneno(a.copy(kontrast = 1.5f)))             // подвинул после загрузки
        // перезаписал не ту — вернуть
        val b = Nastrojki(gamma = 1.4f)
        assertTrue(y.sohranit(2, b))
        assertEquals(b, y.vzyat(2))
        assertEquals(2, y.vernut())
        assertEquals(a, y.vzyat(2))
        assertEquals(0, y.vernut())                                 // второй раз — нечего
        // сохранил в пустую и вернул — снова пустая
        assertTrue(y.sohranit(4, b)); assertEquals(4, y.vernut()); assertFalse(y.est(4))
        assertEquals("{\"aktivnaya\":4,\"izmeneno\":false,\"est\":[0,1,0,0,0],\"mozhno_vernut\":false}", y.json(Nastrojki()))
        // после перезапуска: активная и содержимое на месте
        val y2 = Yachejki(d)
        assertEquals(4, y2.aktivnaya)
        assertEquals(a, y2.zagruzit(2)); assertEquals(2, y2.aktivnaya)
        d.deleteRecursively()
    }
}
