package com.questclaude.most3

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Круг клипа — из моста 2 (2.5.9, клип камеры); в Мосте 3 в него пишет кодировщик картинки игры (Klip3).
 * Ниже — исходное описание. Видео с телефона
 * приходит уже сжатым (H.264, 1080p, ~6 Мбит/с) — его и пишем как пришло, без пережатия: процессору это
 * копирование байтов. Пишем на диск по кругу, не в память (20.10: мост уже закрывала система за память):
 * куски по [kuskomMs] — каждый с ключевого кадра, старше [derzhatMs] — удаляются; ~0,75 МБ/с, ~100 МБ на диске.
 * Звук телефона (AAC), если он идёт, — туда же. [snimok] — последние N секунд для сборки в MP4 (KlipMp4).
 * Смена заголовка видео (другой размер, телефон переподключился) — круг заново: в клипе один заголовок.
 * Обычная JVM — KlipZapisTest.
 */
class KlipZapis(
    private val dir: File,
    private val derzhatMs: Long = 130_000,
    private val kuskomMs: Long = 10_000,
) {
    class Golova(val sps: ByteArray, val pps: ByteArray, val w: Int, val h: Int)

    class Obrazec(val video: Boolean, val ptsUs: Long, val key: Boolean, val data: ByteArray)

    private class Kusok(val file: File, val otUs: Long) {
        var doUs = otUs
        var dlina = 0L
    }

    /** Что собрать: файлы и сколько байт каждого (писатель дописывает дальше — это не трогаем). */
    class Snimok(val golova: Golova, val asc: ByteArray?, private val kuski: List<Pair<File, Long>>,
                 val otUs: Long, val doUs: Long, private val osvobodit: () -> Unit) {
        val sek: Int get() = ((doUs - otUs) / 1_000_000).toInt()
        private val otpushchen = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Снимок больше не нужен: круг снова может удалять старое. Можно звать сколько угодно раз. */
        fun otpustit() { if (otpushchen.compareAndSet(false, true)) osvobodit() }

        /** Образцы по порядку записи, начиная с ключевого кадра otUs; звук до него — мимо. */
        fun obrazcy(sdelat: (Obrazec) -> Unit) {
            try {
                for ((f, n) in kuski) {
                    DataInputStream(Ogranichennyi(FileInputStream(f), n).buffered(1 shl 16)).use { d ->
                        while (true) {
                            val o = chitat(d) ?: break
                            if (o.ptsUs >= otUs) sdelat(o)
                        }
                    }
                }
            } finally { otpustit() }
        }
    }

    private val lock = Any()
    private val kuski = ArrayDeque<Kusok>()
    private var out: DataOutputStream? = null
    private var golova: Golova? = null
    private var asc: ByteArray? = null
    private var n = 0
    private var chitayut = 0
    @Volatile var oshibka: String? = null
        private set

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }           // прошлый запуск — круг заново
    }

    /** Сколько секунд видео есть в круге сейчас. */
    val sekund: Int get() = synchronized(lock) {
        if (kuski.isEmpty()) 0 else ((kuski.last().doUs - kuski.first().otUs) / 1_000_000).toInt()
    }

    fun golova(sps: ByteArray, pps: ByteArray, w: Int, h: Int) = synchronized(lock) {
        val g = golova
        if (g != null && g.sps.contentEquals(sps) && g.pps.contentEquals(pps) && g.w == w && g.h == h) return@synchronized
        sbros()
        golova = Golova(sps, pps, w, h)
    }

    fun zvukGolova(asc: ByteArray) = synchronized(lock) { this.asc = asc }

    /** Есть ли заголовок звука (для повтора клипа без звука). */
    val estZvuk: Boolean get() = synchronized(lock) { asc != null }

    fun video(annexB: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (golova == null) return@synchronized
        val tek = kuski.lastOrNull()
        if (key && (tek == null || ptsUs - tek.otUs >= kuskomMs * 1000 || ptsUs < tek.doUs - 5_000_000)) novyKusok(ptsUs)
        if (out == null) return@synchronized                 // ждём первый ключевой
        zapisat(Obrazec(true, ptsUs, key, annexB))
    }

    fun zvuk(aac: ByteArray, ptsUs: Long) = synchronized(lock) {
        if (out == null || asc == null) return@synchronized
        zapisat(Obrazec(false, ptsUs, false, aac))
    }

    /** Камера отключилась: что записано — остаётся (клип можно сохранить и после). */
    fun konec() = synchronized(lock) {
        try { out?.close() } catch (e: Exception) { }
        out = null
    }

    /**
     * Последние [sek] секунд: с последнего ключевого кадра не позже, чем [sek] назад (или с самого начала круга).
     * null — нечего собирать. Пока снимок не прочитан, старые куски не удаляются.
     */
    fun snimok(sek: Int): Snimok? = synchronized(lock) {
        val g = golova ?: return@synchronized null
        if (kuski.isEmpty()) return@synchronized null
        try { out?.flush() } catch (e: Exception) { }
        val doUs = kuski.last().doUs
        val nado = doUs - sek * 1_000_000L
        var otUs = kuski.first().otUs
        for (k in kuski) {                                     // ключевые кадры: начала кусков и внутри них
            if (k.otUs > nado) break
            otUs = klyuchevoyDo(k, nado) ?: k.otUs
        }
        val nuzhnye = kuski.filter { it.doUs >= otUs }.map { it.file to it.dlina }
        if (nuzhnye.isEmpty()) return@synchronized null
        chitayut++
        Snimok(g, asc, nuzhnye, otUs, doUs) { synchronized(lock) { chitayut--; obrezat() } }
    }

    // ---------- внутри ----------

    private val klyuchevye = HashMap<File, MutableList<Long>>()

    private fun klyuchevoyDo(k: Kusok, nado: Long): Long? = klyuchevye[k.file]?.lastOrNull { it <= nado }

    private fun novyKusok(ptsUs: Long) {
        try { out?.close() } catch (e: Exception) { }
        val f = File(dir, "kusok-${n++}.bin")
        out = try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(f), 1 shl 16)).also { oshibka = null }
        } catch (e: Exception) { oshibka = "клип: не пишется на диск: ${e.message}"; null }
        if (out == null) return
        kuski.addLast(Kusok(f, ptsUs))
        klyuchevye[f] = mutableListOf()
        obrezat()
    }

    private fun zapisat(o: Obrazec) {
        val d = out ?: return
        val k = kuski.last()
        try {
            d.writeByte((if (o.video) 1 else 0) or (if (o.key) 2 else 0))
            d.writeLong(o.ptsUs)
            d.writeInt(o.data.size)
            d.write(o.data)
            k.dlina += 13 + o.data.size
            if (o.video) {
                if (o.ptsUs > k.doUs) k.doUs = o.ptsUs
                if (o.key) klyuchevye[k.file]?.add(o.ptsUs)
            }
        } catch (e: Exception) {
            oshibka = "клип: не пишется на диск: ${e.message}"
            try { d.close() } catch (x: Exception) { }
            out = null
        }
    }

    /** Старше derzhatMs — прочь (кроме последнего куска); пока снимок читают — ничего не удаляем. */
    private fun obrezat() {
        if (chitayut > 0) return
        val posledniy = kuski.lastOrNull() ?: return
        while (kuski.size > 1 && posledniy.otUs - kuski[1].otUs >= derzhatMs * 1000) {
            val k = kuski.removeFirst()
            klyuchevye.remove(k.file)
            k.file.delete()
        }
    }

    private fun sbros() {
        try { out?.close() } catch (e: Exception) { }
        out = null
        if (chitayut == 0) kuski.forEach { it.file.delete() }
        kuski.clear()
        klyuchevye.clear()
        asc = null
    }

    companion object {
        private const val MAKS_OBRAZEC = 8 shl 20

        /** SPS, PPS и разделители кадров — уже в заголовке дорожки; в образце — только сам кадр. */
        fun bezZagolovkov(a: ByteArray): ByteArray {
            val ns = Nal.nals(a)
            if (ns.none { Nal.nalType(a, it.first) in 7..9 }) return a
            val out = java.io.ByteArrayOutputStream(a.size)
            for ((ot, dlina) in ns) {
                if (Nal.nalType(a, ot) in 7..9) continue
                out.write(byteArrayOf(0, 0, 0, 1)); out.write(a, ot, dlina)
            }
            return out.toByteArray()
        }

        /** AudioSpecificConfig → (частота, каналов). */
        fun aac(asc: ByteArray): Pair<Int, Int> {
            val hz = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            if (asc.size < 2) return 44100 to 1
            val i = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() and 0x80) ushr 7)
            val k = (asc[1].toInt() and 0x78) ushr 3
            return (hz.getOrNull(i) ?: 44100) to (if (k in 1..2) k else 1)
        }

        fun chitat(d: DataInputStream): Obrazec? = try {
            val f = d.readUnsignedByte()
            val pts = d.readLong()
            val n = d.readInt()
            if (n < 0 || n > MAKS_OBRAZEC) null else ByteArray(n).also { d.readFully(it) }.let { Obrazec(f and 1 != 0, pts, f and 2 != 0, it) }
        } catch (e: EOFException) { null }
    }

    /** Не дальше [ostalos] байт: писатель дописывает файл, пока его читают. */
    private class Ogranichennyi(private val s: InputStream, private var ostalos: Long) : InputStream() {
        override fun read(): Int = if (ostalos <= 0) -1 else s.read().also { if (it >= 0) ostalos-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ostalos <= 0) return -1
            val r = s.read(b, off, minOf(len.toLong(), ostalos).toInt())
            if (r > 0) ostalos -= r
            return r
        }
        override fun close() = s.close()
    }
}
