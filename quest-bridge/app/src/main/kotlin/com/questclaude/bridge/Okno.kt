package com.questclaude.bridge

import kotlin.math.roundToInt

/**
 * Где окно камеры в кадре эфира — чистая арифметика (проверяется на столе). Начало координат —
 * внизу слева, как в GL. Отступ снизу/сверху — 3 % высоты кадра (или вплотную); сбоку — свой,
 * в процентах ширины (2.3.8, Александр 11.10: «камеру на пару сантиметров от правого края, а по
 * низу вплотную» — 4 % ширины, 77 точек в 1080p, около 2 см на мониторе); окно не выше 90 % кадра.
 *
 * Поворот (2.3.3, Александр 11.10: «снимаю на айфон вертикально — поверни картинку на 90° по
 * часовой, до угла и на 15 % больше»): картинка камеры поворачивается в окне на 90/180/270° по
 * часовой; размер — по длинной стороне окна, так что повёрнутое окно той же величины, только стоя.
 */
object Okno {
    /** rezhim: ugol — в углу, krupno — камера во весь кадр (с полями), skryt — нет окна.
     *  gde: первая буква p/l — справа/слева, вторая n/v — внизу/вверху. dolya — длинная сторона
     *  окна в долях ширины кадра. povorot — 0/90/180/270° по часовой. vplotnuyu — без отступа снизу
     *  (сверху). sboku — отступ от бокового края, % ширины кадра (0 — вплотную). */
    class Vid(val rezhim: String = "ugol", val gde: String = "pn", val dolya: Float = 0.25f,
              val povorot: Int = 0, val vplotnuyu: Boolean = false, val sboku: Float = SBOKU) {
        override fun toString() = "$rezhim $gde $dolya $povorot ${if (vplotnuyu) 0 else 1} $sboku"

        companion object {
            val REZHIMY = setOf("ugol", "krupno", "skryt")
            val UGLY = setOf("pn", "ln", "pv", "lv")
            val POVOROTY = setOf(0, 90, 180, 270)
            const val MIN = 0.1f
            const val MAX = 0.5f
            /** Отступ сбоку по умолчанию (% ширины) и наибольший. */
            const val SBOKU = 4f
            const val SBOKU_MAX = 20f

            /** Из запроса /kamera/vid (v, gde, r, pov, kray=0|1, bok — % ширины) поверх прежнего; чужое — прежнее. */
            fun iz(q: Map<String, String>, b: Vid): Vid = Vid(
                q["v"]?.takeIf { it in REZHIMY } ?: b.rezhim,
                q["gde"]?.takeIf { it in UGLY } ?: b.gde,
                q["r"]?.replace(',', '.')?.toFloatOrNull()?.coerceIn(MIN, MAX) ?: b.dolya,
                q["pov"]?.toIntOrNull()?.takeIf { it in POVOROTY } ?: b.povorot,
                when (q["kray"]) { "0" -> true; "1" -> false; else -> b.vplotnuyu },
                q["bok"]?.replace(',', '.')?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(0f, SBOKU_MAX) ?: b.sboku)

            /** Из строки файла «ugol pn 0.25 90 1 4.0» (старые — без поворота, края и отступа сбоку: сбоку 4 %). */
            fun izStroki(s: String): Vid {
                val p = s.trim().split(' ')
                return iz(listOf("v", "gde", "r", "pov", "kray", "bok").mapIndexed { i, k -> k to p.getOrElse(i) { "" } }.toMap(), Vid())
            }
        }
    }

    /** x, y, ширина, высота окна в кадре w×h; ar — ширина/высота картинки камеры (уже повёрнутой). */
    fun mesto(w: Int, h: Int, ar: Float, v: Vid): IntArray {
        if (v.rezhim == "krupno") {
            var cw = w
            var ch = (w / ar).toInt()
            if (ch > h) { ch = h; cw = (h * ar).toInt() }
            return intArrayOf((w - cw) / 2, (h - ch) / 2, cw, ch)
        }
        val m = if (v.vplotnuyu) 0 else h * 3 / 100            // снизу / сверху
        val mx = (w * v.sboku / 100f).roundToInt()            // сбоку
        val dlina = (w * v.dolya).toInt()                     // длинная сторона окна
        var cw = if (ar >= 1f) dlina else (dlina * ar).toInt()
        var ch = if (ar >= 1f) (dlina / ar).toInt() else dlina
        val maxH = h * 9 / 10 - 2 * m
        if (ch > maxH) { ch = maxH; cw = (ch * ar).toInt() }
        cw = minOf(cw, w - mx)
        val x = if (v.gde.startsWith("l")) mx else w - cw - mx
        val y = if (v.gde.endsWith("n")) m else h - ch - m
        return intArrayOf(x, y, cw, ch)
    }

    /** Пропорции окна для картинки камеры kw×kh, повёрнутой на [povorot]. */
    fun proporcii(kw: Int, kh: Int, povorot: Int): Float =
        if (povorot == 90 || povorot == 270) kh.toFloat() / kw else kw.toFloat() / kh

    /**
     * Матрица текстурных координат (по столбцам, как у GL): точка окна (u, v) → точка картинки
     * камеры, повёрнутой на [povorot]° по часовой. Умножается справа на матрицу SurfaceTexture.
     * 90°: верх картинки — у правого края окна: берём (1 − v, u).
     */
    fun povorot(povorot: Int): FloatArray = when (povorot) {
        90 -> floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
        180 -> floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        270 -> floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}
