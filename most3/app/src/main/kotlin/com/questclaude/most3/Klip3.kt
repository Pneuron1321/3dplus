package com.questclaude.most3

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.File

/**
 * «Машина времени игры» (Мост 3, разбор №1, зуб III): у Моста 3 есть сырой кадр — значит, есть и время.
 * Кодировщик H.264 (аппаратный) получает ту же картинку, что видит Александр, — после шейдера, с авто-ночью;
 * звук игры — AAC. Всё пишется в круг на диске (KlipZapis из моста 2: куски по ключевым кадрам, ~2 мин), по команде
 * последние N секунд собираются в MP4 в галерею (KlipMp4). Ошибка записи никогда не трогает показ и звук.
 */
class Klip3(dir: File, val shirina: Int, val vysota: Int) {
    val zapis = KlipZapis(File(dir, "klip3"))
    @Volatile var oshibka: String? = null; private set
    @Volatile var rabotaet = false; private set
    /** Поверхность кодировщика — сюда рисует Pokaz (EGL), null — запись не идёт. */
    @Volatile var poverhnost: Surface? = null; private set
    private var video: MediaCodec? = null
    private var zvuk: MediaCodec? = null
    private var potokVideo: Thread? = null
    private var potokZvuk: Thread? = null

    fun start(hz: Int?, kanaly: Int) {
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, shirina, vysota).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)         // куски круга режутся по ключевым кадрам
            }
            val v = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            v.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            poverhnost = v.createInputSurface()
            v.start(); video = v
            rabotaet = true
            potokVideo = Thread({ vyhodVideo(v) }, "most3-klip-video").apply { start() }
            if (hz != null) startZvuk(hz, kanaly)
        } catch (e: Exception) {
            oshibka = "запись игры не встала: ${e.message ?: e.javaClass.simpleName}"; stop()
        }
    }

    private fun startZvuk(hz: Int, kanaly: Int) {
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, kanaly).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            a.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            a.start(); zvuk = a
            potokZvuk = Thread({ vyhodZvuk(a) }, "most3-klip-zvuk").apply { start() }
        } catch (e: Exception) { oshibka = "запись звука игры не встала: ${e.message}" }
    }

    /** PCM 16 бит от Zvuk (поток чтения карты). Не ждёт: нет места у кодировщика — кусок мимо (в клипе щелчок). */
    fun pcm(b: ByteArray, n: Int, ptsUs: Long) {
        val a = zvuk ?: return
        if (!rabotaet) return
        try {
            var off = 0
            while (off < n) {
                val i = a.dequeueInputBuffer(0)
                if (i < 0) return
                val buf = a.getInputBuffer(i) ?: return
                val k = minOf(buf.remaining(), n - off)
                buf.put(b, off, k)
                a.queueInputBuffer(i, 0, k, ptsUs, 0)
                off += k
            }
        } catch (e: Exception) { }
    }

    private fun vyhodVideo(v: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (rabotaet) {
            val i = try { v.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { break }
            if (i < 0) continue
            try {
                val buf = v.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val d = ByteArray(info.size); buf.position(info.offset); buf.get(d)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                        Nal.spsPps(d)?.let { (sps, pps) -> zapis.golova(sps, pps, shirina, vysota) }
                    else zapis.video(d, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
            } finally { try { v.releaseOutputBuffer(i, false) } catch (e: Exception) { } }
        }
    }

    private fun vyhodZvuk(a: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (rabotaet) {
            val i = try { a.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { break }
            if (i < 0) continue
            try {
                val buf = a.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val d = ByteArray(info.size); buf.position(info.offset); buf.get(d)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) zapis.zvukGolova(d)
                    else zapis.zvuk(d, info.presentationTimeUs)
                }
            } finally { try { a.releaseOutputBuffer(i, false) } catch (e: Exception) { } }
        }
    }

    fun stop() {
        rabotaet = false
        potokVideo?.join(500); potokZvuk?.join(500)
        for (c in listOf(video, zvuk)) try { c?.stop(); c?.release() } catch (e: Exception) { }
        video = null; zvuk = null
        poverhnost?.release(); poverhnost = null
        zapis.konec()
    }

    fun status(): String = "{\"idyot\":$rabotaet,\"sekund\":${zapis.sekund}," +
        "\"oshibka\":${(oshibka ?: zapis.oshibka)?.let { Json.str(it) } ?: "null"}}"

    companion object { const val BITRATE = 16_000_000 }      // 1080p60: ~2 МБ/с, круг ~2 мин ≈ 260 МБ на диске
}
