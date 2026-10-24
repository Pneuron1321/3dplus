package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Числа ручек картинки карты (2.6.2): 2 байта UVC, знак у яркости и оттенка, шаги «+N»/«-N». */
class KartaKartinkaTest {
    @Test fun chislaTudaObratno() {
        for (v in listOf(0, 1, 100, 255, 256, 4000, -1, -64, -180)) {
            assertEquals(v, Uvc.chislo(Uvc.bayty(v), znak = true))
        }
        assertArrayEquals(byteArrayOf(0x40, 0x01), Uvc.bayty(320))       // младший байт первым
        assertEquals(65535, Uvc.chislo(byteArrayOf(-1, -1), znak = false))
        assertEquals(-1, Uvc.chislo(byteArrayOf(-1, -1), znak = true))
    }

    @Test fun shagi() {
        assertEquals(30, Uvc.novoe("+10", 20))
        assertEquals(10, Uvc.novoe("-10", 20))
        assertNull(Uvc.novoe("+10", null))                               // шаг без текущего — нельзя
        assertEquals(-5, Uvc.novoe("=-5", 20))
        assertEquals(140, Uvc.novoe("140", 20))
        assertNull(Uvc.novoe("sochno", 20))
    }

    @Test fun ruchkiKarty() {
        assertEquals(listOf(0x02, 0x03, 0x06, 0x07), KartaKartinka.RUCHKI.values.toList())
        assertEquals(setOf("yarkost", "ton"), KartaKartinka.ZNAKOVYE)
    }
}
