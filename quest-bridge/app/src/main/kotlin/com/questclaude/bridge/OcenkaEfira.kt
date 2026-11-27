package com.questclaude.bridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10

/**
 * «Свой глаз» (2.8.15, план 25.11 К1; Александр: «нет ориентира»): насколько эфир отличается от кадра, который мост
 * собрал. По команде N секунд: развилка копирует центральный кусок 640×360 кадра эфира прямо перед кодировщиком
 * (эталон, 2 раза в секунду), а свой же поток эфира раскодирует аппаратный декодер — кадры сводятся по времени
 * (у кодировщика время кадра = время развилки) и сравниваются по яркости: SSIM (1 — неотличимо) и PSNR (дБ).
 * Кусок без масштабирования — масштаб прятал бы квадраты сжатия. Разный диапазон и матрица цвета у эталона (RGB) и
 * декодера (YUV, обычно 16–235) снимаются линейной подгонкой яркости по методу наименьших квадратов — остаётся
 * то, что потеряло сжатие. Нагрузка — только пока идёт замер: декодер (аппаратный) и чтение 0,9 МБ дважды в секунду.
 */
object Ocenka {
    const val KUSOK_W = 640
    const val KUSOK_H = 360

    /** Яркость (BT.709, 0..255) куска RGBA из glReadPixels: строки там снизу вверх — переворачиваем. */
    fun yarkostGl(rgba: ByteArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                val o = src + x * 4
                out[y * w + x] = 0.2126f * (rgba[o].toInt() and 0xFF) + 0.7152f * (rgba[o + 1].toInt() and 0xFF) +
                    0.0722f * (rgba[o + 2].toInt() and 0xFF)
            }
        }
        return out
    }

    /** Кусок плоскости Y (строки сверху вниз, шаг строки rowStride, шаг точки pixelStride) с (x0, y0) размером w×h. */
    fun kusokY(plane: ByteArray, rowStride: Int, pixelStride: Int, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val r = (y0 + y) * rowStride + x0 * pixelStride
            for (x in 0 until w) out[y * w + x] = (plane[r + x * pixelStride].toInt() and 0xFF).toFloat()
        }
        return out
    }

    /** b ≈ a·k + c по наименьшим квадратам — a, приведённый к шкале b (диапазон и матрица цвета). */
    fun podognat(a: FloatArray, b: FloatArray): FloatArray {
        val n = a.size.toDouble()
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sab = 0.0
        for (i in a.indices) { sa += a[i]; sb += b[i]; saa += a[i].toDouble() * a[i]; sab += a[i].toDouble() * b[i] }
        val d = n * saa - sa * sa
        val k = if (abs(d) < 1e-9) 1.0 else (n * sab - sa * sb) / d
        val c = (sb - k * sa) / n
        return FloatArray(a.size) { (a[it] * k + c).toFloat() }
    }

    /** PSNR, дБ (шкала 0..255); одинаковые — 99. */
    fun psnr(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = (a[i] - b[i]).toDouble(); s += d * d }
        val mse = s / a.size
        return if (mse < 1e-10) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    /** SSIM по окнам 8×8 с шагом 4 (среднее по окнам), шкала 0..255. */
    fun ssim(a: FloatArray, b: FloatArray, w: Int, h: Int): Double {
        val c1 = (0.01 * 255) * (0.01 * 255); val c2 = (0.03 * 255) * (0.03 * 255)
        var sum = 0.0; var n = 0
        var y = 0
        while (y + 8 <= h) {
            var x = 0
            while (x + 8 <= w) {
                var ma = 0.0; var mb = 0.0
                for (j in 0 until 8) for (i in 0 until 8) { val p = (y + j) * w + x + i; ma += a[p]; mb += b[p] }
                ma /= 64; mb /= 64
                var va = 0.0; var vb = 0.0; var cov = 0.0
                for (j in 0 until 8) for (i in 0 until 8) {
                    val p = (y + j) * w + x + i
                    val da = a[p] - ma; val db = b[p] - mb
                    va += da * da; vb += db * db; cov += da * db
                }
                va /= 63; vb /= 63; cov /= 63
                sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
                n++
                x += 4
            }
            y += 4
        }
        return if (n == 0) 1.0 else sum / n
    }

    /** Пара «эталон — что ушло в эфир» → (SSIM, PSNR) после подгонки яркости. */
    fun sravnit(etalon: FloatArray, efir: FloatArray, w: Int, h: Int): Pair<Double, Double> {
        val e = podognat(efir, etalon)
        return ssim(etalon, e, w, h) to psnr(etalon, e)
    }

    /** Ближайший эталон по времени (мкс) — не дальше допуска. */
    fun blizhajshij(klyuchi: Collection<Long>, t: Long, dopusk: Long = 1_000): Long? =
        klyuchi.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= dopusk }

    /** Итог для Плюса: средний и худший SSIM, средний PSNR. */
    fun itog(pary: List<Pair<Double, Double>>, sek: Int, razmer: String, kbps: Int): String {
        if (pary.isEmpty()) return "{\"oshibka\":\"ни одной пары кадров за $sek с — эфир стоял или декодер не ответил\"}"
        val s = pary.map { it.first }; val p = pary.map { it.second }
        fun f(x: Double, n: Int) = String.format(java.util.Locale.ROOT, "%.${n}f", x)
        return "{\"ok\":true,\"sek\":$sek,\"par\":${pary.size},\"ssim\":${f(s.average(), 4)},\"ssim_min\":${f(s.min(), 4)}," +
            "\"psnr\":${f(p.average(), 2)},\"razmer\":\"$razmer\",\"kbps\":$kbps,\"kusok\":\"${KUSOK_W}x$KUSOK_H по центру\"}"
    }
}

/** Сам замер: эталоны из развилки, свой поток — в декодер, пары по времени. Один замер за раз. */
class OcenkaEfira(private val efir: Efir, private val razvilka: Razvilka) {
    private class Kadr(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    fun zamerit(sek0: Int): String {
        val sek = sek0.coerceIn(3, 60)
        if (!idyot.compareAndSet(false, true)) return "{\"oshibka\":\"замер уже идёт\"}"
        try { return zamer(sek) } finally { idyot.set(false) }
    }

    private fun zamer(sek: Int): String {
        val (sps, pps) = efir.golova ?: return "{\"oshibka\":\"эфир не идёт\"}"
        val vhod = efir.vhod ?: return "{\"oshibka\":\"эфир не идёт\"}"
        val w = efir.shirina; val h = efir.vysota
        val kw = minOf(Ocenka.KUSOK_W, w); val kh = minOf(Ocenka.KUSOK_H, h)
        val etalony = ConcurrentHashMap<Long, FloatArray>()
        val ochered = LinkedBlockingQueue<Kadr>(600)
        val dec = try { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            catch (e: Exception) { return "{\"oshibka\":${FrameServer.json("декодер не создался: ${e.message}")}}" }
        val start = byteArrayOf(0, 0, 0, 1)
        try {
            dec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(start + sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(start + pps))
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }, null, null, 0)
            dec.start()
        } catch (e: Exception) {
            try { dec.release() } catch (x: Exception) { }
            return "{\"oshibka\":${FrameServer.json("декодер не запустился: ${e.message}")}}"
        }
        razvilka.etalon = Razvilka.Etalon(vhod, 500_000_000L, kw, kh) { tsNs, rgba ->
            etalony[tsNs / 1000] = Ocenka.yarkostGl(rgba, kw, kh)
        }
        efir.nablyudatel = { a, pts, key -> ochered.offer(Kadr(a, pts, key)) }
        efir.klyuchevoySeychas()                               // декодер начнёт с ключевого — не ждать 2 с
        val pary = ArrayList<Pair<Double, Double>>()
        val info = MediaCodec.BufferInfo()
        val konec = System.currentTimeMillis() + sek * 1000L
        var nachali = false
        try {
            while (System.currentTimeMillis() < konec + 1500) {
                val podavat = System.currentTimeMillis() < konec
                if (podavat) ochered.poll(20, TimeUnit.MILLISECONDS)?.let { k ->
                    if (!nachali && !k.key) return@let
                    nachali = true
                    val i = dec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        dec.getInputBuffer(i)?.apply { clear(); put(k.data) }
                        dec.queueInputBuffer(i, 0, k.data.size, k.ptsUs, 0)
                    }
                } else if (!podavat) Thread.sleep(10)
                while (true) {
                    val o = dec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val t = Ocenka.blizhajshij(etalony.keys, info.presentationTimeUs)
                    if (t != null) {
                        val img = dec.getOutputImage(o)
                        if (img != null) {
                            val pl = img.planes[0]
                            val buf = pl.buffer
                            val b = ByteArray(buf.remaining()).also { buf.get(it) }
                            val cr = img.cropRect
                            val x0 = cr.left + (cr.width() - kw) / 2; val y0 = cr.top + (cr.height() - kh) / 2
                            val e = Ocenka.kusokY(b, pl.rowStride, pl.pixelStride, x0, y0, kw, kh)
                            etalony.remove(t)?.let { pary += Ocenka.sravnit(it, e, kw, kh) }
                            img.close()
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                }
            }
        } catch (e: Exception) {
            Pamyat.zapis("свой глаз: ${e.message}")
        } finally {
            razvilka.etalon = null
            efir.nablyudatel = null
            try { dec.stop() } catch (e: Exception) { }
            try { dec.release() } catch (e: Exception) { }
        }
        return Ocenka.itog(pary, sek, "${w}x$h@${efir.fpsSeychas}", efir.kbpsSeychas)
    }

    companion object {
        private val idyot = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
