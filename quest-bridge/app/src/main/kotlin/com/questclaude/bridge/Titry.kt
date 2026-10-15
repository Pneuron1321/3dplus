package com.questclaude.bridge

/**
 * Субтитры эфира: что сказал Александр Плюсу и что Плюс ответил вслух — **на двух языках**
 * (2.2.8, Александр: «я прямо говорил, что на два языка»): первый ряд — как сказано, второй —
 * перевод (по-русски сказано — второй английский, по-английски — русский). Переводят titr в
 * Termux (Groq), сюда приходят готовые строки (/titr: t и t2). Рисует Nalozhenie — внизу по
 * центру. «В ухо» субтитров нет: зрители этого не слышат. Обычная JVM — TitryTest.
 *
 * 2.5.0 (гости и «каждый голос»): ещё голоса — "igra" (звук игры) и "g1"…"g3" (гости); их фразы
 * распознаёт и переводит демон sinhron (Rech → Whisper → перевод → /titr, ot=s). Внизу — Александр,
 * Плюс и игра (не больше MAKS реплик); у каждого гостя — своя последняя реплика (пузырь у плитки),
 * или все внизу (gostiVnizu). Пока sinhron слушает микрофон, «titr ya» от ушей — тот же голос второй
 * раз: не берём ([yaOtSinhrona]).
 */
class Titry(
    private val chasy: () -> Long = { System.currentTimeMillis() },
    private val yaOtSinhrona: () -> Boolean = { false },
) {
    /** kto — "ya" (Александр), "plus", "igra", "g1"…"g3"; perevod — второй ряд (может быть пустым); doMs — до какого времени видна. */
    class Stroka(val kto: String, val tekst: String, val doMs: Long, val perevod: String = "")

    private val stroki = ArrayDeque<Stroka>()

    /** Скрыть / показать субтитры в эфире (Плюс: titry skryt|pokazat). */
    @Volatile var vidny = true

    /** Меняется при каждой новой строке — плашке пора перерисоваться. */
    @Volatile var versiya = 0L
        private set

    /** Добавить строку (и перевод); sek — сколько показывать (null — по длине). false — пустая или чужой «кто». */
    @Synchronized fun dobavit(kto: String, tekst: String, sek: Double? = null, perevod: String = ""): Boolean {
        val t = chisto(tekst)
        if (t.isEmpty() || kto !in KTO) return false
        val p = chisto(perevod).takeIf { it != t }.orEmpty()
        val s = (sek?.takeIf { it > 0 && !it.isNaN() } ?: poDline(if (p.length > t.length) p else t)).coerceIn(MIN_SEK, MAKS_SEK)
        stroki.addLast(Stroka(kto, t, chasy() + (s * 1000).toLong(), p))
        while (stroki.size > HRANIT) stroki.removeFirst()
        versiya++
        return true
    }

    /**
     * Что видно сейчас, новые в конце: внизу (ya, plus, igra) — не больше MAKS реплик; у каждого гостя —
     * последняя. [gostiVnizu] — гости тоже внизу; [uPlitok] — голоса гостей, у которых плитка в кадре: гость
     * без плитки (убран с экрана) — тоже внизу, иначе его слов не видно нигде. Гость внизу — до MAKS_VNIZU реплик.
     */
    @Synchronized fun seychas(gostiVnizu: Boolean = false, uPlitok: Set<String>? = null): List<Stroka> {
        val now = chasy()
        stroki.removeAll { it.doMs <= now }
        if (!vidny) return emptyList()
        val vnizu = { s: Stroka -> !gost(s.kto) || gostiVnizu || (uPlitok != null && s.kto !in uPlitok) }
        val vse = stroki.filter(vnizu)
        val niz = vse.takeLast(if (vse.takeLast(MAKS_VNIZU).any { gost(it.kto) }) MAKS_VNIZU else MAKS)
        val gosti = stroki.filter { gost(it.kto) && !vnizu(it) }.groupBy { it.kto }.values.map { it.last() }
        return stroki.filter { it in niz || it in gosti }
    }

    /** /titr: t — как сказано, t2 — перевод (UTF-8 в процентах), kto — ya|plus|igra|g1…g3, sek; ot=s — от sinhron; vidny — 0/1. */
    fun upr(q: Map<String, String>): String {
        q["vidny"]?.let { vidny = it != "0" }
        val t = q["t"]?.let { dekod(it) }
        val kto = q["kto"] ?: "plus"
        if (t != null && kto == "ya" && q["ot"] != "s" && yaOtSinhrona())        // уши — тот же голос второй раз
            return "{\"ok\":false,\"dubl\":true,\"vidny\":$vidny,\"strok\":${seychas().size}}"
        val ok = t?.let { dobavit(kto, it, q["sek"]?.toDoubleOrNull(), q["t2"]?.let { dekod(it) }.orEmpty()) }
        return "{\"ok\":${ok ?: true},\"vidny\":$vidny,\"strok\":${seychas().size}}"
    }

    companion object {
        val KTO = setOf("ya", "plus", "igra", "g1", "g2", "g3")
        const val MAKS = 2
        const val MAKS_VNIZU = 3
        const val HRANIT = 12

        fun gost(kto: String) = kto.length == 2 && kto[0] == 'g' 
        const val MAKS_DLINA = 160
        const val MIN_SEK = 2.5
        const val MAKS_SEK = 14.0

        /** Без управляющих знаков и лишних пробелов, не длиннее MAKS_DLINA. */
        fun chisto(s: String): String {
            val t = s.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
                .joinToString(" ")
            return if (t.length > MAKS_DLINA) t.take(MAKS_DLINA - 1).trimEnd() + "…" else t
        }

        /** Сколько читать: 2 с и по 0,35 с на слово. */
        fun poDline(t: String): Double = 2.0 + 0.35 * t.split(' ').size

        fun dekod(s: String): String = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    }
}
