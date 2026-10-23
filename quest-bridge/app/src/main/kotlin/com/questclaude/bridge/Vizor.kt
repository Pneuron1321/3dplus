package com.questclaude.bridge

import java.io.File
import java.util.Locale

/**
 * Оформление эфира «Визор» (2.7.0, этап 1; docs/OFORMLENIE.md, макет docs/maket/vizor-1080.png): система стиля —
 * палитра, шрифты (имена файлов), размеры — и раскладка плашек в точках кадра 1080p (начало — слева вверху).
 * Чистый Kotlin: проверяется на столе (VizorTest — центр кадра свободен, ничто не вылезает за кадр, текст не
 * мельче минимума). Рисует Nalozhenie через VizorPlashki (шрифты и кисти — VizorKist), места слоёв ставит Razvilka.
 *
 * Кадр: золотые скобы по углам (всегда, пока стиль viz) и тонкая золотая линия сверху; левый верх — карточка
 * топ-донатора и цели доната (полоса визор → золото), справа от неё — табло соведущего; правый верх — налёт
 * пилюлей и Плюс (бюст, под ним плашка «PLUS co-host»); правый столбец — чат (имена фиолетом, метка площадки),
 * «сейчас играет», окно камеры в золотых скобах; низ по центру — субтитры пилюлей (кто говорит — золотом сверху);
 * левый низ — роза ветров. Центр кадра — 70 % ширины × 60 % высоты — свободен всегда.
 */
object Vizor {
    // ---------- палитра ----------
    const val GRAFIT = 0xFF0E1014.toInt()
    /** Подложка карточек: графит 80 %. */
    const val PODLOZHKA = 0xCC0E1014.toInt()
    /** Золото Плюса — скобы, акценты, цифры. */
    const val ZOLOTO = 0xFFF2C230.toInt()
    /** Визор (очки Плюса) — «в эфире», начало полосы цели. */
    const val VIZOR = 0xFFFF4A2B.toInt()
    /** Лёд — основной текст. */
    const val LED = 0xFFEEF2F7.toInt()
    /** Фиолет (гребень Плюса) — имена в чате. */
    const val FIOLET = 0xFF8B5CF6.toInt()
    /** Кант карточки — золото 35 %. */
    const val KANT = 0x59F2C230
    /** Метки площадок у сообщений чата. */
    const val TWITCH = 0xFF9146FF.toInt()
    const val KICK = 0xFF53FC18.toInt()
    const val YOUTUBE = 0xFFFF0033.toInt()

    // ---------- шрифты (assets/shrifty, OFL — лицензии рядом) ----------
    const val SHRIFT_ZAGOLOVKOV = "shrifty/Unbounded.ttf"
    const val SHRIFT_TEKSTA = "shrifty/Manrope.ttf"
    /** Вес вариативных шрифтов: заголовки — 800, текст — 600 (жирный текст — 800). */
    const val VES_ZAGOLOVKOV = 800
    const val VES_TEKSTA = 600
    const val VES_TEKSTA_ZHIRNO = 800

    // ---------- размеры ----------
    const val RADIUS = 14f
    const val KANT_SHIR = 1.5f
    /** Тень карточки: размытие и сдвиг вниз (битмап тени кэшируется по размеру). */
    const val TEN = 16f
    const val TEN_DY = 6f
    const val TEN_A = 115
    /** Скобы визора по углам кадра: длина плеча, толщина, отступ от края, свечение. */
    const val SKOBA = 56f
    const val SKOBA_T = 3f
    const val SKOBA_OTSTUP = 20f
    const val SKOBA_SVET = 10f
    /** Скобы окна камеры: плечо, толщина, вынос за окно. */
    const val KSKOBA = 34f
    const val KSKOBA_T = 3f
    const val KSKOBA_VYNOS = 8f
    /** Золотая линия сверху. */
    const val LINIYA_Y = 34f
    const val LINIYA_OT = 96f

    /** Минимум читаемого текста (Manrope) в кадре 1080 — с телефона мельче не прочесть. */
    const val MIN_TEKST = 28f
    /** Метки — Unbounded заглавными или ник (широкий шрифт, крупный x): не мельче этого. */
    const val MIN_METKA = 22f

    /** Все кегли стиля: имя — (кегль, метка ли). Проверяет VizorTest. */
    const val TEKST = 28f            // чат, цель, табло, «сейчас играет», перевод субтитров
    const val TEKST_TITROV = 32f     // субтитры: как сказано
    const val IMYA_TOPA = 34f        // ник топ-донатора
    const val SUMMA_TOPA = 30f
    const val METKA = 22f            // TOP DONOR, кто говорит, PLUS
    const val NIK = 24f              // ник в чате (Unbounded)
    /** Значок площадки TW / KICK / YT на цветной плашке — значок, не читаемый текст (в KEGLI не входит). */
    const val ZNACHOK = 14f
    const val NALET = 28f
    const val ZAGOLOVOK_SCENY = 104f
    const val TAJMER = 64f
    val KEGLI: Map<String, Pair<Float, Boolean>> = mapOf(
        "tekst" to (TEKST to false), "titry" to (TEKST_TITROV to false), "nalet" to (NALET to false),
        "imya_topa" to (IMYA_TOPA to true), "summa_topa" to (SUMMA_TOPA to true), "metka" to (METKA to true),
        "nik" to (NIK to true),
        "zagolovok_sceny" to (ZAGOLOVOK_SCENY to true), "tajmer" to (TAJMER to true),
    )

    // ---------- кадр и раскладка (точки 1080p, начало — слева вверху) ----------
    const val W = 1920
    const val H = 1080
    /** Центр кадра — свободен всегда: 70 % ширины × 60 % высоты. */
    val CENTR = Pryam(W * 0.15f, H * 0.2f, W * 0.85f, H * 0.8f)
    const val LEVO = 96f             // левый край карточек верха (за плечом скобы)
    const val VERH = 48f             // верх карточек (под золотой линией)
    const val PRAVO = 1880f          // правый край правого столбца
    const val NIZ = 1040f            // низ нижних карточек
    const val ZAZOR = 12f
    /** Правый столбец: от центра кадра до правого края. */
    const val STOLB_X = 1640f
    const val STOLB_W = PRAVO - STOLB_X

    class Pryam(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        fun peresekaet(o: Pryam) = l < o.r && o.l < r && t < o.b && o.t < b
        fun vnutri(o: Pryam) = l >= o.l && t >= o.t && r <= o.r && b <= o.b
        fun shire(d: Float) = Pryam(l - d, t - d, r + d, b + d)
        fun sdvig(dx: Float, dy: Float) = Pryam(l + dx, t + dy, r + dx, b + dy)
        override fun toString() = "Pryam($l, $t, $r, $b)"
    }
    val KADR = Pryam(0f, 0f, W.toFloat(), H.toFloat())

    // слой "lv" — левый верх: карточка топа и цели, справа табло соведущего
    const val LV_W = 1010
    const val LV_H = 168
    val LV = Pryam(LEVO, VERH, LEVO + LV_W, VERH + LV_H)
    const val KARTA_TOPA_W = 480f
    const val TABLO_X = KARTA_TOPA_W + 16f        // в слое
    const val POLE = 16f                          // поля внутри карточки
    /** Высота карточки топа/цели: с топом, с целью или с обоими. */
    fun kartaTopaH(top: Boolean, cel: Boolean): Float {
        var h = POLE + 26f                        // метка
        if (top) h += 44f                         // ник и сумма
        if (cel) h += 38f + 22f                   // название и сумма цели, полоса
        return h + 14f
    }
    /** Высота табло соведущего: строк 1–3 (ставки Плюса, вызовы зрителей, текущий вызов) по 38. */
    const val TABLO_STROKA = 38f
    fun tabloH(strok: Int) = POLE + TABLO_STROKA * strok.coerceIn(1, 3) + 10f

    // слой "pv" — правый верх: налёт пилюлей, Плюс во весь рост (2.7.2; зверь — бюстом), плашка «PLUS co-host»
    const val PV_W = 760
    const val PV_H = 430
    val PV = Pryam(PRAVO - PV_W, 40f, PRAVO, 40f + PV_H)
    /** Сторона картинки лица (точки картинки 256 → кадр): крупнее старого (162). */
    const val D_LICA = 188f
    private const val K = D_LICA / 256f
    /** На сколько плечи (plus-plechi.png) выходят за картинку головы с каждой стороны — как Nalozhenie.PLECHI_VBOK. */
    const val PLECHI_VBOK = 31.5f
    /** Левый край картинки лица и её верх (кадр): плечи (319 точек, −31,5 за голову) — у правого края. */
    val LICO_X = PRAVO - 4f - (319f - PLECHI_VBOK) * K
    val LICO_Y = PV.t
    /**
     * Плюс во весь рост (plus-telo.png 360×576: голова — картинка лица 256 с x = 52, ниже тело в тапочках): на 5 %
     * шире картинки (TELO_SHIRE, Александр 24.10), почти во всю ширину правого столбца; стоит справа вверху над чатом.
     */
    const val TELO_W = 360f
    const val TELO_H = 576f
    const val TELO_GOLOVA_X = 52f
    const val TELO_SHIRE = 1.05f
    /** Масштаб фигуры: 93 % ширины столбца — с айфоном стоя под ней остаётся место на сообщение чата. */
    const val KT = 0.93f * (PRAVO - STOLB_X) / (TELO_W * TELO_SHIRE)
    val TELO_X = PRAVO - (PRAVO - STOLB_X) / 2f - TELO_W * KT / 2f
    val TELO_Y = PV.t
    /** Плюс (во весь рост; бюст зверя — внутри) с запасом на покачивание. */
    val BYUST = Pryam(STOLB_X - 4f, TELO_Y, PRAVO, TELO_Y + TELO_H * KT + 4f)
    /** Плашка «PLUS co-host» под Плюсом (наибольшая): тапочки чуть заходят за её верх. */
    val PLASHKA_PLYUSA = Pryam(STOLB_X, BYUST.b - 18f, PRAVO, BYUST.b - 18f + 44f)
    /** Пилюля налёта — слева от головы, в полосе верха (наибольшая ширина). */
    val NALET_PILYULYA = Pryam(PV.l, VERH, BYUST.l - ZAZOR, VERH + 48f)

    // слой "pc" — чат: правый столбец под плашкой Плюса
    const val CW = 240
    const val CH = 600
    val CHAT_VERH = PLASHKA_PLYUSA.b + ZAZOR

    // слой "mz" — «сейчас играет»: правый столбец над окном камеры
    const val MZW = 240
    const val MZH = 52

    // слой "nc" — субтитры: низ по центру, не выше нижней границы центра
    const val TW = 1100
    const val TH = 172
    val TITRY_VERH = NIZ - TH

    // слой "ln" — роза ветров (Roza3D, 150) в левом нижнем углу, внутри скобы
    val ROZA = Pryam(64f, 870f, 64f + Roza3D.KARTINKA, 870f + Roza3D.KARTINKA)

    // слои "vv", "vn" — скобы и линия: полосы во всю ширину сверху и снизу
    const val RAMKA_H = 96
    val RAMKA_VERH = Pryam(0f, 0f, W.toFloat(), RAMKA_H.toFloat())
    val RAMKA_NIZ = Pryam(0f, (H - RAMKA_H).toFloat(), W.toFloat(), H.toFloat())

    /** Скобы визора по углам кадра (каждая — два плеча): точки 1080p. */
    fun skoby(): List<Pryam> {
        val o = SKOBA_OTSTUP; val d = SKOBA; val t = SKOBA_T
        val r = W - o; val b = H - o
        return listOf(
            Pryam(o, o, o + d, o + t), Pryam(o, o, o + t, o + d),
            Pryam(r - d, o, r, o + t), Pryam(r - t, o, r, o + d),
            Pryam(o, b - t, o + d, b), Pryam(o, b - d, o + t, b),
            Pryam(r - d, b - t, r, b), Pryam(r - t, b - d, r, b))
    }

    /**
     * Скобы окна камеры (x, y, ширина, высота окна — в точках выхода, начало как у GL — снизу слева; s — масштаб к
     * 1080p): восемь плеч [x, y, w, h] в тех же точках, вынесены за окно на KSKOBA_VYNOS.
     */
    fun skobyKamery(x: Int, y: Int, w: Int, h: Int, s: Float): List<IntArray> {
        val v = maxOf(2, (KSKOBA_VYNOS * s).toInt())
        val d = maxOf(6, (KSKOBA * s).toInt())
        val t = maxOf(2, (KSKOBA_T * s).toInt())
        val l = x - v; val n = y - v; val r = x + w + v; val vr = y + h + v
        return listOf(
            intArrayOf(l, vr - t, d, t), intArrayOf(l, vr - d, t, d),          // левый верх
            intArrayOf(r - d, vr - t, d, t), intArrayOf(r - t, vr - d, t, d),  // правый верх
            intArrayOf(l, n, d, t), intArrayOf(l, n, t, d),                    // левый низ
            intArrayOf(r - d, n, d, t), intArrayOf(r - t, n, t, d))            // правый низ
    }

    /** Место субтитров по горизонтали: между розой и окном камеры внизу справа ([pravo] — его левый край, 1080p). */
    fun titryX(pravo: Float = PRAVO): Float {
        val levo = ROZA.r + ZAZOR
        val c = (levo + pravo - ZAZOR) / 2f
        return (c - TW / 2f).coerceIn(levo, maxOf(levo, W - TW - SKOBA_OTSTUP))
    }

    /** Таймер сцены «04:59» (и «1:04:59»); прошло — «00:00». */
    fun tajmer(ostalosMs: Long): String {
        val s = ((ostalosMs + 999) / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    /** Метка площадки у сообщения чата: tw | kick | yt → подпись и цвет. */
    fun ploshchadka(pl: String): Pair<String, Int> = when (pl) {
        "kick" -> "KICK" to KICK
        "yt" -> "YT" to YOUTUBE
        else -> "TW" to TWITCH
    }
}

/**
 * Стиль и сцена эфира (2.7.0): `vid stil viz|staryj` (/efir/stil, приложение запоминает; по умолчанию viz) и сцены
 * во весь кадр поверх игры — `scena skoro 5|otoshyol|konec|net` (/efir/scena). Сцены живут до `scena net` или
 * конца эфира. Чистый Kotlin — VizorTest.
 */
object Oformlenie {
    private const val FAJL = "efir-stil"
    private const val FAJL_LICO = "efir-lico"
    val STILI = setOf("viz", "staryj")
    /** Лицо в эфире: plus — Плюс; zver — живой рисунок зверя (ZverLico). */
    val LICA = setOf("plus", "zver")
    val SCENY = setOf("skoro", "otoshyol", "konec")
    /** Дольше суток отсчёт не ставим. */
    const val MAKS_MIN = 24 * 60
    const val MAKS_FRAZA = 140

    @Volatile var stil = "viz"
        private set
    val viz: Boolean get() = stil == "viz"
    @Volatile var lico = "plus"
        private set
    /** Сцена: "" — нет, skoro, otoshyol, konec. */
    @Volatile var scena = ""
        private set
    /** Конец отсчёта сцены (мс, 0 — без таймера). */
    @Volatile var doMs = 0L
        private set
    /** Фраза Плюса на заставке (пусто — своя). */
    @Volatile var fraza = ""
        private set
    /** Растёт с каждым изменением — плашки перерисовываются. */
    @Volatile var versiya = 0L
        private set
    @Volatile private var dir: File? = null

    fun zagruzit(d: File) {
        dir = d
        stil = try { File(d, FAJL).readText().trim().takeIf { it in STILI } ?: "viz" } catch (e: Exception) { "viz" }
        lico = try { File(d, FAJL_LICO).readText().trim().takeIf { it in LICA } ?: "plus" } catch (e: Exception) { "plus" }
        versiya++
    }

    /** /efir/stil?v=viz|staryj (без v — только состояние). */
    fun stilUpr(v: String?): String {
        if (v != null && v in STILI && v != stil) {
            stil = v
            versiya++
            dir?.let { try { File(it, FAJL).writeText(v) } catch (e: Exception) { } }
        }
        return status()
    }

    /** /efir/lico?v=plus|zver (без v — только состояние); мост запоминает. */
    fun licoUpr(v: String?): String {
        if (v != null && v in LICA && v != lico) {
            lico = v
            versiya++
            dir?.let { try { File(it, FAJL_LICO).writeText(v) } catch (e: Exception) { } }
        }
        return status()
    }

    /** /efir/scena?s=skoro|otoshyol|konec|net&min=5&t=фраза (без s — только состояние; чужое — oshibka). */
    fun scenaUpr(q: Map<String, String>, now: Long = System.currentTimeMillis()): String {
        val s = q["s"] ?: return status(now)
        if (s == "net" || s == "") { sbros(); return status(now) }
        if (s !in SCENY) return status(now).dropLast(1) + ",\"oshibka\":\"сцены $s нет: skoro, otoshyol, konec, net\"}"
        val min = q["min"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { !it.isNaN() && it > 0 }?.coerceAtMost(MAKS_MIN.toDouble())
        scena = s
        doMs = if (min != null && s != "konec") now + (min * 60_000).toLong() else 0L
        fraza = q["t"]?.let { Titry.chisto(Titry.dekod(it)).take(MAKS_FRAZA) } ?: ""
        versiya++
        return status(now)
    }

    /** Конец эфира (и `scena net`): сцену прочь — следующий эфир не начнётся с заставки конца. */
    fun sbros() {
        if (scena.isEmpty() && doMs == 0L && fraza.isEmpty()) return
        scena = ""; doMs = 0L; fraza = ""
        versiya++
    }

    /** Сколько осталось до конца отсчёта, мс (0 — прошло или таймера нет). */
    fun ostalosMs(now: Long = System.currentTimeMillis()) = if (doMs == 0L) 0L else (doMs - now).coerceAtLeast(0L)

    fun status(now: Long = System.currentTimeMillis()): String =
        "{\"stil\":\"$stil\",\"lico\":\"$lico\",\"scena\":\"${scena.ifEmpty { "net" }}\",\"tajmer\":${doMs != 0L}," +
            "\"ostalos_sek\":${(ostalosMs(now) + 999) / 1000},\"fraza\":${json(fraza)}}"

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Только для проверок: как после установки. */
    internal fun zanovo() { stil = "viz"; scena = ""; doMs = 0L; fraza = ""; dir = null; versiya++ }
}
