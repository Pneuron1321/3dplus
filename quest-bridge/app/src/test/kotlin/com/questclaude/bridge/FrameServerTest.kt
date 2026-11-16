package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** FrameServer на обычной JVM: без шлема и без Android. */
class FrameServerTest {
    private val port = 18765
    @Volatile private var frame: FrameServer.Frame? = null
    @Volatile private var capturing = false
    @Volatile private var stale = false
    private val variants = mutableMapOf<String, ByteArray>()
    private val ear = Ear(keep = 3)
    @Volatile private var plenka: ByteArray? = null
    @Volatile private var plenkaT: Long? = null
    @Volatile private var plenkaN: Int? = null
    @Volatile private var wakeMs = 0L
    @Volatile private var kadrMoment: ByteArray? = null
    @Volatile private var kadrT: Long? = null
    private lateinit var server: FrameServer

    /** Подделка эфира: помнит ключ и запросы, ключ наружу не отдаёт. */
    private val efir = object : FrameServer.EfirUpr {
        @Volatile var key: String? = null
        @Volatile var startQ: Map<String, String>? = null
        @Volatile var state = "off"
        override fun start(q: Map<String, String>): String { startQ = q; state = "efir"; return status() }
        override fun stop(): String { state = "off"; return status() }
        override fun status() = "{\"state\":\"$state\"}"
        override fun key(k: String): Boolean { key = k; return true }
        val kluchi = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun key(pl: String, k: String): Boolean { kluchi[pl] = k; return if (pl == "twitch") key(k) else true }
        @Volatile var adresQ: Pair<String, String>? = null
        override fun adres(pl: String, url: String): String { adresQ = pl to url; return "{\"adres\":true}" }
        @Volatile var micG = 0f
        override fun mic(g: Float): String { micG = g; return status() }
    }
    @Volatile private var efirOn = true

    /** Подделка камеры: помнит запрос окна; ключа у неё нет вовсе. */
    private val kamera = object : FrameServer.KameraUpr {
        @Volatile var vidQ: Map<String, String>? = null
        override fun status() = "{\"state\":\"zhdu\",\"adres\":\"rtmp://192.168.1.50:1935/kamera\"}"
        override fun vid(q: Map<String, String>): String { vidQ = q; return status() }
        override fun stranica() = "{\"adres\":\"http://plus.local:8770\",\"sek\":120}"
    }
    @Volatile private var kameraOn = true

    /** Настоящие субтитры, цель и итоги — за подделкой службы глаз. */
    private val titry = Titry()
    private val cel = Cel({ null }, { })
    private val itogi = Itogi()
    private val igra = Igra()
    private val top = Top({ null }, { })
    private val chat = Chat({ null }, { })
    @Volatile private var plashkiOn = true
    private val plashki = object : FrameServer.PlashkiUpr {
        override fun titr(q: Map<String, String>) = titry.upr(q)
        override fun cel(q: Map<String, String>?) = if (q == null) cel.status() else cel.upr(q)
        override fun itogi(q: Map<String, String>?) = if (q == null) itogi.status() else itogi.upr(q)
        override fun igra(q: Map<String, String>?) = if (q == null) igra.status() else igra.upr(q)
        override fun top(q: Map<String, String>?) = if (q == null) top.status() else top.upr(q)
        override fun chat(q: Map<String, String>?) = if (q == null) chat.status() else chat.upr(q)
        override fun chatPachka(qs: List<Map<String, String>>) = chat.pachka(qs)
    }

    private val nalet = object : FrameServer.NaletUpr {
        @Volatile var q: Map<String, String>? = null
        override fun status() = "{\"vsego\":\"123,45\",\"lico\":true}"
        override fun upr(q: Map<String, String>): String { this.q = q; return status() }
    }

    /** Подделка голоса: «играет» 300 мс, stop() обрывает. */
    private val voice = object : FrameServer.Say {
        val got = mutableListOf<Pair<Int, String>>()
        @Volatile var stopped = false
        @Volatile override var playing = false
        override fun play(mp3: ByteArray, mode: String): String {
            got += mp3.size to mode
            playing = true
            stopped = false
            val t = System.currentTimeMillis()
            while (!stopped && System.currentTimeMillis() - t < 300) Thread.sleep(10)
            playing = false
            return if (stopped) "stopped" else "done"
        }
        override fun stop() { stopped = true }
        val rty = mutableListOf<Double>()
        override fun rot(sek: Double) { rty += sek }
    }

    /** Подделка гостей: помнит адрес туннеля и команды; ключей и адреса наружу не отдаёт. */
    private val gosti = object : FrameServer.GostiUpr {
        @Volatile var adresTunnelya: String? = null
        @Volatile var uprQ: Map<String, String>? = null
        @Volatile var idut = false
        override fun status() = "{\"idyot\":$idut}"
        override fun start(): String { idut = true; return status() }
        override fun stop(): String { idut = false; return status() }
        override fun upr(q: Map<String, String>): String { uprQ = q; return "{\"ok\":true}" }
        override fun adres(u: String?): String { adresTunnelya = u; return "{\"ok\":true,\"tunnel\":${u != null}}" }
        override fun ssylki() = "{\"adres\":\"http://plus.local:8770/gosti\"}"
        override fun sobytiya(posle: Long, zhdatMs: Long) = "{\"posl\":$posle,\"zhdat\":$zhdatMs}"
        override fun nastroyka(q: Map<String, String>) = "{\"eho\":${q["eho"]}}"
    }
    private val rechi = Rechi()

    /** Подделка музыки (2.5.6): помнит куски и команды. */
    private val fon = object : FrameServer.FonUpr {
        val kuski = mutableListOf<Pair<ByteArray, String>>()
        @Volatile var q: Map<String, String>? = null
        @Volatile var stopov = 0
        override fun kusok(mp3: ByteArray, mode: String): String { kuski += mp3 to mode; return "{\"ok\":true}" }
        override fun stop(): String { stopov++; return status() }
        override fun status() = "{\"igraet\":${kuski.isNotEmpty()}}"
        override fun gromkost(q: Map<String, String>): String { this.q = q; return "{\"g\":true}" }
        override fun duck(q: Map<String, String>): String { this.q = q; return "{\"d\":true}" }
        override fun plashka(q: Map<String, String>): String { this.q = q; return "{\"p\":true}" }
        val pult = PleerPult()
        override fun pleer(json: String?): String = if (json != null) pult.zapisat(json) else pult.sostoyanie
        override fun komanda(q: Map<String, String>): String = "{\"ok\":${pult.komanda(q["k"].orEmpty(), q["a"].orEmpty())}}"
        override fun zhdatKomandu(ms: Long): String = pult.zhdat(ms)
    }
    @Volatile private var fonOn = true

    private data class Reply(val code: Int, val type: String?, val body: ByteArray)

    private fun get(path: String, timeoutMs: Int = 2000): Reply {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = timeoutMs
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).readBytes()
        return Reply(code, c.contentType, body)
    }

    @Before fun start() {
        server = FrameServer(port, { frame }, { capturing }, { stale }, { variants[it] }, ear, voice,
            { t, n -> plenkaT = t; plenkaN = n; plenka }, { wakeMs }, { if (efirOn) efir else null }, { "" },
            { if (kameraOn) kamera else null }, { nalet }, { if (plashkiOn) plashki else null },
            { t -> kadrT = t; kadrMoment }, { gosti }, rechi, { if (fonOn) fon else null })
        server.start()
    }

    @After fun stop() = server.stop()

    private fun post(path: String, body: ByteArray): Reply {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val b = (if (code < 400) c.inputStream else c.errorStream).readBytes()
        return Reply(code, c.contentType, b)
    }

    @Test fun fonKuskiIUpravlenie() {
        val mp3 = ByteArray(5000) { (it * 7).toByte() }                 // все байты 0–255: тело доходит как есть
        val r = post("/fon?mode=efir", mp3)
        assertEquals(200, r.code)
        assertEquals("{\"ok\":true}", String(r.body))
        assertTrue(fon.kuski.single().first.contentEquals(mp3))
        assertEquals("efir", fon.kuski.single().second)
        post("/fon", byteArrayOf(1, 2, 3))
        assertEquals("oba", fon.kuski.last().second)                     // по умолчанию — в шлем и в эфир
        assertEquals("{\"igraet\":true}", String(get("/fon").body))  // GET без тела — состояние
        assertEquals("{\"igraet\":true}", String(get("/fon/status").body))
        assertEquals("{\"g\":true}", String(get("/fon/gromkost?shlem=80&efir=120").body))
        assertEquals(mapOf("shlem" to "80", "efir" to "120"), fon.q)
        get("/fon/duck?v=0.4"); assertEquals("0.4", fon.q?.get("v"))
        get("/fon/plashka?pokaz=1&tekst=Bossa%20Antigua"); assertEquals("Bossa%20Antigua", fon.q?.get("tekst"))
        get("/fon/stop"); assertEquals(1, fon.stopov)
        assertTrue(String(get("/status").body).contains("\"fon\""))
        fonOn = false
        assertEquals(404, get("/fon/status").code)
    }

    /** Мини-плеер (2.5.7): muzyka шлёт состояние (UTF-8) и ждёт команды; шторка кладёт их через пульт. */
    @Test fun pleerSostoyanieIKomandy() {
        val st = "{\"igraet\":true,\"trek\":\"Мой трек\",\"pleylisty\":[\"техно\"]}"
        assertEquals("{\"ok\":true}", String(post("/fon/pleer", st.toByteArray(Charsets.UTF_8)).body))
        assertEquals(st, String(get("/fon/pleer").body, Charsets.UTF_8))           // кириллица дошла как есть
        assertEquals("{}", String(get("/fon/komanda?zhdat=0.2").body))             // команд нет — пусто по таймауту
        assertEquals("{\"ok\":true}", String(get("/fon/pult?k=sled").body))
        assertTrue(String(get("/fon/komanda?zhdat=1").body).contains("\"k\":\"sled\""))
        val t0 = System.currentTimeMillis()
        Thread { Thread.sleep(300); fon.pult.komanda("pred") }.start()
        assertTrue(String(get("/fon/komanda?zhdat=5", 8000).body).contains("\"pred\""))   // ждёт и отдаёт сразу
        assertTrue(System.currentTimeMillis() - t0 < 3000)
        assertEquals("{\"ok\":false}", String(get("/fon/pult?k=rm").body))
    }

    @Test fun efirKeyStartStop() {
        val k = "live_123456789_AbCdEfGh"
        val r = post("/efir/key", k.toByteArray())
        assertEquals(200, r.code)
        assertEquals("{\"key\":true}", String(r.body))          // ключ обратно не отдаётся
        assertEquals(k, efir.key)
        assertEquals(400, post("/efir/key", "два слова".toByteArray()).code)
        assertEquals(400, post("/efir/key", "korotko".toByteArray()).code)
        assertEquals("{\"state\":\"off\"}", String(get("/efir").body))
        assertEquals("{\"state\":\"efir\"}", String(get("/efir/start?size=720&kbps=4500").body))
        assertEquals(mapOf("size" to "720", "kbps" to "4500"), efir.startQ)
        assertEquals("{\"state\":\"off\"}", String(get("/efir/stop").body))
        get("/efir/mic?g=4.5")
        assertEquals(4.5f, efir.micG)
    }

    /** 2.7.0: ключ на площадку (?pl=), без pl — twitch; ключ не возвращается; адрес Kick — отдельно. */
    @Test fun efirKluchiPloshchadok() {
        val k = "sk_us-west-2_test-key-123"
        val r = post("/efir/key?pl=kick", k.toByteArray())
        assertEquals(200, r.code)
        assertEquals("{\"key\":true}", String(r.body))
        assertEquals(k, efir.kluchi["kick"])
        assertEquals(null, efir.key)                                    // ключ Kick — не в ключ Twitch
        assertEquals(200, post("/efir/key?pl=youtube", "abcd-efgh-ijkl-mnop-qrst".toByteArray()).code)
        assertEquals("abcd-efgh-ijkl-mnop-qrst", efir.kluchi["youtube"])
        assertEquals(400, post("/efir/key?pl=tiktok", "test-key-123".toByteArray()).code)
        assertEquals(200, post("/efir/key", "live_123456789_AbCdEfGh".toByteArray()).code)
        assertEquals("live_123456789_AbCdEfGh", efir.kluchi["twitch"])
        assertEquals("live_123456789_AbCdEfGh", efir.key)
        assertEquals("{\"adres\":true}",
            String(get("/efir/adres?pl=kick&url=rtmps%3A%2F%2Fab12.global-contribute.live-video.net%2Fapp").body))
        assertEquals("kick" to "rtmps://ab12.global-contribute.live-video.net/app", efir.adresQ)
        get("/efir/start?pl=twitch%2Ckick")
        assertEquals("twitch%2Ckick", efir.startQ!!["pl"])               // разбирает приложение (URLDecoder)
    }

    /** Субтитры, цель доната, итоги: текст в процентах (UTF-8) доходит целым; без захвата — 503. */
    @Test fun plashkiTitrCelItogi() {
        assertEquals(200, get("/titr?kto=ya&t=Plus%2C%20look%20at%20the%20map&sek=4").code)
        assertEquals("Plus, look at the map", titry.seychas().single().tekst)
        val c = String(get("/cel/upr?summa=100&imya=%D0%9C%D0%B8%D0%BA%D1%80%D0%BE%D1%84%D0%BE%D0%BD&plus=5").body)
        assertTrue(c, c.contains("\"imya\":\"Микрофон\"") && c.contains("\"tekst\":\"€5 / €100\""))
        assertTrue(String(get("/cel").body).contains("\"vidna\":true"))
        val i = String(get("/itogi/upr?pokaz=1&stroki=Ace%20on%20Bridge%0AChat%20said%20hi").body)
        assertTrue(i, i.contains("\"vidny\":true") && i.contains("[\"Ace on Bridge\",\"Chat said hi\"]"))
        assertTrue(String(get("/itogi").body).contains("\"vidny\":true"))
        val g = String(get("/igra/upr?popal=3&stavok=5&vyzov=pistol%20only&vyzovov=2&sdelano=1").body)
        assertTrue(g, g.contains("\"vyzov\":\"pistol only\"") && g.contains("\"vidna\":true"))
        assertTrue(String(get("/igra").body).contains("\"popal\":3"))
        val t = String(get("/top/upr?imya=Rex&summa=25").body)            // топ-донатор (2.3.0)
        assertTrue(t, t.contains("\"imya\":\"Rex\"") && t.contains("\"tekst\":\"$25\"") && t.contains("\"vidna\":true"))
        assertTrue(String(get("/top").body).contains("\"summa\":25.0"))
        val ch = String(get("/chat/upr?kto=Lena&tekst=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&id=m1").body)   // чат (2.3.4)
        assertTrue(ch, ch.contains("\"vidno\":1"))
        assertEquals("привет", chat.vidimye().single().tekst)
        get("/chat/upr?ubrat=m1")
        assertTrue(String(get("/chat").body).contains("\"vidno\":0"))
        plashkiOn = false
        assertEquals(503, get("/titr?t=x").code)
        assertEquals(503, get("/itogi").code)
    }

    @Test fun naletStatusAndUpr() {
        assertTrue(String(get("/nalet").body).contains("\"vsego\":\"123,45\""))
        assertEquals(200, get("/nalet/upr?lico=0&koiny=200.5").code)
        assertEquals(mapOf("lico" to "0", "koiny" to "200.5"), nalet.q)
    }

    @Test fun kameraStatusAndVid() {
        val r = get("/kamera")
        assertEquals(200, r.code)
        assertTrue(String(r.body).contains("\"state\":\"zhdu\""))
        assertEquals(200, get("/kamera/vid?v=ugol&gde=lv&r=0.3").code)
        assertEquals(mapOf("v" to "ugol", "gde" to "lv", "r" to "0.3"), kamera.vidQ)
        assertTrue(String(get("/kamera/stranica").body).contains("plus.local:8770"))
        kameraOn = false
        assertEquals(503, get("/kamera").code)
    }

        @Test fun efirWithoutCapture() {
        efirOn = false
        assertEquals(503, get("/efir/start").code)
        assertEquals(503, post("/efir/key", "live_123456789_AbCd".toByteArray()).code)
    }

    @Test fun beforeFirstFrame() {
        val s = get("/status")
        assertEquals(200, s.code)
        assertEquals("application/json", s.type)
        assertEquals("{\"capturing\":false,\"width\":0,\"height\":0,\"frames\":0,\"ageMs\":-1," +
            "\"stale\":false,\"ear\":\"off\",\"micDb\":-1,\"lastWakeMs\":0," +
            "\"dosluh\":{\"okno\":1.0,\"db\":38,\"otkryto\":0,\"zagovoril\":0},\"umeet\":[\"kadr_t\",\"chat_pachka\",\"gosti\",\"titry_rech\",\"fon\"]}",
            String(s.body))
        assertEquals(404, get("/frame.jpg").code)
    }

    @Test fun servesLatestFrame() {
        val jpeg = ByteArray(50_000) { (it * 7).toByte() }
        frame = FrameServer.Frame(jpeg, 1920, 1080, System.currentTimeMillis() - 300, 5)
        capturing = true
        val f = get("/frame.jpg")
        assertEquals(200, f.code)
        assertEquals("image/jpeg", f.type)
        assertArrayEquals(jpeg, f.body)
        assertEquals(200, get("/frame.jpg?t=123").code)
        val s = String(get("/status").body)
        assertTrue(s, s.startsWith("{\"capturing\":true,\"width\":1920,\"height\":1080,\"frames\":5,\"ageMs\":"))
        assertEquals(404, get("/secret").code)
    }

    @Test fun bigAndZoomOnRequest() {
        assertEquals(503, get("/frame.jpg?size=big").code)
        variants["big"] = ByteArray(10) { 1 }
        variants["zoom"] = ByteArray(20) { 2 }
        variants["x2"] = ByteArray(15) { 3 }
        assertArrayEquals(variants["big"], get("/frame.jpg?size=big").body)
        assertArrayEquals(variants["x2"], get("/frame.jpg?size=x2").body)
        assertArrayEquals(variants["zoom"], get("/frame.jpg?zoom=2").body)
        assertEquals(404, get("/frame.jpg?zoom=3").code)       // неизвестный — обычный кадр, а его нет
    }

    @Test fun plenkaAroundGivenMoment() {
        val t = System.currentTimeMillis() - 10_000                       // кадры «после» давно сняты
        assertEquals(503, get("/plenka.jpg?t=$t").code)                   // кадров нет (захват стоял)
        plenka = ByteArray(30) { 4 }
        val r = get("/plenka.jpg?t=$t")
        assertEquals(200, r.code)
        assertEquals("image/jpeg", r.type)
        assertArrayEquals(plenka, r.body)
        assertEquals(t, plenkaT)
    }

    @Test fun checkCheckGivesSevenFrames() {
        plenka = ByteArray(5) { 2 }
        val t = System.currentTimeMillis() - 10_000
        assertEquals(200, get("/plenka.jpg?t=$t&n=7").code)
        assertEquals(7, plenkaN)
        assertEquals(200, get("/plenka.jpg?t=$t").code)
        assertEquals(13, plenkaN)                                         // по умолчанию — 13
        assertEquals(200, get("/plenka.jpg?t=$t&n=99").code)
        assertEquals(13, plenkaN)                                         // чужое число — 13
    }

    @Test fun checkCheckWaitsOnlyHalf() {
        plenka = ByteArray(5) { 1 }
        val t = System.currentTimeMillis()                                // «после» — ещё 1,5 с, не 3
        val t0 = System.currentTimeMillis()
        assertEquals(200, get("/plenka.jpg?t=$t&n=7", timeoutMs = 6000).code)
        val waited = System.currentTimeMillis() - t0
        assertTrue("ждал $waited мс", waited in 1500..2600)
    }

    @Test fun plenkaDefaultsToLastWake() {
        plenka = ByteArray(5) { 1 }
        wakeMs = System.currentTimeMillis() - 8000
        assertEquals(200, get("/plenka.jpg").code)
        assertEquals(wakeMs, plenkaT)
    }

    @Test fun plenkaWaitsForFramesAfterWake() {
        plenka = ByteArray(5) { 1 }
        wakeMs = System.currentTimeMillis() - 1000                        // «после» ещё 2 с снимается
        val t0 = System.currentTimeMillis()
        assertEquals(200, get("/plenka.jpg", timeoutMs = 6000).code)
        val waited = System.currentTimeMillis() - t0
        assertTrue("ждал $waited мс", waited in 2000..4500)
    }

    @Test fun staleAndEarInStatus() {
        stale = true
        ear.state = "listening"
        ear.micDb = 42
        val s = String(get("/status").body)
        assertTrue(s, s.endsWith(",\"stale\":true,\"ear\":\"listening\",\"micDb\":42,\"lastWakeMs\":0," +
            "\"dosluh\":{\"okno\":1.0,\"db\":38,\"otkryto\":0,\"zagovoril\":0},\"umeet\":[\"kadr_t\",\"chat_pachka\",\"gosti\",\"titry_rech\",\"fon\"]}"))
    }

    @Test fun earEvents() {
        assertEquals("{\"id\":0,\"kind\":\"none\",\"state\":\"off\"}", String(get("/ear").body))
        ear.post("wake")
        ear.post("text", "посмотри, что \"это\" за\nокно")
        val first = get("/ear?after=0")
        assertEquals("application/json; charset=utf-8", first.type)
        val ms = ear.get(1)!!.ms
        assertEquals("{\"id\":1,\"kind\":\"wake\",\"text\":\"\",\"audio\":false,\"stop\":false,\"ms\":$ms,\"lead\":0,\"how\":\"\"}", String(first.body, Charsets.UTF_8))
        assertEquals("{\"id\":2,\"kind\":\"text\",\"text\":\"посмотри, что \\\"это\\\" за\\nокно\",\"audio\":false,\"stop\":false,\"ms\":${ear.get(2)!!.ms},\"lead\":0,\"how\":\"\"}",
            String(get("/ear?after=1").body, Charsets.UTF_8))
        assertEquals("{\"id\":2,\"kind\":\"none\",\"state\":\"off\"}", String(get("/ear?after=2").body))
        assertEquals("{\"id\":2,\"kind\":\"none\",\"state\":\"off\"}", String(get("/ear").body))
    }

    @Test fun earPhraseWithWakeWords() {
        // звук фразы — с начала «плюс плюс»: сколько мс в начале — слова вызова, и каким путём
        ear.post("text", "окно", ByteArray(3200), lead = 640, how = "рано")
        val s = String(get("/ear?after=0").body, Charsets.UTF_8)
        assertTrue(s, s.endsWith(",\"lead\":640,\"how\":\"рано\"}"))
    }

    @Test fun earServesPhraseAsWav() {
        val pcm = ByteArray(3200) { (it % 7).toByte() }                // 0,1 с
        val e = ear.post("text", "посмотри", pcm)
        val first = String(get("/ear?after=0").body, Charsets.UTF_8)
        assertTrue(first, first.contains(",\"audio\":true,\"stop\":false,\"ms\":"))
        val w = get("/ear/wav?id=${e.id}")
        assertEquals(200, w.code)
        assertEquals("audio/wav", w.type)
        assertEquals(44 + pcm.size, w.body.size)
        assertEquals("RIFF", String(w.body, 0, 4))
        assertEquals("WAVE", String(w.body, 8, 4))
        assertEquals(16000, java.nio.ByteBuffer.wrap(w.body, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(pcm, w.body.copyOfRange(44, w.body.size))
        assertEquals(404, get("/ear/wav?id=999").code)
        assertEquals(404, get("/ear/wav").code)
    }

    @Test fun plusAliveFromEarPolls() {
        assertEquals(false, ear.plusAlive())
        get("/ear?after=0")
        assertEquals(false, ear.plusAlive())                 // уши спрашивают, но Claude не видят
        get("/ear?after=0&plus=1")
        assertEquals(true, ear.plusAlive())
        assertEquals(false, ear.plusAlive(System.currentTimeMillis() + 7000))
        assertEquals(false, ear.strimLive())
        get("/ear?after=0&plus=1&strim=1")
        assertEquals(true, ear.strimLive())
    }

    @Test fun earDosluh() {
        assertEquals("{\"okno\":1.0,\"db\":38,\"otkryto\":1,\"zagovoril\":0}", String(get("/ear/dosluh").body))
        get("/ear/set?dosluh=0")
        assertEquals("{\"okno\":0.0,\"db\":38,\"otkryto\":1,\"zagovoril\":0}", String(get("/ear/dosluh").body))   // выключено — не открывается
    }

    @Test fun earSettings() {
        val d = ",\"dosluh\":1.0,\"db\":38}"                    // секунда после ответа: по умолчанию 1 с, −38 дБ
        assertEquals("{\"pause\":10.0,\"max\":180.0$d", String(get("/ear/set").body))   // Александр: 2 с мало, 45 с мало
        assertEquals("{\"pause\":2.5,\"max\":20.0$d", String(get("/ear/set?pause=2.5&max=20").body))
        assertEquals("{\"pause\":15.0,\"max\":5.0$d", String(get("/ear/set?pause=99&max=1").body))
        assertEquals("{\"pause\":15.0,\"max\":300.0$d", String(get("/ear/set?max=600").body))
        assertEquals("{\"pause\":15.0,\"max\":300.0$d", String(get("/ear/set?pause=abc&max=NaN").body))
        assertEquals("{\"pause\":15.0,\"max\":300.0,\"dosluh\":0.0,\"db\":30}", String(get("/ear/set?dosluh=0&db=30").body))
        assertEquals("{\"pause\":15.0,\"max\":300.0,\"dosluh\":3.0,\"db\":60}", String(get("/ear/set?dosluh=9&db=99").body))
        ear.post("text", "посмотри", ByteArray(2), stop = true)
        assertTrue(String(get("/ear?after=0").body, Charsets.UTF_8).contains(",\"stop\":true,"))
        assertEquals(2.5f, Ear().apply { set(2.5f, null) }.pauseSec)
    }

    @Test fun signalsAreAudibleAndDistinct() {
        val all = Signal.Kind.values().map { Signal.pcm(it) }
        assertTrue(all.all { p -> p.size > Signal.RATE / 20 && p.maxOf { kotlin.math.abs(it.toInt()) } > 8000 })
        assertEquals(all.size, all.map { it.size }.toSet().size)      // разная длина — на слух тоже разные
        assertTrue(all.all { kotlin.math.abs(it.first().toInt()) < 200 && kotlin.math.abs(it.last().toInt()) < 200 })
    }

    @Test fun earKeepsOnlyRecent() {
        repeat(5) { ear.post("text", "фраза $it") }
        assertEquals(5, ear.lastId())
        assertEquals(3L, ear.after(0)?.id)          // старые вытеснены, keep = 3
        assertEquals(null, ear.after(5))
    }

    @Test fun earWaitsForNextEvent() {
        ear.post("wake")
        val t0 = System.currentTimeMillis()
        thread { Thread.sleep(300); ear.post("text", "сразу") }
        val r = String(get("/ear?after=1&wait=3").body, Charsets.UTF_8)
        val dt = System.currentTimeMillis() - t0
        assertTrue(r, r.contains("\"text\":\"сразу\""))
        assertTrue("ждал $dt мс", dt in 250..1500)                  // ответил, как только услышал
        val t1 = System.currentTimeMillis()
        assertTrue(String(get("/ear?after=2&wait=0.5").body).contains("\"kind\":\"none\""))
        assertTrue(System.currentTimeMillis() - t1 >= 450)           // нового нет — ждал, сколько просили
    }

    @Test fun audioKeptOnlyForLastThree() {
        val e = Ear(keep = 20)
        val ids = (1..5).map { e.post("text", "фраза $it", ByteArray(10)).id }
        assertEquals(listOf(false, false, true, true, true), ids.map { e.get(it)?.audio != null })
    }

    @Test fun sayPlaysAndStops() {
        assertEquals("{\"playing\":false,\"govorit\":false,\"rezhimy\":[\"uho\",\"vsluh\",\"efir\",\"oba\"]}",
            String(get("/say/status").body))
        val mp3 = ByteArray(70_000) { (it % 251).toByte() }
        val c = URI("http://127.0.0.1:$port/say?mode=uho").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.readTimeout = 3000
        c.outputStream.use { it.write(mp3) }
        assertEquals("{\"end\":\"done\"}", String(c.inputStream.readBytes()))
        assertEquals(70_000 to "uho", voice.got.last())
        val pool = Executors.newSingleThreadExecutor()
        val f = pool.submit<String> {
            val c2 = URI("http://127.0.0.1:$port/say").toURL().openConnection() as HttpURLConnection
            c2.requestMethod = "POST"
            c2.doOutput = true
            c2.outputStream.use { it.write(ByteArray(5)) }
            String(c2.inputStream.readBytes())
        }
        Thread.sleep(100)
        assertTrue(String(get("/say/status").body).startsWith("{\"playing\":true,\"govorit\":true,"))
        assertEquals("{\"ok\":true}", String(get("/say/stop").body))
        assertEquals("{\"end\":\"stopped\"}", f.get(2, TimeUnit.SECONDS))
        assertEquals("vsluh", voice.got.last().second)
        pool.shutdown()
    }

    /** Голос только в эфир (ответ донатеру): режим доходит до голоса как "efir". */
    @Test fun sayEfirMode() {
        val c = URI("http://127.0.0.1:$port/say?mode=efir").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.readTimeout = 3000
        c.outputStream.use { it.write(ByteArray(10)) }
        assertEquals("{\"end\":\"done\"}", String(c.inputStream.readBytes()))
        assertEquals(10 to "efir", voice.got.last())
    }

    /** «Вслух» через приложение (2.3.9): режим доходит до голоса как "oba". */
    @Test fun sayObaMode() {
        val c = URI("http://127.0.0.1:$port/say?mode=oba").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.readTimeout = 3000
        c.outputStream.use { it.write(ByteArray(12)) }
        assertEquals("{\"end\":\"done\"}", String(c.inputStream.readBytes()))
        assertEquals(12 to "oba", voice.got.last())
    }

    /** Кадр момента из плёнки (2.3.9): t — мс момента; нет кадра — 404; маленькое t — свежий кадр, как было. */
    @Test fun frameMomentFromFilm() {
        val jpeg = ByteArray(20) { 7 }
        kadrMoment = jpeg
        val r = get("/frame.jpg?t=1728150000123")
        assertEquals(200, r.code)
        assertArrayEquals(jpeg, r.body)
        assertEquals(1728150000123L, kadrT)
        kadrMoment = null
        assertEquals(404, get("/frame.jpg?t=1728150000999").code)
        kadrT = null
        frame = FrameServer.Frame(ByteArray(5) { 1 }, 1920, 1080, System.currentTimeMillis(), 1)
        assertEquals(200, get("/frame.jpg?t=123").code)
        assertEquals(null, kadrT)
    }

    /** Чат пачкой (2.3.9): строки тела — как /chat/upr; одна перерисовка на пачку. */
    @Test fun chatPachkaPost() {
        val v0 = chat.versiya
        val telo = "kto=Lena&login=lena&tekst=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&id=a1\n" +
            "kto=Rex&tekst=plus%2C%20hi&id=a2\n\nubrat=a1\n"
        val r = post("/chat/pachka", telo.toByteArray())
        assertEquals(200, r.code)
        assertEquals(listOf("plus, hi"), chat.vidimye().map { it.tekst })
        assertTrue(chat.vidimye().single().vazhno)                    // «plus» в тексте — к Плюсу
        assertEquals(v0 + 1, chat.versiya)
        plashkiOn = false
        assertEquals(503, post("/chat/pachka", "kto=X&tekst=y".toByteArray()).code)
    }

    /** Ава говорит сама (play-audio Termux): демон говорит, сколько звучит кусок, — рот лица движется. */
    @Test fun sayRotFromDaemon() {
        assertEquals("{\"ok\":true}", String(get("/say/rot?sek=3.5").body))
        assertEquals("{\"ok\":true}", String(get("/say/rot?sek=0.0").body))
        assertEquals(listOf(3.5, 0.0), voice.rty)
        assertEquals(400, get("/say/rot").code)
        assertEquals(400, get("/say/rot?sek=-1").code)
        assertEquals(400, get("/say/rot?sek=abc").code)
        assertEquals(listOf(3.5, 0.0), voice.rty)
    }

    @Test fun kolcoKeepsRecentAndCountsSamples() {
        val k = Kolco(sec = 1, rate = 10)
        k.add(ShortArray(7) { it.toShort() })
        assertEquals(7L, k.total)
        assertArrayEquals(shortArrayOf(5, 6), k.since(5))
        k.add(ShortArray(8) { (100 + it).toShort() })                // 15 сэмплов, в кольце — последние 10
        assertEquals(15L, k.total)
        assertArrayEquals(ShortArray(10) { if (it < 2) (5 + it).toShort() else (98 + it).toShort() }, k.since(0))
        assertEquals(0, k.since(15).size)
        assertArrayEquals(shortArrayOf(107), k.since(14))
    }

    @Test fun jsonEscapesControlChars() {
        assertEquals("\"a\\u0001b\\\\c\"", FrameServer.json("a\u0001b\\c"))
    }

    @Test fun parallelRequests() {
        val jpeg = ByteArray(200_000) { it.toByte() }
        frame = FrameServer.Frame(jpeg, 1920, 1080, System.currentTimeMillis(), 1)
        val pool = Executors.newFixedThreadPool(16)
        val sizes = (1..40).map { pool.submit<Int> { get("/frame.jpg").body.size } }
        assertTrue(sizes.all { it.get(5, TimeUnit.SECONDS) == jpeg.size })
        pool.shutdown()
    }

    @Test fun loopbackOnly() {
        val outside = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 } ?: return
        val refused = try {
            Socket().use { it.connect(InetSocketAddress(outside, port), 1000) }
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("порт виден с ${outside.hostAddress}", refused)
    }

    /** На Android getLoopbackAddress() — это ::1; сервер обязан слушать именно IPv4 127.0.0.1. */
    @Test fun boundToIpv4Loopback() {
        assertEquals("127.0.0.1", FrameServer.LOOPBACK.hostAddress)
        assertEquals(FrameServer.LOOPBACK, server.boundAddress)
    }

    @Test fun closedAfterStop() {
        server.stop()
        val closed = try { get("/status"); false } catch (e: Exception) { true }
        assertTrue(closed)
        server.start()      // для @After
    }

    /** 2.5.0: гости — команды, адрес туннеля телом (обратно не отдаётся), события. */
    @Test fun gostiMarshruty() {
        assertEquals("{\"idyot\":true}", String(get("/gosti/start").body))
        assertEquals("{\"idyot\":true}", String(get("/gosti").body))
        val r = post("/gosti/adres", "https://abc-def.trycloudflare.com".toByteArray())
        assertEquals(200, r.code)
        assertEquals("https://abc-def.trycloudflare.com", gosti.adresTunnelya)
        assertTrue(!String(r.body).contains("trycloudflare"))
        get("/gosti/upr?chto=vygnat&kto=%D0%9C%D0%B0%D0%BA%D1%81")
        assertEquals("vygnat", gosti.uprQ!!["chto"])
        assertEquals("{\"posl\":5,\"zhdat\":3000}", String(get("/gosti/sobytiya?posle=5&zhdat=3").body))
        assertEquals("{\"eho\":0.3}", String(get("/gosti/nastroyka?eho=0.3").body))
        assertEquals("{\"idyot\":false}", String(get("/gosti/stop").body))
    }

    /** 2.5.0: фраза для субтитров — WAV с голосом в заголовке; нет — 204 после ожидания. */
    @Test fun titryRech() {
        val c0 = System.currentTimeMillis()
        assertEquals(204, get("/titry/rech?zhdat=0.3").code)
        assertTrue(System.currentTimeMillis() - c0 >= 250)
        rechi.efir = true
        val pcm = ShortArray(16 * 2000) { i -> ((if ((i / 16) % 250 < 150) 5000.0 else 50.0) * Math.sin(i * 0.09)).toInt().toShort() } +
            ShortArray(16 * 1000)
        rechi.podat("igra", pcm, pcm.size)
        val c = URI("http://127.0.0.1:$port/titry/rech?zhdat=1").toURL().openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("igra", c.getHeaderField("X-Golos"))
        assertTrue(c.getHeaderField("X-Tihih").toFloat() > 0.2f)
        val b = c.inputStream.readBytes()
        assertEquals("RIFF", String(b, 0, 4))
        assertTrue(String(get("/titry/upr?igra=0").body).contains("\"igra\":false"))
    }
}
