package com.questclaude.bridge

/**
 * Где гости в кадре эфира (2.5.0) — чистая арифметика в точках кадра 1080p, начало — слева вверху
 * (как у картинок плашек); развилка переводит в свои (GL — от низа, масштаб o.h/1080). Проверяется
 * на столе (RaskladkaTest).
 *
 * Плитки — слева посередине, столбиком: между табло (левый верх, до 129) и розой ветров (левый низ,
 * 150 + отступ), по центру этого промежутка. Квадрат: одно-два места — 240, три — 210; зазор 14.
 * Размер — по числу мест (не прыгает, когда кто-то вышел), место по высоте — по числу плиток в кадре.
 * Без камеры (Александр 15.10: «круг займёт много места») — не квадрат, а строка с именем высотой STROKA
 * и огоньком голоса, полупрозрачная; видео в квадрате — прозрачное на 15 % (VIDEO_A).
 * Пузыри субтитров гостя — справа от его плитки (строки), по её середине.
 *
 * Нижние субтитры — по центру свободного низа: между розой и окном камеры (если окно внизу), а не по
 * центру кадра — длинная строка уходила под окно камеры.
 */
object Raskladka {
    const val KRAY = 11f                     // отступ от края, как у плашек (1 % высоты)
    const val VERH = 150f                    // ниже табло левого верха
    const val NIZ = 1080f - 11f - 150f - 20f // выше розы ветров
    const val ZAZOR = 14f
    const val RADIUS = 18f
    const val PUZYR_OTSTUP = 16f
    const val PUZYR_SHIR = 560f
    /** Строка гостя без камеры: высота (ширина — как у квадрата, столбик ровный). */
    const val STROKA = 44f
    /** Непрозрачность видео в плитке (прозрачность 15 %). */
    const val VIDEO_A = 0.85f
    /** Слои гостей (картинки плашек): "lg" — рамки и имена (SLOY_LG×SLOY_H), "lb" — пузыри (SLOY_LB×SLOY_H);
     *  левый верх обоих — (0; SLOY_Y) в точках 1080p. */
    const val SLOY_Y = 130f
    const val SLOY_H = 800
    const val SLOY_LG = 540                  // 2.7.8: плитка до ×2 (480) со свечением
    const val SLOY_LB = 1160

    /** Плитка: левый верх, ширина [s] (точки 1080p); с камерой — квадрат s×s, без — строка s×STROKA. */
    class Plitka(val x: Float, val y: Float, val s: Float, val video: Boolean = true) {
        val h: Float get() = if (video) s else STROKA
        val niz: Float get() = y + h
        val pravo: Float get() = x + s
        val centrY: Float get() = y + h / 2
        override fun toString() = "Plitka($x, $y, $s${if (video) "" else ", строка"})"
    }

    fun storona(mest: Int) = if (mest >= 3) 210f else 240f

    /** Плитки [n] гостей в кадре при [mest] местах (все с камерой). */
    fun plitki(n: Int, mest: Int): List<Plitka> = plitki(List(maxOf(0, n)) { true }, mest)

    /**
     * Плитки гостей в кадре по порядку: [video] — у кого камера (квадрат), у кого нет (строка с именем);
     * [razmery] — множители размера (2.7.8, `gosti razmer`): не влезают по высоте — все квадраты чуть меньше.
     */
    fun plitki(video: List<Boolean>, mest: Int, razmery: List<Float> = emptyList()): List<Plitka> {
        if (video.isEmpty()) return emptyList()
        val s0 = storona(mest)
        val r = video.indices.map { razmery.getOrElse(it) { 1f }.coerceIn(0.5f, 2f) }
        val stroki = video.count { !it } * STROKA + (video.size - 1) * ZAZOR
        val kv = video.indices.filter { video[it] }.sumOf { (s0 * r[it]).toDouble() }.toFloat()
        val k = if (kv > 0f && stroki + kv > NIZ - VERH) maxOf(0.3f, (NIZ - VERH - stroki) / kv) else 1f
        val vsego = stroki + kv * k
        var y = VERH + maxOf(0f, (NIZ - VERH - vsego) / 2f)
        return video.mapIndexed { i, v -> Plitka(KRAY, y, if (v) s0 * r[i] * k else s0 * r[i], v).also { y += it.h + ZAZOR } }
    }

    /** Левый край пузыря субтитров у плитки. */
    fun puzyrX(p: Plitka) = p.pravo + PUZYR_OTSTUP

    /**
     * Центр нижних субтитров по горизонтали (точки выхода шириной [w]): между левым препятствием
     * [levo] (правый край розы, 0 — нет) и правым [pravo] (левый край окна камеры внизу справа; w — нет);
     * картинка шириной [shir] не выходит за кадр.
     */
    fun centrNiza(w: Int, levo: Int, pravo: Int, shir: Int): Int {
        val c = (levo + pravo) / 2
        return c.coerceIn(shir / 2, maxOf(shir / 2, w - shir / 2))
    }
}
