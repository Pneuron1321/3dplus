package com.questclaude.most3

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// Синтетические сцены для тестов и примеров: rgb w*h*3, 0..1 (уже гамма-кодированные, как с карты).
// Координаты u (0..1 слева направо), v (0..1 сверху вниз).
object Sceny {
    // Спрятанная фигура (тело человека, яркость 0.06) и фон рядом с ней (стена переулка, 0.03).
    val FIGURA = floatArrayOf(0.435f, 0.56f, 0.465f, 0.69f)   // u0 v0 u1 v1
    val FON = floatArrayOf(0.38f, 0.45f, 0.41f, 0.62f)

    private fun tsvet(l: Float, tr: Float, tg: Float, tb: Float): FloatArray {
        val k = l / KartinkaCpu.yarkost(tr, tg, tb)
        return floatArrayOf(min(1f, tr * k), min(1f, tg * k), min(1f, tb * k))
    }

    private fun shum(x: Int, y: Int, sid: Int): Float {
        var h = x * 374761393 + y * 668265263 + sid * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun v(u0: Float, v0: Float, u1: Float, v1: Float, u: Float, vv: Float) = u in u0..u1 && vv in v0..v1

    fun noch(w: Int, h: Int): FloatArray = risovat(w, h) { x, y, u, vv ->
        val asp = w.toFloat() / h
        var c: FloatArray
        if (vv < 0.45f) {
            c = tsvet(0.02f + 0.015f * vv / 0.45f, 0.6f, 0.8f, 1.4f)
            if (shum(x, y, 7) > 0.9985f) c = tsvet(0.35f, 1f, 1f, 1f)              // звёзды
        } else c = tsvet(0.03f, 0.9f, 0.95f, 1.1f)
        // дома
        val doma = listOf(floatArrayOf(0f, 0.18f, 0.2f), floatArrayOf(0.2f, 0.25f, 0.36f), floatArrayOf(0.54f, 0.12f, 0.8f), floatArrayOf(0.8f, 0.3f, 1f))
        for (d in doma) if (u in d[0]..d[2] && vv in d[1]..0.74f) {
            c = tsvet(0.035f, 1f, 0.95f, 0.9f)
            val ou = ((u - d[0]) * 40f) % 1f; val ov = ((vv - d[1]) * 25f) % 1f
            if (ou in 0.3f..0.7f && ov in 0.3f..0.7f) {
                val r = shum(((u - d[0]) * 40f).toInt(), ((vv - d[1]) * 25f).toInt(), (d[0] * 100).toInt())
                c = when {
                    r > 0.85f -> tsvet(0.55f, 1f, 0.8f, 0.45f)      // горящие окна
                    r > 0.6f -> tsvet(0.07f, 0.9f, 0.9f, 1.1f)
                    else -> c
                }
            }
        }
        if (u in 0.36f..0.54f && vv in 0.3f..0.74f) c = tsvet(0.03f, 0.95f, 0.95f, 1f)   // тёмный переулок
        // земля: тротуар и дорога с разметкой
        if (vv > 0.74f) {
            c = if (vv < 0.8f) tsvet(0.04f, 1f, 1f, 1f) else tsvet(0.03f, 1f, 1f, 1.05f)
            if (vv in 0.88f..0.9f && (u * 10f) % 1f < 0.5f) c = tsvet(0.09f, 1f, 1f, 0.9f)
        }
        // человек в тени: голова + тело
        val dx = (u - 0.45f) * asp; val dy = vv - 0.535f
        if (sqrt(dx * dx + dy * dy) < 0.025f || v(FIGURA[0], FIGURA[1], FIGURA[2], FIGURA[3], u, vv)) c = tsvet(0.06f, 1f, 0.95f, 0.9f)
        // машина: кузов, стёкла, колёса, задние огни
        if (u in 0.58f..0.74f && vv in 0.66f..0.79f) c = tsvet(0.055f, 0.8f, 0.9f, 1.2f)
        if (u in 0.61f..0.71f && vv in 0.61f..0.66f) c = tsvet(0.035f, 0.8f, 0.9f, 1.2f)
        if (u in 0.58f..0.595f && vv in 0.68f..0.71f) c = tsvet(0.25f, 1f, 0.12f, 0.08f)
        for (ku in floatArrayOf(0.61f, 0.71f)) { val a = (u - ku) * asp; val b = vv - 0.79f; if (a * a + b * b < 0.0006f) c = tsvet(0.012f, 1f, 1f, 1f) }
        // фонари: столбы, лампы, ореол, пятно света на земле
        val out = c.copyOf()
        for (fu in floatArrayOf(0.15f, 0.88f)) {
            if (u in fu - 0.004f..fu + 0.004f && vv in 0.27f..0.8f) { out[0] = 0.05f; out[1] = 0.05f; out[2] = 0.05f }
            val a = (u - fu) * asp; val b = vv - 0.26f
            val d2 = a * a + b * b
            val sv = if (d2 < 0.00025f) 1f else 0.45f * exp(-d2 / 0.004f)
            val b2 = vv - 0.8f
            val pyatno = if (vv > 0.74f) 0.22f * exp(-(a * a / 0.01f + b2 * b2 / 0.002f)) else 0f
            out[0] += sv + pyatno; out[1] += (sv + pyatno) * 0.85f; out[2] += (sv + pyatno) * 0.55f
        }
        val n = (shum(x, y, 1) - 0.5f) * 0.008f
        floatArrayOf(out[0] + n, out[1] + n, out[2] + n)
    }

    fun den(w: Int, h: Int): FloatArray = risovat(w, h) { x, y, u, vv ->
        val asp = w.toFloat() / h
        var c = tsvet(0.72f - 0.2f * vv / 0.4f, 0.55f, 0.75f, 1.0f)                 // небо
        val o1 = (u - 0.3f) * asp; val o2 = (vv - 0.12f) * 2.5f
        if (o1 * o1 + o2 * o2 < 0.02f) c = tsvet(0.92f, 1f, 1f, 1f)                   // облако
        if (vv > 0.38f) c = tsvet(0.42f, 0.55f, 0.9f, 0.35f)                          // трава
        if (u in 0.05f..0.3f && vv in 0.18f..0.66f) c = tsvet(if (u < 0.22f) 0.48f else 0.26f, 0.95f, 0.6f, 0.45f)
        if (u in 0.55f..0.85f && vv in 0.1f..0.66f) c = tsvet(if (u < 0.78f) 0.82f else 0.42f, 1f, 0.97f, 0.9f)
        if ((u in 0.05f..0.3f || u in 0.55f..0.85f) && vv in 0.18f..0.62f && (u * 30f) % 1f in 0.3f..0.7f && (vv * 18f) % 1f in 0.3f..0.7f)
            c = tsvet(0.22f, 0.6f, 0.75f, 1f)                                         // окна
        val t1 = (u - 0.43f) * asp; val t2 = vv - 0.45f
        if (t1 * t1 + t2 * t2 < 0.012f) c = tsvet(0.22f, 0.4f, 0.8f, 0.3f)            // дерево
        if (vv > 0.66f) c = tsvet(0.36f, 1f, 1f, 1.02f)                                // дорога
        if (vv in 0.8f..0.82f && (u * 8f) % 1f < 0.5f) c = tsvet(0.85f, 1f, 1f, 1f)
        if (u in 0.6f..0.78f && vv in 0.7f..0.8f) c = tsvet(0.24f, 1f, 0.15f, 0.12f)   // красная машина
        val n = (shum(x, y, 2) - 0.5f) * 0.01f
        floatArrayOf(c[0] + n, c[1] + n, c[2] + n)
    }

    private fun risovat(w: Int, h: Int, f: (Int, Int, Float, Float) -> FloatArray): FloatArray {
        val a = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = f(x, y, (x + 0.5f) / w, (y + 0.5f) / h)
            for (i in 0..2) a[(y * w + x) * 3 + i] = min(1f, maxOf(0f, c[i]))
        }
        return a
    }

    // Средняя яркость Rec.709 по области (нормированные координаты).
    fun srednyaya(rgb: FloatArray, w: Int, h: Int, obl: FloatArray? = null): Float {
        var s = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w; val vv = (y + 0.5f) / h
            if (obl != null && !v(obl[0], obl[1], obl[2], obl[3], u, vv)) continue
            val o = (y * w + x) * 3
            s += KartinkaCpu.yarkost(rgb[o], rgb[o + 1], rgb[o + 2]); n++
        }
        return (s / n).toFloat()
    }

    // Как замер у ведущего: кадр -> маленький RGBA (усреднение блоками) -> байты.
    fun snimok(rgb: FloatArray, w: Int, h: Int, mw: Int = 32, mh: Int = 18): ByteArray {
        val b = ByteArray(mw * mh * 4)
        for (my in 0 until mh) for (mx in 0 until mw) {
            val x0 = mx * w / mw; val x1 = (mx + 1) * w / mw; val y0 = my * h / mh; val y1 = (my + 1) * h / mh
            for (i in 0..2) {
                var s = 0f
                for (y in y0 until y1) for (x in x0 until x1) s += rgb[(y * w + x) * 3 + i]
                b[(my * mw + mx) * 4 + i] = Math.round(s / ((x1 - x0) * (y1 - y0)) * 255f).toByte()
            }
            b[(my * mw + mx) * 4 + 3] = -1
        }
        return b
    }

    // Авто-ночь целиком: замер сырого кадра -> avtoTeni -> обработка.
    fun sAvtoNochyu(rgb: FloatArray, w: Int, h: Int, n: Nastrojki): FloatArray =
        KartinkaCpu.obrabotatKadr(rgb, w, h, n, avtoTeni(srednyayaYarkost(snimok(rgb, w, h), 32, 18), n.avtoNoch))

    // --- HDR (PS5) ---
    // Сцена в нитах, линейный BT.709 (w*h*3): сверху полоса — серый PQ-градиент (код 0..1 слева направо),
    // небо 400→150 нит, солнце 4000 нит с ореолом, дома 20-60 нит с окнами 250 нит, тёмный переулок 0.3-2 нит
    // (фигура 1.5 нит на фоне 0.6), красная неоновая вывеска (чистый красный BT.709, R = 800 нит), земля 30 нит.
    val HDR_PEREULOK = floatArrayOf(0.38f, 0.5f, 0.52f, 0.8f)
    val HDR_FIGURA = floatArrayOf(0.44f, 0.6f, 0.47f, 0.78f)

    fun hdrNity(w: Int, h: Int): FloatArray {
        val a = FloatArray(w * h * 3)
        val asp = w.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w; val vv = (y + 0.5f) / h
            var c: FloatArray
            if (vv < 0.08f) { val l = KartinkaCpu.pqVNity(u); c = floatArrayOf(l, l, l) }
            else {
                val l = 400f - 250f * min(1f, (vv - 0.08f) / 0.42f)
                c = floatArrayOf(l * 0.75f, l * 0.92f, l * 1.25f)
                val dx = (u - 0.75f) * asp; val dy = vv - 0.22f
                val d2 = dx * dx + dy * dy
                val sv = if (d2 < 0.0016f) 4000f else 1500f * exp(-d2 / 0.004f)
                c[0] += sv; c[1] += sv * 0.95f; c[2] += sv * 0.8f
                if (vv > 0.3f && (u < 0.33f || u > 0.57f)) {
                    c = floatArrayOf(40f, 36f, 30f)
                    val ou = (u * 30f) % 1f; val ov = (vv * 14f) % 1f
                    if (ou in 0.3f..0.7f && ov in 0.3f..0.7f) {
                        val r = shum((u * 30f).toInt(), (vv * 14f).toInt(), 11)
                        c = if (r > 0.6f) floatArrayOf(300f, 240f, 160f) else floatArrayOf(18f, 18f, 22f)
                    }
                }
                if (vv > 0.3f && u in 0.33f..0.57f) c = floatArrayOf(2f, 2f, 2.3f)                 // стены переулка
                if (v(HDR_PEREULOK[0], HDR_PEREULOK[1], HDR_PEREULOK[2], HDR_PEREULOK[3], u, vv)) c = floatArrayOf(0.6f, 0.6f, 0.7f)
                if (v(HDR_FIGURA[0], HDR_FIGURA[1], HDR_FIGURA[2], HDR_FIGURA[3], u, vv)) c = floatArrayOf(1.5f, 1.4f, 1.3f)
                if (u in 0.39f..0.51f && vv in 0.4f..0.44f) c = floatArrayOf(800f, 0f, 0f)   // неон: R = 800 нит
                if (vv > 0.85f) c = floatArrayOf(30f, 29f, 27f)
            }
            for (i in 0..2) a[(y * w + x) * 3 + i] = max(0f, c[i])
        }
        return a
    }

    // Нит BT.709 -> P010 (BT.2020, PQ, ограниченный 10-битный, цвет 4:2:0 средним 2×2) — как отдаёт PS5.
    fun vP010(nit: FloatArray, w: Int, h: Int): P010Kadr {
        val yy = IntArray(w * h); val cb = FloatArray(w * h); val cr = FloatArray(w * h)
        val m = KartinkaCpu.M709_2020
        for (i in 0 until w * h) {
            val r0 = nit[i * 3]; val g0 = nit[i * 3 + 1]; val b0 = nit[i * 3 + 2]
            val r = KartinkaCpu.nityVPq(m[0] * r0 + m[1] * g0 + m[2] * b0)
            val g = KartinkaCpu.nityVPq(m[3] * r0 + m[4] * g0 + m[5] * b0)
            val b = KartinkaCpu.nityVPq(m[6] * r0 + m[7] * g0 + m[8] * b0)
            val y = 0.2627f * r + 0.678f * g + 0.0593f * b
            yy[i] = Math.round(64f + 876f * y).coerceIn(0, 1023)
            cb[i] = (b - y) / 1.8814f; cr[i] = (r - y) / 1.4746f
        }
        val cw = w / 2; val ch = h / 2
        val u = IntArray(cw * ch); val vv = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var su = 0f; var sv = 0f
            for (dy in 0..1) for (dx in 0..1) { val i = (2 * y + dy) * w + 2 * x + dx; su += cb[i]; sv += cr[i] }
            u[y * cw + x] = Math.round(512f + 896f * su / 4f).coerceIn(0, 1023)
            vv[y * cw + x] = Math.round(512f + 896f * sv / 4f).coerceIn(0, 1023)
        }
        return P010Kadr(w, h, yy, u, vv)
    }

    // «В лоб»: нит/белый, обрезка на 1, гамма 2.2 — для сравнения с тон-маппингом.
    fun hdrObrezka(nit: FloatArray, belyj: Float = KartinkaCpu.BELYJ_NIT): FloatArray =
        FloatArray(nit.size) { (nit[it] / belyj).coerceIn(0f, 1f).pow(1f / 2.2f) }
}
