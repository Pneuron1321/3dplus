package com.questclaude.bridge

/**
 * Секунда после ответа (2.3.1, Александр 09.10): Плюс договорила ответ ему (или реплику
 * соведущего) — уши ещё [oknoMs] слушают без «плюс плюс». Заговорил — фраза по правилам
 * «плюс плюс» (без сигнала вызова); молчит — отдыхают.
 *
 * Голос-демон говорит «договорила» (/ear/dosluh) — окно открыто. Куски микрофона помечены
 * временем чтения (мс, System.nanoTime): распознавание может отставать, а окно — по звуку.
 * Первые [zaderzhkaMs] — хвост её же голоса, не слушаем. Речь — [podryad] кусков по 0,1 с
 * подряд громче [porogDb] (дБ ниже полной шкалы, меньше — громче), и первый из них начался
 * в окне (кусок прочитан к ms — значит, звучал с ms − [kusokMs]). Ава снова заговорила — окно
 * закрыто. oknoMs = 0 — выключено.
 * Обычная JVM — DosluhTest.
 */
class Dosluh(oknoMs: Long = OKNO_MS, porogDb: Int = POROG_DB, private val zaderzhkaMs: Long = 200,
             private val podryad: Int = 2, private val kusokMs: Long = 100) {
    @Volatile var oknoMs = oknoMs
        private set
    @Volatile var porogDb = porogDb
        private set
    private var ot = 0L
    private var do_ = 0L
    private var gromkih = 0
    /** Сколько раз окно открывалось и сколько раз в нём заговорили — для /status. */
    @Volatile var otkryto = 0
        private set
    @Volatile var zagovoril = 0
        private set

    fun set(okno: Float?, db: Int?) {
        if (okno != null && !okno.isNaN()) oknoMs = (okno.coerceIn(0f, 3f) * 1000).toLong()
        if (db != null) porogDb = db.coerceIn(20, 60)
    }

    /** Голос-демон: договорила ответ в [ms]. */
    @Synchronized fun otkryt(ms: Long = seychas()) {
        if (oknoMs <= 0) return
        ot = ms + zaderzhkaMs
        do_ = ms + oknoMs
        gromkih = 0
        otkryto++
    }

    /** Ава снова говорит (или фраза уже слушается) — окно закрыто. */
    @Synchronized fun zakryt() {
        do_ = 0L
        gromkih = 0
    }

    /** Кусок микрофона прочитан к [ms], громкость [db]. true — заговорил: начинать фразу. */
    @Synchronized fun kusok(ms: Long, db: Int): Boolean {
        if (do_ == 0L) return false
        val nachalo = ms - kusokMs
        if (gromkih == 0 && (nachalo < ot || nachalo > do_)) {
            if (nachalo > do_) zakryt()                    // секунда прошла — отдыхаем
            return false
        }
        if (db in 0..porogDb) {
            if (++gromkih >= podryad) {
                zakryt()
                zagovoril++
                return true
            }
        } else {
            gromkih = 0
            if (nachalo > do_) zakryt()
        }
        return false
    }

    fun status(): String = "{\"okno\":${oknoMs / 1000.0},\"db\":$porogDb,\"otkryto\":$otkryto,\"zagovoril\":$zagovoril}"

    companion object {
        const val OKNO_MS = 1000L
        /** Громче −38 дБ: голос у микрофона шлема, а не игра из динамиков (подбирается в шлеме). */
        const val POROG_DB = 38

        fun seychas(): Long = System.nanoTime() / 1_000_000
    }
}
