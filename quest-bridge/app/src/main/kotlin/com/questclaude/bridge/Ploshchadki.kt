package com.questclaude.bridge

import java.net.URI

/**
 * Мульти-эфир (2.7.0): один кодировщик — несколько площадок. Кадр сжимается один раз, а пакеты (те же
 * массивы, без копий) уходят в отправку каждой площадки (Otpravka): у каждой своя очередь, свой поток и
 * своё переподключение. Одна упала — остальные идут; медленная — сбрасывает свою очередь до ключевого
 * кадра и другим не мешает. Ключ каждой площадки — только на её адреса (razreshyon), в статус не попадает.
 * Обычная JVM: проверяется на поддельных серверах RTMP (PloshchadkiTest).
 */
class Ploshchadki(
    private val podklyuchit: (pl: String, url: String, key: String) -> Otpravka.Kanal,
    private val nuzhenKlyuchevoy: () -> Unit = {},
    private val pauzaMs: Long = 3000,
    private val limit: Int = 400,
) {
    /** Площадка эфира: имя (twitch | kick | youtube), адреса по порядку (второй — запасной), ключ (null — нет). */
    class Nastroyka(val pl: String, val adresa: List<String>, val key: String?) {
        override fun toString() = "Nastroyka($pl)"                 // ключ — ни в какие тексты
    }

    inner class Ploshchadka(val n: Nastroyka) {
        val pl get() = n.pl
        @Volatile private var adres = 0
        private var podryad = 0                                  // неудач подряд на этом адресе

        /** null — нет ключа или адреса: площадка в списке, но не шлёт (net_klyucha). */
        val otpravka: Otpravka? = if (n.key.isNullOrEmpty() || n.adresa.isEmpty()) null else Otpravka({
            val i = adres
            val url = n.adresa[i]
            if (!razreshyon(pl, url)) throw java.io.IOException("адрес не разрешён для $pl")
            try {
                podklyuchit(pl, url, n.key).also { podryad = 0 }
            } catch (e: Exception) {
                if (++podryad >= POPYTOK_NA_ADRES && n.adresa.size > 1) { adres = (i + 1) % n.adresa.size; podryad = 0 }
                throw e
            }
        }, { prosit() }, pauzaMs, limit)

        /** На запасном адресе (YouTube: rtmp вместо rtmps). */
        val zapasnoy: Boolean get() = adres > 0

        @Volatile var kbps = 0
            private set
        private var tSent = 0L
        private var tMs = 0L
        private var dropsBylo = 0L
        @Volatile private var tesnoDoMs = 0L
        /** Сеть не успевает: за последние TESNO_MS были сбросы или очередь заполнена больше чем наполовину. */
        val tesno: Boolean get() = System.currentTimeMillis() < tesnoDoMs

        val sostoyanie: String get() = when (otpravka?.state) {
            null -> "net_klyucha"
            "efir" -> "idyot"
            "obryv" -> "obryv"
            "off" -> "vykl"
            else -> "podklyuchayus"
        }

        internal fun tik(now: Long) {
            val o = otpravka ?: return
            if (tMs == 0L) { tMs = now; tSent = o.sent; dropsBylo = o.drops; return }
            if (now - tMs >= 1000) {
                val s = o.sent
                kbps = ((s - tSent) * 8 / (now - tMs)).toInt()
                tSent = s; tMs = now
            }
            val d = o.drops
            if (d > dropsBylo || o.ochered > o.predel / 2) tesnoDoMs = now + TESNO_MS
            dropsBylo = d
        }

        fun json(now: Long): String {
            val o = otpravka
            val sek = if (o != null && o.state == "efir") (now - o.efirS) / 1000 else 0
            val osh = when {
                o != null -> o.oshibka
                n.key.isNullOrEmpty() -> "нет ключа: kluch $pl"
                else -> "нет адреса: kluch $pl (адрес со страницы площадки)"
            }
            return "{\"pl\":\"$pl\",\"sostoyanie\":\"$sostoyanie\",\"kbps\":${if (o?.state == "efir") kbps else 0}," +
                "\"poteri\":${o?.drops ?: 0},\"popytki\":${o?.popytok ?: 0},\"sec\":$sek,\"ochered\":${o?.ochered ?: 0}," +
                "\"est_kluch\":${!n.key.isNullOrEmpty()},\"zapasnoy\":$zapasnoy,\"tesno\":$tesno," +
                "\"oshibka\":${osh?.let { jstr(it) } ?: "null"}}"
        }
    }

    private val lock = Any()
    @Volatile var spisok: List<Ploshchadka> = emptyList()
        private set
    @Volatile private var running = false
    @Volatile private var klyuchevoyMs = 0L

    /** Заголовки потоков — всем площадкам (и тем, что добавятся на ходу). */
    @Volatile var videoConfig: ByteArray? = null
        set(v) { field = v; spisok.forEach { it.otpravka?.videoConfig = v } }
    @Volatile var audioConfig: ByteArray? = null
        set(v) { field = v; spisok.forEach { it.otpravka?.audioConfig = v } }

    /** Кадр — каждой площадке в её очередь (тот же массив). */
    fun video(payload: ByteArray, ptsUs: Long, key: Boolean) { for (p in spisok) p.otpravka?.video(payload, ptsUs, key) }
    fun audio(payload: ByteArray, ptsUs: Long) { for (p in spisok) p.otpravka?.audio(payload, ptsUs) }

    /** Ключевой кадр у кодировщика — не чаще раза в секунду (площадки переподключаются каждая сама). */
    private fun prosit() {
        val now = System.currentTimeMillis()
        if (now - klyuchevoyMs < KLYUCHEVOY_MS) return
        klyuchevoyMs = now
        nuzhenKlyuchevoy()
    }

    fun start(n: List<Nastroyka>) {
        synchronized(lock) {
            running = true
            ustanovit(n)
        }
    }

    /**
     * Набор площадок на ходу («добавь ютуб», «оставь твич»): новые — подключаются, убранные — заканчивают эфир
     * (в фоне, не задерживая остальные), оставшиеся с тем же ключом и адресом — не трогаются.
     */
    fun ustanovit(n: List<Nastroyka>) {
        val ubrat = mutableListOf<Ploshchadka>()
        synchronized(lock) {
            val novye = n.distinctBy { it.pl }.take(MAKS).map { x ->
                val bylo = spisok.firstOrNull { it.pl == x.pl }
                if (bylo != null && bylo.n.key == x.key && bylo.n.adresa == x.adresa) bylo
                else Ploshchadka(x).also { p ->
                    p.otpravka?.videoConfig = videoConfig
                    p.otpravka?.audioConfig = audioConfig
                }
            }
            ubrat += spisok.filter { s -> novye.none { it === s } }
            spisok = novye
            if (running) novye.forEach { p -> if (p.otpravka?.state == "off") p.otpravka.start() }
        }
        if (ubrat.isNotEmpty()) Thread({ ubrat.forEach { it.otpravka?.stop() } }, "efir-ubrat").apply { isDaemon = true; start() }
    }

    /** Закончить эфир на всех площадках сразу (параллельно: каждая говорит серверу «конец» и ждёт до 0,3 с). */
    fun stop() {
        val vse = synchronized(lock) { running = false; spisok }
        vse.mapNotNull { p -> p.otpravka?.let { o -> Thread({ o.stop() }, "efir-stop-${p.pl}").apply { isDaemon = true; start() } } }
            .forEach { it.join(5000) }
    }

    fun tik(now: Long) { for (p in spisok) synchronized(p) { p.tik(now) } }

    // ---------- общее состояние (как было с одной площадкой — для plus-lib и Nalozhenie) ----------

    private val sAktivnyh: List<Otpravka> get() = spisok.mapNotNull { it.otpravka }

    /** off | podklyuchayus | efir | obryv: идёт хоть на одной — efir. */
    val state: String get() {
        val s = sAktivnyh.map { it.state }
        return when {
            s.isEmpty() -> "off"
            "efir" in s -> "efir"
            "obryv" in s -> "obryv"
            s.all { it == "off" } -> "off"
            else -> "podklyuchayus"
        }
    }
    /** Начало эфира — самая давняя из идущих площадок. */
    val efirS: Long get() = sAktivnyh.filter { it.state == "efir" }.minOfOrNull { it.efirS } ?: 0L
    val drops: Long get() = sAktivnyh.sumOf { it.drops }
    val podklyucheniy: Int get() = sAktivnyh.sumOf { it.podklyucheniy }
    val sent: Long get() = sAktivnyh.sumOf { it.sent }
    /** Последняя беда площадки (без ключа); площадок несколько — с именем площадки. */
    val oshibka: String? get() {
        val s = spisok
        return s.firstNotNullOfOrNull { p -> p.otpravka?.oshibka?.let { if (s.size > 1) "${p.pl}: $it" else it } }
    }
    val tesnoSet: Boolean get() = spisok.any { it.otpravka?.state == "efir" && it.tesno }

    /** Совет, если сеть не успевает (эфир идёт через мобильный интернет айфона). */
    fun sovet(): String? {
        val tesno = spisok.filter { it.otpravka?.state == "efir" && it.tesno }.map { it.pl }
        if (tesno.isEmpty()) return null
        val idut = spisok.count { it.otpravka?.state == "efir" }
        return "сеть не успевает (${tesno.joinToString(", ")} теряет кадры): " +
            if (idut > 1) "оставь одну площадку (efir na ${spisok.first().pl}) или снизь поток (efir potok 4)"
            else "снизь поток (efir potok 4) или размер (efir 720)"
    }

    /** Добавка к /efir: площадки по отдельности, выбор, теснота сети. */
    fun json(now: Long = System.currentTimeMillis()): String {
        tik(now)
        return "\"ploshchadki\":[${spisok.joinToString(",") { it.json(now) }}]," +
            "\"vybor\":[${spisok.joinToString(",") { "\"${it.pl}\"" }}]," +
            "\"tesno_set\":$tesnoSet,\"sovet_set\":${sovet()?.let { jstr(it) } ?: "null"}"
    }

    companion object {
        val VSE = listOf("twitch", "kick", "youtube")
        /** Больше трёх сразу — не бывает (Александр: «арсенал из 3, одновременно 2»). */
        const val MAKS = 3
        const val TWITCH = "rtmp://live.twitch.tv/app"
        const val YOUTUBE = "rtmps://a.rtmps.youtube.com:443/live2"
        const val YOUTUBE_ZAPAS = "rtmp://a.rtmp.youtube.com/live2"
        /** Столько неудач подряд — и на запасной адрес (если есть). */
        const val POPYTOK_NA_ADRES = 2
        const val KLYUCHEVOY_MS = 1000L
        /** Сколько держится «тесно» после сброса. */
        const val TESNO_MS = 20_000L

        private val IMENA = mapOf("twitch" to "twitch", "tw" to "twitch", "твич" to "twitch", "твитч" to "twitch",
            "kick" to "kick", "кик" to "kick", "youtube" to "youtube", "yt" to "youtube", "ютуб" to "youtube")

        /** «twitch,kick» (запятые, пробелы, «+») → список; null — пусто, чужое имя или больше MAKS. */
        fun razobrat(s: String?): List<String>? {
            val t = s?.lowercase()?.split(',', ' ', '+', ';')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "и" }
                ?: return null
            val pl = t.map { IMENA[it] ?: return null }.distinct()
            return if (pl.isEmpty() || pl.size > MAKS) null else pl
        }

        private fun petlya(h: String) = h == "127.0.0.1" || h == "localhost"

        /** Ключ площадки — только на её адреса (и на сам шлем — для проверки). */
        fun razreshyon(pl: String, url: String): Boolean = try {
            val u = URI(url)
            val h = u.host.orEmpty().lowercase()
            val sh = u.scheme
            pl in VSE && (sh == "rtmp" || sh == "rtmps") && (petlya(h) || when (pl) {
                "twitch" -> h == "live.twitch.tv" || h.endsWith(".twitch.tv") || h.endsWith(".contribute.live-video.net")
                "kick" -> sh == "rtmps" && h.endsWith(".global-contribute.live-video.net")
                "youtube" -> (sh == "rtmps" && h == "a.rtmps.youtube.com") || (sh == "rtmp" && h == "a.rtmp.youtube.com")
                else -> false
            })
        } catch (e: Exception) { false }

        /**
         * Адрес приёма Kick (свой у каждого аккаунта, со страницы Kick «Stream URL») → rtmps://…:443/app; null — не он.
         * Путь — только /app: адрес с хвостом (туда могли вклеить ключ) не принимаем.
         */
        fun adresKick(s: String?): String? = try {
            val u = URI(s?.trim().orEmpty())
            val h = u.host.orEmpty().lowercase()
            val ok = u.scheme == "rtmps" && h.endsWith(".global-contribute.live-video.net") &&
                u.path.trimEnd('/') == "/app" && u.query == null && u.userInfo == null && (u.port == -1 || u.port == 443)
            if (ok) "rtmps://$h:443/app" else null
        } catch (e: Exception) { null }

        /** Адреса площадки по порядку: twitch — свой (или из запроса), kick — запомненный, youtube — rtmps и запасной rtmp. */
        fun adresa(pl: String, twitch: String = TWITCH, kick: String? = null): List<String> = when (pl) {
            "twitch" -> listOf(twitch)
            "kick" -> listOfNotNull(kick)
            "youtube" -> listOf(YOUTUBE, YOUTUBE_ZAPAS)
            else -> emptyList()
        }

        /** «kluchi»: есть ли ключ у каждой площадки — только да/нет. */
        fun kluchiJson(est: (String) -> Boolean) = "{" + VSE.joinToString(",") { "\"$it\":${est(it)}" } + "}"

        /** Строка JSON (как FrameServer.json — здесь без него, чтобы проверялось отдельно). */
        fun jstr(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c < ' ' -> b.append(String.format("\\u%04x", c.code))
                else -> b.append(c)
            }
            return b.append('"').toString()
        }

        val NAZVANIE = mapOf("twitch" to "Twitch", "kick" to "Kick", "youtube" to "YouTube")
    }
}
