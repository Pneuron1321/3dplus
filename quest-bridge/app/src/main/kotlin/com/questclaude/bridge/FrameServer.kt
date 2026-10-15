package com.questclaude.bridge

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Tiny HTTP server for the headset view and ears. Plain Kotlin/JVM on purpose:
 * no Android classes, so it is tested on a desktop JVM.
 *
 *   GET /frame.jpg           -> latest JPEG, 768 px (404 until the first frame)
 *   GET /frame.jpg?size=x2   -> same moment, twice the pixels (1086 px) — «зум зум»
 *   GET /frame.jpg?size=big  -> same moment, 1536 px, four times — «зум зум зум»
 *   GET /frame.jpg?zoom=2    -> centre of the view at full resolution (960×540) — «зум зум центр»
 *   GET /frame.jpg?t=MS      -> the film frame nearest to moment MS (last 30 s, 768 px; 2.3.9) — the call
 *                              frame for «посмотри», without a snapshot on every «плюс плюс»
 *   GET /plenka.jpg[?t=MS&n=7|13] -> «плёнка» одной картинкой: 13 кадров от t−3 с до t+3 с
 *                                («зум чек»), 7 — от t−1,5 с до t+1,5 с («чек чек»)
 *                              через 0,5 с (t — момент вызова; без t — последний «плюс
 *                              плюс»); если кадры «после» ещё снимаются — ждёт их
 *   GET /status              -> JSON: capturing, frame size, count, age, stale, ears; umeet — what this app
 *                              can (2.3.9: kadr_t, chat_pachka), scripts check it before using new calls
 *   GET /ear[?after=N][&wait=S][&plus=1] -> JSON: next heard event after N (waits up to S s
 *                              for it), or the last id; ms — when it happened;
 *                              plus=1 — ushi says Claude (tmux «plus») is alive,
 *                              strim=1 — the stream relay (retr) has fresh frames
 *   GET /ear/wav?id=N        -> the phrase as WAV (16 kHz mono), for Whisper in Termux
 *   GET /ear/set[?pause=S&max=S] -> set/read end-of-phrase silence and max phrase length, s
 *                               (and dosluh=S, db=N: the follow-up window after Plus answers, 2.3.1)
 *   GET /ear/dosluh          -> golos daemon: Plus finished an answer — listen 1 s without "плюс плюс"
 *   POST /say?mode=uho|vsluh|oba -> play the MP3 body (Ava's voice), reply when it ends:
 *                              {"end":"done|stopped|error"}; uho — not captured by the stream; oba (2.3.9) — into
 *                              the headset like uho and straight into our stream's sound
 *   GET /say/stop, /say/status -> stop the voice; {"playing":…,"rezhimy":[…]}
 *   POST /chat/pachka        -> chat for the stream overlay in one batch (2.3.9): each body line is a /chat/upr query
 *   GET /efir                -> эфир: {"state":"off|podklyuchayus|efir|obryv", kbps, sec, zvuk, oshibka…}
 *   GET /efir/start[?size=720|1080&fps=&kbps=] -> начать эфир на Twitch (ключ — с /efir/key)
 *   GET /efir/stop           -> закончить эфир
 *   GET /efir/mic?g=3        -> громкость голоса в эфире (0,5–8; приложение запоминает)
 *   POST /efir/key           -> тело — ключ эфира (из `kluch twitch`, через `efir`); обратно не отдаётся
 *   GET /pamyat[?n=N]        -> хвост замера памяти (свободно у шлема, сколько держит приложение)
 *   GET /kamera              -> камера телефона: {"state":"vykl|zhdu|idyot", razmer, fps, kbps, v_efire, vid, gde,
 *                              dolya, povorot, vplotnuyu, sboku, adres (без ключа), oshibka}
 *   GET /kamera/vid?v=ugol|krupno|skryt&gde=pn|ln|pv|lv&r=0.25 -> окно камеры в эфире (приложение запоминает);
 *                              pov=0|90|180|270 — повернуть картинку по часовой, kray=0 — вплотную снизу (сверху),
 *                              1 — с отступом 3 % высоты; bok=4 — отступ сбоку, % ширины (0 — вплотную; 2.3.8)
 *   GET /kamera/stranica     -> открыть на 2 минуты страницу для айфона (http://plus.local:8770): {"adres","ip","sek"}
 *   GET /nalet               -> плашки эфира: {"vsego":"123,45","za_efir":"1,20",…,"lico","pokaz","radar",
 *                              "telefon","snimaet","kompas":"absolute|relative|none","kurs":"247° WSW","datchiki"}
 *   GET /nalet/upr?lico=0|1&pokaz=0|1&radar=0|1&telefon=0|1&sever=1&koiny=123.45 -> показать/скрыть лицо,
 *                              налёт, радар; Плюс снимает телефоном (2.3.5); «север здесь»; перенести налёт
 *   GET /gosti               -> гости эфира (2.5.0): комната, звук, видео, сеть — без ключей и адреса
 *   GET /gosti/start, /gosti/stop -> открыть / закрыть комнату (сервер 127.0.0.1:8771 для туннеля)
 *   GET /gosti/upr?chto=vpustit|otklonit|vygnat|skryt|pokazat|zaglushit|vklyuchit|novaya|vpusk|mest&kto=ИМЯ|id&dop=…
 *   POST /gosti/adres        -> тело — адрес туннеля https://….trycloudflare.com (пусто — нет туннеля); обратно не отдаётся
 *   GET /gosti/ssylki        -> открыть на 2 минуты страницу ссылок для айфона (plus.local:8770/gosti)
 *   GET /gosti/sobytiya?posle=N&zhdat=S -> кто ждёт, вошёл, вышел (для голоса в ухо), ждать до S с
 *   GET /gosti/nastroyka?shlem=0.8&efir=1&eho=0.25&eho_db=10&kartinka=0|1
 *   GET /titry/rech?zhdat=S  -> следующая фраза для субтитров (WAV 16 кГц; X-Golos, X-Nachalo, X-Tihih, X-Db), 204 — нет
 *   GET /titry/upr?ya=0|1&igra=0|1&gosti=0|1&gde=u_plitki|vnizu -> какие голоса слушать, где субтитры гостей
 *
 * Listens on 127.0.0.1 only: the headset view is not exposed to the Wi-Fi.
 * The laptop reaches it through `adb forward tcp:8766 tcp:8766`, and apps on
 * the headset (Termux) through localhost.
 */
class FrameServer(
    private val port: Int,
    private val frame: () -> Frame?,
    private val capturing: () -> Boolean,
    private val stale: () -> Boolean = { false },
    private val variant: (String) -> ByteArray? = { null },
    private val ear: Ear? = null,
    private val say: Say? = null,
    private val plenka: (Long, Int) -> ByteArray? = { _, _ -> null },
    private val lastWake: () -> Long = { 0L },
    private val efir: () -> EfirUpr? = { null },
    private val pamyat: (Int) -> String = { "" },
    private val kamera: () -> KameraUpr? = { null },
    private val nalet: () -> NaletUpr? = { null },
    private val plashki: () -> PlashkiUpr? = { null },
    /** Кадр плёнки, ближайший к моменту (мс), — /frame.jpg?t= (2.3.9). */
    private val kadrV: (Long) -> ByteArray? = { null },
    /** Гости эфира (2.5.0). */
    private val gosti: () -> GostiUpr? = { null },
    /** Речь для субтитров по каждому голосу (2.5.0). */
    private val rechi: Rechi? = null,
) {
    class Frame(val jpeg: ByteArray, val width: Int, val height: Int, val timeMs: Long, val count: Long)

    /** Голос Авы через приложение (Android MediaPlayer — Golos.kt; в тестах — подделка). */
    interface Say {
        /** Играет MP3 и ждёт конца: "done", "stopped" (stop() или вызов) или "error". */
        fun play(mp3: ByteArray, mode: String): String
        fun stop()
        val playing: Boolean
        /** Ава говорит сама (play-audio Termux, «вслух»): рот лица в эфире — sek секунд; 0 — замолчала. */
        fun rot(sek: Double) {}
        /** Говорит ли Ава сейчас хоть как (приложение, Termux, только эфир) — соведущий ждёт тишины. */
        val govorit: Boolean get() = playing
    }

    /** Эфир из приложения (CaptureService + Efir; в тестах — подделка). Ответы — JSON. */
    interface EfirUpr {
        fun start(q: Map<String, String>): String
        fun stop(): String
        fun status(): String
        /** Сохранить ключ эфира; false — не похоже на ключ. */
        fun key(k: String): Boolean
        /** Громкость голоса в эфире (и запомнить на следующий). */
        fun mic(g: Float): String
    }

    /** Налёт и плашка в эфире (CaptureService; в тестах — подделка). Ответы — JSON. */
    interface NaletUpr {
        fun status(): String
        /** lico, pokaz — 0/1; koiny — перенести налёт (всего). */
        fun upr(q: Map<String, String>): String
    }

    /** Камера телефона в эфире (Kamera; в тестах — подделка). Ключ камеры сюда не отдаётся. */
    interface KameraUpr {
        fun status(): String
        /** Окно: v (ugol|krupno|skryt), gde (pn|ln|pv|lv), r (доля ширины 0,1–0,5). */
        fun vid(q: Map<String, String>): String
        /** Открыть на 2 минуты страницу для айфона; ответ — её адреса (без ключа). */
        fun stranica(): String = "{\"oshibka\":\"нет страницы\"}"
    }

    /**
     * Субтитры, цель доната и итоги вечера в эфире (CaptureService; в тестах — подделка).
     * q == null — только состояние. Ответы — JSON.
     */
    interface PlashkiUpr {
        fun titr(q: Map<String, String>): String
        fun cel(q: Map<String, String>?): String
        fun itogi(q: Map<String, String>?): String
        /** Табло соведущего (ставки, вызов): q == null — состояние. */
        fun igra(q: Map<String, String>?): String = "{}"
        /** Топ-донатор (2.3.0): q == null — состояние. */
        fun top(q: Map<String, String>?): String = "{}"
        /** Чат Twitch в кадре (2.3.4): q == null — состояние. */
        fun chat(q: Map<String, String>?): String = "{}"
        /** Пачка сообщений чата (2.3.9): каждое — как /chat/upr. */
        fun chatPachka(qs: List<Map<String, String>>): String {
            var otvet = chat(null)
            for (q in qs) otvet = chat(q)
            return otvet
        }
    }

    /** Гости эфира (Gosti; в тестах — подделка). Ответы — JSON; ключи и адрес туннеля сюда не попадают. */
    interface GostiUpr {
        fun status(): String
        fun start(): String
        fun stop(): String
        fun upr(q: Map<String, String>): String
        fun adres(u: String?): String
        fun ssylki(): String
        fun sobytiya(posle: Long, zhdatMs: Long): String
        fun nastroyka(q: Map<String, String>): String
    }

    companion object {
        /** Громкость голоса по умолчанию (Efir.Opts.MIC; здесь — без Android). */
        const val MIC_EFIR = 3f
        /** Что умеет это приложение сверх 2.3.8 — /status «umeet» (скрипты проверяют перед новым). */
        const val UMEET = "[\"kadr_t\",\"chat_pachka\",\"gosti\",\"titry_rech\"]"
        /** Как умеет звучать голос — /say/status «rezhimy» (голос-демон: «oba» — «вслух» через приложение). */
        const val REZHIMY = "[\"uho\",\"vsluh\",\"efir\",\"oba\"]"

        /** Строки «k=v&k=v» (как query) — в словари; пустые строки — мимо. */
        fun stroki(telo: String): List<Map<String, String>> = telo.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { l -> l.split('&').mapNotNull { val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0] to kv[1] else null }.toMap() }

        /** Явно IPv4: на Android getLoopbackAddress() — это ::1, и curl к 127.0.0.1 не попадал. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        fun json(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c < ' ' -> b.append(String.format("\\u%04x", c.code))
                else -> b.append(c)
            }
            return b.append('"').toString()
        }
    }

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var acceptor: Thread? = null

    val boundAddress: InetAddress? get() = socket?.inetAddress

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(LOOPBACK, port))
        socket = s
        acceptor = thread(name = "frame-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: SocketException) { break }
                thread(name = "frame-client", isDaemon = true) { serve(client) }
            }
        }
    }

    /** Порт свободен, когда stop() вернулся: JVM отпускает сокет, только когда
     *  поток, ждущий в accept(), проснётся, — поэтому ждём этот поток. */
    fun stop() {
        socket?.close()
        acceptor?.join(2000)
        socket = null
        acceptor = null
    }

    private fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 5000
        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        var length = 0
        while (true) {                                  // заголовки: нужна только длина тела
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val target = request.split(' ').getOrNull(1) ?: ""
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val out = c.getOutputStream()
        when (path) {
            "/frame.jpg" -> {
                val kind = when {
                    query["size"] == "big" -> "big"
                    query["size"] == "x2" -> "x2"
                    query["zoom"] == "2" -> "zoom"
                    else -> null
                }
                // кадр момента — из плёнки (2.3.9); маленькое t — не момент, а «не кэшируй» (как было)
                val t = query["t"]?.toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
                if (t != null && kind == null) {
                    val jpeg = kadrV(t)
                    if (jpeg == null) reply(out, 404, "text/plain", "no film frame near $t".toByteArray())
                    else reply(out, 200, "image/jpeg", jpeg)
                } else if (kind == null) {
                    val f = frame()
                    if (f == null) reply(out, 404, "text/plain", "no frame yet".toByteArray())
                    else reply(out, 200, "image/jpeg", f.jpeg)
                } else {
                    val jpeg = variant(kind)
                    if (jpeg == null) reply(out, 503, "text/plain", "no frame for $kind".toByteArray())
                    else reply(out, 200, "image/jpeg", jpeg)
                }
            }
            "/plenka.jpg" -> {
                val now = System.currentTimeMillis()
                val n = query["n"]?.toIntOrNull()?.takeIf { it in Plenka.KADROV } ?: 13
                val pol = Plenka.polovina(n)
                val t = query["t"]?.toLongOrNull() ?: lastWake().takeIf { it > 0 } ?: (now - pol)
                val zhdat = t + pol + 300 - now                  // кадры «после» ещё снимаются
                if (zhdat > 0) Thread.sleep(zhdat.coerceAtMost(4000))
                val jpeg = plenka(t, n)
                if (jpeg == null) reply(out, 503, "text/plain", "no frames around $t (capture off?)".toByteArray())
                else reply(out, 200, "image/jpeg", jpeg)
            }
            "/status" -> {
                val f = frame()
                val age = if (f == null) -1 else System.currentTimeMillis() - f.timeMs
                val json = "{\"capturing\":${capturing()},\"width\":${f?.width ?: 0},\"height\":${f?.height ?: 0}," +
                    "\"frames\":${f?.count ?: 0},\"ageMs\":$age,\"stale\":${stale()}," +
                    "\"ear\":\"${ear?.state ?: "off"}\",\"micDb\":${ear?.micDb ?: -1},\"lastWakeMs\":${lastWake()}" +
                    ",\"dosluh\":${ear?.dosluh?.status() ?: "null"},\"umeet\":$UMEET}"
                reply(out, 200, "application/json", json.toByteArray())
            }
            "/ear" -> {
                val e = ear
                fun seen() {
                    if (query["plus"] == "1") e?.plusSeenMs = System.currentTimeMillis()
                    if (query["strim"] == "1") e?.strimSeenMs = System.currentTimeMillis()
                }
                seen()
                val after = query["after"]?.toLongOrNull()
                val waitMs = ((query["wait"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 4.0) * 1000).toLong()
                val ev = if (e == null || after == null) null else e.after(after, waitMs)
                seen()                                      // ждали — Плюс всё ещё жив
                val json = if (ev == null) "{\"id\":${e?.lastId() ?: 0},\"kind\":\"none\",\"state\":\"${e?.state ?: "off"}\"}"
                    else "{\"id\":${ev.id},\"kind\":\"${ev.kind}\",\"text\":${json(ev.text)},\"audio\":${ev.audio != null}," +
                        "\"stop\":${ev.stop},\"ms\":${ev.ms},\"lead\":${ev.lead},\"how\":${json(ev.how)}}"
                reply(out, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
            }
            "/ear/wav" -> {
                val a = query["id"]?.toLongOrNull()?.let { ear?.get(it) }?.audio
                if (a == null) reply(out, 404, "text/plain", "no audio".toByteArray())
                else reply(out, 200, "audio/wav", Ear.wav(a))
            }
            "/ear/set" -> {
                val e = ear
                if (e == null) reply(out, 404, "text/plain", "no ears".toByteArray())
                else {
                    e.set(query["pause"]?.toFloatOrNull(), query["max"]?.toFloatOrNull(),
                        query["dosluh"]?.toFloatOrNull(), query["db"]?.toIntOrNull())
                    reply(out, 200, "application/json", e.nastroyki().toByteArray())
                }
            }
            "/ear/dosluh" -> {                     // голос-демон: договорила ответ — слушай секунду
                val e = ear
                if (e == null) reply(out, 404, "text/plain", "no ears".toByteArray())
                else {
                    e.dosluh.otkryt()
                    reply(out, 200, "application/json", e.dosluh.status().toByteArray())
                }
            }
            "/efir", "/efir/start", "/efir/stop", "/efir/mic" -> {
                val e = efir()
                if (e == null) reply(out, 503, "application/json", "{\"state\":\"off\",\"oshibka\":\"захват не идёт\"}".toByteArray())
                else {
                    val json = when (path) {
                        "/efir/start" -> e.start(query)
                        "/efir/stop" -> e.stop()
                        "/efir/mic" -> e.mic(query["g"]?.toFloatOrNull() ?: MIC_EFIR)
                        else -> e.status()
                    }
                    reply(out, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
            }
            "/kamera", "/kamera/vid", "/kamera/stranica" -> {
                val k = kamera()
                if (k == null) reply(out, 503, "application/json", "{\"state\":\"vykl\",\"oshibka\":\"захват не идёт\"}".toByteArray())
                else reply(out, 200, "application/json; charset=utf-8",
                    when (path) {
                        "/kamera/vid" -> k.vid(query)
                        "/kamera/stranica" -> k.stranica()
                        else -> k.status()
                    }.toByteArray(Charsets.UTF_8))
            }
            "/titr", "/cel", "/cel/upr", "/itogi", "/itogi/upr", "/igra", "/igra/upr", "/top", "/top/upr",
            "/chat", "/chat/upr" -> {
                val p = plashki()
                if (p == null) reply(out, 503, "application/json", "{\"oshibka\":\"нет захвата\"}".toByteArray())
                else reply(out, 200, "application/json", when (path) {
                    "/titr" -> p.titr(query)
                    "/cel" -> p.cel(null)
                    "/cel/upr" -> p.cel(query)
                    "/itogi" -> p.itogi(null)
                    "/igra" -> p.igra(null)
                    "/igra/upr" -> p.igra(query)
                    "/top" -> p.top(null)
                    "/top/upr" -> p.top(query)
                    "/chat" -> p.chat(null)
                    "/chat/upr" -> p.chat(query)
                    else -> p.itogi(query)
                }.toByteArray(Charsets.UTF_8))
            }
            "/chat/pachka" -> {
                val p = plashki()
                val telo = if (length in 1..200_000) CharArray(length).let { ch ->
                    var got = 0
                    while (got < length) { val n = reader.read(ch, got, length - got); if (n < 0) break; got += n }
                    String(ByteArray(got) { ch[it].code.toByte() }, Charsets.UTF_8)
                } else ""
                if (p == null) reply(out, 503, "application/json", "{\"oshibka\":\"нет захвата\"}".toByteArray())
                else reply(out, 200, "application/json", p.chatPachka(stroki(telo)).toByteArray(Charsets.UTF_8))
            }
            "/nalet", "/nalet/upr" -> {
                val n = nalet()
                if (n == null) reply(out, 503, "application/json", "{\"oshibka\":\"захват не идёт\"}".toByteArray())
                else reply(out, 200, "application/json; charset=utf-8",
                    (if (path == "/nalet/upr") n.upr(query) else n.status()).toByteArray(Charsets.UTF_8))
            }
            "/gosti", "/gosti/start", "/gosti/stop", "/gosti/upr", "/gosti/ssylki", "/gosti/sobytiya", "/gosti/nastroyka" -> {
                val g = gosti()
                if (g == null) reply(out, 503, "application/json", "{\"idyot\":false,\"oshibka\":\"захват не идёт\"}".toByteArray(Charsets.UTF_8))
                else {
                    if (path == "/gosti/sobytiya") c.soTimeout = 30_000
                    val json = when (path) {
                        "/gosti/start" -> g.start()
                        "/gosti/stop" -> g.stop()
                        "/gosti/upr" -> g.upr(query)
                        "/gosti/ssylki" -> g.ssylki()
                        "/gosti/sobytiya" -> g.sobytiya(query["posle"]?.toLongOrNull() ?: 0L,
                            ((query["zhdat"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 25.0) * 1000).toLong())
                        "/gosti/nastroyka" -> g.nastroyka(query)
                        else -> g.status()
                    }
                    reply(out, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
            }
            "/gosti/adres" -> {
                val g = gosti()
                val body = if (length in 1..600) CharArray(length).let { ch ->
                    var got = 0
                    while (got < length) { val n = reader.read(ch, got, length - got); if (n < 0) break; got += n }
                    String(ch, 0, got).trim()
                } else ""
                if (g == null) reply(out, 503, "application/json", "{\"ok\":false}".toByteArray())
                else reply(out, 200, "application/json", g.adres(body.ifEmpty { null }).toByteArray(Charsets.UTF_8))
            }
            "/titry/rech" -> {
                val r = rechi
                val zhdat = ((query["zhdat"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 25.0) * 1000).toLong()
                val f = r?.sleduyushaya(zhdat)
                if (f == null) reply(out, 204, "text/plain", ByteArray(0))
                else reply(out, 200, "audio/wav", Rechi.wav(f.pcm), "X-Golos: ${f.golos}\r\nX-Nachalo: ${f.nachaloMs}\r\n" +
                    "X-Tihih: ${String.format(java.util.Locale.ROOT, "%.3f", f.tihih)}\r\nX-Db: ${String.format(java.util.Locale.ROOT, "%.1f", f.db)}\r\n")
            }
            "/titry/upr" -> {
                val r = rechi
                if (r == null) reply(out, 404, "text/plain", "no rechi".toByteArray())
                else reply(out, 200, "application/json; charset=utf-8", r.upr(query).toByteArray(Charsets.UTF_8))
            }
            "/pamyat" -> reply(out, 200, "text/plain; charset=utf-8",
                pamyat(query["n"]?.toIntOrNull() ?: 60).toByteArray(Charsets.UTF_8))
            "/efir/key" -> {
                val e = efir()
                val body = if (length in 1..1000) CharArray(length).let { ch ->
                    var got = 0
                    while (got < length) { val n = reader.read(ch, got, length - got); if (n < 0) break; got += n }
                    String(ByteArray(got) { ch[it].code.toByte() }, Charsets.UTF_8).trim()
                } else ""
                val ok = e != null && body.length in 8..300 && body.none { it.isWhitespace() || it < ' ' } && e.key(body)
                reply(out, if (ok) 200 else if (e == null) 503 else 400, "application/json", "{\"key\":$ok}".toByteArray())
            }
            "/say" -> {
                val g = say
                if (g == null || length <= 0 || length > 8_000_000) reply(out, 404, "text/plain", "no voice".toByteArray())
                else {
                    val chars = CharArray(length)
                    var got = 0
                    c.soTimeout = 15000
                    while (got < length) {
                        val n = reader.read(chars, got, length - got)
                        if (n < 0) break
                        got += n
                    }
                    val mp3 = ByteArray(got) { chars[it].code.toByte() }   // ISO-8859-1: символ = байт
                    c.soTimeout = 0                                         // играет, сколько длится
                    val end = g.play(mp3, when (query["mode"]) { "uho" -> "uho"; "efir" -> "efir"; "oba" -> "oba"; else -> "vsluh" })
                    reply(out, 200, "application/json", "{\"end\":\"$end\"}".toByteArray())
                }
            }
            "/say/stop" -> {
                say?.stop()
                reply(out, if (say == null) 404 else 200, "application/json", "{\"ok\":${say != null}}".toByteArray())
            }
            "/say/rot" -> {
                val g = say
                val sek = query["sek"]?.toDoubleOrNull()
                if (g == null) reply(out, 404, "text/plain", "no voice".toByteArray())
                else if (sek == null || sek.isNaN() || sek < 0) reply(out, 400, "application/json", "{\"ok\":false}".toByteArray())
                else { g.rot(sek); reply(out, 200, "application/json", "{\"ok\":true}".toByteArray()) }
            }
            "/say/status" -> {
                val g = say
                if (g == null) reply(out, 404, "text/plain", "no voice".toByteArray())
                else reply(out, 200, "application/json",
                    "{\"playing\":${g.playing},\"govorit\":${g.govorit},\"rezhimy\":$REZHIMY}".toByteArray())
            }
            else -> reply(out, 404, "text/plain", "try /frame.jpg, /status or /ear".toByteArray())
        }
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, dop: String = "") {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 503 -> "Service Unavailable"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            dop + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }
}
