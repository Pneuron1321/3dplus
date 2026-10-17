package com.questclaude.bridge

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Голос Авы через приложение: голос-демон в Termux (shlem/golos) шлёт сюда MP3 кусками
 * (POST /say). Зачем, если play-audio Termux уже играет:
 *  - «uho» — звук помечен «не записывать» (ALLOW_CAPTURE_BY_SYSTEM, назначение ASSISTANT):
 *    захват чужого приложения (стрим BeamXR) его пропускает, Аву слышит только Александр.
 *    Пропустит ли именно BeamXR — гипотеза, проверяется в шлеме;
 *  - «vsluh» — обычный звук (MEDIA), его пишет и стрим;
 *  - вызов «плюс плюс» обрывает голос здесь же, без пути через Termux (stop()).
 * Одна реплика за раз: новая останавливает прежнюю. «Вслух» демон играет и сам (play-audio
 * Termux) — тогда он только говорит сюда, сколько звучит кусок (GET /say/rot), чтобы двигался рот.
 * «efir» (2.2.8) — только в звук трансляции, в шлеме не звучит (ответ донатеру), идёт рядом с
 * обычной речью и вызовом не обрывается.
 * «oba» (2.3.9, план эфира II.4) — «вслух» через приложение: в шлем — как «в ухо», в эфир — прямо в
 * звук трансляции (как ответ донатеру). Голосу-демону не нужен play-audio Termux на каждую порцию;
 * «плюс плюс» обрывает и то и другое сразу. Нашего эфира нет (BeamXR или без эфира) — обычное «вслух».
 */
class Golos(private val dir: File) : FrameServer.Say {
    private class Call(val player: MediaPlayer, val file: File) {
        val done = CountDownLatch(1)
        @Volatile var end = "done"
    }

    private val n = AtomicLong()
    @Volatile private var now: Call? = null

    override val playing: Boolean get() = now != null

    /** Как звучит реплика сейчас: "vsluh" (слышит и эфир), "uho" или null — молчит. Для лица Плюса в эфире. */
    @Volatile var rezhimSeychas: String? = null
        private set

    /** До какого времени Ава говорит сама, мимо приложения (голос-демон: play-audio Termux, «вслух»). */
    @Volatile private var rotDoMs = 0L
    /** До какого времени звучит голос только в эфир (ответ донатеру): рот тоже движется — зрители слышат. */
    @Volatile private var efirDoMs = 0L

    override fun rot(sek: Double) {
        rotDoMs = if (sek > 0) System.currentTimeMillis() + (minOf(sek, 120.0) * 1000).toLong() else 0L
    }

    /** Говорит ли Ава сейчас — через приложение или сама: для рта лица в эфире. */
    val govoritSeychas: String?
        get() {
            val now = System.currentTimeMillis()
            return rezhimSeychas ?: if (now < rotDoMs) "vsluh" else if (now < efirDoMs) "efir" else null
        }

    override val govorit: Boolean get() = govoritSeychas != null

    override fun play(mp3: ByteArray, mode: String): String {
        if (mode == "efir") return efir(mp3)                // мимо шлема — то, что звучит в шлеме, не трогаем
        if (mode == "oba") return oba(mp3)
        return igrat(mp3, mode)
    }

    /**
     * «Вслух» в шлем и прямо в эфир. В шлеме — со звуком «не записывать» (иначе захват звука эфира
     * записал бы Аву второй раз), в эфир — PCM в Hub.vstavkaGolos ровно перед стартом. Не вышло
     * раскодировать — обычное «вслух» (его пишет захват, как раньше).
     */
    private fun oba(mp3: ByteArray): String {
        val v = Hub.vstavkaGolos ?: return igrat(mp3, "vsluh")
        val f = File(dir, "oba-${n.incrementAndGet()}.mp3")
        val pcm = try { f.writeBytes(mp3); dekodirovat(f) } catch (e: Exception) { null } finally { f.delete() }
        if (pcm == null) return igrat(mp3, "vsluh")
        val end = igrat(mp3, "oba") { v.ochistit(); v.dobavit(pcm) }
        if (end != "done") v.ochistit()                    // оборвали или сломалось — и из эфира прочь
        return end
    }

    /** Сыграть в шлеме; [pered] — прямо перед стартом (голос в эфир — в тот же миг). */
    private fun igrat(mp3: ByteArray, mode: String, pered: () -> Unit = {}): String {
        stop()
        val f = File(dir, "golos-${n.incrementAndGet()}.mp3")
        val p = MediaPlayer()
        val call = Call(p, f)
        try {
            f.writeBytes(mp3)
            val a = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            if (mode == "uho" || mode == "oba") a.setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
            else a.setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            p.setAudioAttributes(a.build())
            p.setDataSource(f.path)
            p.setOnCompletionListener { call.done.countDown() }
            p.setOnErrorListener { _, _, _ -> call.end = "error"; call.done.countDown(); true }
            p.prepare()
            synchronized(this) { now = call }
            rezhimSeychas = mode
            pered()
            p.start()
            call.done.await(10, TimeUnit.MINUTES)
        } catch (e: Exception) {
            call.end = "error"
        }
        synchronized(this) { if (now === call) { now = null; rezhimSeychas = null } }
        try { p.release() } catch (e: Exception) { }
        f.delete()
        return call.end
    }

    /**
     * Голос только в эфир: MP3 → PCM 48 кГц моно → подмешать в звук трансляции (Hub.vstavka);
     * в шлеме не звучит. Эфира нет — "error" (зрителей тоже нет). Ждёт, пока прозвучит.
     */
    private fun efir(mp3: ByteArray): String {
        val v = Hub.vstavka ?: return "error"
        val f = File(dir, "efir-${n.incrementAndGet()}.mp3")
        return try {
            f.writeBytes(mp3)
            val pcm = dekodirovat(f) ?: return "error"
            if (!v.dobavit(pcm)) return "error"
            val sek = pcm.size * 1000L / Vstavka.RATE
            efirDoMs = System.currentTimeMillis() + sek
            val konec = System.currentTimeMillis() + sek + 2000
            while (System.currentTimeMillis() < konec && v.ostalos() > 0) Thread.sleep(50)
            "done"
        } catch (e: Exception) {
            "error"
        } finally {
            f.delete()
        }
    }

    /** MP3 (edge-tts) → PCM 48 кГц моно; больше 5 с работы — сдаёмся. */
    private fun dekodirovat(f: File): ShortArray? = Mp3.raskodirovat(f)?.let { Vstavka.v48mono(it.pcm, it.rate, it.kanalov) }

    override fun stop() {
        rotDoMs = 0L                                       // вызов «плюс плюс» — замолчала и в Termux
        Hub.vstavkaGolos?.ochistit()                       // и в эфире («oba») — сразу
        val c = synchronized(this) { now.also { now = null; rezhimSeychas = null } } ?: return
        c.end = "stopped"
        try { c.player.stop() } catch (e: Exception) { }
        c.done.countDown()
    }
}
