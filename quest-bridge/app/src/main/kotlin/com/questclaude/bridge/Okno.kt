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
              val povorot: Int = 0, val vplotnuyu: Boolean = false, val sboku: Float = SBOKU,
              val ramka: FloatArray = floatArrayOf(70f, 70f, 96f, 100f)) {
        /** ramka — окно «svoe» (2.8.1): левый, верхний, правый, нижний край в % кадра от левого верхнего угла. */
        override fun toString() = "$rezhim $gde $dolya $povorot ${if (vplotnuyu) 0 else 1} $sboku " +
            ramka.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) }

        companion object {
            val REZHIMY = setOf("ugol", "krupno", "skryt", "svoe")
            /** Окно «svoe» не меньше стольких % кадра по каждой стороне. */
            const val SVOE_MIN = 5f
            val UGLY = setOf("pn", "ln", "pv", "lv")
            val POVOROTY = setOf(0, 90, 180, 270)
            const val MIN = 0.1f
            const val MAX = 0.5f
            /** Отступ сбоку по умолчанию (% ширины) и наибольший. */
            const val SBOKU = 4f
            const val SBOKU_MAX = 20f

            /** Из запроса /kamera/vid (v, gde, r, pov, kray=0|1, bok — % ширины, ramka=Л,В,П,Н — % кадра, окно «svoe»)
             *  поверх прежнего; чужое — прежнее. */
            fun iz(q: Map<String, String>, b: Vid): Vid {
                val rm = q["ramka"]?.let { Titry.dekod(it) }?.split(',', ';', ' ')?.mapNotNull { it.trim().replace(',', '.').toFloatOrNull() }
                    ?.takeIf { it.size == 4 && it.none { x -> x.isNaN() } }?.let { popravit(it.toFloatArray()) }
                val v = vid0(q, b)
                return if (rm != null) Vid(if (q["v"] == null) "svoe" else v.rezhim, v.gde, v.dolya, v.povorot, v.vplotnuyu, v.sboku, rm)
                else v
            }

            private fun vid0(q: Map<String, String>, b: Vid): Vid = Vid(
                q["v"]?.takeIf { it in REZHIMY } ?: b.rezhim,
                q["gde"]?.takeIf { it in UGLY } ?: b.gde,
                q["r"]?.replace(',', '.')?.toFloatOrNull()?.coerceIn(MIN, MAX) ?: b.dolya,
                q["pov"]?.toIntOrNull()?.takeIf { it in POVOROTY } ?: b.povorot,
                when (q["kray"]) { "0" -> true; "1" -> false; else -> b.vplotnuyu },
                q["bok"]?.replace(',', '.')?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(0f, SBOKU_MAX) ?: b.sboku,
                b.ramka)

            /** Края в пределах кадра, окно не меньше [SVOE_MIN] %; перепутанные края — местами. */
            fun popravit(r: FloatArray): FloatArray {
                var (l, t, p, n) = listOf(minOf(r[0], r[2]), minOf(r[1], r[3]), maxOf(r[0], r[2]), maxOf(r[1], r[3]))
                l = l.coerceIn(0f, 100f - SVOE_MIN); t = t.coerceIn(0f, 100f - SVOE_MIN)
                p = p.coerceIn(l + SVOE_MIN, 100f); n = n.coerceIn(t + SVOE_MIN, 100f)
                return floatArrayOf(l, t, p, n)
            }

            /** Из строки файла «ugol pn 0.25 90 1 4.0 70,70,96,100» (старые — без поворота, края, отступа сбоку и рамки). */
            fun izStroki(s: String): Vid {
                val p = s.trim().split(' ')
                val v = iz(listOf("v", "gde", "r", "pov", "kray", "bok").mapIndexed { i, k -> k to p.getOrElse(i) { "" } }.toMap(), Vid())
                val rm = p.getOrNull(6)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
                return if (rm == null) v else Vid(v.rezhim, v.gde, v.dolya, v.povorot, v.vplotnuyu, v.sboku, popravit(rm.toFloatArray()))
            }
        }
    }

    /** x, y, ширина, высота окна в кадре w×h; ar — ширина/высота картинки камеры (уже повёрнутой). */
    fun mesto(w: Int, h: Int, ar: Float, v: Vid): IntArray {
        if (v.rezhim == "svoe") {                             // 2.8.1: края в % кадра (сверху вниз), GL — от низа
            val r = v.ramka
            val x = (w * r[0] / 100f).roundToInt()
            val cw = (w * r[2] / 100f).roundToInt() - x
            val verh = (h * r[1] / 100f).roundToInt()
            val ch = (h * r[3] / 100f).roundToInt() - verh
            return intArrayOf(x, h - verh - ch, cw, ch)
        }
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

    /**
     * 2.8.13 (Александр 24.11: «окно камеры не вплотную к левому нижнему углу, а Плюс думает, что вплотную»): при
     * вырезанном фоне края окна срезаются (Oformlenie.fonObrez, доли окна Л, В, П, Н) — срезанное прозрачно, и видимая
     * картинка отходит от края кадра. Окно сдвигается за край на срезанное: у угла «ugol» — к своему углу (отступы
     * vplotnuyu/sboku считаются от видимого края), у «svoe» — у тех краёв, которых окно касается. r — [x, y от низа, w, h].
     */
    fun sdvigObrezki(r: IntArray, w: Int, h: Int, v: Vid, z: FloatArray?): IntArray {
        if (z == null || z.all { it <= 0f } || v.rezhim == "krupno") return r
        val zl = (r[2] * z[0]).roundToInt(); val zp = (r[2] * z[2]).roundToInt()
        val zv = (r[3] * z[1]).roundToInt(); val zn = (r[3] * z[3]).roundToInt()
        var x = r[0]; var y = r[1]
        if (v.rezhim == "svoe") {
            val levo = r[0] <= 0; val pravo = r[0] + r[2] >= w - 1
            val niz = r[1] <= 0; val verh = r[1] + r[3] >= h - 1
            if (levo && !pravo) x -= zl else if (pravo && !levo) x += zp
            if (niz && !verh) y -= zn else if (verh && !niz) y += zv
        } else {
            x += if (v.gde.startsWith("l")) -zl else zp
            y += if (v.gde.endsWith("n")) -zn else zv
        }
        return intArrayOf(x, y, r[2], r[3])
    }

    /**
     * 2.8.16 (Александр 25.11: «просил вернуть — обрезал не с той стороны; говорит, это уже полная картинка, а она явно не
     * полная»): сколько картинки камеры НЕ видно с каждой стороны, % картинки (Л, В, П, Н — как видят зрители; зеркало
     * и поворот этого не меняют: срез — в координатах окна, заполнение — поровну с двух сторон). Режут ДВЕ вещи:
     * срез краёв z (кнопки, `vid fon obrez`; доли окна) и форма своего окна — «svoe» другой формы, чем картинка, картинка
     * заполняет окно, лишнее по краям уходит (obrezka). Плюс видел только первую. Ответ — [Л, В, П, Н, и заполнение
     * Л, В, П, Н].
     */
    fun kartinka(v: Vid, ar: Float, z: FloatArray?, w: Int = 1920, h: Int = 1080): FloatArray {
        var sx = 1f; var sy = 1f
        if (v.rezhim == "svoe") {
            val m = mesto(w, h, ar, v)
            if (m[3] > 0 && ar > 0f) {
                val ok = m[2].toFloat() / m[3]
                if (ok > ar * (1 + TOCHNOST_FORMY)) sy = ar / ok else if (ok < ar * (1 - TOCHNOST_FORMY)) sx = ok / ar
            }
        }
        val zz = z ?: FloatArray(4)
        val zx = (1 - sx) / 2 * 100; val zy = (1 - sy) / 2 * 100
        return floatArrayOf(zx + zz[0] * sx * 100, zy + zz[1] * sy * 100, zx + zz[2] * sx * 100, zy + zz[3] * sy * 100,
            zx, zy, zx, zy)
    }

    /** Форма окна и картинки «одна и та же», если разница меньше 0,5 % (края окна — в целых точках). */
    const val TOCHNOST_FORMY = 0.005f

    /**
     * 2.8.16 «ПОЛНЫЙ КАДР» для формы окна: своему окну — форму картинки в том же масштабе картинки: срезанное
     * заполнением возвращается, окно растёт по той стороне, где резало. Прижато к краям кадра, которых касалось, иначе —
     * от середины; не влезает в кадр — уменьшается целиком. Остальные виды — как есть (там картинка и так целиком).
     */
    fun polnyj(v: Vid, ar: Float, w: Int = 1920, h: Int = 1080): Vid {
        if (v.rezhim != "svoe" || ar <= 0f) return v
        val r = v.ramka
        var cw = (r[2] - r[0]) * w / 100f; var ch = (r[3] - r[1]) * h / 100f
        if (cw <= 0f || ch <= 0f) return v
        val ok = cw / ch
        if (ok <= ar * (1 + TOCHNOST_FORMY) && ok >= ar * (1 - TOCHNOST_FORMY)) return v
        if (ok > ar) ch = cw / ar else cw = ch * ar
        val k = minOf(1f, w / cw, h / ch); cw *= k; ch *= k
        fun os(a: Float, b: Float, nov: Float, ves: Float): Pair<Float, Float> {
            val u = ves * 0.002f
            var (n0, n1) = when {
                a <= u && b < ves - u -> 0f to nov                       // касалось начала — от него
                b >= ves - u && a > u -> ves - nov to ves                // касалось конца — от него
                else -> ((a + b) - nov) / 2 to ((a + b) + nov) / 2       // иначе — от середины
            }
            if (n0 < 0f) { n1 -= n0; n0 = 0f }
            if (n1 > ves) { n0 -= n1 - ves; n1 = ves }
            return n0 to n1
        }
        val (l, p) = os(r[0] * w / 100f, r[2] * w / 100f, cw, w.toFloat())
        val (t, b) = os(r[1] * h / 100f, r[3] * h / 100f, ch, h.toFloat())
        return Vid("svoe", v.gde, v.dolya, v.povorot, v.vplotnuyu, v.sboku,
            Vid.popravit(floatArrayOf(l * 100f / w, t * 100f / h, p * 100f / w, b * 100f / h)))
    }

    /** Видимая часть окна (без срезанных краёв), [x, y от низа, w, h]. */
    fun vidimoe(r: IntArray, z: FloatArray?): IntArray {
        if (z == null || z.all { it <= 0f }) return r
        val zl = (r[2] * z[0]).roundToInt(); val zp = (r[2] * z[2]).roundToInt()
        val zv = (r[3] * z[1]).roundToInt(); val zn = (r[3] * z[3]).roundToInt()
        return intArrayOf(r[0] + zl, r[1] + zn, maxOf(1, r[2] - zl - zp), maxOf(1, r[3] - zv - zn))
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

    /** Края окна сейчас, в % кадра (Л, В, П, Н сверху вниз) — для «растяни / сдвинь» из любого вида (2.8.1). */
    fun ramkaSeychas(v: Vid, ar: Float, w: Int = 1920, h: Int = 1080): FloatArray {
        if (v.rezhim == "svoe") return v.ramka.copyOf()
        val m = mesto(w, h, ar, if (v.rezhim == "skryt") Vid("ugol", v.gde, v.dolya, v.povorot, v.vplotnuyu, v.sboku) else v)
        return floatArrayOf(m[0] * 100f / w, (h - m[1] - m[3]) * 100f / h, (m[0] + m[2]) * 100f / w, (h - m[1]) * 100f / h)
    }

    /**
     * Голосом (2.8.1, Александр 30.10: «растяни влево настолько-то, вниз, вправо… в процентах»):
     * rastyani=vlevo:10 — левый край на 10 % ширины кадра левее (минус — правее, «сожми»); vpravo/vverh/vniz — так же;
     * sdvin=vniz:5 — всё окно на 5 % высоты вниз; masshtab=1.15 — больше на 15 % от центра окна. Результат — окно «svoe».
     */
    fun izmenit(v: Vid, q: Map<String, String>, ar: Float): Vid {
        val ops = listOf("rastyani", "sdvin", "masshtab").filter { q[it] != null }
        if (ops.isEmpty()) return v
        val r = ramkaSeychas(v, ar)
        for (op in ops) {
            val arg = Titry.dekod(q[op] ?: "")
            if (op == "masshtab") {
                val k = arg.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && !it.isNaN() } ?: continue
                val cx = (r[0] + r[2]) / 2; val cy = (r[1] + r[3]) / 2
                val pw = (r[2] - r[0]) * k / 2; val ph = (r[3] - r[1]) * k / 2
                r[0] = cx - pw; r[2] = cx + pw; r[1] = cy - ph; r[3] = cy + ph
                continue
            }
            val chasti = arg.split(':', limit = 2)
            val kuda = chasti[0]
            val skolko = chasti.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()
            if (skolko == null || skolko.isNaN()) continue
            if (op == "rastyani") when (kuda) {                 // сжать сильнее, чем можно, — до [SVOE_MIN], не насквозь
                "vlevo" -> r[0] = minOf(r[0] - skolko, r[2] - Vid.SVOE_MIN)
                "vpravo" -> r[2] = maxOf(r[2] + skolko, r[0] + Vid.SVOE_MIN)
                "vverh" -> r[1] = minOf(r[1] - skolko, r[3] - Vid.SVOE_MIN)
                "vniz" -> r[3] = maxOf(r[3] + skolko, r[1] + Vid.SVOE_MIN)
            } else {
                val (dx, dy) = when (kuda) { "vlevo" -> -skolko to 0f; "vpravo" -> skolko to 0f
                    "vverh" -> 0f to -skolko; "vniz" -> 0f to skolko; else -> 0f to 0f }
                val sx = dx.coerceIn(-r[0], 100f - r[2]); val sy = dy.coerceIn(-r[1], 100f - r[3])   // не за кадр
                r[0] += sx; r[2] += sx; r[1] += sy; r[3] += sy
            }
        }
        return Vid("svoe", v.gde, v.dolya, v.povorot, v.vplotnuyu, v.sboku, Vid.popravit(r))
    }

    /**
     * Окно «svoe» других пропорций, чем картинка: картинка заполняет окно целиком, лишнее по краям срезается
     * (без искажения). Матрица (по столбцам) — для координат окна (u, v), до поворота; ok — ширина/высота окна.
     */
    fun obrezka(ok: Float, ar: Float): FloatArray {
        var sx = 1f; var sy = 1f
        if (ok > ar) sy = ar / ok else if (ok < ar) sx = ok / ar
        return floatArrayOf(sx, 0f, 0f, 0f, 0f, sy, 0f, 0f, 0f, 0f, 1f, 0f, (1f - sx) / 2, (1f - sy) / 2, 0f, 1f)
    }
}
