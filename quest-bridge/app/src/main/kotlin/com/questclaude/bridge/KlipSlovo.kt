package com.questclaude.bridge

import java.io.File

/**
 * Каким словом звать клип (2.8.17, Александр 26.11: «кнопку для переключения триггер-слова для клипа между клип и
 * новым словом скскс, которое будет стоять по умолчанию»):
 *  - sks (по умолчанию) — «скскс скскс скскс»: уши ловят серию скс/кс (Triggers.Seriya), «клип клип клип» молчит;
 *  - klip — как было: «клип клип клип», серия «скскс» молчит.
 * Одно «скскс» модель ушей не отличает от «скажи», «кис-кис», «six» — поэтому три раза подряд, как «клип клип клип».
 * Кнопка — в шторке «🎬 КЛИПЫ», /ear/klip?slovo=sks|klip, у Плюса `efir klip slovo sks|klip`.
 * Файл klip-slovo: sks | klip. Обычная JVM — KlipSlovoTest.
 */
object KlipSlovo {
    const val SKS = "sks"
    const val KLIP = "klip"
    const val FAJL = "klip-slovo"
    @Volatile var slovo = SKS; private set
    @Volatile private var dir: File? = null

    fun zagruzit(d: File) {
        dir = d
        val s = try { File(d, FAJL).readText().trim() } catch (e: Exception) { "" }
        if (s == SKS || s == KLIP) slovo = s
    }

    /** sks | klip (и «скскс» / «клип»); true — поменялось. */
    fun postavit(s: String?): Boolean {
        val n = when (s?.trim()?.lowercase()) { "sks", "скскс" -> SKS; "klip", "клип" -> KLIP; else -> return false }
        if (n == slovo) return false
        slovo = n
        dir?.let { try { File(it, FAJL).writeText(n) } catch (e: Exception) { } }
        return true
    }

    fun pereklyuchit() = postavit(if (slovo == SKS) KLIP else SKS)

    /** Что сказать, чтобы сохранить клип. */
    fun fraza(s: String = slovo) = if (s == SKS) "скскс скскс скскс" else "клип клип клип"

    /** Коротко — для кнопок. */
    fun korotko(s: String = slovo) = if (s == SKS) "«скскс» ×3" else "«клип» ×3"

    /** Это действие ушей сейчас — клип? «клип клип клип» — только при klip, серия «скскс» — только при sks. */
    fun beret(action: String) = when (action) {
        "klip" -> slovo == KLIP
        Triggers.SKS -> slovo == SKS
        else -> true
    }

    fun json() = "{\"slovo\":\"$slovo\",\"fraza\":\"${fraza()}\"}"

    /** Для тестов: как при первом запуске. */
    internal fun zanovo() { slovo = SKS; dir = null }
}
