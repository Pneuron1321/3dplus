package com.questclaude.bridge

import java.io.File

/**
 * Каким словом звать клип (2.8.17, Александр 26.11: «кнопку для переключения триггер-слова для клипа между клип и
 * новым словом скскс, которое будет стоять по умолчанию»):
 *  - sks (по умолчанию) — «кс-кс-кс» одной фразой (2.8.18; Александр 26.11: «три раза скскс — заморочено, сделай одно
 *    кс-кс-кс»): правило Triggers.seriya, «клип клип клип» молчит;
 *  - klip — как было: «клип клип клип»; слов «кс-кс-кс» и их приманок нет в грамматике вовсе.
 * primanki — приманки правила «кс-кс-кс» («скажи», «скорее», «сикс»…): без них одно «скс» дают обычные слова; если
 * начнут перебивать его «кс-кс-кс» — выключить без сборки (/ear/klip?primanki=0, `efir klip primanki vykl`).
 * versiya растёт при каждой перемене — уши сами пересобирают грамматику.
 * Кнопка — в шторке «🎬 КЛИПЫ», /ear/klip?slovo=sks|klip, у Плюса `efir klip slovo sks|klip`.
 * Файл klip-slovo: «sks» | «klip», вторым словом «0» — приманки выключены. Обычная JVM — KlipSlovoTest.
 */
object KlipSlovo {
    const val SKS = "sks"
    const val KLIP = "klip"
    const val FAJL = "klip-slovo"
    @Volatile var slovo = SKS; private set
    @Volatile var primanki = true; private set
    @Volatile var versiya = 0; private set
    @Volatile private var dir: File? = null

    fun zagruzit(d: File) {
        dir = d
        val p = try { File(d, FAJL).readText().trim().split(' ') } catch (e: Exception) { emptyList() }
        val s = p.getOrNull(0)
        if (s == SKS || s == KLIP) slovo = s
        primanki = p.getOrNull(1) != "0"
        versiya++
    }

    private fun zapisat() {
        versiya++
        dir?.let { try { File(it, FAJL).writeText(slovo + if (primanki) "" else " 0") } catch (e: Exception) { } }
    }

    /** sks | klip (и «кс-кс-кс» / «клип»); true — поменялось. */
    fun postavit(s: String?): Boolean {
        val n = when (s?.trim()?.lowercase()) { "sks", "скскс", "кс-кс-кс", "кс" -> SKS; "klip", "клип" -> KLIP; else -> return false }
        if (n == slovo) return false
        slovo = n
        zapisat()
        return true
    }

    /** Приманки правила «кс-кс-кс»: 1|0|vkl|vykl; true — поменялось. */
    fun postavitPrimanki(v: String?): Boolean {
        val n = when (v?.trim()?.lowercase()) { "1", "vkl", "da", "true" -> true; "0", "vykl", "net", "false" -> false; else -> return false }
        if (n == primanki) return false
        primanki = n
        zapisat()
        return true
    }

    fun pereklyuchit() = postavit(if (slovo == SKS) KLIP else SKS)

    /** Что сказать, чтобы сохранить клип. */
    fun fraza(s: String = slovo) = if (s == SKS) "кс-кс-кс" else "клип клип клип"

    /** Коротко — для кнопок. */
    fun korotko(s: String = slovo) = if (s == SKS) "«кс-кс-кс»" else "«клип» ×3"

    /** Это действие ушей сейчас — клип? «клип клип клип» — только при klip, «кс-кс-кс» — только при sks. */
    fun beret(action: String) = when (action) {
        "klip" -> slovo == KLIP
        Triggers.SKS -> slovo == SKS
        else -> true
    }

    fun json() = "{\"slovo\":\"$slovo\",\"fraza\":\"${fraza()}\",\"primanki\":$primanki}"

    /** Для тестов: как при первом запуске. */
    internal fun zanovo() { slovo = SKS; primanki = true; dir = null; versiya++ }
}
