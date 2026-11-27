package com.questclaude.most3

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** ioctl usbfs через JNA — то, чего нет в UsbDeviceConnection (iso-передачи — Zvuk, сброс остановки точки — здесь). */
object Usbfs {
    interface LibC : Library {
        fun ioctl(fd: Int, zapros: NativeLong, arg: Pointer): Int
    }
    val c: LibC by lazy { Native.load("c", LibC::class.java) }

    const val CLEAR_HALT = 0x80045515L       // _IOR('U', 21, unsigned int)

    /** USBDEVFS_CLEAR_HALT: сброс и у карты, и у контроллера шлема (чистый CLEAR_FEATURE сбрасывает только карту). */
    fun sbrositTochku(fd: Int, tochka: Int): Boolean =
        try { c.ioctl(fd, NativeLong(CLEAR_HALT), IntByReference(tochka).pointer) == 0 } catch (e: Throwable) { false }
}
