package com.questclaude.bridge

import java.io.File

/**
 * Гости эфира (2.5.0) — всё вместе: комната (Komnata), сервер страницы и WebSocket (GostiServer,
 * 127.0.0.1:8771 — снаружи туннель cloudflared из `gosti start`), звук (Mikser + GostiZvuk), видео
 * (GostiVideo) и картинка эфира гостям (Prevyu). Живёт со службой глаз; комната открыта от
 * /gosti/start до /gosti/stop. Видео и картинка эфира — только пока идёт эфир (нужна развилка).
 * Ключи ссылок — в закрытой папке приложения; наружу — только на страницу айфона (plus.local:8770/gosti)
 * и пультам (хозяин, модератор). Адрес туннеля тоже не печатается.
 */
class Gosti(private val dir: File, private val stranicaAifona: () -> String?) : GostiServer.Media, FrameServer.GostiUpr,
    Komnata.Slushatel {
    private val klyuchi = Komnata.Klyuchi.iz(chitat(KLYUCHI)) { pisat(KLYUCHI, it) }
    val komnata = Komnata(klyuchi).also { it.slushatel = this }
    val mikser = Mikser(komnata, { g, c, n -> Hub.rechi.podat(g, c, n) }, { Hub.ear.state == "hearing" })
    private val video = GostiVideo { id -> komnata.po(id)?.svyaz?.tekst("{\"t\":\"kluch\"}") }
    private var server: GostiServer? = null
    private var zvuk: GostiZvuk? = null
    @Volatile private var razvilka: Razvilka? = null
    private var prevyu: Prevyu? = null
    @Volatile private var kodek: String? = null
    private val kodekPoslan = HashSet<Int>()
    private val micTap: (ShortArray, Int) -> Unit = { c, n -> mikser.mic(c, n) }
    /** Картинка эфира гостям (gosti kartinka vkl|vykl): без неё — только звук (раздача айфона тесная). */
    @Volatile var kartinka = chitat(KARTINKA)?.trim() != "0"
    @Volatile var oshibka: String? = null; private set
    /** Меняется при каждом изменении состава — плашке гостей пора перерисоваться. */
    @Volatile var versiya = 0L; private set

    val idyot: Boolean get() = server != null

    init {
        chitat(NASTROYKA)?.trim()?.split(' ')?.let { p ->
            p.getOrNull(0)?.toFloatOrNull()?.let { mikser.gromkostShlem = it.coerceIn(0f, 1.5f) }
            p.getOrNull(1)?.toFloatOrNull()?.let { mikser.gromkostEfir = it.coerceIn(0f, 2f) }
            p.getOrNull(2)?.toFloatOrNull()?.let { mikser.eho = it.coerceIn(0f, 1f) }
            p.getOrNull(3)?.toFloatOrNull()?.let { mikser.ehoDb = it.coerceIn(0f, 30f) }
        }
    }

    // ---------- открыть и закрыть ----------

    @Synchronized override fun start(): String {
        if (server == null) {
            val s = GostiServer(GostiServer.PORT, komnata, { imya -> fayl(imya) }, this)
            try { s.start() } catch (e: Exception) { return oshibkaJson("порт ${GostiServer.PORT} занят: ${e.message}") }
            server = s
            zvuk = GostiZvuk(mikser).also { it.start() }
            Hub.micTapy += micTap
            Hub.mikser = mikser
            Pamyat.zapis("гости: комната открыта")
        }
        return status()
    }

    @Synchronized override fun stop(): String {
        Hub.micTapy -= micTap
        if (Hub.mikser === mikser) Hub.mikser = null
        server?.stop()
        server = null
        zvuk?.stop()
        zvuk = null
        komnata.adres = null
        prevyu?.stop(); prevyu = null
        for (u in komnata.vse()) { video.ubrat(u.id); mikser.ushel(u) }
        razvilka?.plitki = emptyList()
        versiya++
        Pamyat.zapis("гости: комната закрыта")
        return status()
    }

    // ---------- эфир ----------

    /** Эфир начался: плитки и картинка эфира — в развилку. */
    @Synchronized fun podklyuchit(rz: Razvilka) {
        razvilka = rz
        komnata.efir = true
        video.podklyuchit(rz)
        izmenilos()
    }

    /** Эфир кончился (до release развилки). */
    @Synchronized fun otklyuchit() {
        komnata.efir = false
        prevyu?.stop(); prevyu = null
        kodek = null
        synchronized(kodekPoslan) { kodekPoslan.clear() }
        video.otklyuchit()
        razvilka?.plitki = emptyList()
        razvilka = null
        versiya++
    }

    // ---------- Komnata.Slushatel ----------

    /** Состав или места поменялись: плитки, картинка эфира, плашка. */
    override fun izmenilos() {
        val rz = razvilka
        val ekran = komnata.naEkrane()
        val p = Raskladka.plitki(ekran.size, komnata.mest)
        rz?.plitki = ekran.mapIndexed { i, u -> u.id to p[i] }
        versiya++
        synchronized(this) {
            val nuzhno = rz != null && kartinka && server != null &&
                komnata.vnutri().any { it.rol != "hozyain" && it.prevyu && it.h264 }
            if (nuzhno && prevyu == null) {
                prevyu = try {
                    Prevyu(rz!!, { d, key, ms -> razoslat(d, key, ms) }, { k -> kodek = k; kodekVsem() }).also { it.start() }
                } catch (e: Exception) { oshibka = "картинка эфира: ${e.message}"; null }
            } else if (!nuzhno && prevyu != null) {
                prevyu?.stop(); prevyu = null
                synchronized(kodekPoslan) { kodekPoslan.clear() }
            }
        }
        kodekVsem()
    }

    /** Кодек картинки эфира — тем, кто её ещё не знает; новый — просит ключевой кадр. */
    private fun kodekVsem() {
        val k = kodek ?: return
        var novye = false
        for (u in komnata.vnutri()) {
            if (u.rol == "hozyain" || !u.h264) continue
            val nov = synchronized(kodekPoslan) { kodekPoslan.add(u.id) }
            if (nov) {
                u.svyaz.tekst("{\"t\":\"video\",\"kodek\":\"$k\",\"w\":${Prevyu.W},\"h\":${Prevyu.H}}")
                novye = true
            }
        }
        if (novye) prevyu?.klyuchevoy()
    }

    private fun razoslat(d: ByteArray, key: Boolean, ms: Long) {
        var kadr: ByteArray? = null
        for (u in komnata.vnutri()) {
            if (u.rol == "hozyain" || !u.prevyu || !u.h264) continue
            if (synchronized(kodekPoslan) { u.id !in kodekPoslan }) continue
            val k = kadr ?: GostiServer.kadrVideo(key, ms, d).also { kadr = it }
            u.svyaz.dvoichnoe(k, 'v', key)
        }
    }

    // ---------- GostiServer.Media ----------

    override fun zvuk(u: Komnata.Uchastnik, seq: Int, d: ByteArray, off: Int, len: Int) = mikser.prinyat(u, seq, d, off, len)
    override fun video(u: Komnata.Uchastnik, key: Boolean, ms: Long, d: ByteArray, off: Int, len: Int, kodek: Char) =
        video.kadr(u.id, key, d, off, len, kodek)
    override fun klyuchevoyEfira() { prevyu?.klyuchevoy() }
    override fun ushel(u: Komnata.Uchastnik) {
        mikser.ushel(u)
        video.ubrat(u.id)
        synchronized(kodekPoslan) { kodekPoslan.remove(u.id) }
        izmenilos()
    }

    // ---------- для плашки (Nalozhenie) ----------

    /** Гости в кадре и их плитки. */
    fun naEkrane(): List<Pair<Komnata.Uchastnik, Raskladka.Plitka>> {
        val e = komnata.naEkrane()
        val p = Raskladka.plitki(e.size, komnata.mest)
        return e.mapIndexed { i, u -> u to p[i] }
    }

    fun videoVidno(id: Int) = razvilka?.gostVidno(id) == true

    // ---------- FrameServer.GostiUpr ----------

    override fun status(): String {
        val s = server
        return "{\"idyot\":${s != null},\"efir\":${razvilka != null},\"kartinka\":$kartinka,\"komnata\":${komnata.status()}," +
            "\"zvuk\":${mikser.status()},\"video\":${video.status()},\"prevyu\":{\"idyot\":${prevyu != null}," +
            "\"kadrov\":${prevyu?.kadrov ?: 0},\"kodek\":${Komnata.js(kodek)}},\"set\":{\"prinyato\":${s?.prinyato?.get() ?: 0}," +
            "\"otpravleno\":${s?.otpravleno?.get() ?: 0},\"vybrosheno\":${s?.vybrosheno ?: 0}}," +
            "\"oshibka\":${Komnata.js(oshibka ?: zvuk?.oshibka ?: video.oshibka ?: prevyu?.oshibka)}}"
    }

    /** chto: vpustit, otklonit, vygnat, skryt, pokazat, zaglushit, vklyuchit, novaya, vpusk (dop), mest (dop); kto — imya или id. */
    override fun upr(q: Map<String, String>): String {
        if (server == null) return oshibkaJson("гостей нет: сначала gosti start")
        val chto = q["chto"] ?: return oshibkaJson("chto?")
        val kto = q["kto"]?.let { Titry.dekod(it).trim() }
        val id = kto?.let { k ->
            k.toIntOrNull()?.takeIf { komnata.po(it) != null } ?: komnata.vse().filter { it.rol != "hozyain" }
                .firstOrNull { it.imya.equals(k, ignoreCase = true) }?.id
            ?: komnata.vse().filter { it.rol != "hozyain" }.firstOrNull { it.imya.startsWith(k, ignoreCase = true) }?.id
            ?: return oshibkaJson("нет гостя «$k»")
        }
        val otvet = komnata.upr(null, chto, id, q["dop"])
        return otvet.dropLast(1) + ",\"status\":${komnata.status()}}"
    }

    /** Адрес туннеля (https://….trycloudflare.com) от gosti; пусто — туннеля нет. Наружу не отдаётся. */
    override fun adres(u: String?): String {
        val a = u?.trim()?.trimEnd('/')
        komnata.adres = a?.takeIf { ADRES.matches(it) }
        komnata.razoslatSsylki()
        return "{\"ok\":${a == null || komnata.adres != null},\"tunnel\":${komnata.adres != null}}"
    }

    /** Открыть на 2 минуты страницу ссылок для айфона (plus.local:8770/gosti). */
    override fun ssylki(): String = stranicaAifona() ?: oshibkaJson("страница не открылась")

    override fun sobytiya(posle: Long, zhdatMs: Long): String {
        val konec = System.currentTimeMillis() + zhdatMs.coerceIn(0, 25_000)
        var s = komnata.sobytiyaPosle(posle)
        while (s.isEmpty() && System.currentTimeMillis() < konec) { Thread.sleep(250); s = komnata.sobytiyaPosle(posle) }
        return "{\"posl\":${komnata.poslednee},\"sobytiya\":[" + s.joinToString(",") {
            "{\"id\":${it.id},\"vid\":\"${it.vid}\",\"imya\":${Komnata.js(it.imya)},\"ms\":${it.ms}}"
        } + "]}"
    }

    override fun nastroyka(q: Map<String, String>): String {
        q["shlem"]?.toFloatOrNull()?.let { mikser.gromkostShlem = it.coerceIn(0f, 1.5f) }
        q["efir"]?.toFloatOrNull()?.let { mikser.gromkostEfir = it.coerceIn(0f, 2f) }
        q["eho"]?.toFloatOrNull()?.let { mikser.eho = it.coerceIn(0f, 1f) }
        q["eho_db"]?.toFloatOrNull()?.let { mikser.ehoDb = it.coerceIn(0f, 30f) }
        q["kartinka"]?.let { kartinka = it != "0"; pisat(KARTINKA, if (kartinka) "1" else "0"); izmenilos() }
        pisat(NASTROYKA, "${mikser.gromkostShlem} ${mikser.gromkostEfir} ${mikser.eho} ${mikser.ehoDb}")
        return status()
    }

    /** Страница ссылок для айфона Александра (по Wi-Fi, 2 минуты): три ссылки — поделиться и скопировать. */
    fun stranicaHtml(): String = KameraStranica.gostiHtml(komnata.ssylka(klyuchi.hozyain), komnata.ssylka(klyuchi.moder),
        komnata.ssylka(klyuchi.gost), komnata.vse().count { it.vnutri && it.rol != "hozyain" })

    // ---------- внутреннее ----------

    private fun chitat(f: String): String? = try { File(dir, f).readText() } catch (e: Exception) { null }
    private fun pisat(f: String, t: String) { try { File(dir, f).writeText(t) } catch (e: Exception) { } }
    private fun oshibkaJson(s: String) = "{\"ok\":false,\"oshibka\":${FrameServer.json(s)}}"

    /** Файл страницы гостя — из assets/gosti (ставит служба глаз). */
    @Volatile var fayl: (String) -> ByteArray? = { null }

    companion object {
        private const val KLYUCHI = "gosti-klyuchi"
        private const val KARTINKA = "gosti-kartinka"
        private const val NASTROYKA = "gosti-nastroyka"
        private val ADRES = Regex("https://[a-z0-9.-]{3,253}")
    }
}
