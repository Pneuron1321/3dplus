package com.questclaude.bridge

/**
 * Табло соведущего (2.2.8), левый верх эфира: «PLUS CALLS 3/5» — сколько ставок Плюса сбылось из
 * проверенных, и вызов раунда «CHALLENGE: pistol only» со счётом «2/3». Счёт ведёт демон
 * vedushchiy в Termux (у него книга ставок и вызовов) и шлёт сюда (/igra); приложение только
 * показывает. Подписи — по-английски. Обычная JVM — IgraTest.
 */
class Igra {
    @Volatile var popal = 0
        private set
    @Volatile var stavok = 0
        private set
    @Volatile var vyzov = ""
        private set
    @Volatile var sdelano = 0
        private set
    @Volatile var vyzovov = 0
        private set
    @Volatile var pokaz = true
        private set

    /** Есть что показать: хоть одна проверенная ставка или вызов. */
    val vidna: Boolean get() = pokaz && (stavok > 0 || vyzov.isNotEmpty() || vyzovov > 0)

    /** /igra: popal, stavok — ставки; vyzov — текущий вызов (по-английски; пусто — нет); sdelano, vyzovov; pokaz — 0/1; sbros=1. */
    @Synchronized fun upr(q: Map<String, String>): String {
        if (q["sbros"] == "1") { popal = 0; stavok = 0; vyzov = ""; sdelano = 0; vyzovov = 0 }
        q["stavok"]?.toIntOrNull()?.let { stavok = it.coerceIn(0, 999) }
        q["popal"]?.toIntOrNull()?.let { popal = it.coerceIn(0, 999) }
        if (popal > stavok) popal = stavok
        q["vyzov"]?.let { vyzov = Titry.chisto(Titry.dekod(it)).take(MAKS_VYZOV) }
        q["vyzovov"]?.toIntOrNull()?.let { vyzovov = it.coerceIn(0, 999) }
        q["sdelano"]?.toIntOrNull()?.let { sdelano = it.coerceIn(0, 999) }
        if (sdelano > vyzovov) sdelano = vyzovov
        q["pokaz"]?.let { pokaz = it != "0" }
        return status()
    }

    fun status(): String =
        "{\"popal\":$popal,\"stavok\":$stavok,\"vyzov\":${FrameServer.json(vyzov)},\"sdelano\":$sdelano," +
            "\"vyzovov\":$vyzovov,\"pokaz\":$pokaz,\"vidna\":$vidna}"

    companion object {
        const val MAKS_VYZOV = 48
    }
}
