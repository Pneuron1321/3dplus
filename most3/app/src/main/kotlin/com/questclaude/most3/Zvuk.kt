package com.questclaude.most3

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Звук карты мимо звуковой системы Android (Мост 3, 22.10). Android даёт один вход на всех: в 2.6.0 запись
 * с карты через AudioRecord отнимала микрофон у ушей и браузера. HDMI Link так не делает — читает изохронную
 * точку сам. Мы тоже: интерфейс звука забираем у драйвера ядра (USB-вход у Android пропадает, встроенный
 * микрофон остаётся единственным), передачи ставим через usbfs (ioctl SUBMITURB/REAPURB, в Java их нет — JNA),
 * отдаём в AudioTrack USAGE_GAME. Эфир моста 2 ловит его захватом воспроизведения, как любую игру.
 * Соединение — СВОЁ (второй openDevice): REAPURB забирает любую готовую передачу своего fd, и на общем fd звук
 * воровал бы передачи видео у UsbRequest, а видео — у звука.
 */
class Zvuk(private val c: UsbDeviceConnection, private val op: UacOpisanie,
           private val alt0: UsbInterface, private val alt1: UsbInterface) {
    @Volatile var rabotaet = false; private set
    @Volatile var oshibka: String? = null; private set
    @Volatile var paketov = 0L; private set
    @Volatile var plohih = 0L; private set
    @Volatile var sbrosheno = 0L; private set        // байт выброшено, чтобы звук не отставал
    @Volatile var pik = 0; private set                // пик за последние ~0,5 с (0..32767)
    /** Куда ещё отдать PCM (запись игры, Klip3): буфер, байт, время в мкс по System.nanoTime. */
    @Volatile var naPcm: ((ByteArray, Int, Long) -> Unit)? = null
    @Volatile var gromkost = 1f
        set(v) { field = v.coerceIn(0f, 1.5f); trek?.setVolume(field.coerceAtMost(1f)) }

    private var trek: AudioTrack? = null
    private var potok: Thread? = null
    private val urby = ArrayList<Memory>()
    /** Передач у ядра (поставлено и ещё не забрано): память Memory нельзя отпускать, пока ядро в неё пишет. */
    private val vPolete = java.util.concurrent.atomic.AtomicInteger()
    /** Пакетов в передаче: ~8 мс звука при любом bInterval точки (1 мс → 8, 125 мкс → 64). */
    private val pakety = (8 * op.paketovVMs).coerceIn(8, 64)

    fun start(): Boolean {
        if (rabotaet) return true
        if (!c.claimInterface(alt0, true)) { oshibka = "интерфейс звука занят"; return false }
        if (!c.setInterface(alt1)) { oshibka = "карта не включила звук (alt ${op.alt})"; c.releaseInterface(alt0); return false }
        // UAC1: частота — запрос к точке (SET_CUR SAMPLING_FREQ); карта с одной частотой может ответить ошибкой — не страшно
        val hz = op.chastoty.firstOrNull { it == 48000 } ?: op.chastoty.firstOrNull() ?: 48000
        c.controlTransfer(0x22, 0x01, 0x0100, op.tochka, byteArrayOf(hz.toByte(), (hz shr 8).toByte(), (hz shr 16).toByte()), 3, 500)

        val kanaly = if (op.kanaly == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(hz, kanaly, AudioFormat.ENCODING_PCM_16BIT)
        val t = try { AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(hz).setChannelMask(kanaly)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, hz * op.kanaly * 2 / 10))       // ≥ 100 мс
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build() } catch (e: Exception) {
            oshibka = "AudioTrack не создался ($hz Гц): ${e.message ?: e.javaClass.simpleName}"
            try { c.setInterface(alt0) } catch (x: Exception) { }
            c.releaseInterface(alt0); return false
        }
        t.setVolume(gromkost.coerceAtMost(1f)); t.play(); trek = t

        val fd = c.fileDescriptor
        repeat(URBOV) {
            val m = Memory((Urb.RAZMER + Urb.ISO * pakety + op.paket * pakety).toLong())
            urby += m
            if (!podat(fd, m)) { val e = Native.getLastError(); stop(); oshibka = "usbfs: передача не встала (errno $e)"; return false }
            vPolete.incrementAndGet()
        }
        rabotaet = true; oshibka = null
        potok = Thread({ cikl(fd, t, hz) }, "most3-zvuk").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Заполнить и поставить одну изохронную передачу: pakety пакетов по op.paket байт. */
    private fun podat(fd: Int, m: Memory): Boolean {
        val bufer = m.share((Urb.RAZMER + Urb.ISO * pakety).toLong())
        val b = m.getByteBuffer(0, (Urb.RAZMER + Urb.ISO * pakety).toLong()).order(ByteOrder.nativeOrder())
        Urb.zapolnit(b, op.tochka, Pointer.nativeValue(bufer), pakety, op.paket, Pointer.nativeValue(m))
        return LibC.I.ioctl(fd, NativeLong(Urb.SUBMITURB), m) == 0
    }

    private fun cikl(fd: Int, t: AudioTrack, hz: Int) {
        val kadrBajt = op.kanaly * 2
        val vyhod = ByteArray(op.paket * pakety)
        val ref = PointerByReference()
        var napisano = 0L                                  // кадров отдано в AudioTrack
        var pikOkno = 0; var pikSchet = 0
        while (rabotaet) {
            if (LibC.I.ioctl(fd, NativeLong(Urb.REAPURB), ref.pointer) != 0) {
                if (rabotaet) oshibka = "usbfs: карта отключилась (errno ${Native.getLastError()})"
                break
            }
            val p = ref.value ?: continue
            vPolete.decrementAndGet()
            val m = urby.firstOrNull { Pointer.nativeValue(it) == Pointer.nativeValue(p) } ?: continue
            val b = m.getByteBuffer(0, (Urb.RAZMER + Urb.ISO * pakety).toLong()).order(ByteOrder.nativeOrder())
            var n = 0
            for (i in 0 until pakety) {
                val (dlina, status) = Urb.paket(b, i)
                paketov++
                if (status != 0 || dlina <= 0) { if (status != 0) plohih++; continue }
                val d = dlina - dlina % kadrBajt
                m.read((Urb.RAZMER + Urb.ISO * pakety + i * op.paket).toLong(), vyhod, n, d)
                n += d
            }
            if (!rabotaet) break
            if (LibC.I.ioctl(fd, NativeLong(Urb.SUBMITURB), m) != 0) {
                oshibka = "usbfs: повторная передача не встала (errno ${Native.getLastError()})"; break
            }
            vPolete.incrementAndGet()
            if (n == 0) continue
            naPcm?.invoke(vyhod, n, System.nanoTime() / 1000)
            pikOkno = maxOf(pikOkno, Pcm.pik(vyhod, n))
            if (++pikSchet >= 50) { pik = pikOkno; pikOkno = 0; pikSchet = 0 }
            // Часы карты и шлема расходятся: если в AudioTrack накопилось больше 120 мс — этот кусок выбросить.
            val vOcheredi = napisano - (t.playbackHeadPosition.toLong() and 0xffffffffL)
            if (vOcheredi > hz * 120 / 1000) { sbrosheno += n; continue }
            val w = t.write(vyhod, 0, n, AudioTrack.WRITE_NON_BLOCKING)
            if (w > 0) napisano += w / kadrBajt
            if (w < n) sbrosheno += (n - maxOf(w, 0))
        }
        rabotaet = false
    }

    /** Остановить: отменить передачи, дождаться, пока ядро вернёт ВСЕ (иначе оно пишет в отпущенную память). */
    fun stop() {
        rabotaet = false
        val fd = c.fileDescriptor
        for (m in urby) LibC.I.ioctl(fd, NativeLong(Urb.DISCARDURB), m)
        potok?.join(1000); potok = null
        val ref = PointerByReference()
        var popytok = 0
        while (vPolete.get() > 0 && popytok++ < 200) {
            if (LibC.I.ioctl(fd, NativeLong(Urb.REAPURBNDELAY), ref.pointer) == 0) vPolete.decrementAndGet()
            else Thread.sleep(2)
        }
        try { c.setInterface(alt0) } catch (e: Exception) { }
        c.releaseInterface(alt0)
        // передачи у ядра так и не вернулись — память держим до закрытия fd (закрытие отменит их само)
        if (vPolete.get() <= 0) urby.clear()
        trek?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        trek = null
        try { c.close() } catch (e: Exception) { }
    }

    fun status(): String =
        "{\"idyot\":$rabotaet,\"hz\":${op.chastoty.firstOrNull() ?: 0},\"kanaly\":${op.kanaly},\"paketov\":$paketov," +
        "\"plohih\":$plohih,\"sbrosheno\":$sbrosheno,\"pik_db\":${Pcm.db(pik)},\"gromkost\":$gromkost," +
        "\"oshibka\":${oshibka?.let { Json.str(it) } ?: "null"}}"

    private object LibC { val I get() = Usbfs.c }

    companion object {
        const val URBOV = 8          // передач в полёте
        // пакетов в передаче — pakety (по bInterval): 8 передач × ~8 мс = ~64 мс запаса у карты
    }
}

/**
 * Раскладка struct usbdevfs_urb (linux/usbdevice_fs.h) для 64-битного ARM: проверена offsetof на C (22.10):
 * type 0, endpoint 1, status 4, flags 8, buffer 16, buffer_length 24, actual_length 28, start_frame 32,
 * number_of_packets 36, error_count 40, signr 44, usercontext 48, iso_frame_desc 56 (по 12 байт: length,
 * actual_length, status). Обычная JVM — ZvukTest.
 */
object Urb {
    const val RAZMER = 56
    const val ISO = 12
    const val SUBMITURB = 0x8038550AL
    const val DISCARDURB = 0x550BL
    const val REAPURB = 0x4008550CL
    const val REAPURBNDELAY = 0x4008550DL
    const val TIP_ISO = 0
    const val ISO_ASAP = 0x2

    fun zapolnit(b: ByteBuffer, tochka: Int, bufer: Long, paketov: Int, paket: Int, kontekst: Long) {
        for (i in 0 until RAZMER + ISO * paketov) b.put(i, 0)
        b.put(0, TIP_ISO.toByte())
        b.put(1, tochka.toByte())
        b.putInt(8, ISO_ASAP)
        b.putLong(16, bufer)
        b.putInt(24, paket * paketov)
        b.putInt(36, paketov)
        b.putLong(48, kontekst)
        for (i in 0 until paketov) b.putInt(RAZMER + ISO * i, paket)
    }

    /** (actual_length, status) i-го пакета после возврата передачи. */
    fun paket(b: ByteBuffer, i: Int): Pair<Int, Int> =
        b.getInt(RAZMER + ISO * i + 4) to b.getInt(RAZMER + ISO * i + 8)
}

/** PCM 16 бит: пик и децибелы — для статуса («звук идёт / тишина»). */
object Pcm {
    fun pik(b: ByteArray, n: Int): Int {
        var m = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > m) m = a
            i += 2
        }
        return m.coerceAtMost(32767)
    }

    fun db(pik: Int): Int = if (pik <= 0) -90 else Math.round(20 * Math.log10(pik / 32767.0)).toInt().coerceAtLeast(-90)
}
