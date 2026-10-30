package com.questclaude.bridge

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Уши: что услышал quest-bridge. Обычная JVM, без Android — тестируется на столе.
 * События нумеруются; Termux (скрипт ushi) забирает новые через /ear?after=N.
 *
 *   kind = "text" — фраза после «плюс плюс» или после кнопки, распознана в шлеме
 *   kind = "chek" — «чек чек плюс»: 1,5 с речи после команды (с аудио), к плёнке 7 кадров
 *          "wake" — услышал вызов (Ава замолкает, пока говоришь ты); text "dosluh" — заговорил в
 *                   секунду после ответа Плюса, без «плюс плюс» (2.3.1); фраза — с how "dosluh"
 *          "tiho" — «плюс тихо»
 *          "zahvat" — захват оборвался (2.8.1): text "oborvan" | "zastyl", с « efir», если шёл эфир — Плюс скажет вслух
 */
class Ear(private val keep: Int = 20, private val keepAudio: Int = 3) {
    /** audio — фраза после вызова, PCM 16 бит моно 16 кГц: её точнее распознаёт Whisper в Termux.
     *  Хранится только у последних [keepAudio] фраз: три минуты речи — 5,8 МБ. */
    /** stop — фразу оборвало «копи копи»: Termux срежет эти слова и в тексте Whisper. */
    /** ms — когда событие случилось (часы шлема): zamer считает задержку от него. */
    /** lead — мс в начале audio до конца слов вызова: сами «плюс плюс» (Termux срежет их в тексте
     *  Whisper). Слитно «плюс плюсокно» Vosk тянул второе «плюс» на «окно» — конец вызова
     *  ненадёжен, его начало надёжно. how — каким путём сработал вызов (для разбора в поле). */
    class Event(val id: Long, val kind: String, val text: String, @Volatile var audio: ByteArray? = null,
                val stop: Boolean = false, val ms: Long = System.currentTimeMillis(),
                val lead: Int = 0, val how: String = "")

    @Volatile var state = "off"         // off | listening | hearing
    @Volatile var micDb = -1            // дБ ниже полной шкалы: 0 — громче некуда, 90 — тишина; -1 — нет данных
    /** Фраза кончается после стольких секунд тишины: Александр думает на ходу — 2 с в игре
     *  мало; быстро отправить — «копи копи». */
    @Volatile var pauseSec = 10f
        private set
    /** Самая длинная фраза, с: 45 обрывало рассказ. */
    @Volatile var maxSec = 180f
        private set

    /** Когда уши Termux (ushi) последний раз сказали, что Claude Плюса жив (tmux «plus»). */
    @Volatile var plusSeenMs = 0L

    /** Плюс уже работает — запускать заново не надо, только открыть Termux. */
    fun plusAlive(nowMs: Long = System.currentTimeMillis()) = nowMs - plusSeenMs < 6000

    /** Когда ushi последний раз видел свежий кадр ретранслятора стрима (retr). */
    @Volatile var strimSeenMs = 0L

    /** Идёт стрим через ретранслятор: свой захват не включать — оборвёт BeamXR. */
    fun strimLive(nowMs: Long = System.currentTimeMillis()) = nowMs - strimSeenMs < 6000

    /** Секунда после ответа Плюса — слушать без «плюс плюс» (2.3.1). */
    val dosluh = Dosluh()

    /** Настройка с /ear/set; значения зажимаются в разумные пределы. */
    fun set(pause: Float?, max: Float?, dosluhSec: Float? = null, dosluhDb: Int? = null) {
        if (pause != null && !pause.isNaN()) pauseSec = pause.coerceIn(0.5f, 15f)
        if (max != null && !max.isNaN()) maxSec = max.coerceIn(5f, 300f)
        dosluh.set(dosluhSec, dosluhDb)
    }

    fun nastroyki(): String =
        "{\"pause\":$pauseSec,\"max\":$maxSec,\"dosluh\":${dosluh.oknoMs / 1000.0},\"db\":${dosluh.porogDb}}"
    private val events = ArrayDeque<Event>()
    private var lastId = 0L
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    fun post(kind: String, text: String = "", audio: ByteArray? = null, stop: Boolean = false,
             lead: Int = 0, how: String = ""): Event = lock.withLock {
        val e = Event(++lastId, kind, text, audio, stop, lead = lead, how = how)
        events.addLast(e)
        while (events.size > keep) events.removeFirst()
        var withAudio = 0
        for (old in events.reversed()) if (old.audio != null && ++withAudio > keepAudio) old.audio = null
        changed.signalAll()
        e
    }

    fun lastId(): Long = lock.withLock { lastId }

    /** Первое событие новее [id]; нет — ждём до [waitMs] (уши Termux не опрашивают
     *  раз в 0,4 с, а узнают сразу). Так и не случилось — null. */
    fun after(id: Long, waitMs: Long = 0): Event? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (left > 0 && events.none { it.id > id }) left = changed.awaitNanos(left)
        events.firstOrNull { it.id > id }
    }

    fun get(id: Long): Event? = lock.withLock { events.firstOrNull { it.id == id } }

    companion object {
        /** WAV-файл из PCM 16 бит моно. */
        fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return b.array()
        }
    }
}
