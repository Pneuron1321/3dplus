package com.questclaude.bridge

/**
 * Общее для служб глаз (CaptureService) и ушей (EarService): последний кадр,
 * уши и один HTTP-сервер на 127.0.0.1:PORT. Сервер живёт, пока работает хоть одна служба.
 */
object Hub {
    const val PORT = 8766   // 1.0 — 8765: обе версии могут стоять рядом

    val ear = Ear()
    @Volatile var frame: FrameServer.Frame? = null
    @Volatile var capturing = false
    @Volatile var stale = false
    /** Кадр по запросу: "big" или "zoom"; ставит служба глаз, пока она жива. */
    @Volatile var variant: (String) -> ByteArray? = { null }
    /** Кадры захвата за полминуты — для плёнки (/plenka.jpg). */
    val plenka = Plenka()
    /** Когда прозвучал последний вызов «плюс плюс» (часы шлема, мс). */
    @Volatile var lastWakeMs = 0L
    /** Плёнка одной картинкой вокруг момента t; ставит служба глаз, пока она жива. */
    @Volatile var plenkaRisunok: (Long, Int) -> ByteArray? = { _, _ -> null }
    /** Голос только в эфир (ответ донатеру): ставит Efir, пока эфир идёт. */
    @Volatile var vstavka: Vstavka? = null
    /** Голос Авы «вслух» прямо в звук эфира (2.3.9, режим «oba»): ставит Efir; вызов «плюс плюс» его чистит. */
    @Volatile var vstavkaGolos: Vstavka? = null
    /** «Тесно / свободно» (2.3.9): новый на каждый эфир, шагает раз в секунду (Nalozhenie), читает /efir. */
    @Volatile var dispetcher = Dispetcher()
    /** Тепловой статус шлема (PowerManager: 0 — норма … 6), ставит служба глаз; -1 — не знаем. */
    @Volatile var teplo = -1
    /** Микрофон ушей для эфира: EarService отдаёт каждый кусок (16 кГц моно), пока эфир идёт. */
    @Volatile var micTap: ((ShortArray, Int) -> Unit)? = null
    /** Ещё слушатели микрофона (2.5.0): гости (их смесь), субтитры (Rech «ya»). Кусок — свой, можно хранить. */
    val micTapy = java.util.concurrent.CopyOnWriteArrayList<(ShortArray, Int) -> Unit>()
    /** Гости эфира (/gosti…): ставит служба глаз, пока жива. */
    @Volatile var gosti: FrameServer.GostiUpr? = null
    /** Смесь гостей — эфир кладёт в неё фон и берёт гостей в свой звук (2.5.0); ставит Gosti, пока комната открыта. */
    @Volatile var mikser: Mikser? = null
    /** Гостя слышно в шлеме (за последние 1,2 с): голос гостя из динамиков не должен будить Плюса. */
    @Volatile var gostiZvuchat = false
    /** «Клип клип клип» (2.7.8): сохранить клипы камеры и эфира по 3 минуты; ставит служба глаз. Ответ — JSON. */
    @Volatile var klipKlip: (() -> String)? = null
    /** Камера получила ключевой кадр — эфиру тоже (2.7.9: клипы на одно время); ставит служба глаз на время эфира. */
    @Volatile var klyuchevoyEfira: (() -> Unit)? = null
    /** Режим клипа шлема (2.7.9): otdelno | efir | vykl; ставит служба глаз. */
    @Volatile var klipUpr: ((Map<String, String>) -> String)? = null
    /** Оповещение в эфире (2.7.8): новый фоловер, подписка, рейд — плашка сверху; ставит служба глаз. */
    @Volatile var opoveshchenie: Opoveshchenie? = null
    /** Речь для субтитров по каждому голосу (2.5.0): фразы — демону sinhron (/titry/rech). */
    val rechi = Rechi()
    /** Эфир (/efir…): ставит служба глаз, пока жива. */
    @Volatile var efir: FrameServer.EfirUpr? = null
    /** Камера телефона (/kamera…): ставит служба глаз, пока жива. */
    @Volatile var kamera: FrameServer.KameraUpr? = null
    /** Налёт и плашка (/nalet…): ставит служба глаз, пока жива. */
    @Volatile var nalet: FrameServer.NaletUpr? = null
    /** Субтитры, цель доната, итоги вечера (/titr, /cel…, /itogi…): ставит служба глаз, пока жива. */
    @Volatile var plashki: FrameServer.PlashkiUpr? = null
    /** Голос Авы через приложение (POST /say); ставит первая служба — нужна папка кэша. */
    @Volatile var say: FrameServer.Say? = null
    /** Фоновая музыка (2.5.6, /fon…): ставит первая служба, как голос. */
    @Volatile var fon: Fon? = null
    /** Звук карты захвата (2.6.0, /karta…): ставит первая служба, как музыку. */
    @Volatile var karta: KartaZvuk? = null

    /** Ручки картинки карты (2.6.2, /karta/kartinka). */
    @Volatile var kartinka: KartaKartinka? = null
    /** Страница клипов для айфона (2.7.0, /klipy…): ставит первая служба (нужен Context для галереи). */
    @Volatile var klipy: KlipyStranica? = null

    @Synchronized fun kartaZapustit(ctx: android.content.Context, dir: java.io.File) {
        if (karta == null) karta = try { KartaZvuk(ctx.applicationContext, dir).also { it.start() } } catch (e: Exception) { null }
        if (kartinka == null) kartinka = try { KartaKartinka(ctx.applicationContext, dir).also { it.start() } } catch (e: Exception) { null }
        if (klipy == null) klipy = KlipyStranica(KlipyGalereya(ctx), { Kamera.ip() })   // клипы (2.7.0): порт — только по команде
    }

    /** Музыка в звук эфира: ставит Efir, пока эфир идёт (Fon кладёт, эфир берёт). */
    @Volatile var fonLenta: Lenta? = null

    private var server: FrameServer? = null
    private val users = mutableSetOf<String>()

    /** Бросает исключение, если порт занят. */
    @Synchronized fun acquire(who: String) {
        if (server == null) {
            val s = FrameServer(PORT, { frame }, { capturing }, { stale }, { variant(it) }, ear, object : FrameServer.Say {
                override fun play(mp3: ByteArray, mode: String) = say?.play(mp3, mode) ?: "error"
                override fun stop() { say?.stop() }
                override val playing get() = say?.playing ?: false
                override fun rot(sek: Double) { say?.rot(sek) }
                override val govorit get() = say?.govorit ?: false
            }, { t, n -> plenkaRisunok(t, n) }, { lastWakeMs }, { efir }, { n -> Pamyat.hvost(n) }, { kamera }, { nalet },
                { plashki }, { t -> plenka.blizhayshiy(t)?.jpeg }, { gosti }, rechi, { fon }, { karta }, { klipy })
            s.start()
            server = s
        }
        users += who
    }

    @Synchronized fun release(who: String) {
        users -= who
        if (users.isEmpty()) {
            server?.stop()
            server = null
            fon?.stop()                  // мост закрыт — очередь музыки не доигрывать (Александр 17.10)
            klipy?.zakrytVse()           // мост закрыт — и страница клипов
        }
    }
}
