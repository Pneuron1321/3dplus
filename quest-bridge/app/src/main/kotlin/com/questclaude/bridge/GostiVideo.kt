package com.questclaude.bridge

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Видео гостей в кадре эфира (2.5.0): у каждого гостя свой аппаратный декодер H.264 или VP8 (браузер
 * без H.264 в WebCodecs) рисует в свою поверхность развилки, как камера айфона; JPEG (браузер без
 * WebCodecs) — картинкой. Пока эфира
 * нет (нет развилки), кадры гостей не раскодируются; эфир начался — просим у гостя ключевой кадр.
 */
class GostiVideo(
    /** Попросить у участника ключевой кадр (новый декодер, сбой). */
    private val prositKlyuchevoy: (Int) -> Unit,
) {
    private inner class Dek(val id: Int) {
        val lock = Any()
        var dec: MediaCodec? = null
        var vyvod: Thread? = null
        @Volatile var zhiv = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var kodek = 'h'
        var vw = 0
        var vh = 0
        var zhdetKlyuch = true
        @Volatile var pokazano = 0L
        @Volatile var propushcheno = 0L
        @Volatile var jpegZanyat = false
        var prosilMs = 0L
    }

    private val deki = ConcurrentHashMap<Int, Dek>()
    @Volatile private var razvilka: Razvilka? = null
    @Volatile var oshibka: String? = null
        private set

    /** Эфир начался: видео гостей — в развилку. */
    fun podklyuchit(rz: Razvilka) {
        razvilka = rz
        for (d in deki.values) synchronized(d.lock) { ostanovit(d); d.zhdetKlyuch = true; prosit(d) }
    }

    /** Эфир кончился: декодеры прочь (до release развилки). */
    fun otklyuchit() {
        val rz = razvilka
        razvilka = null
        for (d in deki.values) synchronized(d.lock) { ostanovit(d) }
        if (rz != null) for (id in deki.keys) try { rz.gostUbrat(id) } catch (e: Exception) { }
    }

    /** Участник ушёл. */
    fun ubrat(id: Int) {
        val d = deki.remove(id) ?: return
        synchronized(d.lock) { ostanovit(d) }
        try { razvilka?.gostUbrat(id) } catch (e: Exception) { }
    }

    /** Кадр от гостя (поток его соединения); kodek: 'h' — H.264, 'v' — VP8, 'j' — JPEG. */
    fun kadr(id: Int, key: Boolean, d: ByteArray, off: Int, len: Int, kodek: Char) {
        val rz = razvilka ?: return
        val k = deki.getOrPut(id) { Dek(id) }
        if (kodek == 'j') { kartinka(rz, k, d, off, len); return }
        synchronized(k.lock) {
            val a = d.copyOfRange(off, off + len)
            if (key && kodek == 'h') {
                val (sps, pps) = Flv.spsPps(a)
                if (sps != null && pps != null && (k.dec == null || k.kodek != 'h' || !sps.contentEquals(k.sps) || !pps.contentEquals(k.pps))) {
                    ostanovit(k)
                    k.kodek = 'h'; k.sps = sps; k.pps = pps
                    zapustit(rz, k)
                }
            } else if (key && kodek == 'v') {
                val r = vp8Razmer(a)
                if (r != null && (k.dec == null || k.kodek != 'v' || r.first != k.vw || r.second != k.vh)) {
                    ostanovit(k)
                    k.kodek = 'v'; k.vw = r.first; k.vh = r.second
                    zapustit(rz, k)
                }
            }
            val dec = k.dec
            if (dec == null || (k.zhdetKlyuch && !key)) { k.propushcheno++; prosit(k); return }
            try {
                val i = dec.dequeueInputBuffer(5_000)
                if (i < 0) { k.propushcheno++; k.zhdetKlyuch = true; prosit(k); return }
                val b = dec.getInputBuffer(i)
                if (b == null || a.size > b.capacity()) { dec.queueInputBuffer(i, 0, 0, 0, 0); k.zhdetKlyuch = true; return }
                b.clear(); b.put(a)
                dec.queueInputBuffer(i, 0, a.size, System.nanoTime() / 1000, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                k.zhdetKlyuch = false
            } catch (e: Exception) {
                oshibka = "декодер гостя: ${e.message ?: e.javaClass.simpleName}"
                ostanovit(k); k.zhdetKlyuch = true; prosit(k)
            }
        }
    }

    private fun kartinka(rz: Razvilka, k: Dek, d: ByteArray, off: Int, len: Int) {
        if (k.jpegZanyat) { k.propushcheno++; return }                    // не успеваем — этот мимо
        k.jpegZanyat = true
        try {
            val b = BitmapFactory.decodeByteArray(d, off, len) ?: return
            if (b.width > 640 || b.height > 640) { b.recycle(); return }      // гость шлёт маленькие
            rz.gostKartinka(k.id, b)
            k.pokazano++
        } catch (e: Exception) {
        } finally { k.jpegZanyat = false }
    }

    private fun prosit(k: Dek) {
        val now = System.currentTimeMillis()
        if (now - k.prosilMs < 1000) return
        k.prosilMs = now
        try { prositKlyuchevoy(k.id) } catch (e: Exception) { }
    }

    private fun zapustit(rz: Razvilka, k: Dek) {
        val vp8 = k.kodek == 'v'
        val sps = k.sps
        val pps = k.pps
        if (!vp8 && (sps == null || pps == null)) return
        val s: Surface = try { rz.gostPoverhnost(k.id) } catch (e: Exception) { oshibka = "развилка: ${e.message}"; return }
        val mime = if (vp8) MediaFormat.MIMETYPE_VIDEO_VP8 else MediaFormat.MIMETYPE_VIDEO_AVC
        val dec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
            oshibka = "декодер $mime не создан: ${e.message}"; return
        }
        fun format(bystro: Boolean) = MediaFormat.createVideoFormat(mime, if (vp8) k.vw else 360, if (vp8) k.vh else 360).apply {
            if (!vp8) {
                setByteBuffer("csd-0", ByteBuffer.wrap(Flv.sRazdelitelem(sps!!)))
                setByteBuffer("csd-1", ByteBuffer.wrap(Flv.sRazdelitelem(pps!!)))
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 400_000)
            if (bystro) {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
        }
        var ok = false
        for (bystro in listOf(true, false)) {
            try { dec.configure(format(bystro), s, null, 0); ok = true; break } catch (e: Exception) { dec.reset() }
        }
        if (!ok) { dec.release(); oshibka = "декодер не принял видео гостя"; return }
        dec.start()
        k.dec = dec
        k.zhiv = true
        k.zhdetKlyuch = true
        k.vyvod = thread(name = "gost-video-${k.id}", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            while (k.zhiv) {
                val i = try { dec.dequeueOutputBuffer(info, 20_000) } catch (e: IllegalStateException) { break }
                if (i >= 0) {
                    try { dec.releaseOutputBuffer(i, true) } catch (e: IllegalStateException) { break }
                    k.pokazano++
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = try { dec.outputFormat } catch (e: IllegalStateException) { break }
                    fun n(x: String) = if (f.containsKey(x)) f.getInteger(x) else -1
                    val w = if (n("crop-right") >= 0) n("crop-right") - maxOf(0, n("crop-left")) + 1 else n(MediaFormat.KEY_WIDTH)
                    val h = if (n("crop-bottom") >= 0) n("crop-bottom") - maxOf(0, n("crop-top")) + 1 else n(MediaFormat.KEY_HEIGHT)
                    razvilka?.gostRazmer(k.id, w, h)
                }
            }
        }
    }

    private fun ostanovit(k: Dek) {
        val dec = k.dec ?: return
        k.zhiv = false
        k.vyvod?.join(500)
        try { dec.stop() } catch (e: Exception) { }
        try { dec.release() } catch (e: Exception) { }
        k.dec = null
        k.vyvod = null
    }

    companion object {
        /** Размер из ключевого кадра VP8 (RFC 6386: 3 байта тега, 9d 01 2a, ширина и высота по 14 бит); не ключевой — null. */
        fun vp8Razmer(a: ByteArray): Pair<Int, Int>? {
            if (a.size < 10 || a[0].toInt() and 1 != 0) return null
            if (a[3] != 0x9d.toByte() || a[4] != 0x01.toByte() || a[5] != 0x2a.toByte()) return null
            val w = ((a[6].toInt() and 0xff) or ((a[7].toInt() and 0xff) shl 8)) and 0x3fff
            val h = ((a[8].toInt() and 0xff) or ((a[9].toInt() and 0xff) shl 8)) and 0x3fff
            return if (w > 0 && h > 0) w to h else null
        }
    }

    fun status(): String = deki.values.joinToString(",", "[", "]") {
        "{\"id\":${it.id},\"dekoder\":${it.dec != null},\"kodek\":\"${it.kodek}\",\"pokazano\":${it.pokazano},\"propushcheno\":${it.propushcheno}," +
            "\"vidno\":${razvilka?.gostVidno(it.id) == true}}"
    }
}
