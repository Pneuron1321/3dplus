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
 *   stop         «копи копи» в конце фразы после вызова — фраза уходит сразу (StopWord)
 *   rezhim:uho   «плюс плюс в ухо» — голос Авы слышит только Александр; rezhim:vsluh — и стрим
 *   plenka:7     «чек чек» — плёнка 7 кадров (±1,5 с), plenka:13 «зум чек» — 13 (±3 с)
 *   wake:chek    «чек чек плюс» — плёнка 7 кадров и 1,5 с речи после команды (CHEK_MS)
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
) {

    class Hit(val action: String, val lang: String, val conf: Double)

    companion object {
        /** «Чек чек плюс»: вызов, но фраза — ровно CHEK_MS после команды, без «копи копи». */
        const val CHEK = "wake:chek"
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
        (phrases[lang].orEmpty().keys + commands[lang].orEmpty().keys + decoys[lang].orEmpty() + "[unk]").distinct()
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
