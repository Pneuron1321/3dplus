package com.questclaude.bridge

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Фоновая музыка без Android (2.5.6, задания Плюса 17.10, п. 1): очередь кусков, громкость шлема и
 * эфира отдельно, приглушение под голосом и полосы для эквалайзера плашки. Звучит в Fon.
 *
 * Очередь: плеер muzyka шлёт MP3 кусками; новый кусок ждёт ([zhdatMesta]), пока впереди меньше
 * [zapasKadrov] — плееру не нужно считать время, а память держит ~запас + один кусок.
 * Приглушение (ducking): пока говорит Ава (любой /say) или Александр в микрофон, множитель плавно
 * идёт к [duck] (за [VNIZ_MS]) и обратно к 1 за [VVERH_MS] после голоса. Шлем и эфир — каждый своим:
 * Ава «в ухо» приглушает музыку только в шлеме (зрители её не слышат).
 * Микрофон: громче [GOVORIT_DB] и не эхо музыки (не тише [EHO] × звука в шлеме — как эхо-заслонка
 * гостей) — Александр говорит, держим [DERZHAT_MS]. Обычная JVM — FonMiksTest.
 */
class FonMiks(private val zapasKadrov: Int = ZAPAS_MS * Vstavka.RATE / 1000) {
    private val lock = Object()
    private val kuski = ArrayDeque<ShortArray>()           // стерео 48 кГц
    private var poz = 0
    private var ostalos = 0                                  // кадров в очереди
    private var stopN = 0
    /** Громкость музыки в шлеме и в эфире: 1 — как прислали, до 1,5. */
    @Volatile var gromShlem = 1f
    @Volatile var gromEfir = 1f
    /** До какой доли стихает музыка под голосом (0,33 — на две трети тише). */
    @Volatile var duck = DUCK
    /** Множители приглушения сейчас (1 — не приглушено). */
    @Volatile var gShlem = 1f
        private set
    @Volatile var gEfir = 1f
        private set
    @Volatile private var micDo = 0L
    @Volatile private var shlemRms = 0f
    /** Полосы эквалайзера 0…1 (низкие → высокие), с плавным спадом — для плашки. */
    val spektr = FloatArray(POLOS)
    private val mono = FloatArray(BLOK)

    /** Кадров в очереди. */
    val ochered: Int get() = synchronized(lock) { ostalos }

    /**
     * Ждать, пока впереди меньше запаса. Ответ — метка для [dobavit]; -1 — остановили (/fon/stop)
     * или ждали дольше [zhdatMs].
     */
    fun zhdatMesta(zhdatMs: Long): Int = synchronized(lock) {
        val metka = stopN
        val do_ = System.currentTimeMillis() + zhdatMs
        while (ostalos >= zapasKadrov && stopN == metka) {
            val w = do_ - System.currentTimeMillis()
            if (w <= 0) return -1
            lock.wait(minOf(w, 200L))
        }
        if (stopN == metka) metka else -1
    }

    /** Положить кусок (стерео 48 кГц); после /fon/stop с этой меткой — мимо (false). */
    fun dobavit(st: ShortArray, metka: Int): Boolean = synchronized(lock) {
        if (metka != stopN || st.size < 2) return false
        kuski.addLast(st)
        ostalos += st.size / 2
        true
    }

    fun stop() = synchronized(lock) {
        kuski.clear(); poz = 0; ostalos = 0; stopN++
        lock.notifyAll()
    }

    /** Кусок микрофона (16 кГц моно, как у ушей). */
    fun mic(c: ShortArray, n: Int, nowMs: Long) {
        if (n <= 0) return
        var s = 0.0
        for (i in 0 until n) s += c[i].toDouble() * c[i]
        val rms = sqrt(s / n).toFloat()
        val db = 20f * log10(maxOf(rms, 1f) / 32768f)
        if (db > GOVORIT_DB && rms >= EHO * shlemRms) micDo = nowMs + DERZHAT_MS
    }

    fun micGovorit(nowMs: Long): Boolean = nowMs < micDo

    /**
     * Следующие [n] кадров ([n] ≤ [BLOK]) — в шлем ([shlem]) и в эфир ([efir], null — не нужно), стерео,
     * с громкостями и приглушением; [golosShlem] / [golosEfir] — слышен ли сейчас голос там. Ответ —
     * сколько кадров музыки (0 — очередь пуста: тишина).
     */
    fun vzyat(n: Int, shlem: ShortArray, efir: ShortArray?, golosShlem: Boolean, golosEfir: Boolean): Int {
        require(n in 1..BLOK)
        val blokMs = n * 1000f / Vstavka.RATE
        val d = duck.coerceIn(0f, 1f)
        fun dalshe(g: Float, golos: Boolean): Float {
            val cel = if (golos) d else 1f
            val razmah = maxOf(1f - d, 0.05f)
            return if (cel < g) maxOf(cel, g - razmah * blokMs / VNIZ_MS) else minOf(cel, g + razmah * blokMs / VVERH_MS)
        }
        val s0 = gShlem
        val s1 = dalshe(s0, golosShlem)
        val e0 = gEfir
        val e1 = dalshe(e0, golosEfir)
        gShlem = s1; gEfir = e1
        val gs = gromShlem
        val ge = gromEfir
        var got = 0
        var kv = 0.0
        synchronized(lock) {
            while (got < n && kuski.isNotEmpty()) {
                val k = kuski.first()
                val l = k[poz].toFloat()
                val r = k[poz + 1].toFloat()
                val t = got.toFloat() / n                    // множитель — плавно внутри куска, без щелчков
                val a = (s0 + (s1 - s0) * t) * gs
                val sl = Mikser.myagko(l * a)
                val sr = Mikser.myagko(r * a)
                shlem[2 * got] = sl; shlem[2 * got + 1] = sr
                if (efir != null) {
                    val b = (e0 + (e1 - e0) * t) * ge
                    efir[2 * got] = Mikser.myagko(l * b); efir[2 * got + 1] = Mikser.myagko(r * b)
                }
                kv += sl.toDouble() * sl + sr.toDouble() * sr
                mono[got] = (l + r) / 2f
                poz += 2; got++; ostalos--
                if (poz >= k.size) { kuski.removeFirst(); poz = 0 }
            }
            if (got > 0) lock.notifyAll()
        }
        java.util.Arrays.fill(shlem, 2 * got, 2 * n, 0)
        if (efir != null) java.util.Arrays.fill(efir, 2 * got, 2 * n, 0)
        shlemRms = if (got > 0) sqrt(kv / (2 * got)).toFloat() else 0f
        polosy(got, blokMs)
        return got
    }

    /** Эквалайзер: громкость на [CHASTOTY] (Гёрцель по куску), подъём сразу, спад за ~0,7 с. */
    private fun polosy(n: Int, blokMs: Float) {
        val spad = blokMs / 700f
        for (p in 0 until POLOS) {
            var v = 0f
            if (n >= 256) {
                val koef = 2.0 * cos(2.0 * Math.PI * CHASTOTY[p] / Vstavka.RATE)
                var q1 = 0.0
                var q2 = 0.0
                for (i in 0 until n) { val q0 = koef * q1 - q2 + mono[i]; q2 = q1; q1 = q0 }
                val moshch = q1 * q1 + q2 * q2 - koef * q1 * q2
                val amp = sqrt(maxOf(moshch, 0.0)) / (n / 2.0) / 32768.0
                v = ((20 * log10(maxOf(amp, 1e-6)) + 54) / 48).toFloat().coerceIn(0f, 1f)
            }
            spektr[p] = maxOf(v, spektr[p] - spad)
        }
    }

    companion object {
        const val BLOK = 1024                 // кадров за шаг (21 мс)
        const val ZAPAS_MS = 12_000
        const val DUCK = 0.33f
        const val VNIZ_MS = 150f
        const val VVERH_MS = 1000f
        const val GOVORIT_DB = -42f           // как «говорит» у гостей (Mikser)
        const val EHO = 0.25f                 // как эхо-заслонка гостей
        const val DERZHAT_MS = 600L
        const val POLOS = 10
        val CHASTOTY = floatArrayOf(60f, 120f, 250f, 500f, 1000f, 2000f, 3500f, 6000f, 9000f, 13000f)
    }
}

/**
 * Музыка в звук эфира (2.5.6): Fon кладёт в такт шлему, звук эфира (Efir) берёт в такт захвату.
 * Перед стартом и после пустоты копит [zapas] кадров — неровный шаг двух потоков не слышен;
 * переполнено (эфир не берёт) — новое мимо. Обычная JVM — FonMiksTest.
 */
class Lenta(private val maks: Int = Vstavka.RATE, private val zapas: Int = Vstavka.RATE * 15 / 100) {
    private val buf = ShortArray(2 * maks)
    private var r = 0
    private var w = 0
    private var est = 0
    private var idet = false

    @Synchronized fun dobavit(st: ShortArray, kadrov: Int): Boolean {
        if (est + kadrov > maks) return false
        for (j in 0 until kadrov) {
            buf[2 * w] = st[2 * j]; buf[2 * w + 1] = st[2 * j + 1]
            w = (w + 1) % maks
        }
        est += kadrov
        return true
    }

    /** Подмешать в стерео [out] [frames] кадров. */
    @Synchronized fun mix(out: ShortArray, frames: Int) {
        if (!idet) { if (est < zapas) return; idet = true }
        var j = 0
        while (j < frames && est > 0) {
            out[2 * j] = Mikser.myagko((out[2 * j] + buf[2 * r]).toFloat())
            out[2 * j + 1] = Mikser.myagko((out[2 * j + 1] + buf[2 * r + 1]).toFloat())
            r = (r + 1) % maks; est--; j++
        }
        if (est == 0) idet = false
    }

    @Synchronized fun ochistit() { r = 0; w = 0; est = 0; idet = false }

    val kadrov: Int @Synchronized get() = est
}
