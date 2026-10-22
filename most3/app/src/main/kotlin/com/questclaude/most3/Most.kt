package com.questclaude.most3

import android.content.Context
import android.hardware.usb.UsbManager
import java.io.File

/**
 * Двигатель Моста 3 — один на процесс: карта, видео, звук, настройки картинки. Окно (MainActivity) и пульт
 * (Server, порт 8767 — для Плюса) смотрят в одно и то же. Карта открывается, когда есть доступ к ней
 * (выбор «Choose an app» → Мост 3, или окно разрешения).
 */
object Most {
    @Volatile var karta: Karta? = null; private set
    @Volatile var video: Video? = null; private set
    @Volatile var zvuk: Zvuk? = null; private set
    @Volatile var oshibka: String? = null; private set
    /** Ошибка сборки шейдера на видеочипе (её не видно иначе, кроме чёрного окна). */
    @Volatile var oshibkaPokaza: String? = null
    @Volatile var nastrojki = Nastrojki(); private set
    @Volatile var zvukVkl = true; private set
    /** Последний целый кадр для показа (поток чтения кладёт, GL берёт). */
    val polka = Polka()
    private var dir: File? = null
    private var server: Server? = null
    @Volatile var app: Context? = null; private set

    @Synchronized fun zapusk(ctx: Context) {
        if (dir == null) {
            app = ctx.applicationContext
            dir = ctx.filesDir
            zagruzit()
            server = Server(PORT).also { it.start() }
        }
    }

    /** Попытки лестницы режимов при последнем подключении — для самоотчёта. */
    val popytki = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * Открыть карту и пустить видео и звук. Режимы — лестницей (разбор №2): лучший по описанию; если за 2,5 с нет
     * ни одного целого кадра — следующий; причина каждого отката пишется в самоотчёт.
     */
    @Synchronized fun podklyuchit(ctx: Context): String {
        zapusk(ctx)
        if (video?.rabotaet == true) return "уже показываю"
        ostanovit()
        popytki.clear()
        val um = ctx.getSystemService(UsbManager::class.java)
        val d = Karta.najti(um) ?: return osh("карты нет — вставлена в хаб?")
        if (!um.hasPermission(d)) return osh("нет доступа к карте: вынь её на 5 с, вставь и выбери «Мост 3»")
        val k = Karta.otkryt(um, d) ?: return osh("карта не открылась")
        karta = k
        try { File(dir, "karta-rezhimy.txt").writeText(opisanieKarty(k)) } catch (e: Exception) { }
        val lestnica = k.op.lestnica()
        if (lestnica.isEmpty()) return osh("карта не назвала ни одного режима видео")
        for (r in lestnica) {
            polka.rezhim = r
            val v = Video(k, r) { b, n -> polka.polozhit(b, n) }
            video = v
            if (!v.start()) { popytki += "${r.opisanie()}: ${v.oshibka}"; v.stop(); video = null; continue }
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 2500 && (v.sbor?.kadrov ?: 0) == 0L && v.rabotaet) Thread.sleep(50)
            val s = v.sbor
            if ((s?.kadrov ?: 0) > 0) {
                popytki += "${r.opisanie()}: кадры идут"
                if (zvukVkl) vklZvuk()
                oshibka = null
                return "показываю: ${r.opisanie()}" + if (popytki.size > 1) " (после ${popytki.size - 1} откат.)" else ""
            }
            popytki += "${r.opisanie()}: за 2,5 с ни одного целого кадра (пакетов ${v.paketov}, байт ${v.bajt}, " +
                "брошено ${s?.broshenyh ?: 0}, ошибок ${s?.oshibok ?: 0}, последний ${s?.poslednijRazmer ?: 0} Б)"
            v.stop(); video = null
        }
        return osh("ни один режим не дал кадров — most3 diagnoz")
    }

    private fun vklZvuk() {
        val k = karta ?: return
        val z = k.op.zvuk ?: return
        val a0 = k.interfejs(z.interfejs, 0) ?: return
        val a1 = k.interfejs(z.interfejs, z.alt) ?: return
        val zz = Zvuk(k.c, z, a0, a1).also { it.gromkost = gromkost }
        if (zz.start()) zvuk = zz
    }

    @Volatile var gromkost = 1f
        set(v) { field = v.coerceIn(0f, 1.5f); zvuk?.gromkost = field; sohranit() }

    fun zvuk(vkl: Boolean) {
        zvukVkl = vkl; sohranit()
        if (!vkl) { zvuk?.stop(); zvuk = null } else if (zvuk == null && video?.rabotaet == true) vklZvuk()
    }

    @Synchronized fun ostanovit() {
        zvuk?.stop(); zvuk = null
        video?.stop(); video = null
        karta?.zakryt(); karta = null
    }

    fun postavit(n: Nastrojki) { nastrojki = n.ogranichit(); sohranit() }

    private fun osh(s: String): String { oshibka = s; return s }

    fun opisanieKarty(k: Karta): String = buildString {
        append("Карта: ${k.ustr.productName} (${"%04x:%04x".format(k.ustr.vendorId, k.ustr.productId)}), UVC ${"%x".format(k.op.bcdUvc)}\n")
        append("Видео: интерфейс ${k.op.vsInterfejs}, точка 0x${k.op.tochka.toString(16)} ${if (k.op.bulk) "bulk" else "iso"}\n")
        append("Ручки: блок ${k.op.puId}, биты ${Integer.toBinaryString(k.op.puUpravlenie)}\n")
        k.op.zvuk?.let { append("Звук: интерфейс ${it.interfejs} alt ${it.alt}, точка 0x${it.tochka.toString(16)}, ${it.kanaly} к., ${it.bajt * 8} бит, ${it.chastoty} Гц, пакет ${it.paket}\n") }
        append("Режимы:\n"); for (r in k.op.rezhimy) append("  ${r.opisanie()} (формат ${r.formatIndex}, кадр ${r.frameIndex})\n")
        append("Выбран: ${k.op.vybrat()?.opisanie()}\n")
    }

    /**
     * Самоотчёт первого запуска (разбор №2): первая строка — вывод одной фразой, дальше всё, что нужно мне,
     * чтобы понять чёрный экран без шлема в руках. Секретов здесь нет: только устройство и счётчики.
     */
    fun diagnoz(ctx: Context): String = buildString {
        val v = video; val z = zvuk; val k = karta
        append(when {
            v?.rabotaet == true && (v.sbor?.kadrov ?: 0) > 0 -> "ИТОГ: видео идёт, ${v.rezhim.opisanie()}, ${"%.0f".format(v.fps)} к/с"
            k == null -> "ИТОГ: карта не открыта — ${oshibka ?: "нет"}"
            else -> "ИТОГ: карта открыта, кадров нет — ${oshibka ?: v?.oshibka ?: "?"}"
        })
        append(if (z?.rabotaet == true) "; звук идёт (${Pcm.db(z.pik)} дБ)\n" else "; звука нет (${z?.oshibka ?: if (zvukVkl) "не включился" else "выключен"})\n")
        append("Мост 3 $VERSIYA. Разрешения: камера ${razr(ctx, android.Manifest.permission.CAMERA)}, " +
            "USB-камера ${razr(ctx, "horizonos.permission.USB_CAMERA")}\n")
        if (k != null) {
            append(opisanieKarty(k))
            append("Лестница:\n"); for (p in popytki) append("  $p\n")
        }
        v?.let {
            append("PROBE туда: ${it.probeTuda}\nPROBE ответ: ${it.probeOtvet}\n")
            it.otvet?.let { o -> append("Карта: кадр до ${o.maxKadr} Б, пакет до ${o.maxPaket} Б, интервал ${o.interval}\n") }
            it.zametka?.let { m -> append("$m\n") }
            append("Первые пакеты:\n"); for (p in it.pervye) append("  $p\n")
            append("Видео: ${it.status()}\n")
        }
        z?.let { append("Звук: ${it.status()}\n") }
        append("Показ: ${"%.0f".format(polka.pokazFps)} к/с, средняя яркость ${"%.3f".format(polka.srednyaya)}" +
            (oshibkaPokaza?.let { ", ошибка: $it" } ?: "") + "\n")
        append("Внешние камеры Android (запасной путь): ${vneshnieKamery(ctx)}\n")
        k?.c?.rawDescriptors?.let { append("Дескрипторы (${it.size} Б): ${Hex.str(it)}\n") }
    }

    private fun razr(ctx: Context, p: String) =
        if (ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) "да" else "НЕТ"

    /** Разведка Camera2 (разбор №2, зуб III): видит ли Android карту как внешнюю камеру и в каких режимах. */
    fun vneshnieKamery(ctx: Context): String = try {
        val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val CC = android.hardware.camera2.CameraCharacteristics.LENS_FACING
        val vneshnie = cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CC) == android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL }
        if (vneshnie.isEmpty()) "нет (всего камер ${cm.cameraIdList.size})" else vneshnie.joinToString("; ") { id ->
            val ch = cm.getCameraCharacteristics(id)
            val m = ch.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val razm = m?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.take(4)?.joinToString(",") ?: "?"
            "id $id: YUV $razm"
        }
    } catch (e: Exception) { "не прочитать: ${e.javaClass.simpleName}" }

    fun status(): String {
        val k = karta
        return "{\"versiya\":\"$VERSIYA\",\"karta\":${k?.let { Json.str(it.ustr.productName.orEmpty()) } ?: "null"}," +
            "\"video\":${video?.status() ?: "null"},\"zvuk\":${zvuk?.status() ?: "null"},\"zvuk_vkl\":$zvukVkl," +
            "\"pokaz_fps\":${"%.1f".format(polka.pokazFps)},\"srednyaya\":${"%.3f".format(polka.srednyaya)}," +
            "\"avto_teni\":${"%.3f".format(polka.avtoTeni)},\"kartinka\":${nastrojkiJson()}," +
            "\"oshibka_pokaza\":${oshibkaPokaza?.let { Json.str(it) } ?: "null"}," +
            "\"oshibka\":${oshibka?.let { Json.str(it) } ?: "null"}}"
    }

    fun nastrojkiJson(): String = nastrojki.let {
        "{\"yarkost\":${it.yarkost},\"kontrast\":${it.kontrast},\"nasyshch\":${it.nasyshch},\"gamma\":${it.gamma}," +
            "\"teni\":${it.teni},\"rezkost\":${it.rezkost},\"avtonoch\":${it.avtoNoch}}"
    }

    private fun zagruzit() {
        val d = dir ?: return
        try { nastrojki = Nastrojki.izStroki(File(d, "kartinka").readText()) } catch (e: Exception) { }
        try {
            val z = File(d, "zvuk").readText().trim().split(' ')
            zvukVkl = z.getOrNull(0) != "0"; z.getOrNull(1)?.toFloatOrNull()?.let { gromkost = it }
        } catch (e: Exception) { }
    }

    private fun sohranit() {
        val d = dir ?: return
        try { File(d, "kartinka").writeText(nastrojki.vStroku()) } catch (e: Exception) { }
        try { File(d, "zvuk").writeText("${if (zvukVkl) 1 else 0} $gromkost") } catch (e: Exception) { }
    }

    const val PORT = 8767
    /** = versionName в app/build.gradle.kts (сборка сверяет). */
    const val VERSIYA = "3.0.0"
}

/**
 * Полка последнего кадра: поток чтения кладёт (без копии — буфер SborKadra живёт до позапрошлой сдачи),
 * GL берёт самый свежий. Плюс счётчики показа и авто-ночи для статуса.
 */
class Polka {
    class Kadr(val buf: ByteArray, val dlina: Int, val nomer: Long)
    @Volatile var rezhim: UvcRezhim? = null
    @Volatile var kadr: Kadr? = null; private set
    @Volatile var pokazFps = 0f
    @Volatile var srednyaya = 0f
    @Volatile var avtoTeni = 0f
    private var n = 0L

    fun polozhit(b: ByteArray, dlina: Int) { kadr = Kadr(b, dlina, ++n) }
}
