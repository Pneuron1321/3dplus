package com.questclaude.bridge

/**
 * Кольцо микрофона: последние [sec] секунд звука и счёт сэмплов с начала. Вызов
 * «плюс плюс» засчитывается чуть позже, чем сказан (распознавателю нужно услышать
 * продолжение), — фразу берём отсюда с конца слов вызова: слова, сказанные сразу
 * следом, не теряются. Обычная JVM — тестируется на столе.
 */
class Kolco(sec: Int = 30, rate: Int = 16000) {
    private val buf = ShortArray(sec * rate)

    /** Сколько сэмплов записано с начала — «часы» в сэмплах. */
    var total = 0L
        private set

    fun add(a: ShortArray, n: Int = a.size) {
        for (i in 0 until n) buf[((total + i) % buf.size).toInt()] = a[i]
        total += n
    }

    /** Звук с сэмпла [from] по сейчас; старше кольца не бывает — обрежется. */
    fun since(from: Long): ShortArray {
        val start = maxOf(from, total - buf.size, 0L)
        if (start >= total) return ShortArray(0)
        return ShortArray((total - start).toInt()) { buf[((start + it) % buf.size).toInt()] }
    }
}
