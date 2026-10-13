package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Плёнка на обычной JVM: кольцо кадров и выбор кадров вокруг вызова. */
class PlenkaTest {
    private fun kadr(n: Int) = ByteArray(4) { n.toByte() }

    @Test fun thirteenMomentsAroundWake() {
        val p = Plenka()
        val t = 100_000L
        for (i in -20..20) p.add(t + i * 500 + (i % 3) * 40, kadr(i))   // 2 в секунду, с дрожью
        val k = p.around(t)
        assertEquals(13, k.size)                                          // −3,0 … +3,0 с через 0,5
        for (i in 0..12) assertEquals(i - 6, k[i]!!.jpeg[0].toInt())     // каждый момент — свой кадр
    }

    @Test fun gapGivesEmptyCell() {
        val p = Plenka()
        val t = 50_000L
        for (i in -10..10) if (i !in -2..1) p.add(t + i * 500, kadr(i))   // захват стоял ~2 с
        val k = p.around(t)
        assertEquals(-6, k[0]!!.jpeg[0].toInt())
        assertNull(k[5])                                                  // −0,5 с — кадра нет
        assertNull(k[6])                                                  // 0 с
        assertEquals(4, k[10]!!.jpeg[0].toInt())                          // +2,0 с
    }

    @Test fun keepsHalfMinute() {
        val p = Plenka(keepMs = 30_000)
        for (i in 0..200) p.add(i * 500L, kadr(i))
        assertTrue("кадров ${p.size()}", p.size() in 60..62)
        assertTrue(p.around(10_000).all { it == null })                   // старое уже выброшено
    }

    @Test fun sevenMomentsForCheckCheck() {
        val p = Plenka()
        val t = 100_000L
        for (i in -20..20) p.add(t + i * 500, kadr(i))
        val pol = Plenka.polovina(7)
        assertEquals(1500L, pol)
        val k = p.around(t, pol, pol)
        assertEquals(7, k.size)                                           // −1,5 … +1,5 с через 0,5
        for (i in 0..6) assertEquals(i - 3, k[i]!!.jpeg[0].toInt())
        assertEquals(3000L, Plenka.polovina(13))
        assertEquals("−1,5 с", Plenka.podpis(0, pol))
        assertEquals("0 с", Plenka.podpis(3, pol))
        assertEquals("+1,5 с", Plenka.podpis(6, pol))
    }

    @Test fun captions() {
        assertEquals("−3,0 с", Plenka.podpis(0))
        assertEquals("−0,5 с", Plenka.podpis(5))
        assertEquals("0 с", Plenka.podpis(6))
        assertEquals("+2,5 с", Plenka.podpis(11))
        assertEquals("+3,0 с", Plenka.podpis(12))
    }

    @Test fun blizhayshiyKadr() {
        val p = Plenka()
        for (ms in listOf(1000L, 1500L, 2000L)) p.add(ms, byteArrayOf(ms.toByte()))
        assertEquals(1500L, p.blizhayshiy(1600)?.ms)
        assertEquals(2000L, p.blizhayshiy(2900)?.ms)
        assertEquals(null, p.blizhayshiy(3100))                           // дальше секунды — кадра нет
    }
}
