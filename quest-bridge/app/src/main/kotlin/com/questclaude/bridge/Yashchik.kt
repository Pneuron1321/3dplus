package com.questclaude.bridge

/**
 * Почтовый ящик плашек (2.3.9, план эфира III.1). Плашки рисует свой поток (Nalozhenie), а в
 * видеокарту их грузит поток развилки — тот же, что отдаёт кадры эфиру. До 2.3.9 каждая плашка
 * грузилась сразу, по вызову: лицо и роза — 28 раз в секунду, чат — на каждое сообщение, и кадр
 * эфира ждал в очереди за загрузками. Теперь писатель кладёт готовую картинку в ящик и сразу
 * уходит; развилка в начале кадра забирает одну — самую давнюю из новых. Не успели забрать, а
 * пришла новее — старая не грузится вовсе (только последнее).
 *
 * Картинки — по два буфера на угол: писатель рисует в свой, кладёт его сюда и забирает прежний
 * (его уже никто не грузит — загрузка идёт под тем же замком). null — убрать плашку.
 * Обычная JVM — YashchikTest.
 */
class Yashchik<T : Any> {
    private class Mesto<T>(var gotovo: T?, var est: Boolean, var t: Long)

    private val mesta = LinkedHashMap<String, Mesto<T>>()
    private var schet = 0L

    /** Положить новую картинку угла (null — убрать). Вернёт прежнюю картинку угла — писателю под следующую. */
    @Synchronized fun polozhit(ugol: String, x: T?): T? {
        val m = mesta.getOrPut(ugol) { Mesto(null, false, 0) }
        val bylo = m.gotovo
        m.gotovo = x
        m.est = true
        m.t = ++schet
        return bylo
    }

    /**
     * Забрать самое давнее новое: [blok] (угол, картинка или null — убрать) выполняется под замком —
     * писатель не подменит картинку посреди загрузки. false — нового нет.
     */
    @Synchronized fun zabrat(blok: (String, T?) -> Unit): Boolean {
        var kto: String? = null
        var m: Mesto<T>? = null
        for ((u, x) in mesta) if (x.est && (m == null || x.t < m.t)) { kto = u; m = x }
        if (kto == null || m == null) return false
        m.est = false
        blok(kto, m.gotovo)
        return true
    }

    /** Сколько углов ждут загрузки. */
    @Synchronized fun zhdut(): Int = mesta.values.count { it.est }

    /** Всё, что лежит (при остановке — освободить). */
    @Synchronized fun vse(): List<T> = mesta.values.mapNotNull { it.gotovo }.also { mesta.clear() }
}
