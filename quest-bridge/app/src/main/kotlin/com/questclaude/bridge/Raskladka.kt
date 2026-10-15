package com.questclaude.bridge

/**
 * Где гости в кадре эфира (2.5.0) — чистая арифметика в точках кадра 1080p, начало — слева вверху
 * (как у картинок плашек); развилка переводит в свои (GL — от низа, масштаб o.h/1080). Проверяется
 * на столе (RaskladkaTest).
 *
 * Плитки — слева посередине, столбиком: между табло (левый верх, до 129) и розой ветров (левый низ,
 * 150 + отступ), по центру этого промежутка. Квадрат: одно-два места — 240, три — 210; зазор 14.
 * Размер — по числу мест (не прыгает, когда кто-то вышел), место по высоте — по числу плиток в кадре.
 * Пузыри субтитров гостя — справа от его плитки, по её середине.
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
    /** Слои гостей (картинки плашек): "lg" — рамки и имена (SLOY_LG×SLOY_H), "lb" — пузыри (SLOY_LB×SLOY_H);
     *  левый верх обоих — (0; SLOY_Y) в точках 1080p. */
    const val SLOY_Y = 130f
    const val SLOY_H = 800
    const val SLOY_LG = 300
    const val SLOY_LB = 900

    /** Плитка: левый верх и сторона квадрата (точки 1080p). */
    class Plitka(val x: Float, val y: Float, val s: Float) {
        val niz: Float get() = y + s
        val pravo: Float get() = x + s
        val centrY: Float get() = y + s / 2
        override fun toString() = "Plitka($x, $y, $s)"
    }

    fun storona(mest: Int) = if (mest >= 3) 210f else 240f

    /** Плитки [n] гостей в кадре при [mest] местах. */
    fun plitki(n: Int, mest: Int): List<Plitka> {
        if (n <= 0) return emptyList()
        val s = storona(mest)
        val vsego = n * s + (n - 1) * ZAZOR
        val y0 = VERH + maxOf(0f, (NIZ - VERH - vsego) / 2f)
        return (0 until n).map { Plitka(KRAY, y0 + it * (s + ZAZOR), s) }
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
