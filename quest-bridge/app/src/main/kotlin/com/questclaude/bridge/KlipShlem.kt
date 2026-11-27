package com.questclaude.bridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlin.concurrent.thread

/**
 * Клип шлема (2.7.9, Александр 29.10: «клип клип клип» — со шлема в полном качестве, с оверлеями как на стриме,
 * но без окна камеры; камера — своим клипом). Ещё один выход развилки со слоями эфира, но без окна камеры
 * (Razvilka.add bezKamery), свой кодировщик H.264 1080p, 30 к/с, KBPS — в круг KlipZapis (тот же, куда Efir кладёт
 * свой звук). Живёт, пока идёт эфир (развилка есть только в эфире).
 * Нагрузка (Александр 29.10: «шлему уже тяжело, ложил бридж и лагал VRChat»): клип никогда не отнимает у игры и эфира —
 *  - кодировщик фоновый (KEY_PRIORITY 1: не реального времени — железо отдаёт ему остаток после эфира);
 *  - игре тесно (Dispetcher.igre: кадры VRChat просели или шлем греется) или эфир сам ушёл на 30 к/с — клип сразу
 *    на 5 к/с (кодировщик повторяет кадр: почти даром), спокойно 30 с — снова 30 к/с; клип при этом не рвётся;
 *  - совсем выключить отдельную запись — `efir klip efir`: тогда клип шлема — это сам эфир (с окном камеры, ноль
 *    нагрузки: те же байты, что уходят на Twitch).
 */
class KlipShlem(private val rz: Razvilka, private val klip: KlipZapis, private val w: Int, private val h: Int,
                private val fps: Int = FPS, private val kbps: Int = KBPS, private val bezKamery: Boolean = true,
                private val fpsEkonom: Int = FPS_EKONOM) {
    val kachestvo: Boolean get() = !bezKamery
    /** 2.8.13: «высокое качество» (KlipNastroyki.kachestvo) — тот же кадр, что в эфире, с окном камеры, 60 к/с,
     *  20 Мбит/с; тесно игре — 30 к/с (не 5: клип должен оставаться смотрибельным). */
    constructor(rz: Razvilka, klip: KlipZapis, w: Int, h: Int, kachestvo: Boolean) :
        this(rz, klip, w, h, if (kachestvo) FPS_KACHESTVO else FPS, if (kachestvo) KBPS_KACHESTVO else KBPS,
            bezKamery = !kachestvo, fpsEkonom = if (kachestvo) FPS_EKONOM_KACHESTVO else FPS_EKONOM)

    private var enc: MediaCodec? = null
    private var surf: Surface? = null
    private var potok: Thread? = null
    @Volatile private var rabotaet = false
    @Volatile var kadrov = 0L; private set
    @Volatile var oshibka: String? = null; private set

    fun start() {
        if (rabotaet) return
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fun format(high: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)                  // клип режется по ключевым — раз в секунду
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 1)                           // фоновый: эфир кодируется первым
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, if (fps > 30 || kbps > 20000) MediaCodecInfo.CodecProfileLevel.AVCLevel42
                    else MediaCodecInfo.CodecProfileLevel.AVCLevel41)   // 1080p60 — уровень 4.2
            }
        }
        try { c.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } catch (e: Exception) {
            c.reset()
            c.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val s = c.createInputSurface()
        c.start()
        enc = c; surf = s
        rabotaet = true
        rz.add(s, fps, stamp = true, ow = w, oh = h, sloi = true, bezKamery = bezKamery)
        potok = thread(name = "klip-shlem", isDaemon = true) { petlya(c) }
        thread(name = "klip-shlem-storozh", isDaemon = true) { storozh(s) }
    }

    /** Игре или шлему тесно — клип на 5 к/с сразу; спокойно 30 с — снова полные 30. */
    @Volatile var ekonom = false; private set
    @Volatile var ekonomRaz = 0; private set

    private fun storozh(s: Surface) {
        var spokoyno = 0
        while (rabotaet) {
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
            val tesno = Hub.dispetcher.igre || Hub.teplo >= Dispetcher.TEPLO || tesnoEfiru()
            if (tesno) {
                spokoyno = 0
                if (!ekonom) { ekonom = true; ekonomRaz++; try { rz.fps(s, fpsEkonom) } catch (e: Exception) { } }
            } else if (ekonom && ++spokoyno >= SPOKOYNO_SEK) {
                ekonom = false
                try { rz.fps(s, fps) } catch (e: Exception) { }
            }
        }
    }

    /** Эфир сам ушёл на 30 к/с (тесно игре) — значит, и клипу пора уступить. Ставит служба глаз. */
    @Volatile var tesnoEfiru: () -> Boolean = { false }

    fun stop() {
        rabotaet = false
        surf?.let { try { rz.remove(it) } catch (e: Exception) { } }
        potok?.join(1000)
        try { enc?.stop() } catch (e: Exception) { }
        try { enc?.release() } catch (e: Exception) { }
        surf?.release()
        enc = null; surf = null; potok = null
    }

    private fun petlya(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (rabotaet) {
            val i = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: IllegalStateException) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val csd = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"))
                    .fold(ByteArray(0)) { acc, b -> acc + ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                golova(csd)
            } else if (i >= 0) {
                val b = c.getOutputBuffer(i)
                val a = ByteArray(info.size)
                if (b != null) { b.position(info.offset); b.get(a) }
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { golova(a); continue }
                if (a.isEmpty()) continue
                kadrov++
                try { klip.video(a, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
                catch (e: Exception) { oshibka = e.message }
            }
        }
    }

    /** Ключевой кадр сейчас (2.7.9: в то же мгновение, что у камеры — клипы на одно время). Не чаще раза в 0,9 с. */
    fun klyuchevoy() {
        val now = System.nanoTime()
        if (now - klyuchNs < 900_000_000L) return
        klyuchNs = now
        try { enc?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (e: Exception) { }
    }
    @Volatile private var klyuchNs = 0L

    private fun golova(annexB: ByteArray) {
        val (sps, pps) = Flv.spsPps(annexB)
        if (sps != null && pps != null) try { klip.golova(sps, pps, w, h) } catch (e: Exception) { oshibka = e.message }
    }

    companion object {
        const val FPS = 30
        const val FPS_EKONOM = 5
        const val SPOKOYNO_SEK = 30
        /** «Полное качество»: 1080p30 — 12 Мбит/с, выше эфира (поток не идёт в сеть — только на диск). */
        const val KBPS = 12000
        /** 2.8.13: высокое качество — 1080p60 H.264 High 20 Мбит/с: ~2,5 МБ/с, клип 2,5 мин ≈ 375 МБ, круг 320 с ≈ 800 МБ. */
        const val FPS_KACHESTVO = 60
        const val KBPS_KACHESTVO = 20000
        const val FPS_EKONOM_KACHESTVO = 30
    }
}
