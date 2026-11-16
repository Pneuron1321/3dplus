package com.questclaude.bridge

import kotlin.math.roundToInt

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Камера телефона в эфире. Сервер RTMP (RtmpPriyom) принимает видео с айфона по Wi-Fi
 * (приложение Moblin или Larix Broadcaster); пока идёт эфир, аппаратный декодер рисует его в поверхность
 * развилки, а развилка — окном поверх кадра эфира (глаза Плюса окна не видят). Без эфира кадры
 * камеры отбрасываются: телефон остаётся подключён, окно появится, как только начнётся эфир.
 * Ключ камеры — секрет (как ключ эфира): в файле приложения, наружу — только на экран
 * приложения и только без эфира (экран в кадре).
 */
class Kamera(
    private val dir: File,
    private val gruppa: (Boolean) -> Unit = {},   // замок групповых пакетов Wi-Fi (для plus.local)
    klipDir: File = File(dir, "klip"),
) : RtmpPriyom.Priyomnik, FrameServer.KameraUpr {
    /** Круг последних двух минут видео телефона как есть — для клипа (2.5.9). */
    val klip = KlipZapis(klipDir)
    /** Сохранить клип (ставит служба глаз: ей нужен Context для галереи). */
    @Volatile var sohranitKlip: ((Int) -> String)? = null
    private val lock = Any()
    @Volatile private var klyuch = zagruzitKlyuch()
    private val priyom = RtmpPriyom(RtmpPriyom.PORT, { klyuch }, this)
    private val mdns = MdnsImya({ ip() })
    /** Страница «подключить камеру» для айфона (2 минуты по кнопке) и имя plus.local на то же время. */
    /** Страница ссылок гостей (2.5.0) — та же страница айфона, путь /gosti; ставит служба глаз. */
    @Volatile var gostiStranica: (() -> String?)? = null
    private val aifon = KameraStranica(KameraStranica.PORT, { adresSKlyuchom() }, dop = { put ->
        if (put == "/gosti") gostiStranica?.invoke() else null
    }, pri = { on ->
        if (on) {
            try { gruppa(true) } catch (e: Exception) { }
            try { mdns.start() } catch (e: Exception) { oshibka = "имя plus.local не заработало: ${e.message}" }
        } else {
            mdns.stop()
            try { gruppa(false) } catch (e: Exception) { }
        }
    })
    private var razvilka: Razvilka? = null
    private var surface: Surface? = null
    private var dec: MediaCodec? = null
    private var vyvod: Thread? = null
    private var podacha: Thread? = null
    /** Запас кадров перед декодером (2.6.7): заминка декодера — подождать, а не прыгать к ключевому кадру. */
    private class Kusok(val a: ByteArray, val pts: Long, val key: Boolean)
    private val zapas = LinkedBlockingQueue<Kusok>()
    @Volatile private var slomalsya: String? = null
    @Volatile private var dekodiruet = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var mw = 0
    private var mh = 0
    @Volatile private var w = 0
    @Volatile private var h = 0
    @Volatile private var pokazano = 0L
    @Volatile private var propushcheno = 0L
    @Volatile private var oshibka: String? = null
    @Volatile var vid = zagruzitVid()
        private set

    /** Порт занят или его закроет система — приёмник откроет его заново сам. */
    fun start() {
        try { priyom.start() } catch (e: Exception) { oshibka = "приёмник не запустился: ${e.message}" }
    }

    fun stop() {
        aifon.zakryt()
        otklyuchit()
        priyom.close()
    }

    /** Эфир начался: окно камеры — в развилку (кадр эфира). */
    fun podklyuchit(rz: Razvilka) = synchronized(lock) {
        razvilka = rz
        rz.vid = vid
        if (w > 0) rz.kameraRazmer(w, h)
        surface = rz.kamera()
        zapustit()
    }

    /** Эфир кончился: декодер прочь, окно из развилки — тоже (до её release). */
    fun otklyuchit() = synchronized(lock) {
        ostanovit()
        try { razvilka?.kameraUbrat() } catch (e: Exception) { }
        razvilka = null
        surface = null
    }

    // ---------- от приёмника ----------

    override fun nachalo(sps: ByteArray, pps: ByteArray, w: Int, h: Int) = synchronized(lock) {
        aifon.zakryt()                                   // телефон подключился — страница больше не нужна
        this.sps = sps; this.pps = pps; mw = w; mh = h
        klip.golova(sps, pps, w, h)
        if (w > 0 && h > 0) { this.w = w; this.h = h; razvilka?.kameraRazmer(w, h) }
        ostanovit()
        zapustit()
    }

    override fun kadr(annexB: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        klip.video(annexB, ptsUs, key)                   // круг клипа — и без эфира
        if (key) try { Hub.klyuchevoyEfira?.invoke() } catch (e: Exception) { }   // 2.7.9: эфиру — ключевой в то же мгновение
        slomalsya?.let {                                 // поток подачи упал — новый декодер, с ключевого кадра
            oshibka = it; slomalsya = null
            ostanovit(); zapustit()
            return@synchronized
        }
        if (dec == null) return@synchronized
        if (zapas.size >= ZAPAS) {                       // декодер отстал на полсекунды — дальше копить задержку
            propushcheno += zapas.size; zapas.clear()
            if (!key) { propushcheno++; priyom.zhdatKlyuchevoy(); return@synchronized }
        }
        zapas.offer(Kusok(annexB, ptsUs, key))
    }

    override fun zvukNachalo(asc: ByteArray) = klip.zvukGolova(asc)

    override fun zvuk(aac: ByteArray, ptsUs: Long) = klip.zvuk(aac, ptsUs)

    override fun klip(q: Map<String, String>): String =
        sohranitKlip?.invoke(q["sek"]?.toIntOrNull() ?: 180) ?: "{\"oshibka\":\"клип: служба глаз не готова\"}"

    override fun konec() = synchronized(lock) {
        klip.konec()
        ostanovit()
        sps = null; pps = null
    }

    // ---------- декодер ----------

    private fun zapustit() {
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        val d = try { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (e: Exception) {
            oshibka = "декодер H.264 не создан: ${e.message}"; return
        }
        fun format(bystro: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
            if (mw > 0) mw else 1280, if (mh > 0) mh else 720).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(Flv.sRazdelitelem(sp)))
            setByteBuffer("csd-1", ByteBuffer.wrap(Flv.sRazdelitelem(pp)))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAKS_KADR)
            if (bystro) {                                   // кадр — сразу на экран, без запаса
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
        }
        var ok = false
        for (bystro in listOf(true, false)) {
            try { d.configure(format(bystro), s, null, 0); ok = true; break } catch (e: Exception) { d.reset() }
        }
        if (!ok) { d.release(); oshibka = "декодер не принял видео камеры"; return }
        d.start()
        dec = d
        zapas.clear()
        dekodiruet = true
        priyom.zhdatKlyuchevoy()
        podacha = thread(name = "kamera-podacha", isDaemon = true) {
            var k: Kusok? = null
            while (dekodiruet) {
                val t = k ?: zapas.poll(20, TimeUnit.MILLISECONDS) ?: continue
                k = t
                val i = try { d.dequeueInputBuffer(20_000) } catch (e: Exception) {
                    slomalsya = "декодер: ${e.message ?: e.javaClass.simpleName}"; break
                }
                if (i < 0) continue                      // занят — тот же кадр ещё раз; остальные ждут в запасе
                k = null
                try {
                    val b = d.getInputBuffer(i)
                    if (b == null || t.a.size > b.capacity()) {
                        d.queueInputBuffer(i, 0, 0, t.pts, 0)
                        propushcheno++; zapas.clear(); priyom.zhdatKlyuchevoy()
                        continue
                    }
                    b.clear()
                    b.put(t.a)
                    d.queueInputBuffer(i, 0, t.a.size, t.pts, if (t.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                } catch (e: Exception) {
                    slomalsya = "декодер: ${e.message ?: e.javaClass.simpleName}"; break
                }
            }
        }
        vyvod = thread(name = "kamera-dekoder", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            while (dekodiruet) {
                val i = try { d.dequeueOutputBuffer(info, 20_000) } catch (e: IllegalStateException) { break }
                if (i >= 0) {
                    try { d.releaseOutputBuffer(i, true) } catch (e: IllegalStateException) { break }
                    pokazano++
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = try { d.outputFormat } catch (e: IllegalStateException) { break }
                    fun k(n: String) = if (f.containsKey(n)) f.getInteger(n) else -1
                    val ow = if (k("crop-right") >= 0) k("crop-right") - maxOf(0, k("crop-left")) + 1 else k(MediaFormat.KEY_WIDTH)
                    val oh = if (k("crop-bottom") >= 0) k("crop-bottom") - maxOf(0, k("crop-top")) + 1 else k(MediaFormat.KEY_HEIGHT)
                    if (ow > 0 && oh > 0) { w = ow; h = oh; razvilka?.kameraRazmer(ow, oh) }
                }
            }
        }
    }

    private fun ostanovit() {
        val d = dec ?: return
        dekodiruet = false
        vyvod?.join(500)
        podacha?.join(500)
        try { d.stop() } catch (e: Exception) { }
        try { d.release() } catch (e: Exception) { }
        dec = null
        vyvod = null
        podacha = null
        zapas.clear()
    }

    // ---------- управление ----------

    override fun status(): String {
        val v = vid
        val ip = ip()
        return "{\"state\":\"${priyom.state}\",\"ot\":${priyom.ot?.let { FrameServer.json(it) } ?: "null"}," +
            "\"razmer\":\"${w}x$h\",\"fps\":${priyom.fps},\"kbps\":${priyom.kbps},\"kadrov\":${priyom.kadrov}," +
            "\"pokazano\":$pokazano,\"klip_sek\":${klip.sekund},\"propushcheno\":$propushcheno,\"v_efire\":${dec != null}," +
            "\"vid\":\"${v.rezhim}\",\"gde\":\"${v.gde}\",\"dolya\":${v.dolya}," +
            "\"povorot\":${v.povorot},\"vplotnuyu\":${v.vplotnuyu},\"sboku\":${v.sboku}," +
            "\"ramka\":[${v.ramka.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }}]," +
            "\"slushaet\":${priyom.slushaet},\"popytok\":${priyom.popytok},\"perezapuskov\":${priyom.perezapuskov}," +
            "\"adres\":${ip?.let { FrameServer.json("rtmp://$it:${RtmpPriyom.PORT}/kamera") } ?: "null"}," +
            "\"oshibka\":${(oshibka ?: priyom.oshibka ?: klip.oshibka)?.let { FrameServer.json(it) } ?: "null"}}"
    }

    override fun vid(q: Map<String, String>): String {
        val ar = if (w > 0 && h > 0) Okno.proporcii(w, h, vid.povorot) else 16f / 9f
        val v = Okno.izmenit(Okno.Vid.iz(q, vid), q, ar)
        vid = v
        razvilka?.vid = v
        try { File(dir, VID).writeText(v.toString()) } catch (e: Exception) { }
        return status()
    }

    /**
     * Открыть на 2 минуты страницу для айфона: http://plus.local:8770 (и по адресу шлема).
     * Ответ — для /kamera/stranica и кнопки: адреса без ключа (ключ — только на самой странице).
     */
    override fun stranica(): String {
        val ip = ip() ?: return "{\"oshibka\":\"шлем не в Wi-Fi\"}"
        try { aifon.otkryt() } catch (e: Exception) {
            return "{\"oshibka\":${FrameServer.json("страница не открылась: ${e.message}")}}"
        }
        return "{\"adres\":\"http://${MdnsImya.IMYA}:${KameraStranica.PORT}\",\"ip\":\"http://$ip:${KameraStranica.PORT}\"," +
            "\"sek\":${aifon.ostalos}}"
    }

    /** Открыть страницу айфона ради ссылок гостей (на 10 минут): ответ — адрес /gosti и он же по IP (без ключей). */
    fun stranicaGosti(): String {
        val ip = ip() ?: return "{\"oshibka\":\"шлем не в Wi-Fi\"}"
        try { aifon.otkryt(KameraStranica.SEK_GOSTI) } catch (e: Exception) {
            return "{\"oshibka\":${FrameServer.json("страница не открылась: ${e.message}")}}"
        }
        return "{\"adres\":\"http://${MdnsImya.IMYA}:${KameraStranica.PORT}/gosti\",\"ip\":\"http://$ip:${KameraStranica.PORT}/gosti\"," +
            "\"sek\":${aifon.ostalos}}"
    }

    /** Строка для экрана приложения после кнопки. */
    fun stranicaTekst(): String {
        val ip = ip() ?: return "Камера: шлем не в Wi-Fi — айфону не к чему подключаться."
        try { aifon.otkryt() } catch (e: Exception) { return "Камера: страница не открылась (${e.message})." }
        return "КАМЕРА ТЕЛЕФОНА — на айфоне в Safari открой:\n${MdnsImya.IMYA}:${KameraStranica.PORT}\n" +
            "(если не откроется — http://$ip:${KameraStranica.PORT})\nи нажми «Добавить в Moblin» (или Larix). " +
            "Страница открыта 2 минуты; сохрани её закладкой — адрес всегда тот же.\n" + kratko()
    }

    /** Адрес для Moblin/Larix вместе с ключом — только для страницы на айфоне. */
    fun adresSKlyuchom(): String? = ip()?.let { "rtmp://$it:${RtmpPriyom.PORT}/kamera/$klyuch" }

    /** Ключ засветился — новый; телефону нужно вписать новый адрес. */
    fun novyKlyuch() {
        aifon.zakryt()
        klyuch = sozdatKlyuch()
        try { File(dir, KLYUCH).writeText(klyuch) } catch (e: Exception) { }
    }

    /** Строка для экрана приложения и уведомления. */
    fun kratko(): String = when (priyom.state) {
        "idyot" -> "КАМЕРА: ${w}×$h, ${priyom.fps} к/с, ${priyom.kbps} кбит/с · " +
            (if (dec != null) "в эфире (${opisanie(vid)})" else "появится, когда начнётся эфир")
        "zhdu" -> "камера: ждёт телефон" + (oshibka ?: priyom.oshibka)?.let { " · $it" }.orEmpty()
        else -> "камера: порт закрыт — открываю заново" + (oshibka ?: priyom.oshibka)?.let { " · $it" }.orEmpty()
    }

    private fun zagruzitKlyuch(): String = try {
        File(dir, KLYUCH).readText().trim().takeIf { it.length >= 8 }
    } catch (e: Exception) { null } ?: sozdatKlyuch().also { try { File(dir, KLYUCH).writeText(it) } catch (e: Exception) { } }

    private fun zagruzitVid(): Okno.Vid = try { Okno.Vid.izStroki(File(dir, VID).readText()) } catch (e: Exception) { Okno.Vid() }

    companion object {
        private const val KLYUCH = "kamera-klyuch"
        private const val VID = "kamera-vid"
        private const val MAKS_KADR = 1_000_000
        /** Запас перед декодером: ~0,3–0,7 с при 60/30 к/с — заминку переждать, задержку не копить. */
        private const val ZAPAS = 20
        private const val BUKVY = "abcdefghijkmnpqrstuvwxyz23456789"     // без 0/o, 1/l — легко вписать

        fun sozdatKlyuch(): String {
            val r = SecureRandom()
            return (1..10).map { BUKVY[r.nextInt(BUKVY.length)] }.joinToString("")
        }

        fun opisanie(v: Okno.Vid): String = when (v.rezhim) {
            "krupno" -> "во весь кадр"
            "skryt" -> "скрыта"
            "svoe" -> "своё окно: слева ${v.ramka[0].roundToInt()} %, сверху ${v.ramka[1].roundToInt()} %, " +
                "справа ${(100 - v.ramka[2]).roundToInt()} %, снизу ${(100 - v.ramka[3]).roundToInt()} % от краёв кадра" +
                (if (v.povorot != 0) ", повёрнута на ${v.povorot}°" else "")
            else -> "угол " + (if (v.gde.startsWith("l")) "слева" else "справа") + " " +
                (if (v.gde.endsWith("n")) "внизу" else "вверху") + ", ${(v.dolya * 100).toInt()} %" +
                (if (v.povorot != 0) ", повёрнута на ${v.povorot}°" else "") +
                (if (v.vplotnuyu) (if (v.gde.endsWith("n")) ", снизу вплотную" else ", сверху вплотную") else "") +
                (if (v.sboku <= 0f) ", сбоку вплотную" else ", сбоку ${if (v.sboku % 1f == 0f) v.sboku.toInt().toString() else v.sboku.toString()} % ширины")
        }

        /** Адрес шлема в домашней сети (Wi-Fi). */
        fun ip(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }
    }
}
