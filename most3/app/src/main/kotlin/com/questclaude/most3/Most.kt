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
    /** Запись игры в круг (Klip3) — для «сохрани клип игры». */
    @Volatile var klip: Klip3? = null; private set
    @Volatile var klipVkl = true; private set
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
            Struny.start(ctx.applicationContext)          // эфир моста 2 может брать игру отсюда (efir igra)
        }
    }

    /** Одна очередь для всего, что ждёт карту (окно и приёмник USB не ждут на главном потоке). */
    private val ochered = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun vFone(f: () -> Unit) { ochered.execute { try { f() } catch (e: Exception) { oshibka = "сбой: ${e.message ?: e.javaClass.simpleName}" } } }

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
        val lestnica = ruchnoj?.let { r -> k.op.rezhimy.firstOrNull { it == r } }?.let { listOf(Shag(it, profil.maxFps)) }
            ?: k.op.lestnica(profil, zapomnennyj(k, profil))
        if (lestnica.isEmpty()) return osh("карта не назвала ни одного режима видео")
        for ((r, potolok) in lestnica) {
            polka.rezhim = r
            val v = Video(k, r, potolok) { b, n -> polka.polozhit(b, n) }
            video = v
            val zapushchen = try { v.start() } catch (e: Exception) { v.oshibkaIzvne("сбой: ${e.message ?: e.javaClass.simpleName}"); false }
            polka.rezhim = v.rezhim
            if (!zapushchen) { popytki += "${r.opisanie()}: ${v.oshibka}"; v.stop(); video = null; continue }
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 2500 && (v.sbor?.kadrov ?: 0) == 0L && v.rabotaet) Thread.sleep(50)
            val s = v.sbor
            if ((s?.kadrov ?: 0) > 0) {
                polka.rezhim = v.rezhim                            // карта могла поправить режим при COMMIT
                popytki += "${v.rezhim.opisanie()}: кадры идут"
                if (ruchnoj == null) zapomnit(k, profil, v.rezhim)
                if (klipVkl) vklKlip(k, v.rezhim)
                if (zvukVkl) vklZvuk()
                app?.let { Sluzhba.vkl(it) }
                oshibka = null
                return "показываю (${profil.nadpis.substringBefore(':')}): ${v.rezhim.opisanie(v.rezhim.fps(v.rezhim.luchshijInterval(potolok)))}" + if (popytki.size > 1) " (после ${popytki.size - 1} откат.)" else ""
            }
            popytki += "${r.opisanie()}: за 2,5 с ни одного целого кадра (пакетов ${v.paketov}, байт ${v.bajt}, " +
                "брошено ${s?.broshenyh ?: 0}, ошибок ${s?.oshibok ?: 0}, последний ${s?.poslednijRazmer ?: 0} Б)"
            v.stop(); video = null
        }
        return osh("ни один режим не дал кадров — most3 diagnoz")
    }

    /** Профиль режимов (заход 2): efir / plavno / maks / 4k / hdr. Смена — переподключение. */
    @Volatile var profil = Profil.MAKS; private set     // Александр 22.10: «Максимум» (NV12 1440p60) по умолчанию

    /** Режим по номеру (most3 rezhim N) — один раз, до смены профиля или переподключения; в профиль не пишется. */
    @Volatile private var ruchnoj: UvcRezhim? = null

    /** Сменить профиль и переподключить карту (в фоне — окно и пульт не ждут); ручной режим — забыть. */
    fun smenitProfil(p: Profil) {
        profil = p; sohranit(); ruchnoj = null
        val c = app ?: return
        vFone { ostanovit(); podklyuchit(c) }
    }

    /** Режим вручную по номеру из списка (most3 rezhimy): переподключить на нём; профиль его не запоминает. */
    fun vybratRezhim(n: Int): String {
        val r = karta?.op?.rezhimy?.getOrNull(n - 1) ?: return "нет режима №$n (карта открыта? most3 rezhimy)"
        ruchnoj = r
        val c = app ?: return "мост не запущен"
        vFone { ostanovit(); podklyuchit(c) }
        return "пробую: ${r.opisanie()}"
    }

    /**
     * Режим, который уже срабатывал в профиле — первым в лестнице. Файл rezhim-<профиль>-<код карты>: у Elgato на USB 2
     * и USB 3 разные коды (009d/009c) и разные списки режимов — номера одного не годятся для другого.
     */
    private fun fajlRezhima(k: Karta, p: Profil) = File(dir, "rezhim-${p.imya}-%04x".format(k.ustr.productId))

    private fun zapomnennyj(k: Karta, p: Profil): UvcRezhim? = try {
        val z = fajlRezhima(k, p).readText().trim().split(' ')
        k.op.rezhimy.firstOrNull { it.formatIndex == z[0].toInt() && it.frameIndex == z[1].toInt() &&
            it.shirina == z[2].toInt() && it.vysota == z[3].toInt() && it.vid.name == z[4] }
    } catch (e: Exception) { null }

    /** Запомнить только режим, ради которого профиль (запасной — нет); «Эфир» — любой рабочий. */
    private fun zapomnit(k: Karta, p: Profil, r: UvcRezhim) {
        if (p != Profil.EFIR && k.op.podhodyat(p).none { it == r }) return
        try { fajlRezhima(k, p).writeText("${r.formatIndex} ${r.frameIndex} ${r.shirina} ${r.vysota} ${r.vid.name}") } catch (e: Exception) { }
    }

    /** Карта на USB 2 (bcdUSB в дескрипторе устройства < 3.0): у Elgato 4K X тогда нет 1440p/4K и несжатого 1080p. */
    fun usb2(k: Karta): Boolean = k.c.rawDescriptors?.let { d -> d.size > 3 && (d[3].toInt() and 0xff) < 3 } ?: false

    const val USB2_SOVET = "карта на USB 2 — только сжатый 1080p, без 1440p/4K: переткни карту, проверь кабель и хаб (нужен USB 3, SS/5 Gbps)"

    /** Звук — на своём соединении с картой (второй openDevice): у usbfs очередь готовых передач — на fd. */
    private fun vklZvuk() {
        val k = karta ?: return
        val z = k.op.zvuk ?: return
        val a0 = k.interfejs(z.interfejs, 0) ?: return
        val a1 = k.interfejs(z.interfejs, z.alt) ?: return
        val um = app?.getSystemService(UsbManager::class.java) ?: return
        val c2 = um.openDevice(k.ustr) ?: run { zvukOshibka = "второе соединение с картой не открылось"; return }
        if (z.bajt != 2 || z.kanaly !in 1..2) { c2.close(); zvukOshibka = "звук карты ${z.bajt * 8} бит, ${z.kanaly} к. — пока не умею"; return }
        val zz = Zvuk(c2, z, a0, a1).also { it.gromkost = gromkost; it.naPcm = { b, n, t -> klip?.pcm(b, n, t) } }
        if (zz.start()) { zvuk = zz; zvukOshibka = null } else { zvukOshibka = zz.oshibka; zz.stop() }   // stop закроет своё соединение
    }

    /** Служба переднего плана (Sluzhba): почему не встала — для самоотчёта. */
    @Volatile var zametkaSluzhby: String? = null

    /** Почему звук не встал (Zvuk после неудачи уже закрыт) — для статуса и самоотчёта. */
    @Volatile var zvukOshibka: String? = null; private set

    @Volatile var gromkost = 1f
        set(v) { field = v.coerceIn(0f, 1.5f); zvuk?.gromkost = field; sohranit() }

    private fun vklKlip(k: Karta, r: UvcRezhim) {
        val c = app ?: return
        val z = k.op.zvuk
        klip = Klip3(c.cacheDir, r.shirina, r.vysota).also { it.start(z?.chastoty?.firstOrNull { h -> h == 48000 } ?: z?.chastoty?.firstOrNull(), z?.kanaly ?: 2) }
    }

    @Synchronized fun klip(vkl: Boolean) {
        klipVkl = vkl; sohranit()
        if (!vkl) { klip?.stop(); klip = null }
        else if (klip == null) { val k = karta; val r = video?.rezhim; if (k != null && r != null && video?.rabotaet == true) vklKlip(k, r) }
    }

    /** Последние [sek] секунд игры — в MP4 в галерею (Movies/Plus). Ответ JSON, как у клипа камеры моста 2. */
    fun sohranitKlip(sek: Int): String {
        val c = app ?: return "{\"oshibka\":\"мост не запущен\"}"
        val k = klip ?: return "{\"oshibka\":\"запись игры выключена (most3 klip vkl)\"}"
        return KlipMp4.sohranit(c, k.zapis, sek)
    }

    @Synchronized fun zvuk(vkl: Boolean) {
        zvukVkl = vkl; sohranit()
        if (!vkl) { zvuk?.stop(); zvuk = null } else if (zvuk == null && video?.rabotaet == true) vklZvuk()
    }

    @Synchronized fun ostanovit() {
        app?.let { Sluzhba.vykl(it) }
        zvuk?.stop(); zvuk = null
        klip?.stop(); klip = null
        video?.stop(); video = null
        karta?.zakryt(); karta = null
    }

    fun postavit(n: Nastrojki) { nastrojki = n.ogranichit(); sohranit() }

    private fun osh(s: String): String { oshibka = s; return s }

    fun opisanieKarty(k: Karta): String = buildString {
        append("Карта: ${k.ustr.productName} (${"%04x:%04x".format(k.ustr.vendorId, k.ustr.productId)}), UVC ${"%x".format(k.op.bcdUvc)}" +
            (if (usb2(k)) " — ВНИМАНИЕ: $USB2_SOVET" else ", USB 3") + "\n")
        append("Видео: интерфейс ${k.op.vsInterfejs}, точка 0x${k.op.tochka.toString(16)} ${if (k.op.bulk) "bulk" else "iso"}\n")
        append("Ручки: блок ${k.op.puId}, биты ${Integer.toBinaryString(k.op.puUpravlenie)}\n")
        k.op.zvuk?.let { append("Звук: интерфейс ${it.interfejs} alt ${it.alt}, точка 0x${it.tochka.toString(16)}, ${it.kanaly} к., ${it.bajt * 8} бит, ${it.chastoty} Гц, пакет ${it.paket}\n") }
        append("Режимы:\n"); k.op.rezhimy.forEachIndexed { i, r -> append("  ${i + 1}. ${r.opisanie()} (формат ${r.formatIndex}, кадр ${r.frameIndex})\n") }
        append("Профиль: ${profil.nadpis}\n")
        // шлем 22.10: «Выбран» показывал выбор по умолчанию, а не то, что идёт, — путало
        append("Сейчас идёт: ${video?.takeIf { it.rabotaet }?.rezhim?.opisanie() ?: "ничего"}" +
            (ruchnoj?.let { " (вручную, до смены профиля)" } ?: "") + "\n")
        append("Лестница профиля: ${k.op.lestnica(profil, zapomnennyj(k, profil)).joinToString(" → ") { it.rezhim.opisanie() }}\n")
    }

    /**
     * Самоотчёт первого запуска (разбор №2): первая строка — вывод одной фразой, дальше всё, что нужно мне,
     * чтобы понять чёрный экран без шлема в руках. Секретов здесь нет: только устройство и счётчики.
     */
    fun diagnoz(ctx: Context): String = buildString {
        val v = video; val z = zvuk; val k = karta
        append(when {
            v?.rabotaet == true && (v.sbor?.kadrov ?: 0) > 0 -> "ИТОГ: видео идёт, ${v.rezhim.opisanie()}, ${"%.0f".format(v.fps)} к/с" +
                (if (k != null && usb2(k)) "; $USB2_SOVET" else "")
            k == null -> "ИТОГ: карта не открыта — ${oshibka ?: "нет"}"
            else -> "ИТОГ: карта открыта, кадров нет — ${oshibka ?: v?.oshibka ?: "?"}"
        })
        append(if (z?.rabotaet == true) "; звук идёт (${Pcm.db(z.pik)} дБ)\n" else "; звука нет (${z?.oshibka ?: zvukOshibka ?: if (zvukVkl) "не включился" else "выключен"})\n")
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
        klip?.let { append("Запись игры: ${it.status()}\n") }
        append("Показ: ${"%.0f".format(polka.pokazFps)} к/с, средняя яркость ${Json.num(polka.srednyaya, 3)}" +
            (if (polka.mjpegMs > 0 && video?.rezhim?.vid == UvcVid.MJPEG) ", MJPEG ${Json.num(polka.mjpegMs)} мс/кадр в ${Pokaz.POTOKOV} потоках, не дождались ${polka.mjpegPropuskov}" else "") +
            (oshibkaPokaza?.let { ", ошибка: $it" } ?: "") + "\n")
        zametkaSluzhby?.let { append("$it\n") }
        append("Эфир (Струны): ${Struny.sostoyanie}, отдано кадров ${Struny.kadrov}\n")
        append("Внешние камеры Android (запасной путь): ${vneshnieKamery(ctx)}\n")
        if (video?.rezhim?.vid == UvcVid.MJPEG) append("Разжиматель MJPEG в железе (проба для «Максимума»): ${mjpegZhelezo()}\n")
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

    /**
     * Проба (Александр 22.10: «зачем разжимать процессором»): есть ли в шлеме декодер MJPEG, который Android
     * открывает приложениям, и аппаратный ли он. Есть аппаратный — «Максимум» можно перевести на него.
     */
    fun mjpegZhelezo(): String = try {
        val tipy = setOf("video/mjpeg", "video/x-motion-jpeg", "image/jpeg")
        val naideno = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.lowercase() in tipy } }
            .map { c ->
                val zhel = if (android.os.Build.VERSION.SDK_INT >= 29) (if (c.isHardwareAccelerated) "железо" else "программный") else "?"
                "${c.name} [${c.supportedTypes.filter { it.lowercase() in tipy }.joinToString(",")}; $zhel]"
            }
        if (naideno.isEmpty()) "нет — разжимаем процессором (BitmapFactory в ${Pokaz.POTOKOV} потоках)" else naideno.joinToString("; ")
    } catch (e: Exception) { "не узнать: ${e.javaClass.simpleName}" }

    fun status(): String {
        val k = karta
        return "{\"versiya\":\"$VERSIYA\",\"karta\":${k?.let { Json.str(it.ustr.productName.orEmpty()) } ?: "null"}," +
            "\"video\":${video?.status() ?: "null"},\"klip\":${klip?.status() ?: "null"},\"zvuk\":${zvuk?.status() ?: zvukOshibka?.let { "{\"idyot\":false,\"oshibka\":${Json.str(it)}}" } ?: "null"},\"zvuk_vkl\":$zvukVkl," +
            "\"usb2\":${k?.let { usb2(it) } ?: false},\"ruchnoj\":${ruchnoj != null},\"profil\":\"${profil.imya}\",\"pokaz_fps\":${Json.num(polka.pokazFps)},\"srednyaya\":${Json.num(polka.srednyaya, 3)}," +
            "\"avto_teni\":${Json.num(polka.avtoTeni, 3)},\"mjpeg_ms\":${Json.num(polka.mjpegMs)},\"mjpeg_propuskov\":${polka.mjpegPropuskov},\"efir\":${Json.str(Struny.sostoyanie)},\"efir_kadrov\":${Struny.kadrov},\"kartinka\":${nastrojkiJson()}," +
            "\"oshibka_pokaza\":${oshibkaPokaza?.let { Json.str(it) } ?: "null"}," +
            "\"oshibka\":${oshibka?.let { Json.str(it) } ?: "null"}}"
    }

    fun nastrojkiJson(): String = nastrojki.let {
        "{\"yarkost\":${it.yarkost},\"kontrast\":${it.kontrast},\"nasyshch\":${it.nasyshch},\"gamma\":${it.gamma}," +
            "\"teni\":${it.teni},\"rezkost\":${it.rezkost},\"avtonoch\":${it.avtoNoch},\"lokalno\":${it.lokalno},\"hdrpik\":${it.hdrPik}}"
    }

    private fun zagruzit() {
        val d = dir ?: return
        try { nastrojki = Nastrojki.izStroki(File(d, "kartinka").readText()) } catch (e: Exception) { }
        try {
            val z = File(d, "zvuk").readText().trim().split(' ')
            zvukVkl = z.getOrNull(0) != "0"
            klipVkl = z.getOrNull(2) != "0"                       // до громкости: её сеттер сохраняет файл
            Profil.po(z.getOrNull(3))?.let { profil = it }
            z.getOrNull(1)?.toFloatOrNull()?.let { gromkost = it }
        } catch (e: Exception) { }
    }

    private fun sohranit() {
        val d = dir ?: return
        try { File(d, "kartinka").writeText(nastrojki.vStroku()) } catch (e: Exception) { }
        try { File(d, "zvuk").writeText("${if (zvukVkl) 1 else 0} $gromkost ${if (klipVkl) 1 else 0} ${profil.imya}") } catch (e: Exception) { }
    }

    const val PORT = 8767
    /** = versionName в app/build.gradle.kts (сборка сверяет). */
    const val VERSIYA = "3.1.4"
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
    /** MJPEG: среднее время разжатия кадра (мс) и сколько кадров не дождались свободного потока. */
    @Volatile var mjpegMs = 0f
    @Volatile var mjpegPropuskov = 0L
    private var n = 0L

    fun polozhit(b: ByteArray, dlina: Int) { kadr = Kadr(b, dlina, ++n) }
}
