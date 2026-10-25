package com.questclaude.bridge

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Смесь звука гостей эфира (2.5.0). Opus — Concentus (чистая Java-копия libopus): проверяется на
 * столе (MikserTest), не зависит от кодеков Quest. Кадр — 20 мс, 48 кГц, моно.
 *
 *  - гость → [prinyat]: раскодировать → два буфера (смесь и эфир) с подстройкой (Bufer);
 *  - [tik] раз в 20 мс (часы — проигрыватель шлема, GostiZvuk): что звучит в шлеме (все гости,
 *    тише, пока Плюс слушает Александра), и каждому участнику — его смесь:
 *    игра (кладёт эфир, [fon]) × его громкость игры + голос Плюса и ответ донатеру ([golos], 2.5.3:
 *    «🔇 Только голоса» гасит игру, а Плюса — нет) + микрофон Александра (через заслонку эха) + все
 *    остальные гости; кодируется и уходит ему;
 *  - [vEfir] — гости в звук эфира (поток эфира, со своим буфером на гостя).
 * Заслонка эха: голос гостей из динамиков шлема попадает в микрофон и вернулся бы им эхом через
 * полсекунды. Пока в шлеме звучат гости, а микрофон тише [eho] × их громкости (Александр сам не
 * говорит — это эхо), микрофон в смеси гостей глушится на [ehoDb]. Эфир заслонка не трогает.
 */
class Mikser(
    private val komnata: Komnata,
    /** Речь гостя для субтитров: голос (g1…g3), 16 кГц моно. */
    private val rech: (String, ShortArray, Int) -> Unit = { _, _, _ -> },
    /** Плюс слушает Александра (после «плюс плюс») — гостей в шлеме тише. */
    private val plusSlushaet: () -> Boolean = { false },
) {
    private inner class Kanal(val id: Int) {
        val dec = OpusDecoder(RATE, 1)
        var enc: OpusEncoder? = null
        val miks = Bufer(RATE, RATE)
        val efir = Bufer(RATE, RATE, tsel0Ms = 80, minTselMs = 60)
        val pcm = ShortArray(KADR)
        val kadr = ShortArray(KADR)
        var zvuchit = false                               // в этом такте пришёл звук
        var urovenDb = -90f
        var govoritDo = 0L
        var seqVyhod = 0
        var paketov = 0L
        var oshibok = 0L
        val vyhod = ByteArray(1500)
    }

    private val kanaly = ConcurrentHashMap<Int, Kanal>()
    private val mic = Bufer(MIC_RATE, RATE, tsel0Ms = 150, minTselMs = 120, maksTselMs = 300)
    private val fonBuf = Bufer(RATE, RATE, tsel0Ms = 60, minTselMs = 40)
    private val golosBuf = Bufer(RATE, RATE, tsel0Ms = 60, minTselMs = 40)
    private val micKadr = ShortArray(KADR)
    private val fonKadr = ShortArray(KADR)
    private val golosKadr = ShortArray(KADR)
    private val shlemF = FloatArray(KADR)
    private val smes = FloatArray(KADR)
    private val izMiks = ShortArray(KADR)
    private val r16 = ShortArray(KADR / 3)
    private var shlemOgib = 0f                             // огибающая звука гостей в шлеме (300 мс, пик)
    private val shlemHist = FloatArray(15)
    private var shlemHistI = 0
    private var micUsil = 1f                               // заслонка: 1 — открыта
    private var zvuchaliMs = 0L
    private var aleksandrMs = -ALEKSANDR_MS                // когда Александр последний раз говорил громче эха гостей

    /** Громкость гостей в шлеме (0–1,5) и в эфире (0–2). */
    @Volatile var gromkostShlem = 0.8f
    @Volatile var gromkostEfir = 1f
    /** Заслонка эха: порог (доля громкости гостей в шлеме; 0 — выключена) и сколько глушить, дБ. */
    @Volatile var eho = 0.25f
    @Volatile var ehoDb = 10f
    /** Время кодирования и раскодирования за всё время, нс — для /gosti. */
    @Volatile var kodNs = 0L; private set
    @Volatile var taktov = 0L; private set

    // ---------- входы ----------

    /** Пакет Opus от участника (поток сети). */
    fun prinyat(u: Komnata.Uchastnik, seq: Int, d: ByteArray, off: Int, len: Int) {
        val k = kanaly.getOrPut(u.id) { Kanal(u.id) }
        val t0 = System.nanoTime()
        val n = try {
            synchronized(k.dec) { k.dec.decode(d, off, len, k.pcm, 0, KADR, false) }
        } catch (e: Exception) { k.oshibok++; return }
        kodNs += System.nanoTime() - t0
        k.paketov++
        if (n <= 0) return
        k.miks.dobavit(k.pcm, 0, n)
        k.efir.dobavit(k.pcm, 0, n)
    }

    /** Участник ушёл — его кодеки и буферы прочь. */
    fun ushel(u: Komnata.Uchastnik) { kanaly.remove(u.id) }

    /** Микрофон Александра (уши, 16 кГц моно). */
    fun mic(c: ShortArray, n: Int) = mic.dobavit(c, 0, n)

    /** Звук игры, 48 кГц моно — кладёт поток эфира; в смеси гостя — × его громкость игры. */
    fun fon(c: ShortArray, n: Int) = fonBuf.dobavit(c, 0, n)

    /** Голос Плюса и ответ донатеру (вставки эфира), 48 кГц моно — кладёт поток эфира вместе с [fon]
     *  (тишиной, когда никто не говорит); в смеси гостя — всегда, «🔇 Только голоса» его не трогает. */
    fun golos(c: ShortArray, n: Int) = golosBuf.dobavit(c, 0, n)

    // ---------- такт ----------

    /**
     * Такт 20 мс: в [shlem] — что играть в шлеме (KADR отсчётов); каждому участнику — его смесь.
     * true — в шлеме говорит гость, а Александр молчит (для уха: голос гостя не должен будить Плюса).
     */
    fun tik(shlem: ShortArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        taktov++
        val slyshno = komnata.slyshno()
        val vnutri = komnata.vnutri().filter { it.rol != "hozyain" }
        val estMic = mic.vzyat(micKadr)
        fonBuf.vzyat(fonKadr)
        golosBuf.vzyat(golosKadr)
        java.util.Arrays.fill(shlemF, 0f)
        val zvuchat = ArrayList<Kanal>(slyshno.size)
        var izmenilos = false
        for (u in slyshno) {
            val k = kanaly[u.id] ?: continue
            k.zvuchit = k.miks.vzyat(k.kadr)
            if (!k.zvuchit) { k.urovenDb = -90f } else {
                k.urovenDb = db(k.kadr)
                zvuchat += k
                for (i in 0 until KADR) shlemF[i] += k.kadr[i].toFloat()
                if (u.golos.isNotEmpty()) { vniz16(k.kadr); rech(u.golos, r16, r16.size) }
            }
            val bylo = u.govorit
            if (k.urovenDb > GOVORIT_DB) k.govoritDo = nowMs + GOVORIT_DERZHAT_MS
            u.govorit = nowMs < k.govoritDo
            if (u.govorit != bylo) izmenilos = true
        }
        for (u in vnutri) if (u.govorit && slyshno.none { it.id == u.id }) { u.govorit = false; izmenilos = true }
        if (izmenilos) komnata.govoryat(vnutri)
        // шлем: все гости; Плюс слушает — на 18 дБ тише
        val g = gromkostShlem * (if (plusSlushaet()) 0.125f else 1f)
        var pik = 0f
        for (i in 0 until KADR) {
            val v = shlemF[i] * g
            pik = maxOf(pik, kotlin.math.abs(v))
            shlem[i] = myagko(v)
        }
        // заслонка эха: громкость гостей в шлеме за 300 мс против микрофона сейчас
        shlemHist[shlemHistI] = rms(shlemF) * g; shlemHistI = (shlemHistI + 1) % shlemHist.size
        shlemOgib = shlemHist.max()
        val micR = if (estMic) rms(micKadr) else 0f
        // 2.7.6 (Санёк 25.10: «пока у гостя включён микрофон, Плюс не слышит „плюс плюс“»): раньше «гость
        // звучит» было при любом пакете — включённый микрофон шлёт тишину и шум без конца, и уши молчали всё
        // время. Теперь — только когда гость правда говорит, его слышно в шлеме, а микрофон Александра — лишь
        // эхо. Александр говорит поверх гостей — его «плюс плюс» проходит.
        if (micR >= MIC_GOLOS && micR >= maxOf(eho, ECHO_DOLYA) * shlemOgib) aleksandrMs = nowMs
        if (slyshno.any { it.govorit } && shlemOgib > POROG_EHO) zvuchaliMs = nowMs
        val zakryt = eho > 0f && shlemOgib > POROG_EHO && micR < eho * shlemOgib
        val cel = if (zakryt) Math.pow(10.0, -ehoDb / 20.0).toFloat() else 1f
        micUsil = if (cel < micUsil) maxOf(cel, micUsil * 0.5f) else minOf(cel, micUsil + 0.08f)
        // каждому — его смесь
        for (u in vnutri) {
            val k = kanaly.getOrPut(u.id) { Kanal(u.id) }
            val e = k.enc ?: noviyKoder().also { k.enc = it }
            for (i in 0 until KADR) smes[i] = fonKadr[i] * u.igra + golosKadr[i] + micKadr[i] * MIC_USIL * micUsil
            for (o in zvuchat) if (o.id != u.id) for (i in 0 until KADR) smes[i] += o.kadr[i].toFloat()
            for (i in 0 until KADR) izMiks[i] = myagko(smes[i])
            val t0 = System.nanoTime()
            val n = try { e.encode(izMiks, 0, KADR, k.vyhod, 0, k.vyhod.size) } catch (x: Exception) { k.oshibok++; 0 }
            kodNs += System.nanoTime() - t0
            if (n > 0) u.svyaz.dvoichnoe(GostiServer.kadrZvuka(k.seqVyhod, k.vyhod, 0, n), 'a', false)
            k.seqVyhod = (k.seqVyhod + 1) and 0xffff
        }
        return gostZvuchit(nowMs)
    }

    /** Гости в звук эфира: стерео [out] (L,R…), [frames] кадров 48 кГц — поток эфира. */
    fun vEfir(out: ShortArray, frames: Int) {
        if (frames > izEfira.size) return
        val g = gromkostEfir
        for (u in komnata.slyshno()) {
            val k = kanaly[u.id] ?: continue
            if (!k.efir.vzyat(izEfira, 0, frames)) continue
            for (j in 0 until frames) {
                val v = Mikrofon.myagko((izEfira[j] * g).toInt())
                out[2 * j] = (out[2 * j] + v).coerceIn(-32768, 32767).toShort()
                out[2 * j + 1] = (out[2 * j + 1] + v).coerceIn(-32768, 32767).toShort()
            }
        }
    }
    private val izEfira = ShortArray(4096)

    /** Звучал ли гость в шлеме за последние ZVUCHALI_MS (уши не будят Плюса его голосом). */
    fun gostZvuchit(nowMs: Long = System.currentTimeMillis()) =
        nowMs - zvuchaliMs < ZVUCHALI_MS && nowMs - aleksandrMs >= ALEKSANDR_MS

    fun status(): String {
        val ks = kanaly.values.joinToString(",") { k ->
            "{\"id\":${k.id},\"paketov\":${k.paketov},\"oshibok\":${k.oshibok},\"bufer_ms\":${k.miks.estMs}," +
                "\"tsel_ms\":${k.miks.tselMs},\"propuski\":${k.miks.propuski},\"sbrosy\":${k.miks.sbrosy}," +
                "\"uroven_db\":${k.urovenDb.toInt()}}"
        }
        val sek = taktov * KADR / RATE.toDouble()
        val dolya = if (sek > 0) kodNs / 1e9 / sek * 100 else 0.0
        return "{\"kanaly\":[$ks],\"opus_proc\":${String.format(java.util.Locale.ROOT, "%.1f", dolya)}," +
            "\"mic_ms\":${mic.estMs},\"fon_ms\":${fonBuf.estMs},\"golos_ms\":${golosBuf.estMs},\"zaslonka\":${String.format(java.util.Locale.ROOT, "%.2f", micUsil)}," +
            "\"shlem\":$gromkostShlem,\"efir\":$gromkostEfir,\"eho\":$eho,\"eho_db\":$ehoDb}"
    }

    private fun noviyKoder() = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        bitrate = 64000
        complexity = 5
        signalType = OpusSignal.OPUS_SIGNAL_AUTO
        useVBR = true
    }

    /** 48 кГц → 16 кГц для субтитров: среднее по три (грубый фильтр — речи хватает). */
    private fun vniz16(a: ShortArray) {
        for (i in r16.indices) r16[i] = ((a[3 * i] + a[3 * i + 1] + a[3 * i + 2]) / 3).toShort()
    }

    companion object {
        const val RATE = 48000
        const val KADR = 960                                 // 20 мс
        const val MIC_RATE = 16000
        /** Микрофон ушей пишется без усиления — гостям, как в эфире, громче. */
        const val MIC_USIL = 3f
        const val GOVORIT_DB = -42f
        const val GOVORIT_DERZHAT_MS = 600L
        const val ZVUCHALI_MS = 1200L
        /** Александр говорил громче эха гостей за столько мс — уши его слушают, даже если гость тоже говорит. */
        const val ALEKSANDR_MS = 1500L
        /** Микрофон ушей громче этого (RMS, ≈ −38 дБ) — голос, а не шум комнаты. */
        const val MIC_GOLOS = 400f
        /** Микрофон громче этой доли звука гостей в шлеме — это сам Александр, а не эхо (как у заслонки). */
        const val ECHO_DOLYA = 0.25f
        /** Ниже — гостей в шлеме не слышно, заслонке нечего делать (≈ −50 дБ). */
        const val POROG_EHO = 100f

        fun db(a: ShortArray): Float {
            var s = 0.0
            for (v in a) s += v.toDouble() * v
            val r = sqrt(s / a.size)
            return if (r < 1) -90f else (20 * log10(r / 32768.0)).toFloat()
        }

        fun rms(a: FloatArray): Float { var s = 0.0; for (v in a) s += v.toDouble() * v; return sqrt(s / a.size).toFloat() }
        fun rms(a: ShortArray): Float { var s = 0.0; for (v in a) s += v.toDouble() * v; return sqrt(s / a.size).toFloat() }

        /** Мягкий ограничитель (как у голоса в эфире): выше 3/4 шкалы — вчетверо тише. */
        fun myagko(v: Float): Short = Mikrofon.myagko(v.toInt()).coerceIn(-32768, 32767).toShort()
    }
}
