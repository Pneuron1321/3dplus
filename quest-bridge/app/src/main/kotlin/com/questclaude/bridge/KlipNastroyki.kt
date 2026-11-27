package com.questclaude.bridge

import java.io.File

/**
 * Что сохраняет «клип клип клип» (2.8.13, Александр 24.11: «клипы намного качественнее… с кнопками вкл/выкл, чтобы
 * одним нажатием вернуться к тому, что было, и обратно; по умолчанию 1 и 2 включены, камера нужна реже»):
 *  - kachestvo — клип шлема своей записью в высоком качестве: тот же кадр, что в эфире (оформление, окно камеры,
 *    Плюс), 1080p 60 к/с, H.264 High 20 Мбит/с (эфир — 6 Мбит/с, 30 к/с, и проседает с сетью). Выкл — как было:
 *    клип = копия эфира;
 *  - most3 — вместе с ним чистая игра из Моста 3 (его круг: 16 Мбит/с, 60 к/с, без оформления);
 *  - kamera — клип камеры телефона (как пришёл), по умолчанию выкл.
 * Файл klip-nastroyki: «kachestvo most3 kamera» нулями и единицами. Обычная JVM — KlipNastroykiTest.
 */
object KlipNastroyki {
    @Volatile var kachestvo = true; private set
    @Volatile var most3 = true; private set
    @Volatile var kamera = false; private set
    @Volatile private var dir: File? = null
    const val FAJL = "klip-nastroyki"

    fun zagruzit(d: File) {
        dir = d
        val p = try { File(d, FAJL).readText().trim().split(' ') } catch (e: Exception) { emptyList() }
        if (p.size == 3) { kachestvo = p[0] == "1"; most3 = p[1] == "1"; kamera = p[2] == "1" }
    }

    private fun da(v: String?): Boolean? = when (v?.lowercase()) {
        "1", "vkl", "da", "true", "on" -> true; "0", "vykl", "net", "false", "off" -> false; else -> null }

    /** kachestvo= most3= kamera= (1|0|vkl|vykl); true — что-то поменялось. */
    fun upr(q: Map<String, String>): Boolean {
        val b = listOf(kachestvo, most3, kamera)
        da(q["kachestvo"])?.let { kachestvo = it }
        da(q["most3"])?.let { most3 = it }
        da(q["kamera"])?.let { kamera = it }
        val izm = b != listOf(kachestvo, most3, kamera)
        if (izm) dir?.let { try { File(it, FAJL).writeText(stroka()) } catch (e: Exception) { } }
        return izm
    }

    fun postavit(kachestvo: Boolean? = null, most3: Boolean? = null, kamera: Boolean? = null): Boolean =
        upr(listOfNotNull(kachestvo?.let { "kachestvo" to (if (it) "1" else "0") }, most3?.let { "most3" to (if (it) "1" else "0") },
            kamera?.let { "kamera" to (if (it) "1" else "0") }).toMap())

    fun stroka() = listOf(kachestvo, most3, kamera).joinToString(" ") { if (it) "1" else "0" }

    fun json() = "\"kachestvo\":$kachestvo,\"most3\":$most3,\"kamera\":$kamera"

    /** Для тестов: как при первом запуске. */
    internal fun zanovo() { kachestvo = true; most3 = true; kamera = false; dir = null }
}
