package com.questclaude.bridge

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Мини-плеер (2.5.7, Александр 17.10): шторка в приложении управляет плеером muzyka в Ubuntu, как голос.
 * Плеер сам присылает своё состояние (POST /fon/pleer: играет ли, трек, автор, громкость, плейлисты, треки) и
 * сам забирает команды (GET /fon/komanda — ждёт до 25 с). Приложение кладёт команду и рисует то, что прислал
 * плеер: своего знания о треках у моста нет, плейлисты живут в Ubuntu.
 */
class PleerPult(private val chasy: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var sostoyanie: String = ""
        private set
    @Volatile var sostoyanieMs = 0L
        private set
    private val komandy = LinkedBlockingDeque<String>()

    /** Плеер прислал состояние (JSON как есть, не длиннее 64 КБ). */
    fun zapisat(json: String): String {
        sostoyanie = json.take(65536)
        sostoyanieMs = chasy()
        return "{\"ok\":true}"
    }

    /** Плеер жив: присылал состояние меньше 15 с назад. */
    fun zhiv(): Boolean = sostoyanie.isNotEmpty() && chasy() - sostoyanieMs < 15_000

    /** Команда плееру: k — vkl, stop, sled, pred, gromkost, pleylist, vse, udalit; a — трек, число, плейлист. */
    fun komanda(k: String, a: String = ""): Boolean {
        if (k !in KOMANDY) return false
        val j = "{\"k\":${FrameServer.json(k)},\"a\":${FrameServer.json(a.take(200))},\"t\":${chasy()}}"
        // громкость ползунком — только последняя; очередь короткая: старое плееру уже не нужно
        if (k == "gromkost") komandy.removeIf { it.contains("\"k\":\"gromkost\"") }
        komandy.offerLast(j)
        while (komandy.size > 16) komandy.pollFirst()
        return true
    }

    /** Для плеера: следующая команда или «{}» через ms. */
    fun zhdat(ms: Long): String = komandy.pollFirst(ms.coerceIn(0, 25_000), TimeUnit.MILLISECONDS) ?: "{}"

    companion object {
        val KOMANDY = setOf("vkl", "stop", "sled", "pred", "gromkost", "pleylist", "vse", "udalit")
    }
}
