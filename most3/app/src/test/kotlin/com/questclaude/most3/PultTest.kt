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

    /** 3.2.2: цвет — минус «=-N» доходит до шейдера как «задать», не «сдвиг». */
    @Test fun kartinkaCvet() {
        val q = Pult.zapros("temperatura==-30&ton==-10&sveta=0.5&balr=1.1&chernyj=0.05&belyj=0.95&zhivost=+0.4")
        val k = Pult.kartinka(Nastrojki(), q)!!
        assertEquals(-30f, k.temperatura, 1e-4f); assertEquals(-10f, k.ton, 1e-4f); assertEquals(0.5f, k.sveta, 1e-6f)
        assertEquals(1.1f, k.balR, 1e-6f); assertEquals(0.05f, k.chernyj, 1e-6f); assertEquals(0.95f, k.belyj, 1e-6f)
        assertEquals(0.4f, k.zhivost, 1e-6f)
        assertEquals(-40f, Pult.kartinka(k, mapOf("temperatura" to "-10"))!!.temperatura, 1e-4f)   // «-N» — сдвиг
    }

    /** 3.2.3: игра в эфир — только пока окно Моста 3 активно (по умолчанию); «всегда» — как раньше; «шлем» — никогда. */
    @Test fun igraVEfirPoFokusu() {
        assertEquals(true, otdavatIgru("fokus", true))
        assertEquals(false, otdavatIgru("fokus", false))       // свернул или перешёл к другому окну — вид шлема
        assertEquals(true, otdavatIgru("vsegda", false))
        assertEquals(false, otdavatIgru("shlem", true))
        assertEquals(false, otdavatIgru("chepuha", false))     // незнакомое — как «по фокусу»
    }
}
