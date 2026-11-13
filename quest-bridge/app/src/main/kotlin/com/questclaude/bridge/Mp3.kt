package com.questclaude.bridge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** MP3 (голос edge-tts, куски музыки) → PCM 16 бит аппаратным декодером. Общее для Golos и Fon. */
object Mp3 {
    class Pcm(val pcm: ShortArray, val rate: Int, val kanalov: Int)

    /** Раскодировать файл целиком; дольше [maksMs] работы — сдаёмся (null). */
    fun raskodirovat(f: File, maksMs: Long = 5000): Pcm? {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(f.path)
            val ti = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()
            val kuski = mutableListOf<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var vhodKonec = false
            val do_ = System.currentTimeMillis() + maksMs
            while (System.currentTimeMillis() < do_) {
                if (!vhodKonec) {
                    val i = d.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = d.getInputBuffer(i)!!
                        val m = ex.readSampleData(b, 0)
                        if (m < 0) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); vhodKonec = true }
                        else { d.queueInputBuffer(i, 0, m, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = d.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = d.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = d.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val sb = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        kuski += ShortArray(sb.remaining()).also { sb.get(it) }
                    }
                    d.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val vse = ShortArray(kuski.sumOf { it.size })
            var p = 0
            for (k in kuski) { k.copyInto(vse, p); p += k.size }
            return Pcm(vse, rate, ch)
        } catch (e: Exception) {
            return null
        } finally {
            try { dec?.stop() } catch (e: Exception) { }
            try { dec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}
