package com.questclaude.bridge

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Клип камеры в MP4 (2.5.9): встроенный MediaMuxer только переупаковывает видео и звук телефона из круга
 * KlipZapis — без пережатия, секунды на две минуты. Файл — сразу в «Фильмы/Plus» (MediaStore: видят
 * галерея шлема, TikTok и Instagram; разрешений не нужно — свой файл). Один клип за раз.
 */
object KlipMp4 {
    private val zanyat = java.util.concurrent.atomic.AtomicBoolean(false)
    const val PAPKA = "Movies/Plus"

    /** Ответ JSON: {"ok":true,"put":"Movies/Plus/…mp4","sek":150,"mb":88} или {"oshibka":"…"}. */
    fun sohranit(ctx: Context, z: KlipZapis, sek: Int): String {
        if (!zanyat.compareAndSet(false, true)) return "{\"oshibka\":\"клип уже сохраняется\"}"
        try {
            val s = z.snimok(sek.coerceIn(5, 155)) ?: return "{\"oshibka\":\"камера ещё ничего не прислала — клипа нет\"}"
            val imya = "plus-klip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, imya)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PAPKA)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: run { s.otpustit(); return "{\"oshibka\":\"галерея не дала создать файл\"}" }
            var bait = 0L
            try {
                r.openFileDescriptor(uri, "rw")!!.use { fd ->
                    val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try { bait = sobrat(m, s) } finally { try { m.release() } catch (e: Exception) { } }
                }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                s.otpustit()                                   // упало до чтения — круг не должен застрять
                try { r.delete(uri, null, null) } catch (x: Exception) { }
                return "{\"oshibka\":${FrameServer.json("клип не собрался: ${e.message ?: e.javaClass.simpleName}")}}"
            }
            return "{\"ok\":true,\"put\":${FrameServer.json("$PAPKA/$imya")},\"sek\":${s.sek},\"mb\":${bait shr 20}," +
                "\"zvuk\":${s.asc != null}}"
        } finally { zanyat.set(false) }
    }

    private fun sobrat(m: MediaMuxer, s: KlipZapis.Snimok): Long {
        val g = s.golova
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, if (g.w > 0) g.w else 1920, if (g.h > 0) g.h else 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(Flv.sRazdelitelem(g.sps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(Flv.sRazdelitelem(g.pps)))
        }
        val vt = m.addTrack(vf)
        val at = s.asc?.let { asc ->
            val (hz, kan) = KlipZapis.aac(asc)
            m.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, kan).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            })
        }
        m.start()
        val info = MediaCodec.BufferInfo()
        var bait = 0L
        var poslZvuk = -1L
        s.obrazcy { o ->
            val pts = o.ptsUs - s.otUs
            if (o.video) {
                val d = KlipZapis.bezZagolovkov(o.data)
                if (d.isEmpty()) return@obrazcy
                info.set(0, d.size, pts, if (o.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(vt, ByteBuffer.wrap(d), info)
                bait += d.size
            } else if (at != null && pts > poslZvuk) {          // звук — строго по возрастанию
                info.set(0, o.data.size, pts, 0)
                m.writeSampleData(at, ByteBuffer.wrap(o.data), info)
                poslZvuk = pts
                bait += o.data.size
            }
        }
        m.stop()
        return bait
    }
}
