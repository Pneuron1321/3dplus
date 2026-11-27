package com.questclaude.bridge

import java.util.PriorityQueue

/**
 * Порядок кадров с B-кадрами (2.8.15, план 25.11 К3: B-кадры — около +10–15 % качества при том же потоке).
 * Кодировщик с B-кадрами отдаёт кадры в порядке декодирования (I0 P3 B1 B2 …) с временем показа pts; RTMP нужно
 * время декодирования dts (только растёт) и сдвиг показа cts = pts − dts ≥ 0 (FLV: composition time).
 * Как: держим окно из [okno] кадров; кадр выходит, когда за ним пришли ещё okno; его dts — самое малое ещё не
 * выданное pts минус [sdvigUs]. Самое малое из окна ≤ pts самого кадра (он в окне) — значит dts ≤ pts всегда; окно
 * больше глубины перестановки (у 2 B-кадров — 2) — значит dts растёт. sdvig — запас на кадр с меньшим pts, который
 * придёт позже (70 мс ≥ 2 кадра даже при 30 к/с). Задержка эфира — okno кадров (~100 мс при 30 к/с).
 * Без B-кадров не используется (эфир как раньше, без задержки). Обычная JVM — PoryadokTest.
 */
class Poryadok(private val okno: Int = OKNO, private val sdvigUs: Long = SDVIG_US) {
    class Gotov(val data: ByteArray, val dtsUs: Long, val ctsUs: Long, val key: Boolean)
    private class Kadr(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val zhdut = ArrayDeque<Kadr>()
    private val vremena = PriorityQueue<Long>()
    private var prevDts = Long.MIN_VALUE
    /** Пришёл кадр с pts меньше уже бывшего — кодировщик правда делает B-кадры. */
    @Volatile var estB = false; private set
    private var maxPts = Long.MIN_VALUE

    /** Кадр в порядке декодирования → готовые к отправке (0 или 1 кадр). */
    fun polozhit(data: ByteArray, ptsUs: Long, key: Boolean): List<Gotov> {
        if (ptsUs < maxPts) estB = true
        maxPts = maxOf(maxPts, ptsUs)
        zhdut.addLast(Kadr(data, ptsUs, key))
        vremena.add(ptsUs)
        val out = ArrayList<Gotov>(1)
        while (zhdut.size > okno) out += vydat()
        return out
    }

    /** Конец эфира — выдать оставшиеся. */
    fun ostatok(): List<Gotov> {
        val out = ArrayList<Gotov>()
        while (zhdut.isNotEmpty()) out += vydat()
        return out
    }

    private fun vydat(): Gotov {
        val k = zhdut.removeFirst()
        var dts = (vremena.poll() ?: k.ptsUs) - sdvigUs
        if (dts > k.ptsUs) dts = k.ptsUs
        if (prevDts != Long.MIN_VALUE && dts <= prevDts) dts = prevDts + 1
        prevDts = dts
        return Gotov(k.data, dts, maxOf(0L, k.ptsUs - dts), k.key)
    }

    companion object {
        const val OKNO = 3
        const val SDVIG_US = 70_000L
        /** Сколько B-кадров просим у кодировщика. */
        const val B_KADROV = 2
    }
}
