package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Буфер с подстройкой: запас перед началом, рост цели при опустении, догон, 16 → 48 кГц. */
class BuferTest {
    @Test fun zapasPotomZvuk() {
        val b = Bufer(48000, 48000, tsel0Ms = 60)
        val out = ShortArray(960)
        b.dobavit(ShortArray(1920) { 100 })                       // 40 мс — меньше цели
        assertFalse(b.vzyat(out))
        assertEquals(0, out.max().toInt())
        b.dobavit(ShortArray(1920) { 100 })                       // 80 мс — пора
        assertTrue(b.vzyat(out))
        assertEquals(100, out[500].toInt())
    }

    @Test fun opustelTselRastyot() {
        val b = Bufer(48000, 48000, tsel0Ms = 60)
        val out = ShortArray(960)
        b.dobavit(ShortArray(2880) { 1 })
        assertTrue(b.vzyat(out)); assertTrue(b.vzyat(out)); assertTrue(b.vzyat(out))
        b.vzyat(out)                                              // пусто посреди — пропуск
        assertEquals(1, b.propuski)
        assertEquals(80, b.tselMs)
        repeat(20) { b.vzyat(out) }                               // тишина не считается пропусками
        assertEquals(1, b.propuski)
    }

    @Test fun tselUmenshaetsyaKogdaRovno() {
        val b = Bufer(48000, 48000, tsel0Ms = 100, minTselMs = 40)
        val out = ShortArray(960)
        b.dobavit(ShortArray(4800))
        repeat(600) { b.dobavit(ShortArray(960)); b.vzyat(out) }  // 12 с ровно
        assertTrue(b.tselMs < 100)
    }

    @Test fun dogonINaSbros() {
        val b = Bufer(48000, 48000, tsel0Ms = 60)
        val out = ShortArray(960)
        b.dobavit(ShortArray(48000 * 2 - 10))                     // почти 2 с запаса — сильно отстали
        b.vzyat(out)
        assertEquals(1, b.sbrosy)
        assertTrue(b.estMs <= 60)
        // чуть больше цели — читаем быстрее: за 50 тактов по 20 мс приход 20 мс, расход ≈ 20,4
        val c = Bufer(48000, 48000, tsel0Ms = 60)
        c.dobavit(ShortArray(48000 * 200 / 1000))                 // 200 мс
        val bylo = c.estMs
        repeat(50) { c.dobavit(ShortArray(960)); c.vzyat(out) }
        assertTrue(c.estMs < bylo)
    }

    @Test fun mikrofon16v48() {
        // уши шлют по 0,1 с (1600 отсчётов 16 кГц), смесь берёт по 20 мс (960 отсчётов 48 кГц)
        val b = Bufer(16000, 48000, tsel0Ms = 150, minTselMs = 120, maksTselMs = 300)
        val out = ShortArray(960)
        var vzyato = 0
        var pusto = 0
        for (t in 0 until 500) {                                  // 10 с
            if (t % 5 == 0) b.dobavit(ShortArray(1600) { 1000 })
            if (b.vzyat(out)) vzyato++ else if (t > 20) pusto++
        }
        assertEquals(0, pusto)
        assertEquals(0, b.propuski)
        assertTrue("почти все такты со звуком: $vzyato", vzyato > 480)
        assertEquals(1000, out[100].toInt())
    }
}
