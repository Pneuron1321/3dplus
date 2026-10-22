package com.questclaude.most3

/** NAL-блоки H.264 в Annex-B (из Flv моста 2): разбор по разделителям 00 00 01. Обычная JVM. */
object Nal {
    /** (начало данных NAL сразу за разделителем, длина) по порядку. */
    fun nals(a: ByteArray, from: Int = 0, to: Int = a.size): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()
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

    fun sRazdelitelem(nal: ByteArray): ByteArray = START + nal

    /** SPS и PPS из заголовка кодировщика (csd в Annex-B); null — их там нет. */
    fun spsPps(a: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null; var pps: ByteArray? = null
        for ((s, n) in nals(a)) when (nalType(a, s)) {
            7 -> sps = a.copyOfRange(s, s + n)
            8 -> pps = a.copyOfRange(s, s + n)
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    private val START = byteArrayOf(0, 0, 0, 1)
}
