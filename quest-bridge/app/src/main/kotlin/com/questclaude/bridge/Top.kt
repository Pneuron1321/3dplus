package com.questclaude.bridge

import java.util.Locale

/**
 * Топ-донатор в эфире (2.3.0): плашка в самом левом верхнем углу, шириной в плечи Плюса
 * (Александр 08.10–09.10): «TOP DONOR» и «Rex · $25». Кто топ — считает donaty в Termux (у него
 * книга донатов, всё время или вечер) и шлёт сюда (/top/upr); правит и Плюс. Помнит в файле.
 * Подписи по-английски. Обычная JVM — TopTest.
 */
class Top(zagruzit: () -> String?, private val sohranit: (String) -> Unit) {
    @Volatile var imya = ""
        private set
    @Volatile var summa = 0.0
        private set
    @Volatile var valyuta = "$"
        private set
    @Volatile var pokaz = true
        private set

    init {
        zagruzit()?.split('\t')?.let { p ->
            if (p.size >= 4) {
                summa = p[0].toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
                valyuta = p[1].take(5).ifEmpty { "$" }
                pokaz = p[2] != "0"
                imya = p.drop(3).joinToString(" ").take(MAKS_IMYA)
            }
        }
    }

    /** Видна в эфире: есть кто и сколько, и не скрыта. */
    val vidna: Boolean get() = pokaz && imya.isNotEmpty() && summa > 0

    /** «$25», «$12.50» или «320 UAH». */
    fun skolko(): String {
        val v = if (summa == Math.floor(summa)) String.format(Locale.US, "%,.0f", summa)
            else String.format(Locale.US, "%,.2f", summa)
        return if (valyuta.length <= 1) valyuta + v else "$v $valyuta"
    }

    /**
     * /top/upr: imya — ник (UTF-8 в процентах), summa — сколько всего, valyuta, pokaz — 0/1,
     * sbros=1 — никого. Ник — чужие слова: только буквы, цифры, «_» и «-».
     */
    @Synchronized fun upr(q: Map<String, String>): String {
        if (q["sbros"] == "1") { imya = ""; summa = 0.0 }
        q["imya"]?.let { imya = nik(Titry.dekod(it)) }
        q["summa"]?.let { Cel.chislo(it) }?.let { summa = it.coerceIn(0.0, Cel.MAKS) }
        q["valyuta"]?.let { Titry.dekod(it).trim().take(5) }?.takeIf { it.isNotEmpty() }?.let { valyuta = it }
        q["pokaz"]?.let { pokaz = it != "0" }
        sohranit(listOf(summa, valyuta, if (pokaz) "1" else "0", imya).joinToString("\t"))
        return status()
    }

    fun status(): String =
        "{\"imya\":${FrameServer.json(imya)},\"summa\":$summa,\"valyuta\":${FrameServer.json(valyuta)}," +
            "\"tekst\":${FrameServer.json(skolko())},\"vidna\":$vidna,\"pokaz\":$pokaz}"

    companion object {
        const val MAKS_IMYA = 25

        fun nik(s: String): String = s.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(MAKS_IMYA)
    }
}
