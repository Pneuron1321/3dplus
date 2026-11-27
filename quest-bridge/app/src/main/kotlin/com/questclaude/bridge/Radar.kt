package com.questclaude.bridge

import kotlin.math.roundToInt

/**
 * Арифметика радара эфира (левый низ): роза ветров по живому компасу шлема. Рисует
 * Nalozhenie, здесь — то, что проверяется на столе (RadarTest). Подписи в эфире — по-английски.
 */
object Radar {
    private val STORONY = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** Угол в 0…360. */
    fun norm(d: Float): Float = ((d % 360f) + 360f) % 360f

    /** Кратчайший поворот от a к b, градусы −180…180. */
    fun raznica(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** Сторона света по курсу: 16 румбов (N, NNE, NE…). */
    fun storona(kurs: Float): String = STORONY[((norm(kurs) / 22.5f).roundToInt()) % 16]

    /** Подпись под радаром: «247° WSW»; без магнитометра — «≈247° WSW»; нет компаса — «no compass». */
    fun podpis(kurs: Float?, tip: String): String = when {
        kurs == null || tip == "none" -> "no compass"
        else -> (if (tip == "relative") "≈" else "") + "${norm(kurs).roundToInt() % 360}° ${storona(kurs)}"
    }
}
