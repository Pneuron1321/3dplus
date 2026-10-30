package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Пульт Плюса: разбор адреса, шаги ручек карты, наборы и шаги шейдера. */
class PultTest {
    @Test fun zapros() {
        val q = Pult.zapros("yarkost=+0.1&kontrast=1.2&nabor=noch&pusto=&=x&g=80%25")
        assertEquals("+0.1", q["yarkost"])                 // плюс — шаг, не пробел
        assertEquals("1.2", q["kontrast"])
        assertEquals("noch", q["nabor"])
        assertEquals("", q["pusto"])
        assertEquals("80%", q["g"])
        assertEquals(5, q.size)
    }

    @Test fun chisloRuchki() {
        assertEquals(30, Pult.chislo("+10", 20))
        assertEquals(10, Pult.chislo("-10", 20))
        assertEquals(-5, Pult.chislo("=-5", 20))
        assertEquals(140, Pult.chislo("140", 20))
        assertNull(Pult.chislo("abc", 20))
    }

    @Test fun kartinka() {
        val n = Nastrojki()
        val noch = Pult.kartinka(n, mapOf("nabor" to "noch"))!!
        assertEquals(NABORY["noch"], noch)
        val shag = Pult.kartinka(n, mapOf("gamma" to "+0.2"))!!
        assertEquals(1.2f, shag.gamma, 1e-4f)
        assertNull(Pult.kartinka(n, mapOf("nabor" to "nety")))
        assertNull(Pult.kartinka(n, mapOf("gamma" to "много")))
    }
}
