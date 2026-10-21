package com.questclaude.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File

/**
 * Ручки картинки карты захвата (2.6.2, Александр 21.10: «в шлеме блёкло, на мониторе я делал поярче»). У Elgato 4K X
 * в описании UVC есть блок обработки (Processing Unit, id 2, интерфейс 0): яркость, контраст, оттенок, насыщенность.
 * Они меняют картинку внутри карты — HDMI Link и эфир получают уже исправленную. Своих ручек в HDMI Link нет.
 * Пока HDMI Link открыт, он может держать интерфейс управления — тогда «занято»: закрыть HDMI Link, выставить, открыть.
 * Настройки запоминаются (karta-kartinka) и ставятся сами, когда карта подключается.
 */
class KartaKartinka(private val ctx: Context, private val dir: File) {
    private val um = ctx.getSystemService(UsbManager::class.java)
    private val nastroyki = File(dir, "karta-kartinka")
    private val lock = Any()
    @Volatile private var oshibka: String? = null
    @Volatile private var zhdu = false

    /** Желаемые значения: имя → число (сырое значение карты). Пусто — как в карте по умолчанию. */
    private val zhelanie = LinkedHashMap<String, Int>()

    init { zagruzit() }

    private val priemnik = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                RAZRESHENIE -> { zhdu = false; if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) Thread { primenit() }.start()
                                 else oshibka = "доступ к карте не дали (окно в шлеме — «Разрешить»)" }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (zhelanie.isNotEmpty()) Thread { Thread.sleep(300); primenit(sprosit = false) }.start()
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply { addAction(RAZRESHENIE); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(priemnik, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(priemnik, f)
        if (zhelanie.isNotEmpty()) Thread { primenit(sprosit = false) }.start()
    }

    private fun karta(): UsbDevice? = um.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
            d.getInterface(it).interfaceSubclass == 1 } && d.vendorId == ELGATO
    } ?: um.deviceList.values.firstOrNull { d -> (0 until d.interfaceCount).any {
        d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO && d.getInterface(it).interfaceSubclass == 1 } }

    /**
     * q: yarkost / kontrast / nasyshchennost / ton = число (сырое), «+N»/«-N» (шаг от текущего желаемого) или «def»;
     * preset=sochno|kak_bylo|<имя из файла>. Пусто — только прочитать. Ответ JSON.
     */
    fun upr(q: Map<String, String>): String {
        if (diapazony.isEmpty() && RUCHKI.keys.any { q[it]?.firstOrNull() in setOf('+', '-') }) primenit(sprosit = true)   // шаг — от текущего
        synchronized(lock) {
            q["preset"]?.let { p ->
                when (p) {
                    "kak_bylo", "def" -> zhelanie.clear()
                    else -> PRESETY[p]?.let { zhelanie.clear(); zhelanie.putAll(it) } ?: return "{\"oshibka\":\"нет такого набора: $p\"}"
                }
            }
            for ((imya, _) in RUCHKI) {
                val v = q[imya] ?: continue
                if (v == "def") { zhelanie.remove(imya); continue }
                val dz = diapazony[imya]
                Uvc.novoe(v, zhelanie[imya] ?: dz?.cur)?.let { zhelanie[imya] = if (dz != null) it.coerceIn(dz.min, dz.max) else it }
            }
            sohranit()
        }
        return primenit(sprosit = true)
    }

    private class Diapazon(val min: Int, val max: Int, val def: Int, var cur: Int)
    private val diapazony = HashMap<String, Diapazon>()

    /** Открыть карту, прочитать диапазоны, выставить желаемое. sprosit — показать окно разрешения, если нужно. */
    fun primenit(sprosit: Boolean = true): String {
        val d = karta() ?: return status("карты нет (вставлена в хаб?)")
        if (!um.hasPermission(d)) {
            if (sprosit && !zhdu) {
                zhdu = true
                val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                um.requestPermission(d, PendingIntent.getBroadcast(ctx, 7, Intent(RAZRESHENIE).setPackage(ctx.packageName), fl))
            }
            return status("жду разрешения: в шлеме окно «Разрешить доступ к Elgato» — нажми «Разрешить» (без галочки «по умолчанию»)")
        }
        val intf = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.interfaceSubclass == 1 }
            ?: return status("у карты нет интерфейса управления видео")
        val c = um.openDevice(d) ?: return status("карта не открылась")
        try {
            val vzyal = c.claimInterface(intf, false)          // не отрывать драйвер у HDMI Link
            if (!vzyal) return status("занято: закрой HDMI Link, дай команду ещё раз, потом открой HDMI Link")
            try {
                val idx = (PU_ID shl 8) or intf.id
                for ((imya, sel) in RUCHKI) {
                    fun get(req: Int): Int? {
                        val b = ByteArray(2)
                        val n = c.controlTransfer(0xA1, req, sel shl 8, idx, b, 2, 500)
                        return if (n == 2) Uvc.chislo(b, imya in ZNAKOVYE) else null
                    }
                    val mn = get(GET_MIN) ?: continue
                    val mx = get(GET_MAX) ?: continue
                    val df = get(GET_DEF) ?: mn
                    diapazony[imya] = Diapazon(mn, mx, df, get(GET_CUR) ?: df)
                }
                if (diapazony.isEmpty()) return status("карта не ответила про ручки картинки")
                val nado = synchronized(lock) { HashMap(zhelanie) }
                for ((imya, sel) in RUCHKI) {
                    val dz = diapazony[imya] ?: continue
                    val v = (nado[imya] ?: dz.def).coerceIn(dz.min, dz.max)
                    if (v == dz.cur) continue
                    val n = c.controlTransfer(0x21, SET_CUR, sel shl 8, idx, Uvc.bayty(v), 2, 500)
                    if (n == 2) dz.cur = v else oshibka = "$imya не выставилась"
                }
                oshibka = null
                return status(null)
            } finally { try { c.releaseInterface(intf) } catch (e: Exception) { } }
        } catch (e: Exception) {
            return status("ошибка: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.close() }
    }

    private fun status(problema: String?): String {
        if (problema != null) oshibka = problema
        val r = RUCHKI.keys.joinToString(",") { imya ->
            val dz = diapazony[imya]
            "\"$imya\":" + (dz?.let { "{\"cur\":${it.cur},\"min\":${it.min},\"max\":${it.max},\"def\":${it.def}" +
                (zhelanie[imya]?.let { z -> ",\"nado\":$z" } ?: "") + "}" } ?: "null")
        }
        return "{$r,\"oshibka\":${oshibka?.let { FrameServer.json(it) } ?: "null"}}"
    }

    private fun zagruzit() {
        try {
            nastroyki.readLines().forEach { l -> val (k, v) = l.split('=', limit = 2); v.trim().toIntOrNull()?.let { zhelanie[k.trim()] = it } }
        } catch (e: Exception) { }
    }

    private fun sohranit() {
        try { nastroyki.writeText(zhelanie.entries.joinToString("\n") { "${it.key}=${it.value}" }) } catch (e: Exception) { }
    }

    companion object {
        const val ELGATO = 0x0FD9
        const val PU_ID = 2                       // Processing Unit карты (USB Tree View, 21.10)
        const val SET_CUR = 0x01
        const val GET_CUR = 0x81
        const val GET_MIN = 0x82
        const val GET_MAX = 0x83
        const val GET_DEF = 0x87
        private const val RAZRESHENIE = "com.questclaude.bridge.KARTA_KARTINKA"
        /** Ручки UVC: имя → селектор Processing Unit. */
        val RUCHKI = linkedMapOf("yarkost" to 0x02, "kontrast" to 0x03, "ton" to 0x06, "nasyshchennost" to 0x07)
        val ZNAKOVYE = setOf("yarkost", "ton")
        /** Наборы (сырые значения карты); «sochno» — до фото с ноутбука Александра. */
        val PRESETY = HashMap<String, Map<String, Int>>()
    }
}

/** Числа UVC: 2 байта, младший первым; яркость и оттенок — со знаком. Обычная JVM — KartaKartinkaTest. */
object Uvc {
    fun chislo(b: ByteArray, znak: Boolean): Int {
        val u = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
        return if (znak && u >= 0x8000) u - 0x10000 else u
    }

    fun bayty(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    /** «+N»/«-N» — шаг от tek (нет tek — null), «=N» или «N» — само число, остальное — null. */
    fun novoe(v: String, tek: Int?): Int? = when {
        (v.startsWith("+") || v.startsWith("-")) && v.drop(1).toIntOrNull() != null -> tek?.plus(v.toInt())
        v.startsWith("=") -> v.drop(1).toIntOrNull()
        else -> v.toIntOrNull()
    }
}
