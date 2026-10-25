package com.questclaude.bridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import kotlin.concurrent.thread

/**
 * Картинка эфира гостям (2.5.0): ещё один выход развилки — маленький кодировщик H.264 (Baseline,
 * 640×360, 2.7.6: 10 к/с, 450 кбит/с — бережём сеть и видеокарту эфира) со всеми слоями эфира. Гость видит то же, что зрители, почти без
 * задержки (без Twitch). Ключевой кадр — раз в 2 с и по просьбе (новый гость, отстал); в ключевом
 * всегда SPS и PPS (Annex B) — браузер (WebCodecs) раскодирует с любого ключевого.
 */
class Prevyu(
    private val rz: Razvilka,
    /** Кадр (Annex B), ключевой ли, время (мс) — разослать гостям. */
    private val razoslat: (ByteArray, Boolean, Long) -> Unit,
    /** Строка кодека для браузера (avc1.PPCCLL) — когда стала известна. */
    private val kodek: (String) -> Unit,
) {
    private var enc: MediaCodec? = null
    private var surf: Surface? = null
    private var potok: Thread? = null
    @Volatile private var rabotaet = false
    private var csd = ByteArray(0)
    @Volatile private var prosilMs = 0L
    @Volatile var kadrov = 0L; private set
    @Volatile var oshibka: String? = null; private set

    fun start() {
        if (rabotaet) return
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, KBPS * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        try { c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } catch (e: Exception) {
            c.reset()
            f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 0)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)          // SPS/PPS добавим сами
        }
        val s = c.createInputSurface()
        c.start()
        enc = c; surf = s
        rabotaet = true
        rz.add(s, FPS, stamp = true, ow = W, oh = H, sloi = true)
        potok = thread(name = "prevyu", isDaemon = true) { petlya(c) }
    }

    fun stop() {
        rabotaet = false
        surf?.let { try { rz.remove(it) } catch (e: Exception) { } }
        potok?.join(1000)
        try { enc?.stop() } catch (e: Exception) { }
        try { enc?.release() } catch (e: Exception) { }
        surf?.release()
        enc = null; surf = null; potok = null
    }

    val idyot: Boolean get() = rabotaet

    /** Кому-то нужен ключевой кадр — не чаще раза в секунду. */
    fun klyuchevoy() {
        val now = System.currentTimeMillis()
        if (now - prosilMs < 1000) return
        prosilMs = now
        try { enc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (e: Exception) { }
    }

    private fun petlya(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (rabotaet) {
            val i = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: IllegalStateException) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                csd = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"))
                    .fold(ByteArray(0)) { acc, b -> acc + ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                ob(csd)
            } else if (i >= 0) {
                val b = c.getOutputBuffer(i)
                val a = ByteArray(info.size)
                if (b != null) { b.position(info.offset); b.get(a) }
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { csd = a; ob(a); continue }
                if (a.isEmpty()) continue
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val kadr = if (key && Flv.spsPps(a).first == null && csd.isNotEmpty()) csd + a else a
                kadrov++
                try { razoslat(kadr, key, info.presentationTimeUs / 1000) } catch (e: Exception) { oshibka = e.message }
            }
        }
    }

    private fun ob(annexB: ByteArray) {
        val sps = Flv.spsPps(annexB).first ?: return
        if (sps.size >= 4) kodek(stroka(sps))
    }

    companion object {
        const val W = 640
        const val H = 360
        const val FPS = 10
        const val KBPS = 450

        /** «avc1.PPCCLL» из SPS (без стартового кода): профиль, ограничения, уровень. */
        fun stroka(sps: ByteArray): String = "avc1.%02X%02X%02X".format(sps[1].toInt() and 0xff, sps[2].toInt() and 0xff, sps[3].toInt() and 0xff)
    }
}
