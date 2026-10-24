package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Налёт: секунды, а не дроби; «за эфир» с начала эфира; сотня — один раз; файл. */
class NaletTest {
    @Test fun chasBezPoteri() {
        var fayl = 0L
        val n = Nalet({ 0L }, { fayl = it })
        repeat(3600) { n.tik(1, it * 1000L) }
        assertEquals("60,00", Nalet.koiny(n.vsegoSek))          // час — ровно 60, не 59,99
        assertEquals(3600L, fayl)
        assertEquals("60,00", Nalet.koiny(n.efirSek))
    }

    @Test fun zaEfirSNachalaEfira() {
        val n = Nalet({ 600L }, { })
        repeat(90) { n.tik(1, 0) }
        assertEquals(90L, n.efirSek)
        repeat(30) { n.tik(1, 0) }                               // обрыв и снова — тот же эфир
        assertEquals(120L, n.efirSek)
        n.tik(2, 0)                                              // новый эфир
        assertEquals(1L, n.efirSek)
        assertEquals(721L, n.vsegoSek)
    }

    @Test fun sotnyaOdinRaz() {
        var fayl = -1L
        val n = Nalet({ Nalet.SOTNYA_SEK - 2 }, { fayl = it })
        assertFalse(n.tik(1, 1000))
        assertTrue(n.tik(1, 2000))                               // 100,00
        assertEquals(100, n.sotnya)
        assertEquals(Nalet.SOTNYA_SEK, fayl)                     // сотня — сразу в файл
        assertTrue(n.sotnyaVidna(11_999))
        assertFalse(n.sotnyaVidna(12_000))
        assertFalse(n.tik(1, 3000))
    }

    @Test fun koinyIUstanovit() {
        assertEquals("0,00", Nalet.koiny(0))
        assertEquals("0,01", Nalet.koiny(1))                     // 1 с = 0,0166… → 0,01
        assertEquals("123,45", Nalet.koiny(123 * 60 + 27))
        assertEquals("1 234,56", Nalet.koiny(1234L * 60 + 34))
        var fayl = 0L
        val n = Nalet({ -5L }, { fayl = it })
        assertEquals(0L, n.vsegoSek)                             // битый файл — с нуля
        n.ustanovit(123.45)
        assertEquals("123,45", Nalet.koiny(n.vsegoSek))
        assertEquals(n.vsegoSek, fayl)
        assertTrue(n.status().contains("\"vsego\":\"123,45\""))
    }

    @Test fun koinyPoAngliyski() {
        assertEquals("0.00", Nalet.koinyEn(0))
        assertEquals("123.45", Nalet.koinyEn(123 * 60 + 27))
        assertEquals("1,234.56", Nalet.koinyEn(1234L * 60 + 34))
        assertEquals("1,000,000.00", Nalet.koinyEn(1_000_000L * 60))
    }
}
