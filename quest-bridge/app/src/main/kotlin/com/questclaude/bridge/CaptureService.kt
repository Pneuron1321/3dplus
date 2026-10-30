package com.questclaude.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Захват вида шлема. Android требует для MediaProjection службу переднего плана
 * типа mediaProjection у любого приложения с targetSdk ≥ 29 (Android 10), не только
 * с Android 14: без неё getMediaProjection() бросает SecurityException
 * (MediaProjectionManagerService.requiresForegroundService, AOSP android14-release).
 *
 * Кадры приходят от композитора с частотой экрана; в JPEG жмём не чаще MAX_FPS
 * и на фоновом потоке — главный поток и батарея свободны. Последний кадр отдаёт
 * FrameServer на 127.0.0.1:PORT.
 */
class CaptureService : Service() {

    companion object {
        private const val KLIP_REZHIM = "klip-rezhim"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.questclaude.bridge.STOP"
        const val PORT = Hub.PORT
        const val MAX_FPS = 2            // для ИИ больше не нужно
        const val OUT_WIDTH = 768        // ширина кадра для Claude: ≈450 токенов за взгляд
        const val CAPTURE_W = 1920
        const val CAPTURE_H = 1080
        const val X2_WIDTH = 1086        // «зум зум»: вдвое больше точек, ≈900 токенов
        const val BIG_WIDTH = 1536       // «зум зум зум»: вчетверо, ≈1800 токенов
        const val STALE_MS = 3000L       // нет кадров дольше — захват «застыл» (системная видеозапись)
        // во время эфира глазам — столько кадров в секунду (в JPEG — MAX_FPS); 2.3.9: было 4 — лишние два
        // кадра 1920×1080 в секунду видеокарта рисовала впустую; в разговоре — 1 (Nalozhenie)
        const val EYE_FPS = 2
        private const val KLYUCH = "efir-klyuch"   // ключ эфира Twitch — в закрытой папке приложения
        private const val KLYUCH_KICK = "efir-klyuch-kick"         // 2.7.0: ключи Kick и YouTube — там же, свои файлы
        private const val KLYUCH_YOUTUBE = "efir-klyuch-youtube"
        private const val ADRES_KICK = "efir-adres-kick"           // адрес приёма Kick (свой у аккаунта, без ключа)
        private const val PLOSHCHADKI = "efir-ploshchadki"         // площадки эфира — запомненные: «twitch,kick»
        private const val MIC = "efir-mic"         // громкость голоса в эфире — запомненная
        private const val NASTROYKI = "efir-nastroyki"   // частота и поток эфира — запомненные (2.6.9): «fps kbps»
        private const val NALET = "nalet-sek"      // налёт: всего секунд в эфире
        private const val POKAZ = "nalozhenie"     // плашки в эфире: «lico nalet radar», 1/0
        private const val CEL = "donat-cel"        // цель доната: сумма, собрано, валюта, показ, на что
        /** Первая цель (Александр 08.10): $700 на Meta Quest 3 для отца. */
        private const val CEL_PERVAYA = "700.0\t0.0\t$\t1\tMeta Quest 3 for Dad"
        private const val TOP = "donat-top"        // топ-донатор: сколько, валюта, показ, ник
        private const val CHAT = "chat-pokaz"      // чат Twitch в кадре: 1 — показывать, 0 — нет
        private const val SEVER = "kompas-sever"   // поправка компаса («север здесь»), если компас настоящий
        private const val CHANNEL = "capture"

        @Volatile var running = false
        @Volatile var status = "Готов."
        /** Камера телефона — для кнопки в приложении (адрес с ключом); живёт, пока идёт захват. */
        @Volatile var kamera: Kamera? = null
        /** Гости эфира (2.5.0) — для строки в приложении; живут, пока идёт захват. */
        @Volatile var gosti: Gosti? = null
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var serving = false

    private var full: Bitmap? = null
    private var small: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastEncodeMs = 0L
    private var arrived = 0L
    private var encoded = 0L
    private val lock = Any()                 // full и размеры кадра: поток захвата и сервер
    private var lastW = 0
    private var lastH = 0
    @Volatile private var lastArrivalMs = 0L
    private var revived = false
    private var razvilka: Razvilka? = null      // эфир: кадр захвата — и в кодировщик, и в глаза
    private var efir: Efir? = null
    /** Круг клипа шлема (2.7.8; 2.7.9 — без окна камеры): живёт со службой глаз — клип можно сохранить и после эфира. */
    private val efirKlip by lazy { KlipZapis(File(cacheDir, "klip-efir")) }
    private var klipShlem: KlipShlem? = null

    /** Как пишется клип шлема (2.7.9): "otdelno" — свой кодировщик без окна камеры (уступает игре), "efir" — сам эфир
     *  (с окном камеры, без лишней нагрузки), "vykl" — клипа шлема нет. Со следующего эфира. */
    private fun klipRezhim(): String = try { File(filesDir, KLIP_REZHIM).readText().trim() } catch (x: Exception) { "" }
        .takeIf { it in setOf("otdelno", "efir", "vykl") } ?: "efir"     // по умолчанию — ноль нагрузки (до «Заплатки»)

    private fun klipUpr(q: Map<String, String>): String {
        q["rezhim"]?.takeIf { it in setOf("otdelno", "efir", "vykl") }?.let { File(filesDir, KLIP_REZHIM).writeText(it) }
        val k = klipShlem
        return "{\"rezhim\":\"${klipRezhim()}\",\"sejchas\":\"${when { k != null -> "otdelno"; efir?.klipVideo == true -> "efir"; else -> "net" }}\"," +
            "\"ekonom\":${k?.ekonom ?: false},\"ekonom_raz\":${k?.ekonomRaz ?: 0},\"kadrov\":${k?.kadrov ?: 0}," +
            "\"sekund\":${efirKlip.sekund},\"so_sleduyushchego_efira\":${q.containsKey("rezhim")}}"
    }

    /** «Клип клип клип»: камера и шлем (эфир; в режиме otdelno — без окна камеры) по 3 минуты в «Фильмы/Plus», на одно
     *  и то же время; ответ — {"kamera":{…},"efir":{…},"sinhron":…,"rashozhdenie_ms":…}. */
    private fun klipKlip(): String {
        val vremya = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val kz = kamera?.klip
        val shlemNet = klipRezhim() == "vykl"
        // 2.7.9: оба круга есть — два клипа на одно и то же время (KlipSinhron): начало — пара ключевых кадров около
        // «3 минуты назад», конец — одно мгновение; имена с одним временем — пара видна в галерее
        if (kz != null && !shlemNet) {
            val t1 = listOfNotNull(kz.konecVremya(), efirKlip.konecVremya()).takeIf { it.size == 2 }?.minOrNull()
            val para = t1?.let { KlipSinhron.vybrat(kz.klyuchevyeVremya(), efirKlip.klyuchevyeVremya(), it - (KlipMp4.MAKS_SEK - 5) * 1_000_000L) }
            if (t1 != null && para != null) {
                // 2.8.0 (Александр 29.10): у камеры звука не надо — звук общий, в клипе эфира (два видео на одно время)
                val k = KlipMp4.sohranitSnimok(this, { kz.snimokVremya(para.kamera, t1) }, "plus-klip-kamera", vremya, zvuk = false)
                val e = KlipMp4.sohranitSnimok(this, { efirKlip.snimokVremya(para.efir, t1) }, "plus-klip-shlem", vremya)
                return "{\"kamera\":$k,\"efir\":$e,\"sinhron\":true,\"rashozhdenie_ms\":${para.rashozhdenieMs}}"
            }
        }
        val efirEst = !shlemNet && efirKlip.sekund > 0                // звук — в клипе эфира; эфира нет — у камеры свой звук
        val k = kz?.let { KlipMp4.sohranit(this, it, KlipMp4.MAKS_SEK - 5, "plus-klip-kamera", zvuk = !efirEst) }
            ?: "{\"oshibka\":\"камера не подключена\"}"
        val e = if (shlemNet) "{\"oshibka\":\"клип шлема выключен (efir klip efir — включить)\"}"
            else KlipMp4.sohranit(this, efirKlip, KlipMp4.MAKS_SEK - 5, "plus-klip-shlem")
        return "{\"kamera\":$k,\"efir\":$e,\"sinhron\":false}"
    }

    private lateinit var nalet: Nalet
    private lateinit var cel: Cel
    private lateinit var top: Top
    private lateinit var chat: Chat
    /** Субтитры и итоги вечера — живут со службой, рисует Nalozhenie во время эфира. */
    private val titry = Titry(yaOtSinhrona = { Hub.rechi.slushaetYa })
    /** Микрофон — в субтитры (Rech «ya»), пока демон sinhron слушает и идёт эфир (2.5.0). */
    private val rechYa: (ShortArray, Int) -> Unit = { c, n -> Hub.rechi.podat("ya", c, n) }
    private val itogi = Itogi()
    private val igra = Igra()
    private var nalozhenie: Nalozhenie? = null   // лицо Плюса и налёт, радар — по углам эфира
    private var kompas: Kompas? = null
    @Volatile private var pokaz = Nalozhenie.Pokaz()
    /** Тепло шлема (2.3.9): отдаёт ли Quest его приложению — проба; статус — в /efir «teplo». */
    private var teploSlushatel: android.os.PowerManager.OnThermalStatusChangedListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        // Сначала служба переднего плана, потом проекция — иначе SecurityException.
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        Pamyat.start(this)
        Pamyat.zapis("глаза: захват начат")

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            data?.let { manager.getMediaProjection(code, it) }
        } catch (e: SecurityException) {
            fail("Система не дала захват: ${e.message}")
            return START_NOT_STICKY
        }
        if (p == null) {
            fail("Нет разрешения на захват.")
            return START_NOT_STICKY
        }
        projection = p

        val thread = HandlerThread("capture").also { it.start() }
        worker = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        // Android 14: колбэк — до createVirtualDisplay, иначе IllegalStateException.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                status = "Захват остановлен системой."
                // 2.8.1 (Александр 30.10: эфир оборвался через 54 мин, а он не понял): уши — Плюсу, тот скажет вслух
                Hub.ear.post("zahvat", if (Hub.efir != null) "oborvan efir" else "oborvan")
                stopSelf()
            }
        }, handler)

        val r = newReader(handler)
        reader = r
        display = p.createVirtualDisplay(
            "QuestClaudeBridge", CAPTURE_W, CAPTURE_H, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )

        try {
            if (Hub.say == null) Hub.say = Golos(cacheDir)
            if (Hub.fon == null) Hub.fon = Fon(cacheDir, java.io.File(filesDir, "fon-nastroyki"))
            Hub.kartaZapustit(this, filesDir)                    // звук карты захвата (2.6.0)
            Hub.acquire("capture")
            serving = true
        } catch (e: Exception) {
            fail("Порт $PORT занят: ${e.message}")
            return START_NOT_STICKY
        }
        Hub.variant = { kind -> variant(kind) }
        Hub.plenkaRisunok = { t, n -> plenka(t, n) }
        Hub.efir = upr
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val gruppa = wifi?.createMulticastLock("plus-kamera")?.apply { setReferenceCounted(false) }
        Struny.zagruzit(filesDir)                       // основа эфира: игра (Мост 3) или вид шлема
        Oformlenie.zagruzit(filesDir)                   // стиль плашек эфира: viz | staryj (2.7.0)
        kamera = Kamera(filesDir, { on -> if (on) gruppa?.acquire() else if (gruppa?.isHeld == true) gruppa.release() },
            File(cacheDir, "klip"))
            .also { k ->
                k.sohranitKlip = { sek -> KlipMp4.sohranit(this, k.klip, sek) }   // клип камеры (2.5.9) — в «Фильмы/Plus»
                k.start(); Hub.kamera = k
            }
        val gostiFayly = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        gosti = Gosti(filesDir) { kamera?.stranicaGosti() }.also { g ->
            g.fayl = { imya -> gostiFayly[imya] ?: try { assets.open("gosti/$imya").use { it.readBytes() }.also { gostiFayly[imya] = it } } catch (e: Exception) { null } }
            kamera?.gostiStranica = { g.stranicaHtml() }
            Hub.gosti = g
            // 2.7.6: комната была открыта до перезапуска моста — открыть снова (туннель gosti отдаст адрес сам)
            if (g.bylaOtkryta) try { g.start() } catch (e: Exception) { }
        }
        Hub.micTapy += rechYa
        // 2.7.8: «клип клип клип» — два клипа по 3 минуты: камера телефона и эфир (что в очках, со слоями), без пережатия
        Hub.klipKlip = { klipKlip() }
        Hub.klipUpr = { q -> klipUpr(q) }
        Hub.opoveshchenie = Opoveshchenie(this) { razvilka }
        nalet = Nalet({ try { File(filesDir, NALET).readText().trim().toLong() } catch (e: Exception) { 0L } },
            { sek -> try { File(filesDir, NALET).writeText(sek.toString()) } catch (e: Exception) { } })
        pokaz = try {
            File(filesDir, POKAZ).readText().trim().split(' ')
                .let { Nalozhenie.Pokaz(it[0] != "0", it.getOrNull(1) != "0", it.getOrNull(2) != "0") }
        } catch (e: Exception) { Nalozhenie.Pokaz() }
        cel = Cel({ try { File(filesDir, CEL).readText() } catch (e: Exception) { CEL_PERVAYA } },
            { t -> try { File(filesDir, CEL).writeText(t) } catch (e: Exception) { } })
        top = Top({ try { File(filesDir, TOP).readText() } catch (e: Exception) { null } },
            { t -> try { File(filesDir, TOP).writeText(t) } catch (e: Exception) { } })
        chat = Chat({ try { File(filesDir, CHAT).readText() } catch (e: Exception) { null } },
            { t -> try { File(filesDir, CHAT).writeText(t) } catch (e: Exception) { } })
        kompas = Kompas(this)
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            Hub.teplo = pm.currentThermalStatus
            teploSlushatel = android.os.PowerManager.OnThermalStatusChangedListener { t ->
                Hub.teplo = t
                Pamyat.zapis("тепло шлема: $t")
            }.also { pm.addThermalStatusListener(mainExecutor, it) }
        } catch (e: Exception) {
            Hub.teplo = -1                                   // Quest не отдаёт — диспетчер смотрит на кадры
        }
        Hub.nalet = naletUpr
        Hub.plashki = plashkiUpr
        Hub.capturing = true
        Hub.stale = false
        running = true
        lastArrivalMs = SystemClock.uptimeMillis()
        handler.postDelayed(watchdog, 1000)
        status = "ЗАХВАТ ИДЁТ\nждём первый кадр…\nкадр: http://127.0.0.1:$PORT/frame.jpg"
        return START_NOT_STICKY
    }

    /**
     * Сторож. Системная видеозапись Quest перенастраивает зеркало, и кадры перестают
     * приходить, а onStop не приходит (найдено 04.10). Тогда пробуем переподключить
     * поверхность (второй createVirtualDisplay на той же проекции Android 14 запрещает);
     * не помогло — уведомление «нажми». /status отдаёт "stale":true.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val silent = SystemClock.uptimeMillis() - lastArrivalMs
            if (silent > STALE_MS && !Hub.stale) {
                Hub.stale = true
                revive()
            } else if (silent > 2 * STALE_MS && !revived) {
                revived = true
                status = "ЗАХВАТ ЗАСТЫЛ\nнажми «Остановить», потом «Начать захват»"
                Hub.ear.post("zahvat", if (Hub.efir != null) "zastyl efir" else "zastyl")   // 2.8.1: Плюс скажет вслух
                getSystemService(NotificationManager::class.java)
                    .notify(1, notification("Захват застыл — открой и нажми «Начать захват»"))
            }
            handler?.postDelayed(this, 1000)
        }
    }

    private fun newReader(h: Handler): ImageReader =
        ImageReader.newInstance(CAPTURE_W, CAPTURE_H, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, h)
        }

    private fun revive() {
        val d = display ?: return
        val h = handler ?: return
        razvilka?.let { rz ->                  // эфир: захват переподключаем к развилке
            d.surface = null
            d.surface = rz.input
            return
        }
        val old = reader
        val r = newReader(h)
        d.surface = r.surface
        reader = r
        old?.close()
    }

    private fun onImage(r: ImageReader) {
        // после revive() в очереди может остаться вызов от закрытого ImageReader
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            arrived++
            lastArrivalMs = SystemClock.uptimeMillis()
            if (Hub.stale) {
                Hub.stale = false
                if (revived) {
                    revived = false
                    getSystemService(NotificationManager::class.java).notify(1, notification())
                }
            }
            val now = SystemClock.uptimeMillis()
            // в эфире кадры глазам идут ровно EYE_FPS раз в секунду — с запасом на дрожь, иначе каждый второй мимо
            val porog = if (razvilka != null) 1000L / MAX_FPS - 150 else 1000L / MAX_FPS
            if (now - lastEncodeMs < porog) return
            lastEncodeMs = now
            encode(image)
        } catch (e: Exception) {
            status = "Ошибка кадра: ${e.message}"
        } finally {
            image.close()
        }
    }

    private fun encode(image: Image) {
        synchronized(lock) { encodeLocked(image) }
    }

    private fun encodeLocked(image: Image) {
        val plane = image.planes[0]
        val stridePx = plane.rowStride / plane.pixelStride     // строка буфера бывает шире кадра
        var f = full
        if (f == null || f.width != stridePx || f.height != image.height) {
            f?.recycle()
            f = Bitmap.createBitmap(stridePx, image.height, Bitmap.Config.ARGB_8888)
            full = f
        }
        plane.buffer.rewind()
        f!!.copyPixelsFromBuffer(plane.buffer)
        lastW = image.width
        lastH = image.height

        val outH = (image.height * OUT_WIDTH / image.width) and 1.inv()
        var s = small
        if (s == null || s.height != outH) {
            s?.recycle()
            s = Bitmap.createBitmap(OUT_WIDTH, outH, Bitmap.Config.ARGB_8888)
            small = s
        }
        Canvas(s!!).drawBitmap(f, Rect(0, 0, image.width, image.height), Rect(0, 0, OUT_WIDTH, outH), paint)

        val jpeg = ByteArrayOutputStream(96 * 1024).also { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        encoded++
        val ms = System.currentTimeMillis()
        Hub.frame = FrameServer.Frame(jpeg, image.width, image.height, ms, encoded)
        Hub.plenka.add(ms, jpeg)
        status = "ЗАХВАТ ИДЁТ\n${image.width}×${image.height} → ${OUT_WIDTH}×$outH\n" +
            "кадров от системы: $arrived · в JPEG: $encoded (${jpeg.size / 1024} КБ)\n" +
            "кадр: http://127.0.0.1:$PORT/frame.jpg" + (efir?.let { "\n" + it.kratko() } ?: "") +
            (kamera?.let { "\n" + it.kratko() } ?: "") +
            (if (::nalet.isInitialized) "\nналёт: ${Nalet.koiny(nalet.vsegoSek)} (за эфир +${Nalet.koiny(nalet.efirSek)})" else "")
    }

    // ---------- эфир ----------

    private val upr = object : FrameServer.EfirUpr {
        override fun start(q: Map<String, String>) = try { naPotoke { efirStart(q) } } catch (e: Exception) { oshibka(e.message ?: "?") }
        override fun stop() = try { naPotoke { efirStop() } } catch (e: Exception) { oshibka(e.message ?: "?") }
        override fun status() = efir?.status(kluchiJson()) ?:
            "{\"state\":\"off\",\"klyuch\":${File(filesDir, KLYUCH).exists()},\"mic\":${micZapomnen()},\"teplo\":${Hub.teplo}," +
                "\"fps_vybor\":${nastroykiZapomneny().first},\"tsel_kbps\":${nastroykiZapomneny().second}," +
                "\"kluchi\":${kluchiJson()},\"adres_kick\":${adresKick() != null}," +
                "\"vybor\":[${ploshchadkiZapomneny().joinToString(",") { "\"$it\"" }}]}"
        override fun key(k: String) = key("twitch", k)
        override fun key(pl: String, k: String) = try { klyuchFile(pl).writeText(k); true } catch (e: Exception) { false }
        override fun adres(pl: String, url: String): String {
            val a = if (pl == "kick") Ploshchadki.adresKick(url) else null
            if (a != null) try { File(filesDir, ADRES_KICK).writeText(a) } catch (e: Exception) { return "{\"adres\":false}" }
            return "{\"adres\":${a != null}}"                   // адрес обратно не повторяем — мало ли что в него вклеили
        }
        override fun mic(g: Float): String {
            val v = Efir.Opts.glush(g)
            try { File(filesDir, MIC).writeText(v.toString()) } catch (e: Exception) { }
            efir?.mikrofon = v
            return efir?.status() ?: "{\"state\":\"off\",\"mic\":$v}"
        }
        override fun nastroyki(q: Map<String, String>): String {
            val (f0, k0) = nastroykiZapomneny()
            val f = Efir.Opts.fps(q["fps"]?.toIntOrNull()) ?: f0
            val k = Efir.Opts.kbps(q["kbps"]?.toIntOrNull()) ?: k0
            try { File(filesDir, NASTROYKI).writeText("$f $k") } catch (e: Exception) { }
            efir?.nastroit(f, k)
            return efir?.status() ?: "{\"state\":\"off\",\"fps_vybor\":$f,\"tsel_kbps\":$k}"
        }
    }

    /** Плашки эфира: налёт, лицо, радар — /nalet, /nalet/upr, скрипты nalet и radar. */
    private val naletUpr = object : FrameServer.NaletUpr {
        override fun status(): String {
            val k = kompas
            return nalet.status().dropLast(1) +
                ",\"lico\":${pokaz.lico},\"pokaz\":${pokaz.nalet},\"radar\":${pokaz.radar},\"v_efire\":${nalozhenie != null}," +
                "\"kompas\":\"${k?.tip ?: "none"}\",\"kurs\":${FrameServer.json(Radar.podpis(k?.kurs, k?.tip ?: "none"))}," +
                "\"datchiki\":${FrameServer.json(k?.spisok() ?: "")}}"
        }
        override fun upr(q: Map<String, String>): String {
            val p = pokaz
            fun da(k: String, b: Boolean) = q[k]?.let { it != "0" } ?: b
            pokaz = Nalozhenie.Pokaz(da("lico", p.lico), da("pokaz", p.nalet), da("radar", p.radar))
            try {
                File(filesDir, POKAZ).writeText(listOf(pokaz.lico, pokaz.nalet, pokaz.radar).joinToString(" ") { if (it) "1" else "0" })
            } catch (e: Exception) { }
            q["koiny"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 && it < 10_000_000 }?.let { nalet.ustanovit(it) }
            if (q["sever"] == "1") kompas?.let { k ->
                k.sever()
                if (k.tip == "absolute") try { File(filesDir, SEVER).writeText(k.popravka.toString()) } catch (e: Exception) { }
            }
            return status()
        }
    }

    /** Субтитры, цель доната, итоги вечера, табло соведущего, топ-донатор — /titr, /cel…, /itogi…, /igra…, /top…. */
    private val plashkiUpr = object : FrameServer.PlashkiUpr {
        override fun titr(q: Map<String, String>) = titry.upr(q)
        override fun cel(q: Map<String, String>?) = if (q == null) cel.status() else cel.upr(q)
        override fun itogi(q: Map<String, String>?) = if (q == null) itogi.status() else itogi.upr(q)
        override fun igra(q: Map<String, String>?) = if (q == null) igra.status() else igra.upr(q)
        override fun top(q: Map<String, String>?) = if (q == null) top.status() else top.upr(q)
        override fun chat(q: Map<String, String>?): String = chatOtvet(if (q == null) chat.status() else chat.upr(q))
        override fun chatPachka(qs: List<Map<String, String>>): String = chatOtvet(chat.pachka(qs))

        private fun chatOtvet(s: String): String {
            val n = nalozhenie                                   // в кадре ли чат на самом деле (2.3.8)
            return s.dropLast(1) + ",\"v_efire\":${n != null},\"na_ekrane\":${n?.chatNaEkrane ?: 0}," +
                "\"mesto\":${razvilka?.chatMesto ?: 0},\"oshibka\":${n?.oshibka?.let { FrameServer.json(it) } ?: "null"}}"
        }
    }

    /** Запомненные частота и поток эфира (нет файла — 30 к/с и 6000 кбит/с, как было). */
    private fun nastroykiZapomneny(): Pair<Int, Int> = try {
        val z = File(filesDir, NASTROYKI).readText().trim().split(' ')
        (Efir.Opts.fps(z[0].toIntOrNull()) ?: Efir.Opts.FPS) to (Efir.Opts.kbps(z[1].toIntOrNull()) ?: Efir.Opts.KBPS)
    } catch (e: Exception) { Efir.Opts.FPS to Efir.Opts.KBPS }

    /** Файл ключа площадки (twitch — прежний файл, как до 2.7.0). */
    private fun klyuchFile(pl: String) = File(filesDir, when (pl) { "kick" -> KLYUCH_KICK; "youtube" -> KLYUCH_YOUTUBE; else -> KLYUCH })

    private fun klyuch(pl: String): String? =
        try { klyuchFile(pl).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

    private fun kluchiJson() = Ploshchadki.kluchiJson { klyuch(it) != null }

    private fun adresKick(): String? = try { Ploshchadki.adresKick(File(filesDir, ADRES_KICK).readText()) } catch (e: Exception) { null }

    /** Запомненный набор площадок (нет файла — Twitch, как было). */
    private fun ploshchadkiZapomneny(): List<String> =
        try { Ploshchadki.razobrat(File(filesDir, PLOSHCHADKI).readText().trim()) } catch (e: Exception) { null } ?: listOf("twitch")

    private fun nastroykiPloshchadok(vybor: List<String>, twitchUrl: String) =
        vybor.map { Ploshchadki.Nastroyka(it, Ploshchadki.adresa(it, twitchUrl, adresKick()), klyuch(it)) }

    private fun micZapomnen() = try { File(filesDir, MIC).readText().trim().toFloat() } catch (e: Exception) { Efir.Opts.MIC }

    private fun oshibka(msg: String) = "{\"state\":\"off\",\"oshibka\":${FrameServer.json(msg)}}"

    /** Выполнить на потоке захвата (там живут display и reader) и дождаться. */
    private fun <T> naPotoke(block: () -> T): T {
        val h = handler ?: throw IllegalStateException("захват не идёт")
        if (Looper.myLooper() == h.looper) return block()
        var r: Result<T>? = null
        val done = CountDownLatch(1)
        h.post { r = runCatching(block); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "захват не ответил" }
        return r!!.getOrThrow()
    }

    private fun efirStart(q: Map<String, String>): String {
        // 2.7.0: площадки — из запроса (запомнить) или запомненные
        val plQ = q["pl"]?.let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }
        val plZ = plQ?.let {
            Ploshchadki.razobrat(it) ?: return efir?.status(kluchiJson())
                ?: oshibka("площадки: twitch, kick, youtube — не больше ${Ploshchadki.MAKS} сразу")
        }
        if (plZ != null) try { File(filesDir, PLOSHCHADKI).writeText(plZ.joinToString(",")) } catch (e: Exception) { }
        val vybor = plZ ?: ploshchadkiZapomneny()
        efir?.let { e ->
            if (plZ != null) {
                val n = nastroykiPloshchadok(vybor, e.twitchUrl)
                if (n.any { it.key != null && it.adresa.isNotEmpty() }) {   // ни одного ключа — набор не трогаем
                    e.ploshchadki(n)                           // эфир идёт — сменить набор на ходу
                    notifyEfir(vybor, null)
                }
            }
            return e.status(kluchiJson())
        }
        val (fps0, kbps0) = nastroykiZapomneny()
        val opts = try { Efir.Opts.iz(q, micZapomnen(), fps0, kbps0) } catch (e: Exception) { return oshibka(e.message ?: "настройки эфира") }
        val nastroyki = nastroykiPloshchadok(vybor, opts.url)
        if (nastroyki.none { it.key != null && it.adresa.isNotEmpty() }) return oshibka(netKlyucha(vybor))
        val p = projection ?: return oshibka("захват не идёт")
        val d = display ?: return oshibka("захват не идёт")
        val h = handler ?: return oshibka("захват не идёт")
        val rz = try { Razvilka(CAPTURE_W, CAPTURE_H) } catch (e: Exception) { return oshibka("видеокарта: ${e.message}") }
        val r = newReader(h)
        try {
            rz.add(r.surface, EYE_FPS, stamp = false)
            rz.glazaSurface = r.surface
            rz.fps(r.surface, EYE_FPS)
        } catch (e: Exception) {
            rz.release(); r.close()
            return oshibka("развилка: ${e.message}")
        }
        d.surface = rz.input
        reader?.close()
        reader = r
        razvilka = rz
        Hub.dispetcher = Dispetcher()                    // «тесно» — заново на каждый эфир
        val e = Efir(p, rz, opts, nastroyki, efirKlip)
        e.klipVideo = klipRezhim() == "efir"
        try {
            e.start()
        } catch (ex: Exception) {
            e.stop()
            vernutPryamo()
            return oshibka("эфир не запустился: ${ex.message}")
        }
        efir = e
        Hub.klyuchevoyEfira = { e.klyuchevoyKlipa(); klipShlem?.klyuchevoy() }
        // 2.7.9: клип шлема — свой кодировщик, слои эфира без окна камеры (не вышло — эфир идёт и без него)
        klipShlem = if (klipRezhim() != "otdelno") null else try {
            KlipShlem(rz, efirKlip, opts.w, opts.h).also { k -> k.tesnoEfiru = { efir?.avto30 == true }; k.start() }
        } catch (x: Exception) {
            Pamyat.zapis("клип шлема не запустился: ${x.message} — клип шлема будет из эфира"); e.klipVideo = true; null
        }
        kamera?.podklyuchit(rz)
        Struny.podklyuchit(rz)
        gosti?.tesno = { efir?.tesnoSet == true || Hub.dispetcher.igre }
        gosti?.podklyuchit(rz)
        Hub.rechi.efir = true
        val k = kompas ?: Kompas(this).also { kompas = it }
        nalozhenie = Nalozhenie(this, nalet, k, rz, { efir }, { pokaz }, titry, cel, itogi, igra, top, chat,
            glaza = { f -> reader?.surface?.let { s -> razvilka?.fps(s, f) } }, gosti = gosti).also { it.start() }
        if (k.tip == "absolute") k.popravka = try { File(filesDir, SEVER).readText().trim().toFloat() } catch (e: Exception) { 0f }
        notifyEfir(vybor, opts)
        return e.status(kluchiJson())
    }

    /** Нет ни одного ключа (или адреса Kick) у выбранных площадок — что сделать. */
    private fun netKlyucha(vybor: List<String>): String =
        if (vybor == listOf("twitch")) "нет ключа эфира: в Termux `kluch twitch`, потом `efir`"
        else "нет ключа эфира для " + vybor.joinToString(", ") + ": в Termux `kluch " + vybor.first() + "`" +
            (if ("kick" in vybor && adresKick() == null) " (для Kick — и адрес: kluch kick с адресом в буфере)" else "") + ", потом `efir`"

    private var efirRazmer = ""

    private fun notifyEfir(vybor: List<String>, opts: Efir.Opts?) {
        if (opts != null) efirRazmer = "${opts.w}×${opts.h}"
        val pl = vybor.joinToString(" + ") { Ploshchadki.NAZVANIE[it] ?: it }
        getSystemService(NotificationManager::class.java).notify(1, notification("ЭФИР · $pl · $efirRazmer · глаза Плюса работают"))
    }

    private fun efirStop(): String {
        nalozhenie?.stop()
        nalozhenie = null
        Hub.rechi.efir = false
        kamera?.otklyuchit()
        Struny.otklyuchit()
        gosti?.otklyuchit()
        klipShlem?.stop(); klipShlem = null
        Hub.klyuchevoyEfira = null
        efir?.stop()
        efir = null
        if (razvilka != null) vernutPryamo()
        if (running) getSystemService(NotificationManager::class.java).notify(1, notification())
        return "{\"state\":\"off\"}"
    }

    /** Без эфира: захват снова прямо в ImageReader глаз, развилку — прочь. */
    private fun vernutPryamo() {
        val d = display
        val h = handler
        val old = reader
        if (d != null && h != null) {
            val r = newReader(h)
            d.surface = r.surface
            reader = r
        }
        razvilka?.release()
        razvilka = null
        if (old !== reader) old?.close()
    }

    /**
     * Тот же момент, что и последний кадр 768 px, но крупнее — по запросу, на потоке
     * сервера. "x2" — весь вид 1086 px (вдвое больше точек), "big" — 1536 px (вчетверо),
     * "zoom" — центр вида (куда смотрит голова) в полном разрешении, 960×540.
     */
    private fun variant(kind: String): ByteArray? = synchronized(lock) {
        val f = full ?: return null
        val w = lastW
        val h = lastH
        if (w == 0 || h == 0) return null
        val src = if (kind == "zoom") Rect(w / 4, h / 4, w * 3 / 4, h * 3 / 4) else Rect(0, 0, w, h)
        val outW = when (kind) { "zoom" -> src.width(); "x2" -> X2_WIDTH; else -> BIG_WIDTH }
        val outH = (src.height() * outW / src.width()) and 1.inv()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(f, src, Rect(0, 0, outW, outH), paint)
        val jpeg = ByteArrayOutputStream(256 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        out.recycle()
        jpeg
    }

    /**
     * Плёнка одной картинкой 1536×864 (≈1800 токенов, как четыре обычных кадра):
     * двенадцать кадров 384×216 по порядку слева направо, сверху вниз (−3,0 … +2,5 с)
     * и последний (+3,0 с) обычного размера 768×432 в правом нижнем углу. Подписи
     * времени внизу слева. Момент вызова (0 с) — в красной рамке. Кадра не было —
     * серая клетка «нет кадра».
     * Короткая («чек чек», 7 кадров −1,5 … +1,5 с) — 1536×432 (≈900 токенов): четыре
     * кадра 384×216 сверху, три снизу, в пустой клетке — что это за плёнка.
     */
    private fun plenka(t: Long, n: Int = 13): ByteArray? {
        val pol = Plenka.polovina(n)
        val kadry = Hub.plenka.around(t, pol, pol)
        if (kadry.all { it == null }) return null
        val tw = OUT_WIDTH / 2
        val th = tw * 9 / 16
        val korotkaya = kadry.size <= 8
        val out = Bitmap.createBitmap(tw * 4, th * (if (korotkaya) 2 else 4), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.DKGRAY)
        val obvodka = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK
            style = Paint.Style.STROKE; strokeWidth = 5f
        }
        val bukvy = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
        val ramka = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.RED }
        val melko = BitmapFactory.Options().apply { inSampleSize = 2 }      // 768 → 384 прямо при чтении
        val posl = kadry.lastIndex
        val vyzov = (pol / Plenka.STEP_MS).toInt()
        kadry.forEachIndexed { i, k ->
            val krupno = !korotkaya && i == posl
            val dst = if (krupno) Rect(tw * 2, th * 2, tw * 4, th * 4) else {
                val slot = if (korotkaya || i < 8) i else 8 + (i - 8) / 2 * 4 + (i - 8) % 2  // обход большого угла
                Rect(slot % 4 * tw, slot / 4 * th, slot % 4 * tw + tw, slot / 4 * th + th)
            }
            val b = k?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size, if (krupno) null else melko) }
            if (b != null) {
                c.drawBitmap(b, null, dst, paint)
                b.recycle()
            }
            var pod = Plenka.podpis(i, pol)
            if (i == vyzov) pod += " · вопрос"
            if (i == posl) pod += " · сейчас"
            if (b == null) pod += " · нет кадра"
            // подпись внизу слева: вверху у игр обычно здоровье и карта (проба 05.10: подпись
            // сверху закрыла полоску здоровья, и модель ошиблась на полсекунды)
            c.drawText(pod, dst.left + 8f, dst.bottom - 10f, obvodka)
            c.drawText(pod, dst.left + 8f, dst.bottom - 10f, bukvy)
            if (i == vyzov) c.drawRect(dst.left + 3f, dst.top + 3f, dst.right - 3f, dst.bottom - 3f, ramka)
        }
        if (korotkaya) {                                   // пустая восьмая клетка
            val x = tw * 3 + 12f
            for ((j, s) in listOf("чек чек", "7 кадров через 0,5 с", "−1,5 … +1,5 с").withIndex())
                c.drawText(s, x, th + 40f + j * 34f, bukvy)
        }
        val jpeg = ByteArrayOutputStream(256 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        out.recycle()
        return jpeg
    }

    private fun fail(message: String) {
        status = message
        stopSelf()
    }

    private fun notification(text: String = "Захват вида шлема · порт $PORT"): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Захват вида", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Quest Claude Bridge 2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Pamyat.trim(level)
    }

    override fun onDestroy() {
        Pamyat.zapis("глаза: захват остановлен (${status.lineSequence().firstOrNull().orEmpty()})")
        running = false
        Hub.capturing = false
        Hub.stale = false
        Hub.variant = { null }
        Hub.plenkaRisunok = { _, _ -> null }
        handler?.removeCallbacks(watchdog)
        Hub.efir = null
        Hub.kamera = null
        Hub.micTapy -= rechYa
        Hub.rechi.efir = false
        Hub.gosti = null
        Hub.klipKlip = null
        Hub.klipUpr = null
        Hub.opoveshchenie = null
        gosti?.let { it.otklyuchit(); it.stop(zabyt = false) }
        gosti = null
        Hub.nalet = null
        Hub.plashki = null
        teploSlushatel?.let { try { getSystemService(android.os.PowerManager::class.java).removeThermalStatusListener(it) } catch (e: Exception) { } }
        teploSlushatel = null
        nalozhenie?.stop()
        nalozhenie = null
        kamera?.stop()
        kamera = null
        klipShlem?.stop(); klipShlem = null
        efir?.stop()
        efir = null
        razvilka?.let { display?.surface = null; it.release() }
        razvilka = null
        if (serving) Hub.release("capture")
        serving = false
        display?.release()
        reader?.close()
        projection?.stop()
        worker?.quitSafely()
        synchronized(lock) {
            full?.recycle()
            small?.recycle()
            full = null; small = null
        }
        display = null; reader = null; projection = null; worker = null; handler = null
        if (status.startsWith("ЗАХВАТ")) status = "Остановлено."
        super.onDestroy()
    }
}
