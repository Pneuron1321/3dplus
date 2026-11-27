package com.questclaude.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Фоновая музыка (2.5.6, задания Плюса 17.10, п. 1–3): второй канал рядом с голосом — играет вместе
 * с Авой, в шлем и в эфир. Плеер muzyka (Ubuntu) шлёт MP3 кусками в POST /fon; приглушение под
 * голосом, громкости и очередь — FonMiks. В шлем — звуком «не записывать» (как «oba»: захват эфира
 * его не пишет второй раз), в эфир — прямо (Efir берёт из Hub.fonLenta), гостям — вместе со звуком
 * игры: «🔇 Только голоса» её глушит. Голос, рот лица и соведущую музыка не трогает: /say о ней не
 * знает. Нашего эфира нет (BeamXR или без эфира) — обычным звуком, его пишет чужой захват.
 * Полоска «сейчас играет» — [plashkaVidna]: просили показать (/fon/plashka) и музыка звучит.
 */
class Fon(private val dir: File, private val nastroyki: File) : FrameServer.FonUpr {
    val miks = FonMiks()
    /** Мини-плеер (2.5.7): состояние muzyka и команды из шторки приложения. */
    val pult = PleerPult()
    private val n = AtomicLong()
    @Volatile private var rezhim = "oba"
    private var potok: Thread? = null
    @Volatile private var zvuchalaMs = 0L
    @Volatile private var plashkaPokaz = false
    @Volatile var plashkaTekst = ""
        private set
    private val micTap: (ShortArray, Int) -> Unit = { c, k -> miks.mic(c, k, System.currentTimeMillis()) }

    init { zagruzit() }

    /** Полоска «сейчас играет» в кадре: просили показать и музыка звучит (или звучала меньше 3 с назад). */
    fun plashkaVidna(now: Long = System.currentTimeMillis()): Boolean =
        plashkaPokaz && plashkaTekst.isNotBlank() && now - zvuchalaMs < 3000

    override fun kusok(mp3: ByteArray, mode: String): String {
        rezhim = mode
        val metka = miks.zhdatMesta(180_000)                      // впереди много — ждём, MP3 пока не раскодируем
        if (metka < 0) return "{\"ok\":false,\"stop\":true}"
        val f = File(dir, "fon-${n.incrementAndGet()}.mp3")
        val pcm = try { f.writeBytes(mp3); Mp3.raskodirovat(f, 20_000) } catch (e: Exception) { null } finally { f.delete() }
        val st = pcm?.let { Vstavka.v48stereo(it.pcm, it.rate, it.kanalov) }
        if (st == null || st.isEmpty()) return "{\"ok\":false,\"oshibka\":\"не раскодировал\"}"
        val ok = miks.dobavit(st, metka)
        if (ok) zapustit()
        return "{\"ok\":$ok,\"sek\":${okr(st.size / 2.0 / Vstavka.RATE)},\"ochered_s\":${okr(miks.ochered.toDouble() / Vstavka.RATE)}}"
    }

    override fun pleer(json: String?): String = if (json != null) pult.zapisat(json) else
        (pult.sostoyanie.ifEmpty { "{}" }.let { if (it.trimEnd().endsWith("}") && it.length > 2) it.trimEnd().dropLast(1) + ",\"zhiv\":${pult.zhiv()}}" else "{\"zhiv\":false}" })

    override fun komanda(q: Map<String, String>): String {
        fun d(s: String?) = s?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }.orEmpty()
        return "{\"ok\":${pult.komanda(d(q["k"]), d(q["a"]))}}"
    }

    override fun zhdatKomandu(ms: Long): String = pult.zhdat(ms)

    override fun stop(): String {
        miks.stop()
        Hub.fonLenta?.ochistit()
        return status()
    }

    override fun status(): String {
        val now = System.currentTimeMillis()
        return "{\"igraet\":${now - zvuchalaMs < 1000},\"ochered_s\":${okr(miks.ochered.toDouble() / Vstavka.RATE)}," +
            "\"rezhim\":\"$rezhim\",\"v_efir\":${Hub.fonLenta != null}," +
            "\"gromkost\":{\"shlem\":${proc(miks.gromShlem)},\"efir\":${proc(miks.gromEfir)}},\"duck\":${okr2(miks.duck)}," +
            "\"seychas\":{\"shlem\":${okr2(miks.gShlem)},\"efir\":${okr2(miks.gEfir)}},\"mikrofon\":${miks.micGovorit(now)}," +
            "\"plashka\":{\"pokaz\":$plashkaPokaz,\"vidna\":${plashkaVidna(now)},\"tekst\":${FrameServer.json(plashkaTekst)}}}"
    }

    /** v — в шлем и в эфир сразу; shlem, efir — по отдельности. Проценты 0–150 (100 — как прислали). */
    override fun gromkost(q: Map<String, String>): String {
        fun p(k: String) = q[k]?.replace(',', '.')?.toFloatOrNull()?.coerceIn(0f, 150f)?.div(100f)
        p("v")?.let { miks.gromShlem = it; miks.gromEfir = it }
        p("shlem")?.let { miks.gromShlem = it }
        p("efir")?.let { miks.gromEfir = it }
        sohranit()
        return status()
    }

    /** До какой доли стихает музыка под голосом: v=0.33 (0 — замолкает, 1 — не стихает). */
    override fun duck(q: Map<String, String>): String {
        q["v"]?.replace(',', '.')?.toFloatOrNull()?.let { miks.duck = it.coerceIn(0f, 1f); sohranit() }
        return status()
    }

    /** pokaz=1|0; tekst — «трек — автор» (автор обязателен: CC BY 4.0); avtor — можно отдельно. */
    override fun plashka(q: Map<String, String>): String {
        fun d(s: String?) = s?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }?.trim()
        d(q["tekst"])?.let { t ->
            val a = d(q["avtor"]).orEmpty()
            plashkaTekst = (if (a.isNotEmpty() && !t.contains(a)) "$t — $a" else t).take(140)
        }
        q["pokaz"]?.let { plashkaPokaz = it == "1" }
        return status()
    }

    @Synchronized private fun zapustit() {
        if (potok != null) return
        Hub.micTapy += micTap
        potok = thread(name = "fon", isDaemon = true) { igrat() }
    }

    private fun igrat() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var dlyaEfira = false
        val shlem = ShortArray(2 * FonMiks.BLOK)
        val efir = ShortArray(2 * FonMiks.BLOK)
        var tishina = 0L
        try {
            while (true) {
                val lenta = Hub.fonLenta                               // наш эфир идёт — Efir поставил ленту
                val nash = lenta != null
                if (track == null || dlyaEfira != nash) {
                    track?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
                    track = sozdat(nash); dlyaEfira = nash
                }
                val now = System.currentTimeMillis()
                val g = (Hub.say as? Golos)?.govoritSeychas
                val mic = miks.micGovorit(now)
                val vEfir = nash && rezhim != "uho"
                val got = miks.vzyat(FonMiks.BLOK, shlem, if (vEfir) efir else null, g != null || mic, (g != null && g != "uho") || mic)
                if (got > 0) { zvuchalaMs = now; tishina = 0 }
                else {
                    tishina += FonMiks.BLOK * 1000L / Vstavka.RATE
                    if (tishina > TISHINA_MS) {                         // очередь пуста давно — поток прочь (новый кусок поднимет)
                        val konec = synchronized(this) { if (miks.ochered == 0) { potok = null; true } else false }
                        if (konec) break
                        tishina = 0
                    }
                }
                if (vEfir && got > 0) lenta!!.dobavit(efir, got)
                if (rezhim == "efir") java.util.Arrays.fill(shlem, 0)
                track!!.write(shlem, 0, shlem.size)                     // ждёт места — шаг в такт звуку шлема
            }
        } catch (e: Exception) {
        } finally {
            track?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
            Hub.micTapy -= micTap
            synchronized(this) { if (potok === Thread.currentThread()) potok = null }
        }
    }

    private fun sozdat(nash: Boolean): AudioTrack {
        val a = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        if (nash) a.setUsage(AudioAttributes.USAGE_ASSISTANT).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
        else a.setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        val min = AudioTrack.getMinBufferSize(Vstavka.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(a.build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Vstavka.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, FonMiks.BLOK * 4 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    private fun zagruzit() {
        val c = try { nastroyki.readText().trim().split(' ').mapNotNull { it.toFloatOrNull() } } catch (e: Exception) { emptyList() }
        if (c.size >= 3) {
            miks.gromShlem = c[0].coerceIn(0f, 1.5f); miks.gromEfir = c[1].coerceIn(0f, 1.5f); miks.duck = c[2].coerceIn(0f, 1f)
        }
    }

    private fun sohranit() {
        try { nastroyki.writeText("${miks.gromShlem} ${miks.gromEfir} ${miks.duck}") } catch (e: Exception) { }
    }

    companion object {
        const val TISHINA_MS = 5000L
        private fun okr(v: Double) = String.format(Locale.ROOT, "%.1f", v)
        private fun okr2(v: Float) = String.format(Locale.ROOT, "%.2f", v)
        private fun proc(v: Float) = Math.round(v * 100)
    }
}
