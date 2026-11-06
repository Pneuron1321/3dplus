package com.questclaude.bridge

import java.util.Locale

/**
 * Итоги вечера (2.2.7): тёмный экран во весь кадр эфира по кнопке приложения или команде Плюса
 * (itogi → /itogi/upr). Цифры приложение считает само: сколько шёл эфир, налёт за эфир и всего,
 * сколько раз звали Плюса, цель доната; ниже — до 6 строк от Плюса (лучшие моменты, по-английски).
 * Рисует Nalozhenie. Обычная JVM — ItogiTest.
 */
class Itogi {
    @Volatile var vidny = false
    @Volatile var stroki: List<String> = emptyList()
        private set
    /** Сколько раз звали Плюса за этот эфир (считает Nalozhenie по Hub.lastWakeMs). */
    @Volatile var vyzovov = 0
    /** Меняется, когда меняется показ, — плашке пора перерисоваться. */
    @Volatile var versiya = 0L
        private set

    /** Новый эфир — счёт с нуля; строки Плюса прошлого вечера — прочь (включённые заранее итоги — остаются). */
    fun novyEfir() {
        vyzovov = 0
        stroki = emptyList()
        versiya++
    }

    /** Эфир кончился — итоги убрать: следующий эфир не начнётся с тёмного экрана. */
    fun konecEfira() {
        vidny = false
        versiya++
    }

    /** /itogi/upr: pokaz — 0/1; stroki — строки через перевод строки (UTF-8 в процентах); sbros=1. */
    fun upr(q: Map<String, String>): String {
        if (q["sbros"] == "1") stroki = emptyList()
        q["stroki"]?.let { s ->
            stroki = Titry.dekod(s).split('\n').map { Titry.chisto(it).take(MAKS_DLINA) }.filter { it.isNotEmpty() }.take(MAKS_STROK)
        }
        q["pokaz"]?.let { vidny = it != "0" }
        versiya++
        return status()
    }

    fun status(): String =
        "{\"vidny\":$vidny,\"vyzovov\":$vyzovov,\"stroki\":[${stroki.joinToString(",") { FrameServer.json(it) }}]}"

    companion object {
        const val MAKS_STROK = 6
        const val MAKS_DLINA = 90

        /** «1:52:10» или «12 min». */
        fun vremya(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 3600) "${s / 60} min" else String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }

        /** Ряды цифр итогов: подпись — значение (по-английски). */
        fun ryady(efirMs: Long, zaEfirSek: Long, vsegoSek: Long, vyzovov: Int, cel: Cel?, igra: Igra? = null): List<Pair<String, String>> {
            val r = mutableListOf(
                "On air" to vremya(efirMs),
                "Airtime coins" to "+" + Nalet.koinyEn(zaEfirSek) + "  (total " + Nalet.koinyEn(vsegoSek) + ")",
                "Questions to Plus" to vyzovov.toString(),
            )
            if (igra != null && igra.stavok > 0) r += "Plus calls" to "${igra.popal}/${igra.stavok}"
            if (igra != null && igra.vyzovov > 0) r += "Challenges" to "${igra.sdelano}/${igra.vyzovov}"
            if (cel != null && cel.summa > 0) r += "Goal" to (if (cel.imya.isNotEmpty()) cel.imya + " · " else "") + cel.tekst()
            return r
        }
    }
}
