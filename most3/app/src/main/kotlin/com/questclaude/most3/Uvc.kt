package com.questclaude.most3

import java.util.Locale

// Чистая логика UVC/UAC1 без android.*: разбор дескрипторов, выбор режима, probe/commit.
// Сборка кадров из bulk-пакетов — в UvcSbor.kt.

enum class UvcVid { YUY2, NV12, I420, P010, MJPEG, DRUGOE }

/** Байт на кадр для несжатых форматов; 0 — переменный размер (MJPEG и прочее). */
fun razmerKadra(vid: UvcVid, w: Int, h: Int): Int = when (vid) {
    UvcVid.YUY2 -> w * h * 2
    UvcVid.NV12, UvcVid.I420 -> w * h * 3 / 2
    UvcVid.P010 -> w * h * 3
    UvcVid.MJPEG, UvcVid.DRUGOE -> 0
}

/**
 * Один режим: формат + кадр. intervaly — в единицах 100 нс.
 * nepreryvno = true: интервал непрерывный, intervaly = [min, max] (шаг не храним).
 */
data class UvcRezhim(
    val formatIndex: Int,
    val frameIndex: Int,
    val vid: UvcVid,
    val shirina: Int,
    val vysota: Int,
    val intervaly: List<Int>,
    val maxBuf: Int,
    val nepreryvno: Boolean = false,
) {
    fun fps(interval: Int): Double = if (interval > 0) 1e7 / interval else 0.0
    val maxFps: Double get() = intervaly.filter { it > 0 }.minOrNull()?.let { fps(it) } ?: 0.0
    val nesjatyj: Boolean get() = vid == UvcVid.NV12 || vid == UvcVid.YUY2 || vid == UvcVid.I420

    fun opisanie(fpsZn: Double = maxFps): String = "$vid ${shirina}×$vysota ${fpsTekst(fpsZn)} к/с"

    /**
     * Интервал с наибольшей частотой, не превышающей maxFps (допуск 0.1%: 166666 = 60.0002 к/с).
     * Если все интервалы быстрее maxFps (устройство даёт только 120) — самый медленный из имеющихся.
     * Непрерывный диапазон: ceil(1e7/maxFps), прижатый к [min, max].
     */
    fun luchshijInterval(maxFps: Int = 60): Int {
        val polozh = intervaly.filter { it > 0 }
        if (polozh.isEmpty()) return 0
        if (nepreryvno && polozh.size >= 2) {
            val cel = Math.ceil(1e7 / maxFps).toInt()
            return cel.coerceIn(polozh.min(), polozh.max())
        }
        val dopustimye = polozh.filter { fps(it) <= maxFps * 1.001 }
        return dopustimye.minOrNull() ?: polozh.max()
    }
}

private fun fpsTekst(f: Double): String {
    val r = Math.round(f)
    return if (Math.abs(f - r) < 0.01) r.toString()
    else String.format(Locale.ROOT, "%.2f", f).trimEnd('0').trimEnd('.')
}

/** Аудио UAC1 Type I. tochka — адрес iso IN, paket — байт за (микро)кадр с учётом доп. транзакций. */
data class UacOpisanie(
    val interfejs: Int,
    val alt: Int,
    val tochka: Int,
    val paket: Int,
    val kanaly: Int,
    val bajt: Int,
    val chastoty: List<Int>,
    /** bInterval точки (high/super speed: период 2^(n-1) по 125 мкс; 4 = 1 мс). */
    val interval: Int = 4,
) {
    /** Пакетов точки за 1 мс (1 при bInterval 4, 8 при bInterval 1). */
    val paketovVMs: Int get() = if (interval in 1..4) 1 shl (4 - interval) else 1
}

data class UvcOpisanie(
    val bcdUvc: Int,
    val vcInterfejs: Int,
    val vsInterfejs: Int,
    val tochka: Int,
    val bulk: Boolean,
    val puId: Int?,
    val puUpravlenie: Int,
    val rezhimy: List<UvcRezhim>,
    val zvuk: UacOpisanie?,
)

object UvcRazbor {
    // Хвост GUID-а медиа-подтипов: XXXXXXXX-0000-0010-8000-00AA00389B71 (в байтах после FourCC)
    private val HVOST = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)

    /** Вид по guidFormat (16 байт с позиции p). */
    fun vidPoGuid(b: ByteArray, p: Int): UvcVid {
        if (p < 0 || p + 16 > b.size) return UvcVid.DRUGOE
        for (i in 0 until 12) if (b[p + 4 + i] != HVOST[i]) return UvcVid.DRUGOE
        return when (String(b, p, 4, Charsets.ISO_8859_1)) {
            "YUY2" -> UvcVid.YUY2
            "NV12" -> UvcVid.NV12
            "I420" -> UvcVid.I420
            "P010" -> UvcVid.P010
            else -> UvcVid.DRUGOE
        }
    }

    /**
     * raw — UsbDeviceConnection.getRawDescriptors(): дескриптор устройства + активная конфигурация.
     * Никогда не бросает: битые дескрипторы пропускаются, на bLength < 2 или выходе за край — стоп.
     * Если UVC не найден — vcInterfejs/vsInterfejs/tochka = -1, rezhimy пуст.
     */
    fun razobrat(raw: ByteArray): UvcOpisanie {
        var bcd = 0; var vc = -1; var vs = -1
        var bulkT = -1; var isoT = -1; var isoP = -1
        var puId: Int? = null; var puUpr = 0
        val rezhimy = ArrayList<UvcRezhim>()
        // текущий интерфейс
        var ifNom = -1; var ifAlt = 0; var ifKl = -1; var ifPod = -1; var ifProt = 0
        // текущий формат VS
        var fIdx = -1; var fVid = UvcVid.DRUGOE; var fBpp = 0; var fTip = 0
        // аудио: формат текущего alt (ждёт конечной точки) и итог
        var aKan = 0; var aBajt = 0; var aChast: List<Int> = emptyList(); var aEst = false
        var zvuk: UacOpisanie? = null
        var vsHeaderT = -1

        var i = 0
        while (i + 2 <= raw.size) {
            val dl = raw.u8(i)
            if (dl < 2 || i + dl > raw.size) break
            val tip = raw.u8(i + 1)
            val p = i
            when {
                tip == 4 && dl >= 9 -> {
                    ifNom = raw.u8(p + 2); ifAlt = raw.u8(p + 3)
                    ifKl = raw.u8(p + 5); ifPod = raw.u8(p + 6); ifProt = raw.u8(p + 7)
                    aEst = false
                    if (ifKl == 14 && ifPod == 1 && vc < 0) vc = ifNom
                    if (ifKl == 14 && ifPod == 2 && vs < 0) vs = ifNom
                }
                tip == 0x24 && dl >= 3 && ifKl == 14 && ifPod == 1 && ifNom == vc -> {
                    val pt = raw.u8(p + 2)
                    if (pt == 0x01 && dl >= 5) bcd = raw.u16(p + 3)
                    if (pt == 0x05 && dl >= 8 && puId == null) {
                        puId = raw.u8(p + 3)
                        val n = minOf(raw.u8(p + 7), dl - 8, 4)
                        var m = 0
                        for (k in 0 until n) m = m or (raw.u8(p + 8 + k) shl (8 * k))
                        puUpr = m
                    }
                }
                tip == 0x24 && dl >= 3 && ifKl == 14 && ifPod == 2 && ifNom == vs -> {
                    when (val pt = raw.u8(p + 2)) {
                        0x01 -> if (dl >= 7) vsHeaderT = raw.u8(p + 6)
                        0x04, 0x10 -> if (dl >= 22) {
                            fIdx = raw.u8(p + 3); fTip = pt + 1
                            fVid = if (pt == 0x04) vidPoGuid(raw, p + 5) else UvcVid.DRUGOE
                            fBpp = raw.u8(p + 21)
                        } else fIdx = -1
                        0x06 -> if (dl >= 4) { fIdx = raw.u8(p + 3); fTip = 0x07; fVid = UvcVid.MJPEG; fBpp = 0 } else fIdx = -1
                        0x0A, 0x0C, 0x12, 0x13, 0x15, 0x17 -> fIdx = -1 // MPEG2TS/DV/stream/H264/VP8… — не поддерживаем
                        0x05, 0x07, 0x11 -> if (pt == fTip && fIdx >= 0) kadr(raw, p, dl, pt, fIdx, fVid, fBpp)?.let { rezhimy += it }
                    }
                }
                tip == 0x24 && dl >= 8 && ifKl == 1 && ifPod == 2 && ifAlt > 0 && ifProt == 0 &&
                    raw.u8(p + 2) == 0x02 && raw.u8(p + 3) == 0x01 -> {
                    // UAC1 Type I: bNrChannels, bSubframeSize, bBitResolution, bSamFreqType, tSamFreq[]
                    aKan = raw.u8(p + 4); aBajt = raw.u8(p + 5)
                    val n = raw.u8(p + 7)
                    val skolko = minOf(if (n == 0) 2 else n, (dl - 8) / 3)
                    aChast = (0 until skolko).map { raw.u24(p + 8 + 3 * it) }
                    aEst = true
                }
                tip == 5 && dl >= 7 -> {
                    val adr = raw.u8(p + 2); val atr = raw.u8(p + 3) and 3
                    val w = raw.u16(p + 4)
                    val paket = (w and 0x7FF) * (1 + ((w shr 11) and 3))
                    val vhod = adr and 0x80 != 0
                    if (vhod && ifKl == 14 && ifNom == vs && vs >= 0) {
                        if (atr == 2 && bulkT < 0) bulkT = adr
                        if (atr == 1 && paket > isoP) { isoT = adr; isoP = paket }
                    }
                    if (vhod && atr == 1 && ifKl == 1 && ifPod == 2 && aEst && zvuk == null)
                        zvuk = UacOpisanie(ifNom, ifAlt, adr, paket, aKan, aBajt, aChast, raw.u8(p + 6))
                }
            }
            i += dl
        }
        val bulk = bulkT >= 0
        val tochka = if (bulk) bulkT else if (isoT >= 0) isoT else vsHeaderT
        return UvcOpisanie(bcd, vc, vs, tochka, bulk, puId, puUpr, rezhimy, zvuk)
    }

    // Кадр: 0x05/0x07 — bFrameIndex(3) wWidth(5) wHeight(7) dwMin/MaxBitRate(9,13) dwMaxVideoFrameBufferSize(17)
    //        dwDefaultFrameInterval(21) bFrameIntervalType(25) интервалы(26..)
    //       0x11 (frame-based) — то же, но вместо буфера: dwDefaultFrameInterval(17) тип(21) dwBytesPerLine(22) интервалы(26..)
    private fun kadr(b: ByteArray, p: Int, dl: Int, pt: Int, fIdx: Int, vid: UvcVid, bpp: Int): UvcRezhim? {
        if (dl < 26) return null
        val w = b.u16(p + 5); val h = b.u16(p + 7)
        val tipInt = if (pt == 0x11) b.u8(p + 21) else b.u8(p + 25)
        var maxBuf = if (pt == 0x11) 0 else b.s32(p + 17)
        if (maxBuf <= 0) maxBuf = if (bpp > 0) w * h * bpp / 8 else razmerKadra(vid, w, h)
        val mest = (dl - 26) / 4
        val nepr = tipInt == 0
        val n = minOf(if (nepr) 3 else tipInt, mest)
        val vse = (0 until n).map { b.s32(p + 26 + 4 * it) }
        val iv = if (nepr) vse.take(2) else vse // непрерывный: [min, max], шаг отбрасываем
        return UvcRezhim(fIdx, b.u8(p + 3), vid, w, h, iv, maxBuf, nepr && iv.size == 2)
    }
}

/**
 * Лучший режим для PS4 1080p на Quest:
 * 1) несжатый 1920×1080 с ≥59 к/с (NV12 > YUY2 > I420);
 * 2) несжатый 1920×1080 с наибольшей частотой ≥30;
 * 3) MJPEG 1920×1080 (по luchshijInterval(60), т.е. 60, но и 120 годится);
 * 4) самый большой несжатый не больше 1920×1080;
 * 5) что угодно (известные форматы вперёд, затем площадь, затем частота).
 * P010 и DRUGOE в 1–4 не участвуют.
 */
fun UvcOpisanie.vybrat(): UvcRezhim? {
    fun prioritet(v: UvcVid) = when (v) { UvcVid.NV12 -> 3; UvcVid.YUY2 -> 2; UvcVid.I420 -> 1; else -> 0 }
    val nesj = rezhimy.filter { it.nesjatyj && it.shirina > 0 && it.vysota > 0 }
    val fhd = nesj.filter { it.shirina == 1920 && it.vysota == 1080 }
    fhd.filter { it.maxFps >= 59.0 }
        .maxWithOrNull(compareBy<UvcRezhim>({ prioritet(it.vid) }, { it.maxFps }))?.let { return it }
    fhd.filter { it.maxFps >= 29.9 }
        .maxWithOrNull(compareBy<UvcRezhim>({ it.maxFps }, { prioritet(it.vid) }))?.let { return it }
    rezhimy.filter { it.vid == UvcVid.MJPEG && it.shirina == 1920 && it.vysota == 1080 }
        .maxByOrNull { it.fps(it.luchshijInterval(60)) }?.let { return it }
    nesj.filter { it.shirina <= 1920 && it.vysota <= 1080 }
        .maxWithOrNull(compareBy<UvcRezhim>({ it.shirina * it.vysota }, { it.maxFps }, { prioritet(it.vid) }))?.let { return it }
    return rezhimy.maxWithOrNull(compareBy<UvcRezhim>({ it.vid != UvcVid.DRUGOE }, { it.shirina.toLong() * it.vysota }, { it.maxFps }))
}

/**
 * Лестница режимов для первого запуска (Мост 3, разбор №2): лучший по vybrat(), потом лучший из оставшихся и т. д.,
 * без повторов «формат+размер» (разные частоты одного режима — один шаг). Карта сама решит, какой отдаёт кадры.
 */
fun UvcOpisanie.lestnica(maks: Int = 5): List<UvcRezhim> {
    val out = ArrayList<UvcRezhim>()
    var ost = rezhimy
    while (out.size < maks) {
        val r = copy(rezhimy = ost).vybrat() ?: break
        out += r
        ost = ost.filterNot { it.vid == r.vid && it.shirina == r.shirina && it.vysota == r.vysota }
    }
    return out
}

/**
 * Профили режимов (заход 2, Трезубец, зуб I): режим выбирается по задаче, а не по числу. На USB 5 Гбит/с у Elgato
 * 4K X несжатое — только до 1080p60 и 1440p30–50; выше — MJPEG (разжатие пулом потоков в Pokaz).
 * maxFps — потолок частоты для luchshijInterval.
 */
enum class Profil(val imya: String, val nadpis: String, val maxFps: Int) {
    EFIR("efir", "Эфир: 1080p60, легко шлему", 60),
    PLAVNO("plavno", "Плавность: 1080p 120 к/с", 120),
    MAKS("maks", "Максимум: 1440p60", 60),
    K4("4k", "4К: 2160p30 — для глаз вблизи", 30),
    HDR("hdr", "HDR: P010 для PS5", 60);

    companion object {
        fun po(imya: String?): Profil? = values().firstOrNull { it.imya == imya }
    }
}

/** Шаг лестницы профиля: режим и потолок частоты для него. */
data class Shag(val rezhim: UvcRezhim, val maxFps: Int)

/**
 * Лестница профиля: сначала режимы, подходящие задаче профиля (лучшие вперёд), потом — запасная лестница «Эфир»,
 * без повторов «формат+размер». zapomnennyj (режим, который уже срабатывал в этом профиле) — первым.
 */
fun UvcOpisanie.lestnica(profil: Profil, zapomnennyj: UvcRezhim? = null, maks: Int = 6): List<Shag> {
    fun fpsPri(r: UvcRezhim, cap: Int) = r.fps(r.luchshijInterval(cap))
    fun prioritet(v: UvcVid) = when (v) { UvcVid.NV12 -> 3; UvcVid.YUY2 -> 2; UvcVid.I420 -> 1; else -> 0 }
    val cap = profil.maxFps
    val podhodyat: List<UvcRezhim> = when (profil) {
        Profil.EFIR -> emptyList()
        Profil.PLAVNO -> rezhimy.filter { it.shirina == 1920 && it.vysota == 1080 && fpsPri(it, 120) >= 99 &&
            (it.nesjatyj || it.vid == UvcVid.MJPEG) }
            .sortedWith(compareByDescending<UvcRezhim> { it.nesjatyj }.thenByDescending { fpsPri(it, 120) })
        Profil.MAKS -> rezhimy.filter { it.vysota == 1440 && (it.nesjatyj || it.vid == UvcVid.MJPEG) && fpsPri(it, 60) >= 29.9 }
            .sortedWith(compareByDescending<UvcRezhim> { fpsPri(it, 60) >= 49.9 }      // плавные — вперёд
                .thenByDescending { it.nesjatyj }.thenByDescending { prioritet(it.vid) }.thenByDescending { fpsPri(it, 60) })
        Profil.K4 -> rezhimy.filter { it.vysota == 2160 && (it.nesjatyj || it.vid == UvcVid.MJPEG) && fpsPri(it, 30) >= 23.9 }
            .sortedWith(compareByDescending<UvcRezhim> { it.nesjatyj }.thenByDescending { fpsPri(it, 30) })
        Profil.HDR -> rezhimy.filter { it.vid == UvcVid.P010 && fpsPri(it, 60) >= 23.9 }
            .sortedWith(compareByDescending<UvcRezhim> { it.shirina.toLong() * it.vysota * minOf(fpsPri(it, 60), 60.0) })
    }
    val out = ArrayList<Shag>()
    fun dobavit(r: UvcRezhim, c: Int) {
        if (out.size < maks && out.none { it.rezhim.vid == r.vid && it.rezhim.shirina == r.shirina && it.rezhim.vysota == r.vysota })
            out += Shag(r, c)
    }
    zapomnennyj?.let { z -> rezhimy.firstOrNull { it == z }?.let { dobavit(it, cap) } }
    podhodyat.forEach { dobavit(it, cap) }
    if (profil == Profil.K4) rezhimy.filter { it.vysota == 1440 && (it.nesjatyj || it.vid == UvcVid.MJPEG) }   // 4К нет — хотя бы 1440p
        .sortedByDescending { fpsPri(it, 60) }.forEach { dobavit(it, 60) }
    lestnica(maks).forEach { dobavit(it, 60) }
    return out
}

data class ProbeOtvet(
    val formatIndex: Int,
    val frameIndex: Int,
    val interval: Int,
    val maxKadr: Int,   // dwMaxVideoFrameSize
    val maxPaket: Int,  // dwMaxPayloadTransferSize
)

/**
 * VS_PROBE_CONTROL / VS_COMMIT_CONTROL. Порядок: SET_CUR(probe, upakovat) → GET_CUR(probe) →
 * SET_CUR(commit, те же байты, что вернул GET_CUR). Всё little-endian.
 */
object UvcProbe {
    fun dlina(bcdUvc: Int): Int = when {
        bcdUvc >= 0x0150 -> 48
        bcdUvc >= 0x0110 -> 34
        else -> 26
    }

    /** bmHint=1 (держать интервал), остальное 0 — устройство заполнит. dlina < 26 поднимается до 26. */
    fun upakovat(r: UvcRezhim, interval: Int, dlina: Int): ByteArray {
        val b = ByteArray(maxOf(dlina, 26))
        b[0] = 1
        b[2] = r.formatIndex.toByte()
        b[3] = r.frameIndex.toByte()
        b.put32(4, interval)
        return b
    }

    /**
     * «Вежливый» PROBE: взять то, что карта сама ответила на GET_CUR, и поменять только подсказку, формат, кадр
     * и интервал. Часть прошивок не любит нули в остальных полях (libuvc шлёт нули — это запасной путь).
     */
    fun nalozhit(tek: ByteArray, r: UvcRezhim, interval: Int, dlina: Int): ByteArray {
        val b = upakovat(r, interval, dlina)
        if (tek.size < 8) return b
        val n = tek.copyOf(b.size)
        n[0] = 1; n[1] = 0
        n[2] = r.formatIndex.toByte(); n[3] = r.frameIndex.toByte()
        n.put32(4, interval)
        return n
    }

    /** Короткий ответ не ломает разбор: недостающие поля = 0. */
    fun razobrat(b: ByteArray): ProbeOtvet = ProbeOtvet(
        formatIndex = if (b.size > 2) b.u8(2) else 0,
        frameIndex = if (b.size > 3) b.u8(3) else 0,
        interval = if (b.size >= 8) b.s32(4) else 0,
        maxKadr = if (b.size >= 22) b.s32(18) else 0,
        maxPaket = if (b.size >= 26) b.s32(22) else 0,
    )
}

// --- байты (вызывающий сам проверяет границы) ---
private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.u24(i: Int): Int = u16(i) or (u8(i + 2) shl 16)
private fun ByteArray.s32(i: Int): Int = u16(i) or (u16(i + 2) shl 16)
private fun ByteArray.put32(i: Int, v: Int) {
    for (k in 0 until 4) this[i + k] = (v ushr (8 * k)).toByte()
}
