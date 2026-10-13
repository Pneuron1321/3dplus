package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Голос только в эфир: подмешивается в оба канала по порядку, кончился — тишина как была; частота — в 48 кГц. */
class VstavkaTest {
    @Test fun podmeshivaetPoPoryadku() {
        val v = Vstavka()
        v.dobavit(shortArrayOf(100, 200))
        v.dobavit(shortArrayOf(300))
        val out = ShortArray(8) { 10 }
        v.mix(out, 4)
        assertEquals(listOf(110, 110, 210, 210, 310, 310, 10, 10), out.map { it.toInt() })
        assertEquals(0, v.ostalos())
    }

    @Test fun potolokIOchistka() {
        val v = Vstavka(maksSek = 1)
        assertTrue(v.dobavit(ShortArray(48000)))
        assertFalse(v.dobavit(ShortArray(10)))               // больше секунды — не берём
        v.ochistit()
        assertEquals(0, v.ostalos())
        val out = ShortArray(4)
        v.mix(out, 2)
        assertEquals(listOf(0, 0, 0, 0), out.map { it.toInt() })
    }

    @Test fun v48mono() {
        val st24 = ShortArray(24000 * 2) { if (it % 2 == 0) 1000 else 3000 }    // 1 с, 24 кГц стерео
        val m = Vstavka.v48mono(st24, 24000, 2)
        assertEquals(48000, m.size)
        assertTrue(m.all { it.toInt() == 2000 })
        assertEquals(3, Vstavka.v48mono(shortArrayOf(1, 2, 3), 48000, 1).size)
    }
}
