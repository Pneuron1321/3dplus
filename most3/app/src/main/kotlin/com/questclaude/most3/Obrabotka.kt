package com.questclaude.most3

/**
 * 3.2.1 (Александр 12.11): обработка звука игры для ушей — «шаги» и «ровно». Только то, что он слышит: запись
 * игры и эфир получают звук как есть. Оба канала обрабатываются ОДИНАКОВО (один и тот же фильтр, одно общее
 * усиление) — разница между левым и правым ухом, по которой слышно направление, не меняется.
 *  - шаги: гул ниже ~120 Гц тише на 6 дБ, полоса шагов и перезарядки (~3 кГц) громче на 7 дБ;
 *  - ровно: тихое громче (до +9 дБ), громкое сжато 3:1 выше −30 дБ — взрыв не глушит шаги.
 * PCM 16 бит, на месте. Не потокобезопасно: зовёт один поток звука.
 */
class Obrabotka(private val hz: Int, private val kanaly: Int) {
    @Volatile var shagi = false
    @Volatile var rovno = false

    /** Биквад (RBJ): коэффициенты общие, память — своя на канал. */
    private class Bikvad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double, kanaly: Int) {
        val z1 = DoubleArray(kanaly); val z2 = DoubleArray(kanaly)
        fun shag(x: Double, k: Int): Double {
            val y = b0 * x + z1[k]
            z1[k] = b1 * x - a1 * y + z2[k]
            z2[k] = b2 * x - a2 * y
            return y
        }
    }

    private fun nizkayaPolka(f: Double, db: Double): Bikvad {
        val a = Math.pow(10.0, db / 40); val w = 2 * Math.PI * f / hz; val c = Math.cos(w); val s = Math.sin(w)
        val al = s / 2 * Math.sqrt(2.0); val k = 2 * Math.sqrt(a) * al
        val a0 = (a + 1) + (a - 1) * c + k
        return Bikvad(a * ((a + 1) - (a - 1) * c + k) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0, a * ((a + 1) - (a - 1) * c - k) / a0,
            -2 * ((a - 1) + (a + 1) * c) / a0, ((a + 1) + (a - 1) * c - k) / a0, kanaly)
    }

    private fun kolokol(f: Double, db: Double, q: Double): Bikvad {
        val a = Math.pow(10.0, db / 40); val w = 2 * Math.PI * f / hz; val c = Math.cos(w); val al = Math.sin(w) / (2 * q)
        val a0 = 1 + al / a
        return Bikvad((1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0, kanaly)
    }

    private val gul = nizkayaPolka(120.0, -6.0)
    private val polosa = kolokol(3000.0, 7.0, 0.9)
    private var ogib = 0.0                                              // огибающая (0..1), общая на оба канала
    private val ataka = 1 - Math.exp(-1.0 / (hz * 0.005))               // 5 мс
    private val spad = 1 - Math.exp(-1.0 / (hz * 0.200))                // 200 мс

    /** Общее усиление «ровно» по уровню (дБ от полной шкалы): для проверок. */
    fun usilenieDb(urovenDb: Double): Double =
        (if (urovenDb > POROG) POROG + (urovenDb - POROG) / SZHATIE - urovenDb else 0.0) + PODYOM

    fun obrabotat(b: ByteArray, n: Int) {
        val sh = shagi; val rv = rovno
        if (!sh && !rv) return
        var i = 0
        val x = DoubleArray(kanaly)
        while (i + 2 * kanaly <= n) {
            var maks = 0.0
            for (k in 0 until kanaly) {
                var s = ((b[i + 2 * k].toInt() and 0xff) or (b[i + 2 * k + 1].toInt() shl 8)).toShort() / 32768.0
                if (sh) s = polosa.shag(gul.shag(s, k), k) * ZAPAS
                x[k] = s
                val a = Math.abs(s); if (a > maks) maks = a
            }
            var g = 1.0
            if (rv) {
                ogib += (maks - ogib) * (if (maks > ogib) ataka else spad)
                val db = 20 * Math.log10(Math.max(ogib, 1e-5))
                g = Math.pow(10.0, usilenieDb(db) / 20)
            }
            for (k in 0 until kanaly) {
                val v = Math.round(x[k] * g * 32767.0).toInt().coerceIn(-32768, 32767)
                b[i + 2 * k] = v.toByte(); b[i + 2 * k + 1] = (v shr 8).toByte()
            }
            i += 2 * kanaly
        }
    }

    companion object {
        const val POROG = -30.0     // дБ: выше — сжимаем
        const val SZHATIE = 3.0     // 3:1
        const val PODYOM = 9.0      // дБ: тихое громче
        val ZAPAS = Math.pow(10.0, -3.0 / 20)   // −3 дБ перед подъёмом полосы шагов — чтобы пики не резало
    }
}
