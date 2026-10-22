package com.questclaude.bridge

/**
 * Чат Twitch в кадре эфира (2.3.4, Александр 11.10: «полупрозрачный чат между камерой и Плюсом
 * под донат-целью»). Сообщения шлют уши Termux (ushi читает чат анонимно) — /chat/upr; новые —
 * внизу, живут [ZHIZN_MS] и гаснут за последние [GASNET_MS]; удалённое модератором (CLEARMSG),
 * бан и тайм-аут (CLEARCHAT) — убирается и с экрана. Текст — чужие слова: только показать (без
 * управляющих знаков, коротко), ник — буквы, цифры, «_», «-». Показ (вкл/выкл) — запоминает.
 * 2.3.9: уши шлют пачкой (/chat/pachka, раз в 0,3 с) — версия растёт раз на пачку; «важное»
 * (обращение к Плюсу, донат — vazhno=1 от ушей, или «плюс» в тексте) в шквал показывается первым.
 * Обычная JVM — ChatTest.
 */
class Chat(zagruzit: () -> String?, private val sohranit: (String) -> Unit) {
    /** pl — площадка: tw | kick | yt (метка у сообщения в стиле «Визор»; 2.7.0 — пока все из Twitch). */
    class Soob(val id: String, val login: String, val kto: String, val tekst: String, val cvet: Int, val t: Long,
               val vazhno: Boolean = false, val pl: String = "tw")

    @Volatile var pokaz = zagruzit()?.trim() != "0"
        private set
    /** Растёт с каждым изменением — плашка перерисовывается. */
    @Volatile var versiya = 0L
        private set
    private val spisok = ArrayDeque<Soob>()

    /** Что на экране сейчас: живые, последние [MAKS]; старые первыми. */
    @Synchronized fun vidimye(now: Long = System.currentTimeMillis()): List<Soob> =
        if (!pokaz) emptyList() else spisok.filter { now - it.t < ZHIZN_MS }.takeLast(MAKS)

    /** Прозрачность сообщения: 1 — живое, к концу жизни гаснет до 0. */
    fun yarkost(s: Soob, now: Long = System.currentTimeMillis()): Float {
        val ostalos = ZHIZN_MS - (now - s.t)
        return (ostalos.toFloat() / GASNET_MS).coerceIn(0f, 1f)
    }

    /**
     * /chat/upr: kto, login, tekst, cvet (#RRGGBB), id, pl (tw|kick|yt) — новое сообщение; ubrat=id — удалено
     * модератором; ubrat_kto=login — бан/тайм-аут; ochistit=1 — чат очищен; pokaz=0|1.
     */
    @Synchronized fun upr(q: Map<String, String>, now: Long = System.currentTimeMillis()): String {
        odno(q, now)
        versiya++
        return status(now)
    }

    /** Пачка от ушей (2.3.9): каждая строка — как /chat/upr; плашка перерисуется раз на пачку. */
    @Synchronized fun pachka(qs: List<Map<String, String>>, now: Long = System.currentTimeMillis()): String {
        for (q in qs) odno(q, now)
        if (qs.isNotEmpty()) versiya++
        return status(now)
    }

    /** Сообщений в секунду за последние [OKNO_MS] — шквал или нет. */
    @Synchronized fun skorost(now: Long = System.currentTimeMillis()): Float =
        spisok.count { now - it.t in 0 until OKNO_MS } * 1000f / OKNO_MS

    private fun odno(q: Map<String, String>, now: Long) {
        q["pokaz"]?.let { pokaz = it != "0"; sohranit(if (pokaz) "1" else "0") }
        if (q["ochistit"] == "1") spisok.clear()
        q["ubrat"]?.let { id -> spisok.removeAll { it.id == id } }
        q["ubrat_kto"]?.let { l -> val lg = l.lowercase(); spisok.removeAll { it.login == lg } }
        val tekst = q["tekst"]?.let { chisto(Titry.dekod(it)) }
        if (!tekst.isNullOrEmpty()) {
            val kto = Top.nik(Titry.dekod(q["kto"] ?: "")).ifEmpty { "?" }
            val login = Top.nik(Titry.dekod(q["login"] ?: kto)).lowercase()
            spisok.addLast(Soob(q["id"]?.take(64) ?: "", login, kto, tekst, cvet(Titry.dekod(q["cvet"] ?: ""), login), now,
                q["vazhno"] == "1" || K_PLUSU.containsMatchIn(tekst), q["pl"]?.takeIf { it in PLOSHCHADKI } ?: "tw"))
            while (spisok.size > HRANIT) spisok.removeFirst()
        }
    }

    @Synchronized fun status(now: Long = System.currentTimeMillis()): String =
        "{\"pokaz\":$pokaz,\"vidno\":${vidimye(now).size},\"vsego\":${spisok.size}}"

    companion object {
        /** Площадки сообщений (/chat/upr pl=…): метка у сообщения в кадре. */
        val PLOSHCHADKI = setOf("tw", "kick", "yt")
        const val ZHIZN_MS = 90_000L
        const val GASNET_MS = 10_000L
        const val MAKS = 12
        const val HRANIT = 40
        const val DLINA = 200
        /** Шквал: больше стольких сообщений в секунду (за [OKNO_MS]) — быстрый режим плашки. */
        const val BYSTRO_V_SEK = 2f
        const val OKNO_MS = 5_000L
        /** В быстром режиме на плашке — столько сообщений, по строке. */
        const val BYSTRO_MAKS = 5
        private val K_PLUSU = Regex("(?iu)(^|[^\\p{L}])(плюс|plus)([^\\p{L}]|$)")

        /**
         * Быстрый режим (шквал): из видимых — [BYSTRO_MAKS], важные (к Плюсу, донаты) первыми, остальное —
         * самые новые; на экране — в порядке прихода.
         */
        fun bystryyVybor(ss: List<Soob>, n: Int = BYSTRO_MAKS): List<Soob> {
            if (ss.size <= n) return ss
            val vazhnye = ss.filter { it.vazhno }.takeLast(n)
            val ostalnye = ss.filter { !it.vazhno }.takeLast(n - vazhnye.size)
            val vybrano = (vazhnye + ostalnye).toHashSet()
            return ss.filter { it in vybrano }
        }

        /** Чужой текст — показать как есть, но без управляющих знаков и не длиннее [DLINA]. */
        fun chisto(s: String): String {
            val t = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")
                .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            return if (t.length > DLINA) t.take(DLINA) + "…" else t
        }

        /** Цвета Twitch для ников без своего цвета (как у Twitch: по нику). */
        private val PALITRA = intArrayOf(0xFF0000, 0x0000FF, 0x00FF00, 0xB22222, 0xFF7F50, 0x9ACD32, 0xFF4500,
            0x2E8B57, 0xDAA520, 0xD2691E, 0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F)

        /** #RRGGBB → цвет; слишком тёмный на тёмной подложке — светлее. */
        fun cvet(s: String, login: String): Int {
            val c = Regex("#?([0-9A-Fa-f]{6})").matchEntire(s.trim())?.groupValues?.get(1)?.toInt(16)
                ?: PALITRA[Math.floorMod(login.hashCode(), PALITRA.size)]
            val r = c shr 16 and 255
            val g = c shr 8 and 255
            val b = c and 255
            val yarko = (r * 299 + g * 587 + b * 114) / 1000
            if (yarko >= 90) return c or (0xFF shl 24)
            fun svetlee(x: Int) = x + (255 - x) / 2
            return (0xFF shl 24) or (svetlee(r) shl 16) or (svetlee(g) shl 8) or svetlee(b)
        }
    }
}
