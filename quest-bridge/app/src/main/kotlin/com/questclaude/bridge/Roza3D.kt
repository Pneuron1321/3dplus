package com.questclaude.bridge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Роза ветров в 3D (2.3.5, Александр 11.10): из прежней — 4 длинных луча и 4 коротких, каждый со
 * светлой и тёмной половиной, — объёмная звезда: лучи — пирамиды с ребром сверху и снизу (видно
 * со всех сторон), вокруг — красный обод с делениями, в центре — фиолетовая ступица. Цвета Плюса:
 * главные лучи жёлтые, промежуточные розовые с фиолетовым, обод красный, контуры тонкие чёрные.
 *
 * Движение (2.3.6, Александр 11.10: «чтобы крутилась рандомом, просто анимацией, не по северу»):
 * компас на розу больше не влияет — она крутится сама, как попало: то в одну сторону, то в другую,
 * то быстрее, то медленнее; заодно переворачивается — то лицом, то ребром, то спиной — вокруг
 * оси, которая тоже гуляет, и вокруг смещённой точки («центр тяжести» не на месте). Случай
 * выбирается на каждый эфир свой (Dvizhenie).
 *
 * Здесь — геометрия, поворот, проекция и порядок граней (дальние первыми, обратные не рисуются);
 * рисует Nalozhenie. Обычная JVM — Roza3DTest.
 */
object Roza3D {
    /** Грань на экране: многоугольник, цвет (ARGB), какие рёбра обводить (ребро i — от точки i к i+1),
     *  черты поверх (деления обода: x1, y1, x2, y2…), глубина центра (больше — ближе). */
    class Gran(val x: FloatArray, val y: FloatArray, val cvet: Int, val kontur: BooleanArray,
               val cherty: FloatArray?, val z: Float)

    /** Кадр: грани в порядке рисования; где подписать N — x, y (середина буквы) и кегль. */
    class Kadr(val grani: List<Gran>, val sever: FloatArray)

    /** Грань сетки: точки, цвет при полном свете, обводка рёбер, деления; chast — луч, обод или ступица. */
    private class Tri(val v: Array<DoubleArray>, val svetlo: Int, val kontur: BooleanArray,
                      val cherty: Array<DoubleArray>? = null, val chast: Int = LUCH)

    private const val LUCH = 0
    private const val OBOD = 1
    private const val STUPICA = 2

    // цвета Плюса (sRGB)
    const val ZHELT = 0xFFF8CF1E.toInt()
    const val ZHELT_TEN = 0xFFD99A0A.toInt()
    const val ROZ = 0xFFF07ADF.toInt()
    const val ROZ_TEN = 0xFFA94CCB.toInt()
    const val KRASN = 0xFFE3322A.toInt()
    const val FIOL = 0xFF8656EC.toInt()
    const val CHERN = 0xFF0C0C10.toInt()

    /** Длинный луч — 1; обод — между OBOD_VN и OBOD_NAR, толщина ±OBOD_T. */
    const val OBOD_VN = 0.68
    const val OBOD_NAR = 0.775
    const val OBOD_T = 0.035
    private const val SEGM = 48

    private val setka: List<Tri> = postroit()

    private fun postroit(): List<Tri> {
        val g = mutableListOf<Tri>()
        // лучи: главные (N, E, S, W) — жёлтые, промежуточные — розовые; левая половина светлее
        for (glavn in listOf(false, true)) for (i in 0 until 4) {
            val a = (i * 90.0 + if (glavn) 0.0 else 45.0) * PI / 180
            val dl = if (glavn) 1.0 else 0.62
            val sh = if (glavn) 0.155 else 0.1
            val h = if (glavn) 0.13 else 0.09
            val d = doubleArrayOf(sin(a), cos(a), 0.0)
            val bok = doubleArrayOf(cos(a), -sin(a), 0.0)            // вправо от луча
            val tip = doubleArrayOf(d[0] * dl, d[1] * dl, 0.0)
            val lev = doubleArrayOf(-bok[0] * sh, -bok[1] * sh, 0.0)
            val prav = doubleArrayOf(bok[0] * sh, bok[1] * sh, 0.0)
            val verh = doubleArrayOf(0.0, 0.0, h)
            val niz = doubleArrayOf(0.0, 0.0, -h)
            val sv = if (glavn) ZHELT else ROZ
            val tm = if (glavn) ZHELT_TEN else ROZ_TEN
            // обвод: ребро от вершины к острию и боковое — да, внутреннее у ступицы — нет
            val k = booleanArrayOf(true, true, false)
            g += Tri(arrayOf(verh, tip, lev), sv, booleanArrayOf(true, true, false))
            g += Tri(arrayOf(verh, prav, tip), tm, booleanArrayOf(false, true, true))
            g += Tri(arrayOf(niz, lev, tip), sv, booleanArrayOf(false, true, true))
            g += Tri(arrayOf(niz, tip, prav), tm, k)
        }
        // обод: кольцо из сегментов — верх, низ, наружная и внутренняя стенки; деления через 30°
        for (s in 0 until SEGM) {
            val a0 = 2 * PI * s / SEGM
            val a1 = 2 * PI * (s + 1) / SEGM
            fun p(r: Double, a: Double, z: Double) = doubleArrayOf(sin(a) * r, cos(a) * r, z)
            val r0 = OBOD_VN; val r1 = OBOD_NAR; val t = OBOD_T
            val delenie = (s * 360 / SEGM) % 30 == 0
            fun cherta(z: Double): Array<DoubleArray>? {
                if (!delenie) return null
                val dl = if ((s * 360 / SEGM) % 90 == 0) 1.0 else 0.6
                val rm = (r0 + r1) / 2; val pol = (r1 - r0) / 2 * dl
                return arrayOf(p(rm - pol, a0, z), p(rm + pol, a0, z))
            }
            val kr = KRASN
            val krT = ten(KRASN, 0.72)
            val dugi = booleanArrayOf(false, true, false, true)          // обводка — по дугам, не поперёк
            g += Tri(arrayOf(p(r0, a0, t), p(r1, a0, t), p(r1, a1, t), p(r0, a1, t)), kr, dugi, cherta(t + 0.001), OBOD)
            g += Tri(arrayOf(p(r0, a1, -t), p(r1, a1, -t), p(r1, a0, -t), p(r0, a0, -t)), kr, dugi, cherta(-t - 0.001), OBOD)
            g += Tri(arrayOf(p(r1, a0, t), p(r1, a0, -t), p(r1, a1, -t), p(r1, a1, t)), krT, dugi, null, OBOD)
            g += Tri(arrayOf(p(r0, a1, t), p(r0, a1, -t), p(r0, a0, -t), p(r0, a0, t)), krT, dugi, null, OBOD)
        }
        // ступица: восьмигранник
        val r = 0.11; val hz = 0.16
        val krug = (0 until 4).map { val a = (45 + it * 90) * PI / 180; doubleArrayOf(sin(a) * r, cos(a) * r, 0.0) }
        for (i in 0 until 4) {
            val a = krug[i]; val b = krug[(i + 1) % 4]
            g += Tri(arrayOf(doubleArrayOf(0.0, 0.0, hz), a, b), FIOL, booleanArrayOf(true, true, true), null, STUPICA)
            g += Tri(arrayOf(doubleArrayOf(0.0, 0.0, -hz), b, a), FIOL, booleanArrayOf(true, true, true), null, STUPICA)
        }
        // нормали — наружу (от оси луча, от средней линии обода, от центра ступицы)
        return g.map { naruzhu(it) }
    }

    /** Порядок точек — чтобы нормаль смотрела наружу; иначе развернуть (и флажки рёбер с ним). */
    private fun naruzhu(t: Tri): Tri {
        val n = normal(t.v)
        val c = centr(t.v)
        val rr = sqrt(c[0] * c[0] + c[1] * c[1])
        val os = when (t.chast) {
            STUPICA -> doubleArrayOf(0.0, 0.0, 0.0)
            OBOD -> {
                val m = (OBOD_VN + OBOD_NAR) / 2
                if (rr > 1e-9) doubleArrayOf(c[0] / rr * m, c[1] / rr * m, 0.0) else doubleArrayOf(0.0, 0.0, 0.0)
            }
            else -> {                                              // луч: ближайшая точка оси луча
                val tip = t.v.maxByOrNull { it[0] * it[0] + it[1] * it[1] }!!
                val dl = sqrt(tip[0] * tip[0] + tip[1] * tip[1])
                val d = doubleArrayOf(tip[0] / dl, tip[1] / dl, 0.0)
                val pr = c[0] * d[0] + c[1] * d[1]
                doubleArrayOf(d[0] * pr, d[1] * pr, 0.0)
            }
        }
        val k = (0..2).sumOf { n[it] * (c[it] - os[it]) }
        if (k >= 0) return t
        val m = t.v.size
        val v = Array(m) { t.v[(m - it) % m] }                     // 0, m−1, m−2…
        // ребро i было (i, i+1); после разворота точка j = старая (m − j) % m, ребро j → старое ребро (m − j − 1)
        val kontur = BooleanArray(m) { j -> t.kontur[(m - j - 1 + m) % m] }
        return Tri(v, t.svetlo, kontur, t.cherty, t.chast)
    }

    private fun normal(v: Array<DoubleArray>): DoubleArray {
        val a = v[0]; val b = v[1]; val c = v[2]
        val u = doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val w = doubleArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        val n = doubleArrayOf(u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 1e-12 } ?: 1.0
        return doubleArrayOf(n[0] / l, n[1] / l, n[2] / l)
    }

    private fun centr(v: Array<DoubleArray>) = DoubleArray(3) { i -> v.sumOf { it[i] } / v.size }

    /** Цвет темнее/светлее: k < 1 — темнее. */
    fun ten(c: Int, k: Double): Int {
        fun ch(sh: Int) = ((c shr sh and 255) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Случайное движение розы: фазы, скорости (±20 %) и сторона кручения выбираются один раз —
     * у каждого эфира своё. Всё плавно: суммы синусов с несоизмеримыми частотами, без рывков.
     */
    class Dvizhenie(sluchay: java.util.Random = java.util.Random()) {
        private val f = DoubleArray(6) { sluchay.nextDouble() * 2 * PI }
        private val k = DoubleArray(7) { 0.8 + 0.4 * sluchay.nextDouble() }
        private val zn = if (sluchay.nextBoolean()) 1.0 else -1.0

        /** Кручение в своей плоскости: то туда, то обратно, то быстрее, то медленнее. */
        fun vrashchenie(t: Double) = zn * (1.8 * PI * sin(0.21 * k[0] * t + f[0]) +
            1.1 * PI * sin(0.47 * k[1] * t + f[1]) + 0.5 * PI * sin(0.93 * k[2] * t + f[2]))

        /**
         * Переворот: роза подолгу показывает лицо или спину и время от времени переворачивается —
         * когда как (то вперёд, то назад: «счётчик» переворотов гуляет случайно); переворот плавный,
         * занимает долю [PEREVOROT] своего отрезка, ребром долго не стоит.
         */
        fun perevorot(t: Double): Double {
            val u = 0.085 * k[3] * t + 0.7 * sin(0.21 * k[4] * t + f[3]) + 0.25 * sin(0.53 * k[4] * t + f[4])
            val n = floor(u)
            val h = ((u - n - (1 - PEREVOROT)) / PEREVOROT).coerceIn(0.0, 1.0)
            return PI * (n + h * h * (3 - 2 * h))
        }

        /** Куда в плоскости кадра смотрит ось переворота — тоже гуляет. */
        fun os(t: Double) = 0.37 * k[5] * t + 1.5 * sin(0.17 * k[6] * t + f[5])
    }

    /**
     * Поворот розы во времени [t] (с) — матрица 3×3 по строкам: экран x вправо, y вверх, z к зрителю.
     * Сначала кручение в своей плоскости, потом лёгкий наклон (объём и «лицом»), потом переворот
     * вокруг гуляющей оси в плоскости кадра.
     */
    fun povorot(t: Double, d: Dvizhenie): DoubleArray {
        val p = d.os(t)
        return mul(mul(rz(p), mul(rx(d.perevorot(t)), rz(-p))), mul(rx(NAKLON), rz(d.vrashchenie(t))))
    }

    /** Смещённый центр вращения (в длинах луча): гуляет медленно. */
    fun centrTyazhesti(t: Double) = doubleArrayOf(0.07 * sin(0.53 * t), 0.06 * cos(0.41 * t + 1.0), 0.0)

    private fun rx(a: Double) = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    private fun rz(a: Double) = doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)
    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Свет — сверху слева, чуть к зрителю. */
    private val SVET = doubleArrayOf(-0.45, 0.62, 0.64).let { val l = sqrt(it.sumOf { x -> x * x }); DoubleArray(3) { i -> it[i] / l } }

    /**
     * Кадр розы: центр ([cx], [cy]) в точках картинки (y вниз), [masshtab] — точек на длину луча.
     * Перспектива мягкая (камера в [KAMERA] длинах луча).
     */
    fun kadr(t: Double, d: Dvizhenie, cx: Float, cy: Float, masshtab: Float): Kadr {
        val m = povorot(t, d)
        val o = centrTyazhesti(t)
        fun vid(p: DoubleArray): DoubleArray {
            val q = doubleArrayOf(p[0] - o[0], p[1] - o[1], p[2] - o[2])
            val x = m[0] * q[0] + m[1] * q[1] + m[2] * q[2] + o[0]
            val y = m[3] * q[0] + m[4] * q[1] + m[5] * q[2] + o[1]
            val z = m[6] * q[0] + m[7] * q[1] + m[8] * q[2]
            return doubleArrayOf(x, y, z)
        }
        fun ekran(p: DoubleArray): FloatArray {
            val s = KAMERA / (KAMERA - p[2])
            return floatArrayOf((cx + p[0] * s * masshtab).toFloat(), (cy - p[1] * s * masshtab).toFloat())
        }
        val out = ArrayList<Gran>(setka.size)
        for (tr in setka) {
            val v = Array(tr.v.size) { vid(tr.v[it]) }
            val n = normal(v)
            val c = centr(v)
            // к зрителю: от точки грани на камеру (перспектива)
            val kKam = doubleArrayOf(-c[0], -c[1], KAMERA - c[2])
            if (n[0] * kKam[0] + n[1] * kKam[1] + n[2] * kKam[2] <= 0) continue
            val sv = n[0] * SVET[0] + n[1] * SVET[1] + n[2] * SVET[2]
            val cvet = ten(tr.svetlo, 0.58 + 0.5 * max(0.0, sv) + 0.12 * abs(n[2]))
            val xs = FloatArray(v.size); val ys = FloatArray(v.size)
            for (i in v.indices) { val e = ekran(v[i]); xs[i] = e[0]; ys[i] = e[1] }
            val ch = tr.cherty?.let { pts ->
                val a = ekran(vid(pts[0])); val b = ekran(vid(pts[1]))
                floatArrayOf(a[0], a[1], b[0], b[1])
            }
            out += Gran(xs, ys, cvet, tr.kontur, ch, c[2].toFloat())
        }
        out.sortBy { it.z }
        // N — за острием северного луча; кегль — с перспективой; у края картинки — не вылезает
        val tipN = vid(doubleArrayOf(0.0, 1.14, 0.0))
        val e = ekran(tipN)
        val kegl = (SEVER_KEGL * KAMERA / (KAMERA - tipN[2])).toFloat()
        val pol = kegl * 0.6f
        val sever = floatArrayOf(e[0].coerceIn(cx - (cx - pol), cx + (cx - pol)),
            e[1].coerceIn(cy - (cy - pol), cy + (cy - pol)), kegl)
        return Kadr(out, sever)
    }

    /** Какую долю времени между переворотами занимает сам переворот (остальное — лицом или спиной). */
    const val PEREVOROT = 0.4
    /** Постоянный наклон к зрителю — чтобы и «лицом» роза была объёмной. */
    const val NAKLON = -0.42
    const val KAMERA = 6.5
    /** Картинка розы в кадре 1080p (квадрат) и точек на длинный луч — переваливаясь, влезает вместе с N. */
    const val KARTINKA = 150
    /** Кегль буквы N, когда острие в плоскости кадра. */
    const val SEVER_KEGL = 15f
    const val MASSHTAB = 54f
}
