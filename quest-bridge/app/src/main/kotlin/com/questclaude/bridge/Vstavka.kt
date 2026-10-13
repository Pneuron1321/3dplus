package com.questclaude.bridge

/**
 * Голос только в эфир (2.2.8): ответ донатеру слышат зрители, а Александр в шлеме — нет
 * (решение 06.10: «голосом в эфир, но мимо ушей»). Golos раскодирует MP3 и кладёт сюда PCM
 * 48 кГц моно; звук эфира (Efir) подмешивает его в стерео, как микрофон. Не больше maksSek
 * в очереди. Обычная JVM — VstavkaTest.
 */
class Vstavka(private val maksSek: Int = 120) {
    private val kuski = ArrayDeque<ShortArray>()
    private var poz = 0
    private var vsego = 0

    /** Добавить PCM 48 кГц моно; переполнено — новое не берём (false). */
    @Synchronized fun dobavit(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        if (vsego + pcm.size > maksSek * RATE) return false
        kuski.addLast(pcm)
        vsego += pcm.size
        return true
    }

    /** Подмешать в стерео [out] (L,R,L,R…) [frames] кадров; кончилось — дальше как было. */
    @Synchronized fun mix(out: ShortArray, frames: Int, gain: Float = 1f) {
        var j = 0
        while (j < frames && kuski.isNotEmpty()) {
            val k = kuski.first()
            val v = Mikrofon.myagko((k[poz] * gain).toInt())
            out[2 * j] = (out[2 * j] + v).coerceIn(-32768, 32767).toShort()
            out[2 * j + 1] = (out[2 * j + 1] + v).coerceIn(-32768, 32767).toShort()
            j++
            poz++
            vsego--
            if (poz >= k.size) { kuski.removeFirst(); poz = 0 }
        }
    }

    /** Сколько кадров ещё прозвучит. */
    @Synchronized fun ostalos(): Int = vsego

    @Synchronized fun ochistit() {
        kuski.clear(); poz = 0; vsego = 0
    }

    companion object {
        const val RATE = 48000

        /** PCM 16 бит (каналы вперемешку) любой частоты → 48 кГц моно, линейно. */
        fun v48mono(pcm: ShortArray, rate: Int, kanalov: Int): ShortArray {
            val ch = kanalov.coerceAtLeast(1)
            val n = pcm.size / ch
            if (n == 0 || rate <= 0) return ShortArray(0)
            val mono = FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += pcm[i * ch + c]; s / ch }
            if (rate == RATE) return ShortArray(n) { mono[it].toInt().toShort() }
            val m = (n.toLong() * RATE / rate).toInt()
            return ShortArray(m) { j ->
                val t = j.toDouble() * rate / RATE
                val i = t.toInt().coerceAtMost(n - 1)
                val i2 = (i + 1).coerceAtMost(n - 1)
                val f = (t - i).toFloat()
                (mono[i] + (mono[i2] - mono[i]) * f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
