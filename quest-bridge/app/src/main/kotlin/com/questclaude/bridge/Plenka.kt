package com.questclaude.bridge

import kotlin.math.abs

/**
 * Плёнка: кадры захвата (2 в секунду, те же 768 px, что /frame.jpg) за последние
 * полминуты и выбор кадров вокруг момента вызова — что было за 3 с до «плюс плюс»
 * и что стало за 3 с после. Обычная JVM: тестируется на столе; в одну картинку
 * кадры склеивает CaptureService (Android).
 */
class Plenka(private val keepMs: Long = 30_000) {
    class Kadr(val ms: Long, val jpeg: ByteArray)

    private val kadry = ArrayDeque<Kadr>()

    @Synchronized fun add(ms: Long, jpeg: ByteArray) {
        kadry.addLast(Kadr(ms, jpeg))
        while (kadry.isNotEmpty() && kadry.first().ms < ms - keepMs) kadry.removeFirst()
    }

    @Synchronized fun size() = kadry.size

    /** Кадр, ближайший к моменту t, не дальше [dopusk] мс (2.3.9: кадр вызова для «посмотри»); нет — null. */
    @Synchronized fun blizhayshiy(t: Long, dopusk: Long = 1000): Kadr? =
        kadry.minByOrNull { abs(it.ms - t) }?.takeIf { abs(it.ms - t) <= dopusk }

    /**
     * Моменты t − before … t + after через step (по умолчанию 13: −3,0 … +3,0 с).
     * На каждый — ближайший кадр не дальше step/2 + 100 мс, иначе null: захват в
     * это время стоял (стрим держит BeamXR, вид застыл) — пустую клетку видно.
     */
    @Synchronized fun around(t: Long, before: Long = BEFORE_MS, after: Long = AFTER_MS,
                             step: Long = STEP_MS): List<Kadr?> {
        val n = ((before + after) / step).toInt()
        val dopusk = step / 2 + 100
        return (0..n).map { i ->
            val at = t - before + i * step
            kadry.minByOrNull { abs(it.ms - at) }?.takeIf { abs(it.ms - at) <= dopusk }
        }
    }

    companion object {
        const val BEFORE_MS = 3000L
        const val AFTER_MS = 3000L
        const val STEP_MS = 500L

        /** Сколько кадров бывает: «чек чек» — 7 (±1,5 с), «зум чек» и «плюс плюс» — 13 (±3 с). */
        val KADROV = setOf(7, 13)

        /** Сколько мс до момента (и столько же после) у плёнки из n кадров. */
        fun polovina(n: Int): Long = (n - 1) / 2 * STEP_MS

        /** Подпись клетки i: «−3,0 с» … «0 с» … «+3,0 с». */
        fun podpis(i: Int, before: Long = BEFORE_MS, step: Long = STEP_MS): String {
            val ms = i * step - before
            if (ms == 0L) return "0 с"
            val s = String.format(java.util.Locale.ROOT, "%.1f", abs(ms) / 1000.0).replace('.', ',')
            return (if (ms < 0) "−" else "+") + s + " с"
        }
    }
}
