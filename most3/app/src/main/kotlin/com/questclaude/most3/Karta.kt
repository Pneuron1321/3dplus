package com.questclaude.most3

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Карта захвата целиком (Мост 3): одно соединение на всё — видео (интерфейс VS, bulk), звук (AS alt 1, iso через
 * usbfs), ручки картинки внутри карты (Processing Unit на интерфейсе VC). Описание берётся из живых дескрипторов
 * при каждом подключении: в шлеме карта — USB 3 (PID 0x009C), режимы не те, что в отчёте с ноутбука (USB 2).
 */
class Karta(val ustr: UsbDevice, val c: UsbDeviceConnection, val op: UvcOpisanie) {
    private val vse = (0 until ustr.interfaceCount).map { ustr.getInterface(it) }

    fun interfejs(id: Int, alt: Int = 0): UsbInterface? = vse.firstOrNull { it.id == id && it.alternateSetting == alt }

    val vc: UsbInterface? get() = interfejs(op.vcInterfejs)
    val vs: UsbInterface? get() = interfejs(op.vsInterfejs)
    val tochkaVideo: UsbEndpoint?
        get() = vse.filter { it.id == op.vsInterfejs }.flatMap { i -> (0 until i.endpointCount).map { i.getEndpoint(it) } }
            .firstOrNull { it.address == op.tochka }

    /** Ручки блока обработки UVC: имя → (селектор, бит bmControls, со знаком). Как в мосте 2 (2.6.2). */
    data class Ruchka(val sel: Int, val bit: Int, val znak: Boolean)
    data class Diapazon(val min: Int, val max: Int, val def: Int, val cur: Int)

    /** Прочитать диапазон ручки (GET_MIN/MAX/DEF/CUR). Интерфейс VC должен быть захвачен. */
    fun diapazon(imya: String): Diapazon? {
        val r = RUCHKI[imya] ?: return null
        val pu = op.puId ?: return null
        if (op.puUpravlenie != 0 && op.puUpravlenie and (1 shl r.bit) == 0) return null
        val idx = (pu shl 8) or op.vcInterfejs
        fun get(req: Int): Int? {
            val b = ByteArray(2)
            return if (c.controlTransfer(0xA1, req, r.sel shl 8, idx, b, 2, 300) == 2) chislo(b, r.znak) else null
        }
        val mn = get(0x82) ?: return null
        val mx = get(0x83) ?: return null
        val df = get(0x87) ?: mn
        return Diapazon(mn, mx, df, get(0x81) ?: df)
    }

    fun ustanovit(imya: String, v: Int): Boolean {
        val r = RUCHKI[imya] ?: return false
        val pu = op.puId ?: return false
        val b = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return c.controlTransfer(0x21, 0x01, r.sel shl 8, (pu shl 8) or op.vcInterfejs, b, 2, 300) == 2
    }

    fun zakryt() { try { c.close() } catch (e: Exception) { } }

    companion object {
        const val ELGATO = 0x0FD9
        val RUCHKI = linkedMapOf(
            "yarkost" to Ruchka(0x02, 0, true), "kontrast" to Ruchka(0x03, 1, false),
            "ton" to Ruchka(0x06, 2, true), "nasyshchennost" to Ruchka(0x07, 3, false))

        fun chislo(b: ByteArray, znak: Boolean): Int {
            val u = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
            return if (znak && u >= 0x8000) u - 0x10000 else u
        }

        /** Карта среди USB-устройств: сначала Elgato, иначе любое с интерфейсом видео (класс 14). */
        fun najti(um: UsbManager): UsbDevice? {
            val video = um.deviceList.values.filter { d ->
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
            }
            return video.firstOrNull { it.vendorId == ELGATO } ?: video.firstOrNull()
        }

        fun otkryt(um: UsbManager, d: UsbDevice): Karta? {
            val c = um.openDevice(d) ?: return null
            val raw = c.rawDescriptors ?: run { c.close(); return null }
            return Karta(d, c, UvcRazbor.razobrat(raw))
        }
    }
}
