package com.questclaude.bridge

import java.util.Locale

/**
 * Цель доната в эфире (2.2.7): полоса слева вверху — «GOAL: New mic · €23 / €100». Правит
 * Плюс во время эфира (скрипт donat → /cel/upr): цель и сумма, собрано, +N, валюта, скрыть.
 * Помнит в файле (как налёт). Подписи по-английски. Обычная JVM — CelTest.
 */
class Cel(zagruzit: () -> String?, private val sohranit: (String) -> Unit) {
    @Volatile var imya = ""
        private set
    @Volatile var summa = 0.0
        private set
    @Volatile var sobrano = 0.0
        private set
    @Volatile var valyuta = "€"
        private set
    /** Видна в эфире: только когда цель задана и не скрыта. */
    @Volatile var pokaz = true
        private set

    init {
        zagruzit()?.split('\t')?.let { p ->
            if (p.size >= 5) {
                summa = p[0].toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
                sobrano = p[1].toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
                valyuta = p[2].take(5)
                pokaz = p[3] != "0"
                imya = p.drop(4).joinToString(" ").take(MAKS_IMYA)
            }
        }
    }

    val vidna: Boolean get() = pokaz && summa > 0

    /** Доля собранного, 0–1. */
    fun dolya(): Float = if (summa > 0) (sobrano / summa).coerceIn(0.0, 1.0).toFloat() else 0f

    /** «€23 / €100» или «23 / 100 UAH». */
    fun tekst(): String =
        if (valyuta.length <= 1) valyuta + chislo(sobrano) + " / " + valyuta + chislo(summa)
        else chislo(sobrano) + " / " + chislo(summa) + " " + valyuta

    private fun chislo(v: Double): String =
        if (v == Math.floor(v)) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%,.2f", v)

    /**
     * /cel/upr: summa — цель (сумма); imya — на что (UTF-8 в процентах); sobrano — сколько
     * собрано; plus — прибавить (минус — убавить); valyuta; pokaz — 0/1; sbros=1 — убрать цель.
     */
    @Synchronized fun upr(q: Map<String, String>): String {
        if (q["sbros"] == "1") { summa = 0.0; sobrano = 0.0; imya = "" }
        q["summa"]?.let { chislo(it) }?.let { summa = it.coerceIn(0.0, MAKS) }
        q["imya"]?.let { imya = Titry.chisto(Titry.dekod(it)).take(MAKS_IMYA) }
        q["sobrano"]?.let { chislo(it) }?.let { sobrano = it.coerceIn(0.0, MAKS) }
        q["plus"]?.let { chislo(it) }?.let { sobrano = (sobrano + it).coerceIn(0.0, MAKS) }
        q["valyuta"]?.let { Titry.dekod(it).trim().take(5) }?.takeIf { it.isNotEmpty() }?.let { valyuta = it }
        q["pokaz"]?.let { pokaz = it != "0" }
        sohranit(listOf(summa, sobrano, valyuta, if (pokaz) "1" else "0", imya).joinToString("\t"))
        return status()
    }

    fun status(): String =
        "{\"imya\":${FrameServer.json(imya)},\"summa\":$summa,\"sobrano\":$sobrano,\"valyuta\":${FrameServer.json(valyuta)}," +
            "\"tekst\":${FrameServer.json(tekst())},\"dolya\":${dolya()},\"vidna\":$vidna,\"pokaz\":$pokaz}"

    companion object {
        const val MAKS = 10_000_000.0
        const val MAKS_IMYA = 40

        /** «23», «23.5», «23,50», «-5» — число; иначе null. */
        fun chislo(s: String): Double? =
            Titry.dekod(s).trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}
