package com.questclaude.bridge

/**
 * Кодовые фразы ушей (assets/triggers.json): какая фраза — какое действие.
 * Обычная JVM, без Android — правила проверяются тестами на столе, а та же таблица
 * с тем же порогом уверенности — живыми голосами на GitHub (ears/proverka.py).
 *
 *   wake         «плюс плюс» / «plus plus» — сигнал, потом фраза для Плюса
 *   zoom:x2      «зум зум» — взгляд вдвое крупнее обычного (≈900 токенов)
 *   zoom:x4      «зум зум зум» — вчетверо (≈1800 токенов)
 *   zoom:centr   «зум зум центр» — центр вида в полном разрешении (≈700 токенов)
 *   tiho         «плюс тихо» / «plus quiet» — Ава замолкает
 *   temp         «плюс темп» — этот ответ Плюса пропустить, звучит следующий (2.8.5)
 *   pauza        «плюс пауза» — голос на паузу, ещё раз — дальше с того же места (2.8.5)
 *   stop         «копи копи» в конце фразы после вызова — фраза уходит сразу (StopWord)
 *   rezhim:uho   «плюс плюс в ухо» — голос Авы слышит только Александр; rezhim:vsluh — и стрим
 *   plenka:7     «чек чек» — плёнка 7 кадров (±1,5 с), plenka:13 «зум чек» — 13 (±3 с)
 *   wake:chek    «чек чек плюс» — плёнка 7 кадров и 1,5 с речи после команды (CHEK_MS)
 *   wake:joy     «плюс джой» — как «плюс плюс», но фраза — Джою (2.8.14)
 *   klip         «клип клип клип» — клип (если слово клипа — «клип», KlipSlovo)
 *   klip:sks     «скскс скскс скскс» — клип (2.8.17, слово по умолчанию): серия, не фраза (Seriya)
 *
 * commands — короткие команды в начале фразы после вызова («в ухо», «компакт»): ранний вызов
 * будит на «плюс плюс» + следующее слово, и команда уже часть фразы — исполняем её сразу,
 * без паузы тишины. earlyChunks — сколько кусков по 0,1 с ждать ранний вызов (0 — выключен).
 * bareWaitChunks — сколько кусков голый «плюс плюс» ждёт точную команду от другой модели.
 */
class Triggers(
    val minConf: Double,
    val phrases: Map<String, Map<String, String>>,
    val decoys: Map<String, List<String>> = emptyMap(),
    val phraseBonus: Map<String, Double> = emptyMap(),
    val stop: Map<String, Set<String>> = emptyMap(),
    val stopOne: Map<String, Set<String>> = emptyMap(),
    val commands: Map<String, Map<String, String>> = emptyMap(),
    val earlyChunks: Int = 3,
    val bareWaitChunks: Int = 5,
    val seriya: Map<String, Seriya> = emptyMap(),
) {

    /**
     * 2.8.17 (Александр 26.11: клип — словом «скскс»). Слова «скскс» нет в словаре модели ушей (Vosk small-ru):
     * она слышит его как «скс» или «кс», а три раза подряд — то «скс скс скс» одним итогом, то «скс | скс кс»
     * разными итогами (пауза между словами — конец итога). Поэтому не фраза, а серия: [skolko] уверенных слов из
     * [slova] подряд (другие слова рвут серию, [unk] не считается), от конца одного до начала следующего — не
     * больше [pauzaSek]. Пробы голосами (piper ru ×4, en, 26.11): «скскс» ×3 — 18 из 36; одно «скскс» — ни разу
     * без ложных: «скажи», «скорее», «кис-кис», «six six» тоже дают «скс»/«кс кс»; при трёх — 3 ложных из 252
     * («next next next», «sixty six sixty six» и одна длинная русская фраза).
     */
    class Seriya(val slova: Set<String>, val skolko: Int, val pauzaSek: Double, val action: String)

    /** Счёт серии по итогам грамматики, свой у каждого языка. */
    class SeriyaSlov(private val t: Triggers) {
        private val podryad = HashMap<String, Int>()
        private val konec = HashMap<String, Double>()
        private val hudshaya = HashMap<String, Double>()

        fun reset() { podryad.clear(); konec.clear(); hudshaya.clear() }

        /** Слова одного итога; [base] — секунды от начала прослушки до создания распознавателя (время слов
         *  Vosk — от него). Серия набралась — Hit (уверенность — худшего слова серии), счёт заново. */
        fun check(lang: String, words: List<Word>, base: Double): Hit? {
            val s = t.seriya[lang] ?: return null
            var hit: Hit? = null
            for (w in words) {
                if (w.word == "[unk]") continue
                if (w.word !in s.slova || w.conf < t.minConf) { podryad[lang] = 0; continue }
                val n0 = podryad[lang] ?: 0
                val dalshe = n0 > 0 && base + w.start - (konec[lang] ?: Double.NEGATIVE_INFINITY) <= s.pauzaSek
                val n = if (dalshe) n0 + 1 else 1
                hudshaya[lang] = if (dalshe) minOf(hudshaya[lang] ?: 1.0, w.conf) else w.conf
                konec[lang] = base + w.end
                podryad[lang] = n
                if (n >= s.skolko) { hit = Hit(s.action, lang, hudshaya[lang] ?: w.conf); podryad[lang] = 0 }
            }
            return hit
        }
    }

    class Hit(val action: String, val lang: String, val conf: Double)

    companion object {
        /** «Чек чек плюс»: вызов, но фраза — ровно CHEK_MS после команды, без «копи копи». */
        const val CHEK = "wake:chek"
        /** 2.8.14 (Александр 25.11): «плюс джой» — вызов Джоя, творческого режима Плюса: фраза уходит ему (how "joy"). */
        const val JOY = "wake:joy"
        /** 2.8.17: «скскс» ×3 — клип (KlipSlovo решает, какое слово клипа сейчас). */
        const val SKS = "klip:sks"
        const val CHEK_MS = 1500L
    }

    /** Слово Vosk со временем: start/end — секунды от последнего reset() распознавателя. */
    class Word(val word: String, val start: Double, val end: Double, val conf: Double = 1.0)

    /**
     * «Плюс плюс» в начале итога грамматики, а за ним уже звучат ещё слова — Александр
     * сказал вызов и сразу фразу. Каждое слово вызова уверено не меньше minConf
     * (needConf=false — для промежуточного текста, у него уверенности нет).
     * Вернёт конец слов вызова, с, или null. Точные фразы («плюс плюс компакт»)
     * раньше ловит match().
     */
    fun wakeAtStart(lang: String, words: List<Word>, needConf: Boolean = true, action: String = "wake"): Double? {
        for (p in phrases[lang].orEmpty().filterValues { it == action }.keys) {
            val w = p.split(' ')
            if (words.size > w.size && words.take(w.size).map { it.word } == w &&
                (!needConf || words.take(w.size).all { it.conf >= minConf })) return words[w.size - 1].end
        }
        return null
    }

    /**
     * Ранний вызов по промежуточному тексту грамматики: «плюс плюс» и уже следующее
     * слово [chunks] кусков по 0,1 с подряд — сигнал и пауза Авы сразу, не после фразы.
     * Уверенности у промежуточного текста нет, поэтому ждём продолжения и повторения:
     * мимолётная догадка распознавателя не будит. chunks = 0 — выключено.
     */
    class EarlyWake(private val t: Triggers, private val chunks: Int = 3) {
        private val seen = HashMap<String, Int>()

        fun reset() = seen.clear()

        /** Конец слов вызова (с), когда пора будить; иначе null. */
        fun check(lang: String, words: List<Word>): Double? {
            if (chunks <= 0) return null
            val end = t.wakeAtStart(lang, words, needConf = false)
            val n = if (end != null) (seen[lang] ?: 0) + 1 else 0
            seen[lang] = n
            return if (n >= chunks) end else null
        }
    }

    /**
     * Грамматика Vosk: наши фразы, слова-приманки и [unk]. Грамматика Vosk — набор
     * слов в любом порядке, поэтому без приманок «плюс минус» становилось «плюс плюс»,
     * а «please stop» — «plus stop» (проверка голосами на GitHub, 04.10).
     */
    fun grammar(lang: String): String =
        (phrases[lang].orEmpty().keys + commands[lang].orEmpty().keys + seriya[lang]?.slova.orEmpty() +
            decoys[lang].orEmpty() + "[unk]").distinct()
            .joinToString(",", "[", "]") { FrameServer.json(it) }

    /**
     * Фраза засчитывается, только если распознана целиком и каждое её слово
     * уверено не меньше minConf: «зум» не станет «зум зум», а «плюс один» — вызовом.
     */
    fun match(lang: String, text: String, confs: List<Double>): Hit? {
        val action = phrases[lang]?.get(text.trim()) ?: return null
        val words = text.trim().split(' ').size
        if (confs.size != words) return null
        val worst = confs.minOrNull() ?: return null
        return if (worst >= minConf) Hit(action, lang, worst) else null
    }

    /** Короткая команда целиком в первой части фразы, каждое слово уверено. */
    fun command(lang: String, text: String, confs: List<Double>): Hit? {
        val action = commands[lang]?.get(text.trim()) ?: return null
        if (confs.size != text.trim().split(' ').size) return null
        val worst = confs.minOrNull() ?: return null
        return if (worst >= minConf) Hit(action, lang, worst) else null
    }

    /**
     * Фраза после «плюс плюс»: её слушают обе модели; берём ту, что уверенней в среднем
     * на слово, плюс перевес основному языку (phraseBonus): украинскую фразу английская
     * модель иногда «слышит» бессмыслицей уверенней русской.
     */
    class Heard(val lang: String, val text: String, val confs: List<Double>)

    /**
     * Итог грамматики во время фразы: перед «копи копи» — [unk] и приманки, поэтому
     * смотрим конец текста, и каждое слово «копи копи» уверено не меньше minConf.
     */
    fun endsWithStop(lang: String, text: String, confs: List<Double>): Boolean {
        val words = text.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size != confs.size) return false
        return phrases[lang].orEmpty().filterValues { it == "stop" }.keys.any { p ->
            val k = p.split(' ').size
            words.size >= k && words.takeLast(k).joinToString(" ") == p && confs.takeLast(k).all { it >= minConf }
        }
    }

    fun bestPhrase(heard: List<Heard>): Heard? =
        heard.filter { it.text.isNotBlank() && it.confs.isNotEmpty() }
            .maxByOrNull { it.confs.average() + (phraseBonus[it.lang] ?: 0.0) }

    /** Две модели слышат одно и то же: берём самую уверенную, повтор того же действия за 1,5 с — отбрасываем. */
    class Chooser(private val windowMs: Long = 1500) {
        private var lastAction = ""
        private var lastMs = Long.MIN_VALUE / 2

        fun pick(hits: List<Hit>, nowMs: Long): Hit? {
            val best = hits.maxByOrNull { it.conf } ?: return null
            if (best.action == lastAction && nowMs - lastMs < windowMs) return null
            lastAction = best.action
            lastMs = nowMs
            return best
        }
    }

    /**
     * «Копи копи» в конце фразы — отправить сразу, не ждать тишины (как «приём» в рации).
     * Два стоп-слова подряд ([count]): одно «копи» бывает в речи, два подряд — нет.
     * Срабатывает, когда они последние в промежуточном тексте Vosk [chunks] кусков
     * по 0,1 с подряд: мимолётная догадка распознавателя фразу не обрывает.
     */
    class StopWord(
        private val words: Map<String, Set<String>>,
        private val chunks: Int = 2,
        private val count: Int = 2,
        private val one: Map<String, Set<String>> = emptyMap(),   // быстрое «копи копи» одним словом
    ) {
        private val seen = HashMap<String, Int>()

        fun reset() = seen.clear()

        fun check(lang: String, partial: String): Boolean {
            val n = if (before(lang, partial) != null) (seen[lang] ?: 0) + 1 else 0
            seen[lang] = n
            return n >= chunks
        }

        /** Текст без стоп-слов в конце. */
        fun strip(lang: String, text: String): String = before(lang, text) ?: text.trim()

        /** Текст до стоп-слов, если фраза ими кончается, иначе null. */
        private fun before(lang: String, s: String): String? {
            val w = s.trim().split(' ').filter { it.isNotEmpty() }
            if (w.isNotEmpty() && w.last().lowercase() in one[lang].orEmpty()) return w.dropLast(1).joinToString(" ")
            if (w.size < count) return null
            val stops = words[lang].orEmpty()
            return if (w.takeLast(count).all { it.lowercase() in stops }) w.dropLast(count).joinToString(" ") else null
        }
    }
}
