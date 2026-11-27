package com.questclaude.bridge

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Сигналы ушей — свои, синусом через AudioTrack: системный генератор тонов
 * на Quest не звучал (04.10). Каждый сигнал узнаётся на слух, не глядя:
 *
 *   WAKE    «говори» — два тона вверх
 *   HEARD   «услышал» — один высокий
 *   NOTHING «фразы не было» — вниз
 *   ZOOM    «зум» — два коротких одинаковых
 *   TIHO    «тихо» — один низкий
 *   COMPACT «компакт» — три коротких
 *   PODYOM  «Плюс пропал — поднимаю» — три вверх, аккорд (Плюса подняли с тем же разговором)
 *   SOTNYA  «налёт — сотня» — звон вверх по четырём нотам (его слышит и эфир)
 */
object Signal {
    const val RATE = 22050

    enum class Kind(val notes: List<Pair<Int, Int>>) {      // (частота Гц или 0 — пауза, длительность мс)
        WAKE(listOf(660 to 90, 0 to 30, 990 to 130)),
        HEARD(listOf(1320 to 120)),
        NOTHING(listOf(520 to 110, 0 to 30, 350 to 160)),
        ZOOM(listOf(880 to 70, 0 to 60, 880 to 70)),
        TIHO(listOf(440 to 160)),
        COMPACT(listOf(740 to 60, 0 to 50, 740 to 60, 0 to 50, 740 to 60)),
        PODYOM(listOf(523 to 90, 0 to 20, 659 to 90, 0 to 20, 784 to 160)),
        KLIP(listOf(988 to 60, 0 to 40, 988 to 60, 0 to 40, 1319 to 160)),   // «клип клип клип» — сохраняю
        SOTNYA(listOf(1047 to 110, 0 to 15, 1319 to 110, 0 to 15, 1568 to 110, 0 to 15, 2093 to 320)),
    }

    /** PCM 16 бит моно, с плавным входом и выходом по 8 мс — без щелчков. */
    fun pcm(kind: Kind, volume: Double = 0.5): ShortArray {
        val out = ArrayList<Short>()
        val ramp = RATE * 8 / 1000
        for ((hz, ms) in kind.notes) {
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val env = min(1.0, min(i, n - 1 - i).toDouble() / ramp)
                val v = if (hz == 0) 0.0 else sin(2 * PI * hz * i / RATE) * env * volume
                out.add((v * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return out.toShortArray()
    }
}
