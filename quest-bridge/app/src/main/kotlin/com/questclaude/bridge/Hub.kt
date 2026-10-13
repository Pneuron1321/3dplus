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
                { plashki }, { t -> plenka.blizhayshiy(t)?.jpeg })
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
        }
    }
}
