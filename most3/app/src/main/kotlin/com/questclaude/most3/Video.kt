package com.questclaude.most3

import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Видео карты по UVC (Мост 3): PROBE/COMMIT выбранного режима, потом bulk-точка читается очередью запросов
 * (UsbRequest, несколько в полёте — иначе 250 МБ/с YUY2 не успеть), каждый вернувшийся запрос = один payload UVC,
 * SborKadra складывает их в кадры. Кадр отдаётся naKadr (поток чтения; показ берёт последний).
 */
class Video(private val k: Karta, zhelaemyj: UvcRezhim, private val naKadr: (ByteArray, Int) -> Unit) {
    /** Режим, который карта на самом деле приняла (COMMIT может поправить формат/кадр). */
    @Volatile var rezhim: UvcRezhim = zhelaemyj; private set
    @Volatile var rabotaet = false; private set
    @Volatile var oshibka: String? = null; private set
    @Volatile var fps = 0f; private set
    @Volatile var otvet: ProbeOtvet? = null; private set
    var sbor: SborKadra? = null; private set
    /** Для самоотчёта: что послали и что карта ответила на PROBE, первые заголовки пакетов, счёт пакетов. */
    @Volatile var probeTuda = ""; private set
    @Volatile var probeOtvet = ""; private set
    @Volatile var zametka: String? = null; private set
    val pervye = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var paketov = 0L; private set
    @Volatile var bajt = 0L; private set
    private var potok: Thread? = null
    private val zaprosy = ArrayList<UsbRequest>()
    /** Запросов у ядра: закрывать UsbRequest можно, только когда все вернулись (иначе ядро пишет в освобождённое). */
    private val vPolete = AtomicInteger()

    fun oshibkaIzvne(s: String) { oshibka = s }

    fun start(): Boolean {
        val c = k.c
        val vs = k.vs ?: return osh("у карты нет интерфейса видео")
        val tochka = k.tochkaVideo ?: return osh("у карты нет точки видео 0x${k.op.tochka.toString(16)}")
        k.vc?.let { c.claimInterface(it, true) }                     // ручки картинки и ошибки VC
        if (!c.claimInterface(vs, true)) return osh("видео карты занято (открыт HDMI Link?) — закрой его")

        val dlina = UvcProbe.dlina(k.op.bcdUvc)
        val interval = rezhim.luchshijInterval(60)
        // «Вежливо»: сначала спросить, что у карты сейчас (GET_CUR), и поменять только своё; не вышло — нулями, как libuvc
        val tek = ByteArray(dlina)
        val estTek = c.controlTransfer(0xA1, 0x81, 0x0100, vs.id, tek, dlina, 1000) == dlina
        var zapros = if (estTek) UvcProbe.nalozhit(tek, rezhim, interval, dlina) else UvcProbe.upakovat(rezhim, interval, dlina)
        if (c.controlTransfer(0x21, 0x01, 0x0100, vs.id, zapros, dlina, 1000) != dlina) {
            zapros = UvcProbe.upakovat(rezhim, interval, dlina)
            if (c.controlTransfer(0x21, 0x01, 0x0100, vs.id, zapros, dlina, 1000) != dlina) return osh("карта не приняла режим (PROBE)")
        }
        probeTuda = Hex.str(zapros)
        val prinyato = ByteArray(dlina)
        if (c.controlTransfer(0xA1, 0x81, 0x0100, vs.id, prinyato, dlina, 1000) != dlina) return osh("карта не ответила на PROBE")
        probeOtvet = Hex.str(prinyato)
        val o = UvcProbe.razobrat(prinyato)
        if (o.formatIndex != rezhim.formatIndex || o.frameIndex != rezhim.frameIndex) {
            val fakt = k.op.rezhimy.firstOrNull { it.formatIndex == o.formatIndex && it.frameIndex == o.frameIndex }
            zametka = "карта поменяла режим: формат ${o.formatIndex}, кадр ${o.frameIndex}" + (fakt?.let { " (${it.opisanie()})" } ?: " (не из списка)")
            if (fakt != null) rezhim = fakt
        }
        if (c.controlTransfer(0x21, 0x01, 0x0200, vs.id, prinyato, dlina, 1000) != dlina) return osh("карта не приняла COMMIT")
        otvet = o

        val ozhidaemyj = razmerKadra(rezhim.vid, rezhim.shirina, rezhim.vysota)
        val maxKadr = maxOf(o.maxKadr, ozhidaemyj, rezhim.maxBuf, 1 shl 20)
        // Один bulk-запрос = один payload: буфер не меньше dwMaxPayloadTransferSize, иначе хвост payload
        // придёт следующему запросу без заголовка.
        val razmer = (if (o.maxPaket > 0) o.maxPaket else 512 * 1024).coerceIn(16 * 1024, 6 * 1024 * 1024)   // больше — продолжение без заголовка
        val s = SborKadra(ozhidaemyj, maxKadr, naKadr); sbor = s

        // Ядро держит на передачи usbfs ~16 МБ на ВСЮ систему (usbfs_memory_mb) — в полёте не больше ~12 МБ
        val skolko = (BYUDZHET / razmer).coerceIn(2, ZAPROSOV)
        repeat(skolko) {
            val r = UsbRequest()
            if (!r.initialize(c, tochka)) return osh("запрос USB не создался")
            r.clientData = ByteBuffer.allocateDirect(razmer)
            zaprosy += r
        }
        rabotaet = true; oshibka = null
        for (r in zaprosy) { val b = r.clientData as ByteBuffer; b.clear(); if (r.queue(b)) vPolete.incrementAndGet() }
        if (vPolete.get() == 0) { rabotaet = false; return osh("ядро не приняло ни одного запроса (память usbfs?)") }
        if (vPolete.get() < zaprosy.size) zametka = (zametka?.let { "$it; " } ?: "") + "в полёте ${vPolete.get()} из ${zaprosy.size}"
        potok = Thread({ cikl(razmer) }, "most3-video").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun cikl(razmer: Int) {
        val tmp = ByteArray(razmer)
        var t0 = System.nanoTime(); var k0 = 0L
        var polnyj = false                                   // прошлая передача заполнила буфер до конца
        while (rabotaet) {
            val r = try { k.c.requestWait(1000) } catch (e: TimeoutException) { continue } catch (e: Exception) { null }
            if (r == null) { if (rabotaet) oshibka = "карта отключилась (запрос не вернулся)"; break }
            vPolete.decrementAndGet()
            val b = r.clientData as? ByteBuffer ?: continue
            val n = b.position()
            if (n > 0) {
                b.flip(); b.get(tmp, 0, n); paketov++; bajt += n
                if (pervye.size < 4) pervye += "$n Б: " + Hex.str(tmp, minOf(n, 12))
                val sb = sbor
                // карта занизила dwMaxPayloadTransferSize: продолжение payload приходит без заголовка
                if (sb != null && !sb.podat(tmp, 0, n) && polnyj) sb.prodolzhit(tmp, 0, n)
                polnyj = n == razmer
            }
            if (!rabotaet) break
            b.clear()
            if (!r.queue(b)) { oshibka = "карта отключилась"; break }
            vPolete.incrementAndGet()
            val t = System.nanoTime()
            if (t - t0 >= 1_000_000_000L) {
                val kk = sbor?.kadrov ?: 0
                fps = (kk - k0) * 1e9f / (t - t0); t0 = t; k0 = kk
            }
        }
        rabotaet = false
    }

    fun stop() {
        rabotaet = false
        for (r in zaprosy) try { r.cancel() } catch (e: Exception) { }
        potok?.join(1500); potok = null
        // забрать у ядра все отменённые запросы, и только потом закрыть их
        var popytok = 0
        while (vPolete.get() > 0 && popytok++ < 30) {
            val r = try { k.c.requestWait(100) } catch (e: TimeoutException) { null } catch (e: Exception) { break }
            if (r != null) vPolete.decrementAndGet()
        }
        if (vPolete.get() <= 0) for (r in zaprosy) try { r.close() } catch (e: Exception) { }
        zaprosy.clear()                                      // не вернулись — не закрываем: закроет само соединение
        // UVC bulk: остановка — CLEAR_HALT через usbfs (сброс и у карты, и у контроллера шлема)
        k.tochkaVideo?.let { Usbfs.sbrositTochku(k.c.fileDescriptor, it.address) }
        k.vs?.let { k.c.releaseInterface(it) }
        k.vc?.let { k.c.releaseInterface(it) }
    }

    private fun osh(s: String): Boolean { oshibka = s; return false }

    fun status(): String {
        val s = sbor
        return "{\"idyot\":$rabotaet,\"rezhim\":${Json.str(rezhim.opisanie())},\"fps\":${"%.1f".format(fps)}," +
            "\"kadrov\":${s?.kadrov ?: 0},\"broshenyh\":${s?.broshenyh ?: 0},\"oshibok\":${s?.oshibok ?: 0}," +
            "\"paket\":${otvet?.maxPaket ?: 0},\"oshibka\":${oshibka?.let { Json.str(it) } ?: "null"}}"
    }

    companion object {
        const val ZAPROSOV = 8
        const val BYUDZHET = 12 * 1024 * 1024
    }
}
