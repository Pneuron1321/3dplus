package com.questclaude.bridge

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Комната гостей эфира (2.5.0, Александр 13.10: «ещё два соучастника на стриме, одного сделаю
 * модератором, чтобы он мог делиться ключом на подключение»). Чистая логика без сети и звука — кто
 * в комнате, кто ждёт, кто в кадре, кто что может; проверяется на столе (KomnataTest).
 *
 * Роли — по ключу из ссылки (часть после #, в журналы Cloudflare не попадает):
 *  - hozyain — Александр (айфон): только управление, в кадре его нет; видит обе ссылки;
 *  - moder — модератор: участник (камера, микрофон) и управление; делится ссылкой гостя;
 *  - gost — гость: ждёт впуска (если впуск не «авто»), потом участник.
 * Мест в кадре — [mest] (1–3, по умолчанию 2): участник с камерой или микрофоном занимает место;
 * без них (модератор только с пультом) — нет. Цвет и голос субтитров — по месту в порядке входа:
 * g1 — розовый, g2 — оранжевый, g3 — сиреневый.
 */
class Komnata(
    private val klyuchi: Klyuchi,
    private val chasy: () -> Long = { System.currentTimeMillis() },
) {
    /** Связь с участником (соединение сервера): текст — JSON; закрыть — с причиной. Не блокирует. */
    interface Svyaz {
        fun tekst(json: String)
        fun zakryt(prichina: String)
        /** Двоичный кадр: звук 'a', картинка 'v' (key — ключевой кадр). */
        fun dvoichnoe(d: ByteArray, vid: Char, key: Boolean) {}
    }

    /** Что делать со звуком и картинкой (движок гостей): вошёл в кадр, вышел, поменялось. */
    interface Slushatel {
        fun izmenilos() {}
    }

    class Uchastnik(val id: Int, val rol: String, var imya: String, val svyaz: Svyaz, val prishelMs: Long) {
        /** Жетон возврата: оборвалась сеть — с ним в течение VOZVRAT_MS сразу внутрь, без впуска. */
        val zheton: String = Klyuchi.sozdat()
        @Volatile var sost = "zhdet"                 // zhdet | vnutri
        @Volatile var kamera = false
        @Volatile var mikrofon = false
        @Volatile var skryt = false                  // убран с экрана модератором
        @Volatile var zaglushen = false              // микрофон выключен модератором
        @Volatile var kameraZapret = false           // камера выключена модератором (кадры не принимаем)
        @Volatile var cvet = -1                      // 0..2 → g1..g3; -1 — без места
        @Volatile var vnutriMs = 0L
        @Volatile var igra = IGRA                    // громкость игры в его смеси, 0–1
        @Volatile var prevyu = true                  // хочет картинку эфира
        @Volatile var h264 = false                   // умеет раскодировать H.264 (WebCodecs)
        @Volatile var govorit = false                // слышно сейчас (ставит движок звука)
        val vnutri: Boolean get() = sost == "vnutri"
        val media: Boolean get() = kamera || mikrofon
        val upravlyaet: Boolean get() = rol == "hozyain" || rol == "moder"
        /** Камера в кадре: включена у гостя и не выключена модератором. Нет — строка с именем вместо квадрата. */
        val sVideo: Boolean get() = kamera && !kameraZapret
        /** Голос в субтитрах и цвет: g1…g3; без места — "". */
        val golos: String get() = if (cvet >= 0) "g${cvet + 1}" else ""
    }

    /** Ключи ссылок: хозяин, модератор, гость. Хранит и меняет приложение (файл в закрытой папке). */
    class Klyuchi(hozyain: String, moder: String, gost: String, private val sohranit: (String) -> Unit = {}) {
        @Volatile var hozyain = hozyain; private set
        @Volatile var moder = moder; private set
        @Volatile var gost = gost; private set

        /** Роль по ключу — сравнение за одинаковое время (по ответам не подобрать). */
        fun rol(k: String): String? {
            val b = k.toByteArray()
            var r: String? = null
            for ((rol, kl) in listOf("hozyain" to hozyain, "moder" to moder, "gost" to gost))
                if (MessageDigest.isEqual(b, kl.toByteArray())) r = rol
            return r
        }

        fun novyGost() { gost = sozdat(); sohranit(vStroku()) }
        fun vseNovye() { hozyain = sozdat(); moder = sozdat(); gost = sozdat(); sohranit(vStroku()) }
        fun vStroku() = "$hozyain $moder $gost"

        companion object {
            private const val BUKVY = "abcdefghijkmnpqrstuvwxyz23456789"     // без 0/o, 1/l
            const val DLINA = 16                                              // 32^16 = 2^80
            private val rnd = SecureRandom()

            fun sozdat(): String = (1..DLINA).map { BUKVY[rnd.nextInt(BUKVY.length)] }.joinToString("")

            fun pohozh(k: String) = k.length == DLINA && k.all { it in BUKVY }

            /** Из строки файла «хозяин модератор гость»; битое — новые (и сохранить). */
            fun iz(s: String?, sohranit: (String) -> Unit): Klyuchi {
                val p = s?.trim()?.split(' ')?.filter { pohozh(it) }
                if (p != null && p.size == 3 && p.toSet().size == 3) return Klyuchi(p[0], p[1], p[2], sohranit)
                return Klyuchi(sozdat(), sozdat(), sozdat(), sohranit).also { sohranit(it.vStroku()) }
            }
        }
    }

    class Sobytie(val id: Long, val vid: String, val imya: String, val ms: Long)

    /** Кто вышел не по своей воле (оборвалась сеть): жетон → роль, имя, цвет — вернуться сразу внутрь. */
    /** Место того, у кого оборвалась сеть: вернётся с жетоном — с теми же цветом и запретами модератора. */
    private class Vozvrat(val rol: String, val imya: String, val cvet: Int, val ms: Long,
                          val skryt: Boolean = false, val zaglushen: Boolean = false, val kameraZapret: Boolean = false,
                          val igra: Float = IGRA) {
        constructor(u: Uchastnik, ms: Long) : this(u.rol, u.imya, u.cvet, ms, u.skryt, u.zaglushen, u.kameraZapret, u.igra)
    }
    private val vozvraty = HashMap<String, Vozvrat>()

    private val lyudi = LinkedHashMap<Int, Uchastnik>()
    private var sledId = 1
    private val sobytiya = ArrayDeque<Sobytie>()
    private var sledSobytie = 1L
    private val zamok = Object()

    @Volatile var slushatel: Slushatel? = null
    /** Мест в кадре (1–3). */
    @Volatile var mest = MEST
        private set
    /** Гостя впускают сами (без ожидания). */
    @Volatile var vpuskAvto = false
    /** Адрес туннеля (https://….trycloudflare.com) — для ссылок; ставит gosti. Секрет: не в кадр. */
    @Volatile var adres: String? = null
    /** Идёт ли эфир — гостям в «привет» и при смене. */
    @Volatile var efir = false
        set(v) { if (field != v) { field = v; vsem("{\"t\":\"efir\",\"idyot\":$v}") } }

    // ---------- вход и выход ----------

    /**
     * Вход по сообщению {"t":"vhod","k":…,"imya":…,"kamera":…,"mikrofon":…,"h264":…}. Ключ не подошёл,
     * мест в ожидании нет — участника нет (null), связь закрыта с причиной.
     */
    fun vhod(svyaz: Svyaz, k: String, imya0: String?, kamera: Boolean, mikrofon: Boolean, h264: Boolean = false,
             zheton: String? = null): Uchastnik? {
        val rol = klyuchi.rol(k)
        if (rol == null) { svyaz.zakryt("klyuch"); return null }
        synchronized(zamok) {
            vozvraty.entries.removeAll { chasy() - it.value.ms > VOZVRAT_MS }
            // Сеть оборвалась молча (iPhone после фона): сервер ещё считает старое соединение живым, а страница
            // уже пришла с жетоном. Старое — призрак: его место (и запреты модератора) переходят новому.
            val prizrak = zheton?.takeIf { it.isNotEmpty() }?.let { z -> lyudi.values.firstOrNull { it.zheton == z && it.vnutri } }
            if (prizrak != null && prizrak.rol == rol) {
                lyudi.remove(prizrak.id)
                vozvraty[prizrak.zheton] = Vozvrat(prizrak, chasy())
                prizrak.svyaz.zakryt("zamena")
            }
            val v = zheton?.let { vozvraty.remove(it) }?.takeIf { it.rol == rol }
            if (v != null && lyudi.values.count { it.vnutri } < MAKS_VNUTRI) {      // вернулся после обрыва — сразу внутрь
                val u = Uchastnik(sledId++, rol, v.imya, svyaz, chasy())
                u.h264 = h264
                u.skryt = v.skryt; u.zaglushen = v.zaglushen; u.kameraZapret = v.kameraZapret; u.igra = v.igra
                if (rol != "hozyain") { u.kamera = kamera; u.mikrofon = mikrofon }
                lyudi[u.id] = u
                if (u.media && zanyato() >= mest) { u.kamera = false; u.mikrofon = false }
                u.sost = "vnutri"
                u.vnutriMs = chasy()
                if (u.rol != "hozyain" && u.media)
                    u.cvet = if (v.cvet >= 0 && lyudi.values.none { it !== u && it.vnutri && it.cvet == v.cvet }) v.cvet else svobodnyCvet()
                privet(u)
                razoslatSostav()
                slushatel?.izmenilos()
                return u
            }
            val vnutri = lyudi.values.count { it.vnutri }
            val zhdut = lyudi.values.count { !it.vnutri }
            if (rol == "gost" && !vpuskAvto && zhdut >= MAKS_ZHDUT) { svyaz.zakryt("ochered"); return null }
            if (vnutri >= MAKS_VNUTRI && (rol != "gost" || vpuskAvto)) { svyaz.zakryt("polno"); return null }
            if (rol == "hozyain" && lyudi.values.count { it.rol == "hozyain" } >= 2) { svyaz.zakryt("polno"); return null }
            val u = Uchastnik(sledId++, rol, imya(imya0, rol), svyaz, chasy())
            u.h264 = h264
            if (rol != "hozyain") { u.kamera = kamera; u.mikrofon = mikrofon }
            lyudi[u.id] = u
            if (rol == "gost" && !vpuskAvto) {
                privet(u)
                sobytie("zhdet", u.imya)
                razoslatSostav()
            } else if (!vpustitIn(u, sam = true)) return null        // гость по «авто», а мест нет
            return u
        }
    }

    /** Соединение закрылось. */
    fun uhod(u: Uchastnik, sam: Boolean = false): Unit = synchronized(zamok) {
        if (lyudi.remove(u.id) == null) return                                 // выгнали или отказали — возврата нет
        if (u.vnutri && !sam) vozvraty[u.zheton] = Vozvrat(u, chasy())
        if (u.vnutri && u.rol != "hozyain") sobytie("vyshel", u.imya)
        u.cvet = -1
        razoslatSostav()
        slushatel?.izmenilos()
    }

    // ---------- управление (хозяин, модератор, Плюс через /gosti/upr) ----------

    /**
     * Команда управления: vpustit, otklonit, vygnat, skryt, pokazat, zaglushit, vklyuchit, novaya
     * (новый ключ гостя), vpusk (rezhim avto|ruchnoy), mest (n). kto — кто велел (null — Плюс/скрипт:
     * можно всё). Ответ — JSON с ok и ошибкой.
     */
    fun upr(kto: Uchastnik?, chto: String, id: Int?, dop: String? = null): String = synchronized(zamok) {
        if (kto != null && !kto.upravlyaet) return oshibka("нет прав")
        val u = id?.let { lyudi[it] }
        if (id != null && u == null) return oshibka("нет такого")
        if (u != null && u.rol == "hozyain") return oshibka("хозяина не трогаем")
        if (u != null && kto != null && kto.rol == "moder" && u.rol == "moder" && u !== kto && chto == "vygnat")
            return oshibka("модератора выгоняет хозяин")
        when (chto) {
            "vpustit" -> {
                val c = u ?: lyudi.values.filter { !it.vnutri }.minByOrNull { it.prishelMs } ?: return oshibka("никто не ждёт")
                if (c.vnutri) return oshibka("уже внутри")
                if (!vpustitIn(c, sam = false)) return oshibka("мест в кадре нет: ${mest}")
            }
            "otklonit" -> {
                val c = u ?: return oshibka("кого?")
                if (c.vnutri) return oshibka("уже внутри — тогда vygnat")
                lyudi.remove(c.id); c.svyaz.zakryt("otkaz"); razoslatSostav()
            }
            "vygnat" -> {
                val c = u ?: return oshibka("кого?")
                lyudi.remove(c.id)
                c.cvet = -1
                c.svyaz.zakryt("vygnali")
                sobytie("vygnan", c.imya)
                razoslatSostav(); slushatel?.izmenilos()
            }
            "skryt", "pokazat" -> {
                val c = u ?: return oshibka("кого?")
                c.skryt = chto == "skryt"
                c.svyaz.tekst("{\"t\":\"skryt\",\"da\":${c.skryt}}")
                razoslatSostav(); slushatel?.izmenilos()
            }
            "zaglushit", "vklyuchit" -> {
                val c = u ?: return oshibka("кого?")
                c.zaglushen = chto == "zaglushit"
                c.svyaz.tekst("{\"t\":\"zaglushen\",\"da\":${c.zaglushen}}")
                razoslatSostav(); slushatel?.izmenilos()
            }
            "kamera_vykl", "kamera_vkl" -> {                 // камеру гостя — модератор (как микрофон)
                val c = u ?: return oshibka("кого?")
                c.kameraZapret = chto == "kamera_vykl"
                c.svyaz.tekst("{\"t\":\"kamera_zapret\",\"da\":${c.kameraZapret}}")
                razoslatSostav(); slushatel?.izmenilos()
            }
            "novaya" -> { klyuchi.novyGost(); razoslatSsylki() }
            "vpusk" -> {
                if (kto != null && kto.rol != "hozyain") return oshibka("впуск меняет хозяин")
                vpuskAvto = dop == "avto"
                if (vpuskAvto) lyudi.values.filter { !it.vnutri }.sortedBy { it.prishelMs }.forEach { vpustitIn(it, sam = false) }
                razoslatSostav()
            }
            "mest" -> {
                if (kto != null && kto.rol != "hozyain") return oshibka("места меняет хозяин")
                val n = dop?.toIntOrNull()?.coerceIn(1, MAKS_MEST) ?: return oshibka("mest 1…$MAKS_MEST")
                mest = n
                razoslatSostav(); slushatel?.izmenilos()
            }
            else -> return oshibka("не знаю «$chto»")
        }
        return "{\"ok\":true}"
    }

    /** Участник сам: камера, микрофон, громкость игры, картинка эфира. */
    fun sam(u: Uchastnik, kamera: Boolean?, mikrofon: Boolean?, igra: Double?, prevyu: Boolean?): Unit = synchronized(zamok) {
        if (!lyudi.containsKey(u.id) || u.rol == "hozyain") return
        igra?.takeIf { !it.isNaN() }?.let { u.igra = it.toFloat().coerceIn(0f, 1f) }
        prevyu?.let { u.prevyu = it }
        val bylo = u.media
        val k = kamera ?: u.kamera
        val m = mikrofon ?: u.mikrofon
        if (u.vnutri && !bylo && (k || m) && zanyato() >= mest) {
            u.svyaz.tekst("{\"t\":\"mest_net\",\"mest\":$mest}")
            return
        }
        u.kamera = k; u.mikrofon = m
        if (u.vnutri && u.media && u.cvet < 0) u.cvet = svobodnyCvet()
        if (u.vnutri && !u.media) u.cvet = -1
        razoslatSostav(); slushatel?.izmenilos()
    }

    // ---------- что видно ----------

    /** В кадре — по порядку входа: внутри, с камерой или микрофоном, не скрыт; не больше mest. */
    fun naEkrane(): List<Uchastnik> = synchronized(zamok) {
        lyudi.values.filter { it.vnutri && it.media && !it.skryt && it.cvet >= 0 }.sortedBy { it.vnutriMs }.take(mest)
    }

    /** Чей звук идёт в эфир и другим: внутри, с местом, микрофон включён и не заглушён (скрытого слышно). */
    fun slyshno(): List<Uchastnik> = synchronized(zamok) {
        lyudi.values.filter { it.vnutri && it.cvet >= 0 && it.mikrofon && !it.zaglushen }
    }

    fun vnutri(): List<Uchastnik> = synchronized(zamok) { lyudi.values.filter { it.vnutri } }
    fun vse(): List<Uchastnik> = synchronized(zamok) { lyudi.values.toList() }
    fun po(id: Int): Uchastnik? = synchronized(zamok) { lyudi[id] }

    /** Имя по голосу субтитров (g1…g3) — для подписи; нет такого — null. */
    fun imyaGolosa(golos: String): String? = synchronized(zamok) { lyudi.values.firstOrNull { it.golos == golos }?.imya }

    /** Ссылка с ключом; r — подсказка странице (пульт хозяина — без камеры), права всё равно по ключу. */
    fun ssylka(kl: String): String? = adres?.let {
        "${it.trimEnd('/')}/#k=$kl" + when (kl) { klyuchi.hozyain -> "&r=h"; klyuchi.moder -> "&r=m"; else -> "" }
    }

    /** Ссылки для страницы на айфоне (хозяин): все три. Секрет — только туда. */
    fun ssylkiJson(): String = "{\"adres\":${adres != null},\"hozyain\":${js(ssylka(klyuchi.hozyain))}," +
        "\"moder\":${js(ssylka(klyuchi.moder))},\"gost\":${js(ssylka(klyuchi.gost))}}"

    /** Состояние для /gosti (без ключей и адреса). */
    fun status(): String = synchronized(zamok) {
        "{\"mest\":$mest,\"vpusk\":\"${if (vpuskAvto) "avto" else "ruchnoy"}\",\"tunnel\":${adres != null}," +
            "\"lyudi\":[" + lyudi.values.joinToString(",") { chelovek(it) } + "]}"
    }

    // ---------- события для Александра (голос в ухо) ----------

    /** События после [posle] (id); пусто — ничего нового. */
    fun sobytiyaPosle(posle: Long): List<Sobytie> = synchronized(zamok) { sobytiya.filter { it.id > posle } }
    val poslednee: Long get() = synchronized(zamok) { sledSobytie - 1 }

    /** Закрыть комнату (gosti stop): всех — прочь. */
    fun zakryt() = synchronized(zamok) {
        for (u in lyudi.values.toList()) u.svyaz.zakryt("zakryto")
        lyudi.clear()
        vozvraty.clear()
        slushatel?.izmenilos()
    }

    // ---------- внутреннее ----------

    private fun zanyato() = lyudi.values.count { it.vnutri && it.media && it.cvet >= 0 }

    private fun svobodnyCvet(): Int {
        val zanyaty = lyudi.values.filter { it.vnutri }.map { it.cvet }.toSet()
        return (0 until MAKS_MEST).first { it !in zanyaty }
    }

    /** Впустить: с камерой или микрофоном — только если есть место. sam — вошёл сам (модератор, авто). */
    private fun vpustitIn(u: Uchastnik, sam: Boolean): Boolean {
        if (u.rol != "hozyain" && u.media && zanyato() >= mest) {
            if (u.rol == "moder") { u.kamera = false; u.mikrofon = false }    // модератор — за кадром, с пультом
            else if (!sam || !vpuskAvto) return false
            else { u.svyaz.zakryt("mest_net"); lyudi.remove(u.id); return false }
        }
        u.sost = "vnutri"
        u.vnutriMs = chasy()
        if (u.rol != "hozyain" && u.media) u.cvet = svobodnyCvet()
        privet(u)
        if (u.rol != "hozyain") sobytie("voshel", u.imya)
        razoslatSostav()
        slushatel?.izmenilos()
        return true
    }

    private fun privet(u: Uchastnik) {
        u.svyaz.tekst("{\"t\":\"privet\",\"id\":${u.id},\"rol\":\"${u.rol}\",\"sost\":\"${u.sost}\",\"imya\":${js(u.imya)}," +
            "\"zheton\":\"${u.zheton}\"," +
            "\"cvet\":${u.cvet},\"kamera\":${u.kamera},\"mikrofon\":${u.mikrofon},\"efir\":$efir,\"mest\":$mest," +
            "\"skryt\":${u.skryt},\"zaglushen\":${u.zaglushen},\"kamera_zapret\":${u.kameraZapret}}")
        if (u.upravlyaet && u.vnutri) ssylkiDlya(u)
    }

    private fun ssylkiDlya(u: Uchastnik) {
        if (!u.vnutri) return
        val s = if (u.rol == "hozyain") "{\"t\":\"ssylki\",\"gost\":${js(ssylka(klyuchi.gost))},\"moder\":${js(ssylka(klyuchi.moder))}}"
            else "{\"t\":\"ssylki\",\"gost\":${js(ssylka(klyuchi.gost))}}"
        u.svyaz.tekst(s)
    }

    /** Новый адрес туннеля или ключ гостя — пультам. */
    fun razoslatSsylki() = synchronized(zamok) { lyudi.values.filter { it.upravlyaet }.forEach { ssylkiDlya(it) } }

    /** Состав: пультам — все (и ждущие); участникам — кто внутри. */
    fun razoslatSostav() = synchronized(zamok) {
        val vse = "{\"t\":\"sostav\",\"mest\":$mest,\"vpusk\":\"${if (vpuskAvto) "avto" else "ruchnoy"}\",\"lyudi\":[" +
            lyudi.values.joinToString(",") { chelovek(it) } + "]}"
        val vnutri = "{\"t\":\"sostav\",\"mest\":$mest,\"lyudi\":[" +
            lyudi.values.filter { it.vnutri && it.rol != "hozyain" }.joinToString(",") { chelovek(it) } + "]}"
        for (u in lyudi.values) if (u.vnutri) u.svyaz.tekst(if (u.upravlyaet) vse else vnutri)
    }

    /** Говорит ли кто-то — пультам и участникам (подсветка), только при смене. */
    fun govoryat(izmenilis: Collection<Uchastnik>) {
        if (izmenilis.isEmpty()) return
        val s = "{\"t\":\"govoryat\",\"id\":[" + vnutri().filter { it.govorit }.joinToString(",") { it.id.toString() } + "]}"
        vsem(s)
    }

    private fun vsem(s: String) = synchronized(zamok) { for (u in lyudi.values) if (u.vnutri) u.svyaz.tekst(s) }

    private fun chelovek(u: Uchastnik) = "{\"id\":${u.id},\"imya\":${js(u.imya)},\"rol\":\"${u.rol}\",\"sost\":\"${u.sost}\"," +
        "\"kamera\":${u.kamera},\"mikrofon\":${u.mikrofon},\"skryt\":${u.skryt},\"zaglushen\":${u.zaglushen}," +
        "\"kamera_zapret\":${u.kameraZapret},\"igra\":${u.igra}," +
        "\"cvet\":${u.cvet},\"govorit\":${u.govorit}}"

    private fun sobytie(vid: String, imya: String) {
        sobytiya.addLast(Sobytie(sledSobytie++, vid, imya, chasy()))
        while (sobytiya.size > 50) sobytiya.removeFirst()
    }

    private fun oshibka(s: String) = "{\"ok\":false,\"oshibka\":${js(s)}}"

    private fun imya(s: String?, rol: String): String {
        val t = chistoeImya(s)
        if (t.isNotEmpty()) return t
        return when (rol) { "hozyain" -> "Alexander"; "moder" -> "Moderator"; else -> "Guest ${lyudi.size + 1}" }
    }

    companion object {
        const val MEST = 2
        const val MAKS_MEST = 3
        const val MAKS_ZHDUT = 4
        const val MAKS_VNUTRI = 5
        const val MAKS_IMYA = 18
        const val IGRA = 0.5f
        const val VOZVRAT_MS = 90_000L

        /** Имя в кадре: буквы, цифры, пробел и « .-_'»; без управляющих; не длиннее MAKS_IMYA. */
        fun chistoeImya(s: String?): String {
            if (s == null) return ""
            val t = s.filter { it.isLetterOrDigit() || it == ' ' || it in ".-_'" }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            return t.take(MAKS_IMYA).trim()
        }

        fun js(s: String?) = if (s == null) "null" else FrameServer.json(s)
    }
}
