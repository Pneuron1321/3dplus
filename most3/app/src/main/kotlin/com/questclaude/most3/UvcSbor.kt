package com.questclaude.most3

/**
 * Сборка кадров UVC из bulk-передач. Одна завершённая передача = один payload (заголовок + данные).
 *
 * ozhidaemyj — точный размер несжатого кадра (razmerKadra); 0 — переменный (MJPEG, нужен FF D8 в начале).
 * maxKadr — ёмкость буфера; кадр больше — бракуется без переполнения.
 *
 * Контракт naKadr(buf, dlina): буферов три, по кругу. Буфер, отданный в доставке k, не трогается
 * до окончания доставки k+2 (т.е. «до следующей-следующей»). Нужен дольше — копируйте.
 * Не потокобезопасен: podat() вызывать из одного потока.
 */
class SborKadra(val ozhidaemyj: Int, val maxKadr: Int, val naKadr: (ByteArray, Int) -> Unit) {
    private val bufy = Array(3) { ByteArray(maxKadr.coerceAtLeast(0)) }
    private var tek = 0          // какой буфер наполняем
    private var dlina = 0        // сколько в нём
    private var vKadre = false   // идёт кадр (был хоть один payload после конца прошлого)
    private var fid = -1
    private var plohoj = false   // переполнение
    private var oshibka = false  // был ERR

    var kadrov = 0L; private set            // отдано кадров
    var broshenyh = 0L; private set         // отброшено непустых кадров (ERR/размер/переполнение/не JPEG)
    var oshibok = 0L; private set           // payload-ов с битом ERR
    var plohihZagolovkov = 0L; private set  // payload-ов с негодным заголовком (проигнорированы)
    var poslednijRazmer = 0; private set    // размер последнего завершённого кадра (даже брошенного)

    /** Начать с чистого листа (после перезапуска потока). Счётчики не сбрасываются. */
    fun sbros() { dlina = 0; vKadre = false; fid = -1; plohoj = false; oshibka = false }

    fun podat(buf: ByteArray, off: Int, len: Int) {
        if (len == 0) return // пустая передача законна
        if (off < 0 || len < 2 || off + len > buf.size) { plohihZagolovkov++; return }
        val hl = buf[off].toInt() and 0xFF
        val info = buf[off + 1].toInt() and 0xFF
        // bHeaderLength: 2..len и не меньше, чем требуют PTS(4)/SCR(6)
        val minHl = 2 + (if (info and 0x04 != 0) 4 else 0) + (if (info and 0x08 != 0) 6 else 0)
        if (hl < minHl || hl > len) { plohihZagolovkov++; return }

        val f = info and 0x01
        if (vKadre && f != fid) zavershit() // FID сменился без EOF — прошлый кадр окончен
        fid = f
        vKadre = true

        if (info and 0x40 != 0) { oshibka = true; oshibok++ }
        val n = len - hl
        if (n > 0 && !oshibka && !plohoj) {
            if (dlina + n > maxKadr) plohoj = true
            else { System.arraycopy(buf, off + hl, bufy[tek], dlina, n); dlina += n }
        }
        if (info and 0x02 != 0) zavershit()
    }

    private fun zavershit() {
        val b = bufy[tek]
        val pusto = dlina == 0 && !plohoj && !oshibka
        if (!pusto) {
            poslednijRazmer = dlina
            val goden = !plohoj && !oshibka && if (ozhidaemyj == 0)
                dlina >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            else dlina >= ozhidaemyj            // больше — хвост выравнивания, показ берёт начало
            if (goden) {
                kadrov++
                naKadr(b, dlina)
                tek = (tek + 1) % bufy.size
            } else broshenyh++
        }
        dlina = 0; vKadre = false; plohoj = false; oshibka = false
    }
}
