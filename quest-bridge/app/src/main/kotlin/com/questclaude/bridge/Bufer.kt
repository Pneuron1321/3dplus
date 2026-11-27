package com.questclaude.bridge

/**
 * Буфер звука с подстройкой (2.5.0, гости эфира): моно PCM 16 бит на частоте [vhod] → кусками на
 * частоте [vyhod] (48 кГц). Один пишет (сеть или микрофон), один читает (часы смеси или эфира);
 * их часы чуть расходятся, сеть приносит звук неровно. Поэтому, как у голоса в эфире (Mikrofon, 2.3.2):
 *  - до начала и после опустения — тишина, пока не наберётся [tsel];
 *  - опустел посреди речи — цель растёт на 20 мс (до [maksTsel]): сеть неровная — запас больше;
 *    10 с без опустений — цель снова на 10 мс меньше (до [minTsel]);
 *  - накопилось больше цели + 60 мс — читаем на 2 % быстрее (на слух незаметно), больше цели + 300 мс
 *    (сильно отстали) — выбрасываем лишнее до цели.
 * Обычная JVM — BuferTest.
 */
class Bufer(
    private val vhod: Int = 48000,
    private val vyhod: Int = 48000,
    private val cap: Int = vhod * 2,
    tsel0Ms: Int = 60,
    private val minTselMs: Int = 40,
    private val maksTselMs: Int = 250,
    /** Запас больше цели на столько — догоняем сразу, выбросив лишнее (2.7.7: гостям — 120 мс, было 300 у всех). */
    private val dogonMs: Int = 300,
) {
    private val tsel0 = vhod * tsel0Ms / 1000
    private var pustoS = 0L                                   // отсчётов выхода подряд без звука (буфер пуст)
    private val a = ShortArray(cap)
    private var r = 0
    private var w = 0
    private var size = 0
    private var frac = 0.0
    private var igraet = false
    private val shag0 = vhod.toDouble() / vyhod
    private var tsel = ms(tsel0Ms)
    private var spokoynoS = 0L                                // отсчётов выхода без опустений

    /** Опустел посреди звука / выброшено лишнее — для /gosti. */
    @Volatile var propuski = 0; private set
    @Volatile var sbrosy = 0; private set

    private fun ms(m: Int) = vhod * m / 1000

    /** Цель запаса сейчас, мс. */
    val tselMs: Int @Synchronized get() = tsel * 1000 / vhod

    /** Сколько в буфере, мс. */
    val estMs: Int @Synchronized get() = size * 1000 / vhod

    @Synchronized fun dobavit(b: ShortArray, off: Int = 0, n: Int = b.size - off) {
        for (i in 0 until n) {
            a[w] = b[off + i]
            w = (w + 1) % cap
            if (size < cap) size++ else r = (r + 1) % cap
        }
    }

    /**
     * Взять [n] отсчётов выхода в [out] (с [off]). Звука нет — тишина; false — тишина целиком
     * (буфер набирает запас или пуст).
     */
    @Synchronized fun vzyat(out: ShortArray, off: Int = 0, n: Int = out.size - off): Boolean {
        if (size > tsel + ms(dogonMs)) {                          // сильно отстали — догоняем сразу
            val drop = size - tsel
            r = (r + drop) % cap
            size -= drop
            sbrosy++
        }
        if (!igraet) {
            if (size < maxOf(2, tsel)) {
                // 2.7.7: долго пусто (гость свернул Safari, сеть стояла) — это не дрожание сети: запас — снова
                // начальный, иначе после каждого перерыва задержка только росла (эфир 25.10: 142 → 504 мс)
                pustoS += n
                if (pustoS > vyhod && tsel > tsel0) tsel = tsel0
                java.util.Arrays.fill(out, off, off + n, 0); return false
            }
            igraet = true
            pustoS = 0
        }
        val shag = if (size > tsel + ms(60)) shag0 * 1.02 else shag0
        var j = 0
        while (j < n) {
            if (size < 2) {                                   // кончился посреди — тишина и запас побольше
                igraet = false
                propuski++
                tsel = minOf(ms(maksTselMs), tsel + ms(20))
                spokoynoS = 0
                java.util.Arrays.fill(out, off + j, off + n, 0)
                return j > 0
            }
            val s0 = a[r].toInt()
            val s1 = a[(r + 1) % cap].toInt()
            out[off + j] = (s0 + (s1 - s0) * frac).toInt().toShort()
            frac += shag
            while (frac >= 1.0 && size >= 2) {
                frac -= 1.0
                r = (r + 1) % cap
                size--
            }
            j++
        }
        spokoynoS += n
        if (spokoynoS > vyhod * 10L) {                        // 10 с ровно — запас можно поменьше
            spokoynoS = 0
            tsel = maxOf(ms(minTselMs), tsel - ms(10))
        }
        return true
    }

    @Synchronized fun ochistit() { r = 0; w = 0; size = 0; frac = 0.0; igraet = false }
}
