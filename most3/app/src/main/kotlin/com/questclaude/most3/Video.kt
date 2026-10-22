package com.questclaude.most3

import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Видео карты по UVC (Мост 3): PROBE/COMMIT выбранного режима, потом bulk-точка читается очередью запросов
 * (UsbRequest, несколько в полёте — иначе 250 МБ/с YUY2 не успеть), каждый вернувшийся запрос = один payload UVC,
 * SborKadra складывает их в кадры. Кадр отдаётся naKadr (поток чтения; показ берёт последний).
 */
class Video(private val k: Karta, val rezhim: UvcRezhim, private val naKadr: (ByteArray, Int) -> Unit) {
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
        if (o.formatIndex != rezhim.formatIndex || o.frameIndex != rezhim.frameIndex)
            zametka = "карта поменяла режим: формат ${o.formatIndex}, кадр ${o.frameIndex}"
        if (c.controlTransfer(0x21, 0x01, 0x0200, vs.id, prinyato, dlina, 1000) != dlina) return osh("карта не приняла COMMIT")
        otvet = o

        val ozhidaemyj = razmerKadra(rezhim.vid, rezhim.shirina, rezhim.vysota)
        val maxKadr = maxOf(o.maxKadr, ozhidaemyj, rezhim.maxBuf, 1 shl 20)
        // Один bulk-запрос = один payload: буфер не меньше dwMaxPayloadTransferSize, иначе хвост payload
        // придёт следующему запросу без заголовка.
        val razmer = (if (o.maxPaket > 0) o.maxPaket else 512 * 1024).coerceIn(16 * 1024, 16 * 1024 * 1024)
        val s = SborKadra(ozhidaemyj, maxKadr, naKadr); sbor = s

        repeat(if (razmer > 4 * 1024 * 1024) 3 else ZAPROSOV) {       // большие payload — меньше в полёте (память)
            val r = UsbRequest()
            if (!r.initialize(c, tochka)) return osh("запрос USB не создался")
            r.clientData = ByteBuffer.allocateDirect(razmer)
            zaprosy += r
        }
        rabotaet = true; oshibka = null
        for (r in zaprosy) { val b = r.clientData as ByteBuffer; b.clear(); r.queue(b) }
        potok = Thread({ cikl(razmer) }, "most3-video").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun cikl(razmer: Int) {
        val tmp = ByteArray(razmer)
        var t0 = System.nanoTime(); var k0 = 0L
        while (rabotaet) {
            val r = try { k.c.requestWait(1000) } catch (e: TimeoutException) { null } catch (e: Exception) { null }
            if (r == null) continue
            val b = r.clientData as? ByteBuffer ?: continue
            val n = b.position()
            if (n > 0) {
                b.flip(); b.get(tmp, 0, n); paketov++; bajt += n
                if (pervye.size < 4) pervye += "$n Б: " + Hex.str(tmp, minOf(n, 12))
                sbor?.podat(tmp, 0, n)
            }
            if (!rabotaet) break
            b.clear()
            if (!r.queue(b)) { oshibka = "карта отключилась"; break }
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
        for (r in zaprosy) try { r.close() } catch (e: Exception) { }
        zaprosy.clear()
        // UVC bulk: остановка потока — CLEAR_FEATURE(ENDPOINT_HALT) на точку
        k.tochkaVideo?.let { k.c.controlTransfer(0x02, 0x01, 0, it.address, null, 0, 300) }
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

    companion object { const val ZAPROSOV = 6 }
}
