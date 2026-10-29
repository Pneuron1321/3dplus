package com.questclaude.bridge

/**
 * Два клипа — одно время (2.7.9, Александр 29.10: «нужны два идентичных по таймингу видео, от камеры и стрима, —
 * тогда легко сделать рилсы»). Без пережатия клип может начаться только с ключевого кадра, а ключевые кадры камеры
 * (телефон, раз в секунду) и эфира (шлем) идут вразнобой — клипы расходились до 1–2 с. Поэтому: (1) когда от камеры
 * приходит ключевой кадр, мост тут же просит ключевой и у эфира (Kamera → Hub.klyuchevoyEfira) — у эфира появляются
 * ключевые в те же мгновения, ±1 кадр; (2) при сохранении [vybrat] ищет такую пару около «3 минуты назад»; конец обоих —
 * одно и то же мгновение. Обычная JVM — KlipSinhronTest.
 */
object KlipSinhron {
    /** Насколько далеко от «ровно 3 минуты назад» можно сдвинуть начало ради точной пары, мкс. */
    const val OKNO_US = 6_000_000L

    class Para(val kamera: Long, val efir: Long) {
        val rashozhdenieMs: Long get() = kotlin.math.abs(kamera - efir) / 1000
    }

    /**
     * Пара ключевых кадров (часы шлема, мкс) около [t0]: сначала самая точная (наименьшее расхождение), при равной —
     * ближе к t0. null — у кого-то нет ни одного ключевого в окне.
     */
    fun vybrat(kamera: List<Long>, efir: List<Long>, t0: Long, okno: Long = OKNO_US): Para? {
        val kk = kamera.filter { kotlin.math.abs(it - t0) <= okno }
        val ke = efir.filter { kotlin.math.abs(it - t0) <= okno }
        if (kk.isEmpty() || ke.isEmpty()) return null
        var best: Para? = null
        var bestKey = Long.MAX_VALUE to Long.MAX_VALUE
        for (a in kk) {
            val b = ke.minByOrNull { kotlin.math.abs(it - a) } ?: continue
            // точность важнее близости к t0, но разница меньше кадра (17 мс) — уже «точно»
            val key = (kotlin.math.abs(a - b) / 17_000) to kotlin.math.abs(maxOf(a, b) - t0)
            if (key.first < bestKey.first || key.first == bestKey.first && key.second < bestKey.second) { bestKey = key; best = Para(a, b) }
        }
        return best
    }
}
