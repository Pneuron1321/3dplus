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
 *
 * 2.6.8 («черновик → чистовик», Александр 22.10): строка sinhron с id — одна реплика. Черновик растёт на месте
 * (новый кусок той же реплики — тот же id), потом чистовик (tochno=1: вся реплика полной моделью) заменяет текст,
 * и строка закреплена — черновик её больше не тронет. Когда Александр говорит Плюсу, уши слышат фразу полной
 * моделью: этот текст и есть чистовик — заменяет свежую реплику sinhron (кто бы ни пришёл первым, окно 12 с).
 */
class Titry(
    private val chasy: () -> Long = { System.currentTimeMillis() },
    private val yaOtSinhrona: () -> Boolean = { false },
) {
    /** kto — "ya" (Александр), "plus", "igra", "g1"…"g3"; perevod — второй ряд (может быть пустым); doMs — до какого времени видна. */
    class Stroka(val kto: String, val tekst: String, val doMs: Long, val perevod: String = "", val id: String = "",
                 val tochno: Boolean = false, val rodilsyaMs: Long = 0L)

    private val stroki = ArrayDeque<Stroka>()

    /** Скрыть / показать субтитры в эфире (Плюс: titry skryt|pokazat). */
    @Volatile var vidny = true

    /** Меняется при каждой новой строке — плашке пора перерисоваться. */
    @Volatile var versiya = 0L
        private set

    /** Фраза ушей (полная модель), пришедшая раньше реплики sinhron: ждёт её до USHI_OKNO_MS. */
    private var ushiZapas: Triple<String, String, Long>? = null

    /** Добавить строку (и перевод); sek — сколько показывать (null — по длине). false — пустая или чужой «кто». */
    @Synchronized fun dobavit(kto: String, tekst: String, sek: Double? = null, perevod: String = "", id: String = "",
                              tochno: Boolean = false): Boolean {
        val t = chisto(tekst)
        if (t.isEmpty() || kto !in KTO) return false
        val p = chisto(perevod).takeIf { it != t }.orEmpty()
        val s = (sek?.takeIf { it > 0 && !it.isNaN() } ?: poDline(if (p.length > t.length) p else t)).coerceIn(MIN_SEK, MAKS_SEK)
        val now = chasy()
        val i = if (id.isEmpty()) -1 else stroki.indexOfFirst { it.kto == kto && it.id == id }
        if (i >= 0) {
            val bylo = stroki[i]
            if (bylo.tochno && !tochno) return true                 // чистовик уже стоит — черновик опоздал
            stroki[i] = Stroka(kto, t, maxOf(bylo.doMs, now + (s * 1000).toLong()), p, id, tochno, bylo.rodilsyaMs)
        } else {
            stroki.addLast(Stroka(kto, t, now + (s * 1000).toLong(), p, id, tochno, now))
            while (stroki.size > HRANIT) stroki.removeFirst()
        }
        if (kto == "ya" && id.isNotEmpty() && !tochno) ushiZapas?.let { (ut, up, ms) ->
            if (now - ms <= USHI_OKNO_MS && pohozhi(t, ut)) { ushiZapas = null; zamenitUshami(ut, up) }
        }
        versiya++
        return true
    }

    /** Строка закреплена чистовиком? (для ответа sinhron: свой чистовик ей уже не нужен) */
    @Synchronized fun zakreplena(kto: String, id: String) = id.isNotEmpty() && stroki.any { it.kto == kto && it.id == id && it.tochno }

    /**
     * Фраза Александра от ушей (полная модель), пока sinhron слушает микрофон: свежая реплика sinhron того же
     * разговора получает этот текст как чистовик. Реплики ещё нет — фраза ждёт её. true — заменили.
     */
    @Synchronized fun otUshey(tekst: String, perevod: String): Boolean {
        val t = chisto(tekst)
        if (t.isEmpty()) return false
        if (zamenitUshami(t, chisto(perevod))) return true
        ushiZapas = Triple(t, chisto(perevod), chasy())
        return false
    }

    private fun zamenitUshami(t: String, p: String): Boolean {
        val now = chasy()
        val i = stroki.indexOfLast { it.kto == "ya" && it.id.isNotEmpty() && !it.tochno && now - it.rodilsyaMs <= USHI_OKNO_MS }
        if (i < 0 || !pohozhi(stroki[i].tekst, t)) return false
        val bylo = stroki[i]
        stroki[i] = Stroka("ya", t, maxOf(bylo.doMs, now + (poDline(t).coerceIn(MIN_SEK, MAKS_SEK) * 1000).toLong()),
            p.takeIf { it != t }.orEmpty(), bylo.id, true, bylo.rodilsyaMs)
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
        if (t != null && kto == "ya" && q["ot"] != "s" && yaOtSinhrona()) {      // уши — тот же голос второй раз:
            val zamena = otUshey(t, q["t2"]?.let { dekod(it) }.orEmpty())          // не новой строкой, а чистовиком
            return "{\"ok\":false,\"dubl\":true,\"chistovik\":$zamena,\"vidny\":$vidny,\"strok\":${seychas().size}}"
        }
        val id = q["id"].orEmpty().take(40)
        val ok = t?.let { dobavit(kto, it, q["sek"]?.toDoubleOrNull(), q["t2"]?.let { dekod(it) }.orEmpty(), id, q["tochno"] == "1") }
        // "id":true — мост понимает реплики (sinhron по этому включает черновик → чистовик)
        return "{\"ok\":${ok ?: true},\"id\":true,\"zakreplena\":${zakreplena(kto, id)},\"vidny\":$vidny,\"strok\":${seychas().size}}"
    }

    companion object {
        val KTO = setOf("ya", "plus", "igra", "g1", "g2", "g3")
        const val MAKS = 2
        const val MAKS_VNIZU = 3
        const val HRANIT = 12

        fun gost(kto: String) = kto.length == 2 && kto[0] == 'g' 
        const val MAKS_DLINA = 160
        const val MIN_SEK = 2.5
        /** Фраза ушей и реплика sinhron — один разговор, если между ними не больше (мс). */
        const val USHI_OKNO_MS = 12_000L

        /** Одна и та же речь двумя распознаваниями: есть общее слово от 3 букв (или фраза ушей совсем короткая),
         *  и реплика не намного длиннее фразы ушей — иначе в ней есть слова, которых уши не слышали. */
        fun pohozhi(sinhron: String, ushi: String): Boolean {
            fun slova(x: String) = Regex("[\\p{L}\\p{N}]+").findAll(x.lowercase()).map { it.value }.toList()   // \w в Java — только латиница
            val a = slova(sinhron); val b = slova(ushi)
            if (b.isEmpty() || a.size > b.size * 16 / 10 + 3) return false
            return b.size <= 2 || b.any { it.length >= 3 && it in a }
        }
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
