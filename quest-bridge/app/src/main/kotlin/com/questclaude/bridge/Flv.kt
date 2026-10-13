package com.questclaude.bridge

import java.io.ByteArrayOutputStream

/**
 * Упаковка эфира: H.264 и AAC от кодировщиков Android → полезная нагрузка RTMP (как теги FLV)
 * и AMF0 для команд RTMP. Обычная JVM — тестируется на столе (и с настоящим ffmpeg).
 */
object Flv {
    /** NAL-единицы в потоке Annex-B (разделители 00 00 01 / 00 00 00 01): пары (начало, длина). */
    fun nals(a: ByteArray, from: Int = 0, to: Int = a.size): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()        // начало данных NAL, сразу за разделителем
        var i = from
        while (i + 2 < to) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else i++
        }
        val out = mutableListOf<Pair<Int, Int>>()
        for ((k, s) in starts.withIndex()) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else to
            while (e > s && a[e - 1].toInt() == 0) e--     // хвостовые нули и 4-й байт разделителя
            if (e > s) out += s to (e - s)
        }
        return out
    }

    fun nalType(a: ByteArray, at: Int) = a[at].toInt() and 0x1f

    /** AVCDecoderConfigurationRecord — «заголовок» видео для RTMP (из SPS и PPS без разделителей). */
    fun avcConfig(sps: ByteArray, pps: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(1); write(sps[1].toInt()); write(sps[2].toInt()); write(sps[3].toInt())
        write(0xff)                       // длина NAL — 4 байта
        write(0xe1)                       // один SPS
        write(sps.size shr 8); write(sps.size and 0xff); write(sps)
        write(1)                          // один PPS
        write(pps.size shr 8); write(pps.size and 0xff); write(pps)
    }.toByteArray()

    /** Кадр Annex-B → NAL с длиной (AVCC), без SPS/PPS/AUD (они в заголовке). */
    fun avcc(a: ByteArray, from: Int = 0, to: Int = a.size): ByteArray {
        val out = ByteArrayOutputStream(to - from + 16)
        for ((s, n) in nals(a, from, to)) {
            val t = nalType(a, s)
            if (t == 7 || t == 8 || t == 9) continue
            out.write(n ushr 24); out.write(n ushr 16 and 0xff); out.write(n ushr 8 and 0xff); out.write(n and 0xff)
            out.write(a, s, n)
        }
        return out.toByteArray()
    }

    /** SPS и PPS из Annex-B (данные кодировщика с флагом CODEC_CONFIG или csd-0/csd-1). */
    fun spsPps(a: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for ((s, n) in nals(a)) when (nalType(a, s)) {
            7 -> sps = a.copyOfRange(s, s + n)
            8 -> pps = a.copyOfRange(s, s + n)
        }
        return sps to pps
    }

    /** Обратно, для приёма камеры: заголовок AVCDecoderConfigurationRecord → SPS, PPS и длина поля длины NAL. */
    class AvcConfig(val sps: ByteArray, val pps: ByteArray, val lenSize: Int)

    fun avcConfigParse(r: ByteArray, from: Int = 0): AvcConfig? = try {
        var i = from + 5
        val lenSize = (r[from + 4].toInt() and 3) + 1
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        repeat(r[i++].toInt() and 0x1f) {
            val n = (r[i].toInt() and 0xff shl 8) or (r[i + 1].toInt() and 0xff)
            if (sps == null) sps = r.copyOfRange(i + 2, i + 2 + n)
            i += 2 + n
        }
        repeat(r[i++].toInt() and 0xff) {
            val n = (r[i].toInt() and 0xff shl 8) or (r[i + 1].toInt() and 0xff)
            if (pps == null) pps = r.copyOfRange(i + 2, i + 2 + n)
            i += 2 + n
        }
        val s = sps
        val p = pps
        if (s == null || p == null || s.isEmpty() || p.isEmpty()) null else AvcConfig(s, p, lenSize)
    } catch (e: IndexOutOfBoundsException) { null }

    /** NAL с длиной (AVCC) → Annex-B с разделителями 00 00 00 01 (так их ест декодер Android). Битую длину — обрезаем. */
    fun annexB(a: ByteArray, from: Int, lenSize: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(16, a.size - from + 32))
        var i = from
        while (i + lenSize <= a.size) {
            var n = 0
            repeat(lenSize) { n = (n shl 8) or (a[i++].toInt() and 0xff) }
            if (n <= 0 || n > a.size - i) break
            out.write(START, 0, 4)
            out.write(a, i, n)
            i += n
        }
        return out.toByteArray()
    }

    /** csd-0 / csd-1 для декодера: SPS или PPS с разделителем. */
    fun sRazdelitelem(nal: ByteArray): ByteArray = START + nal

    private val START = byteArrayOf(0, 0, 0, 1)

    /** Видео для RTMP (тип 9): config — заголовок, иначе кадр (key — ключевой). */
    fun video(data: ByteArray, key: Boolean, config: Boolean = false): ByteArray {
        val out = ByteArray(5 + data.size)
        out[0] = (if (key || config) 0x17 else 0x27).toByte()   // ключевой/обычный, кодек 7 — AVC
        out[1] = (if (config) 0 else 1).toByte()
        // composition time 0: кодировщик без B-кадров
        System.arraycopy(data, 0, out, 5, data.size)
        return out
    }

    /** Звук для RTMP (тип 8): AAC, config — AudioSpecificConfig, иначе сырой кадр. */
    fun audio(data: ByteArray, config: Boolean = false): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = 0xaf.toByte()            // AAC, 44 кГц (для AAC всегда), 16 бит, стерео
        out[1] = (if (config) 0 else 1).toByte()
        System.arraycopy(data, 0, out, 2, data.size)
        return out
    }

    /** AMF0 — язык команд RTMP. */
    class Amf0 {
        private val b = ByteArrayOutputStream()
        fun str(s: String) = apply { b.write(2); raw(s) }
        fun num(d: Double) = apply { b.write(0); long(java.lang.Double.doubleToLongBits(d)) }
        fun bool(v: Boolean) = apply { b.write(1); b.write(if (v) 1 else 0) }
        fun nul() = apply { b.write(5) }
        fun obj(m: Map<String, Any?>) = apply { b.write(3); props(m) }
        fun ecma(m: Map<String, Any?>) = apply {
            b.write(8); b.write(m.size ushr 24); b.write(m.size ushr 16 and 0xff)
            b.write(m.size ushr 8 and 0xff); b.write(m.size and 0xff); props(m)
        }
        fun bytes(): ByteArray = b.toByteArray()

        private fun props(m: Map<String, Any?>) {
            for ((k, v) in m) {
                raw(k)
                when (v) {
                    is String -> str(v)
                    is Number -> num(v.toDouble())
                    is Boolean -> bool(v)
                    null -> nul()
                    else -> str(v.toString())
                }
            }
            b.write(0); b.write(0); b.write(9)
        }
        private fun raw(s: String) {
            val u = s.toByteArray(Charsets.UTF_8)
            b.write(u.size ushr 8); b.write(u.size and 0xff); b.write(u)
        }
        private fun long(v: Long) { for (i in 7 downTo 0) b.write((v ushr (8 * i)).toInt() and 0xff) }
    }

    /** Разбор AMF0 (ответы сервера RTMP): строки, числа, логика, null, объекты. */
    fun amf0(a: ByteArray): List<Any?> {
        val out = mutableListOf<Any?>()
        var i = 0
        fun u16(): Int { val v = (a[i].toInt() and 0xff shl 8) or (a[i + 1].toInt() and 0xff); i += 2; return v }
        fun s(): String { val n = u16(); val v = String(a, i, n, Charsets.UTF_8); i += n; return v }
        fun value(): Any? {
            val t = a[i++].toInt()
            return when (t) {
                0 -> { var v = 0L; repeat(8) { v = (v shl 8) or (a[i++].toLong() and 0xff) }; java.lang.Double.longBitsToDouble(v) }
                1 -> a[i++].toInt() != 0
                2 -> s()
                5, 6 -> null
                3, 8 -> {
                    if (t == 8) i += 4
                    val m = LinkedHashMap<String, Any?>()
                    while (i + 2 < a.size && !(a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 9)) {
                        val k = s(); m[k] = value()
                    }
                    i += 3
                    m
                }
                else -> throw IllegalArgumentException("AMF0 тип $t")
            }
        }
        try { while (i < a.size) out += value() } catch (e: RuntimeException) { }
        return out
    }
}
