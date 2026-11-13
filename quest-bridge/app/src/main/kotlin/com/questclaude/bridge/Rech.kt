package com.questclaude.bridge

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Речь для субтитров по каждому голосу (2.5.0): поток 16 кГц моно одного источника → фразы.
 * Голоса: "ya" — микрофон Александра, "igra" — звук игры, "g1"…"g3" — гости. Фразы забирает
 * демон sinhron в Ubuntu (/titry/rech): Whisper в Groq, перевод, обратно — /titr.
 *
 * Кусок 20 мс; громкость против плавающего порога шума (вниз быстро, вверх медленно); речь —
 * громче шума на [PORS] дБ и громче [minDb]. Фраза: 3 куска речи подряд — начало (с запасом 0,3 с
 * до); тишина [pauza] — конец (хвост 0,2 с); длиннее [maks] — рез в самом тихом месте последних
 * 1,5 с, остаток — начало следующей. Короче 0,3 с речи — не фраза.
 * «Тихих» (доля кусков тише половины средней громкости фразы) — у речи между слогами паузы
 * (0,3–0,6), у музыки — нет (≈0,1): демон по ней не шлёт в Whisper музыку игры.
 * Обычная JVM — RechTest.
 */
class Rech(
    val golos: String,
    private val vyvod: (Fraza) -> Unit,
    private val chasy: () -> Long = { System.currentTimeMillis() },
    private val pauza: Int = 30,             // кусков по 20 мс: 0,6 с
    private val maks: Int = 350,             // 7 с
    private val minDb: Float = -55f,
) {
    class Fraza(val golos: String, val nachaloMs: Long, val pcm: ShortArray, val tihih: Float, val db: Float) {
        val dlitMs: Int get() = pcm.size * 1000 / RATE
    }

    private val kus = ShortArray(KUS)
    private var kusN = 0
    private var shum = -60f
    private val zapas = ArrayDeque<ShortArray>()                 // 0,3 с до начала речи
    private var podryad = 0
    private val fraza = ArrayList<ShortArray>()
    private val fdb = ArrayList<Float>()
    private var tishina = 0
    private var rechKuskov = 0
    private var nachaloMs = 0L
    private var idyot = false

    @Synchronized fun dobavit(c: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            val m = minOf(KUS - kusN, n - i)
            System.arraycopy(c, i, kus, kusN, m)
            kusN += m; i += m
            if (kusN == KUS) { kusok(kus.copyOf()); kusN = 0 }
        }
    }

    private fun kusok(k: ShortArray) {
        val e = db(k)
        val rech = e > maxOf(shum + PORS, minDb)
        if (!rech) shum += (e - shum) * (if (e < shum) 0.2f else 0.005f)
        else shum += (e - shum) * 0.001f
        shum = shum.coerceIn(-80f, -25f)
        if (!idyot) {
            zapas.addLast(k)
            while (zapas.size > ZAPAS) zapas.removeFirst()
            podryad = if (rech) podryad + 1 else 0
            if (podryad >= 3) {
                idyot = true
                fraza.clear(); fdb.clear()
                for (z in zapas) { fraza += z; fdb += db(z) }
                zapas.clear()
                rechKuskov = podryad
                tishina = 0
                nachaloMs = chasy() - fraza.size * 20L
            }
            return
        }
        fraza += k; fdb += e
        if (rech) { rechKuskov++; tishina = 0 } else tishina++
        if (tishina >= pauza) {
            val hvost = maxOf(0, tishina - HVOST)
            zakonchit(fraza.size - hvost)
            idyot = false; podryad = 0
            return
        }
        if (fraza.size >= maks) {
            // рез — в самом тихом окне из 5 кусков среди последних 75
            val ot = maxOf(5, fraza.size - 75)
            var luchshe = fraza.size - 1
            var min = Float.MAX_VALUE
            for (j in ot until fraza.size - 5) {
                val s = (j until j + 5).sumOf { fdb[it].toDouble() }.toFloat()
                if (s < min) { min = s; luchshe = j + 2 }
            }
            val ostatok = fraza.subList(luchshe, fraza.size).toList()
            val ostDb = fdb.subList(luchshe, fdb.size).toList()
            zakonchit(luchshe)
            fraza.clear(); fdb.clear()
            fraza.addAll(ostatok); fdb.addAll(ostDb)
            nachaloMs = chasy() - fraza.size * 20L
            rechKuskov = ostDb.count { it > maxOf(shum + PORS, minDb) }
            tishina = 0
        }
    }

    private fun zakonchit(n: Int) {
        val kuski = fraza.subList(0, n.coerceIn(0, fraza.size))
        val dbs = fdb.subList(0, kuski.size)
        if (rechKuskov >= MIN_RECH && kuski.isNotEmpty()) {
            val pcm = ShortArray(kuski.size * KUS)
            kuski.forEachIndexed { j, k -> System.arraycopy(k, 0, pcm, j * KUS, KUS) }
            val lin = dbs.map { Math.pow(10.0, it / 20.0) }
            val sred = lin.average()
            val tihih = lin.count { it < sred / 2 }.toFloat() / lin.size
            val db = (20 * log10(maxOf(1e-9, sred))).toFloat()
            try { vyvod(Fraza(golos, nachaloMs, pcm, tihih, db)) } catch (e: Exception) { }
        }
        rechKuskov = 0
    }

    companion object {
        const val RATE = 16000
        const val KUS = 320                   // 20 мс
        const val ZAPAS = 15                  // 0,3 с
        const val HVOST = 10                  // 0,2 с тишины в конце остаётся
        const val MIN_RECH = 15               // 0,3 с речи
        const val PORS = 10f

        fun db(a: ShortArray): Float {
            var s = 0.0
            for (v in a) s += v.toDouble() * v
            val r = sqrt(s / a.size)
            return if (r < 1) -90f else (20 * log10(r / 32768.0)).toFloat()
        }
    }
}

/**
 * Все голоса для субтитров и очередь их фраз (2.5.0). Слушаем, только пока идёт эфир (субтитры есть
 * только в кадре эфира) и жив демон sinhron (спрашивал фразы за последние 30 с) — иначе ни резать, ни
 * копить нечего. Какие голоса слушать — [vkl] (titry ya|igra|gosti vkl|vykl; Александр решает).
 */
class Rechi(private val chasy: () -> Long = { System.currentTimeMillis() }) {
    private val golosa = ConcurrentHashMap<String, Rech>()
    private val ochered = ArrayDeque<Rech.Fraza>()
    private val zamok = Object()
    @Volatile private var sprashivalMs = 0L
    /** Идёт эфир (ставит служба глаз). */
    @Volatile var efir = false
    /** Какие голоса слушать: ya, igra, gosti (g1…g3). */
    @Volatile var ya = true
    @Volatile var igra = true
    @Volatile var gosti = true
    /** Субтитры гостей — пузырём у плитки (true) или внизу вместе со всеми. */
    @Volatile var uPlitki = true
    @Volatile var vybrosheno = 0L; private set
    @Volatile var fraz = 0L; private set

    val demonZhiv: Boolean get() = chasy() - sprashivalMs < 30_000L

    /** Слушает ли sinhron микрофон Александра сейчас — тогда «titr ya» от ушей лишний (тот же голос). */
    val slushaetYa: Boolean get() = efir && ya && demonZhiv

    fun vklyucheno(golos: String) = when {
        golos == "ya" -> ya
        golos == "igra" -> igra
        golos.startsWith("g") -> gosti
        else -> false
    }

    /** Кусок звука голоса (16 кГц моно); не слушаем — мимо. */
    fun podat(golos: String, c: ShortArray, n: Int) {
        if (!efir || !demonZhiv || !vklyucheno(golos)) return
        golosa.getOrPut(golos) { Rech(golos, { f -> v(f) }, chasy, maks = if (golos == "igra") 300 else 350,
            minDb = if (golos == "igra") -45f else -55f) }.dobavit(c, n)
    }

    private fun v(f: Rech.Fraza) = synchronized(zamok) {
        ochered.addLast(f)
        fraz++
        while (ochered.size > MAKS_OCHERED) { ochered.removeFirst(); vybrosheno++ }
        zamok.notifyAll()
    }

    /** Следующая фраза (ждать до [zhdatMs]); null — нет. Демон жив, пока спрашивает. */
    fun sleduyushaya(zhdatMs: Long): Rech.Fraza? = synchronized(zamok) {
        sprashivalMs = chasy()
        val konec = System.currentTimeMillis() + zhdatMs
        while (ochered.isEmpty()) {
            val ost = konec - System.currentTimeMillis()
            if (ost <= 0) return null
            zamok.wait(ost)
        }
        sprashivalMs = chasy()
        ochered.removeFirst()
    }

    fun upr(q: Map<String, String>): String {
        fun da(k: String, b: Boolean) = q[k]?.let { it != "0" && it != "vykl" } ?: b
        ya = da("ya", ya); igra = da("igra", igra); gosti = da("gosti", gosti)
        q["gde"]?.let { uPlitki = it != "vnizu" }
        if (!ya) golosa.remove("ya")
        if (!igra) golosa.remove("igra")
        return status()
    }

    fun status(): String = "{\"efir\":$efir,\"demon\":$demonZhiv,\"ya\":$ya,\"igra\":$igra,\"gosti\":$gosti," +
        "\"gosti_gde\":\"${if (uPlitki) "u_plitki" else "vnizu"}\",\"fraz\":$fraz,\"v_ocheredi\":${synchronized(zamok) { ochered.size }}," +
        "\"vybrosheno\":$vybrosheno}"

    companion object {
        const val MAKS_OCHERED = 24

        /** WAV 16 кГц моно 16 бит. */
        fun wav(pcm: ShortArray): ByteArray {
            val n = pcm.size * 2
            val b = java.nio.ByteBuffer.allocate(44 + n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + n); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(Rech.RATE); b.putInt(Rech.RATE * 2)
            b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(n)
            for (v in pcm) b.putShort(v)
            return b.array()
        }
    }
}
