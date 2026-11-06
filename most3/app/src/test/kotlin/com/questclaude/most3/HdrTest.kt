package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

// HDR (P010/PQ), местная ночь, уменьшение с 4 выборками, константы шейдера.
class HdrTest {
    private val ID = Nastrojki()
    private val HDR = Nastrojki()   // hdrPik = 1000

    private fun yKod(e: Float) = 64f + 876f * e   // PQ-код (0..1) -> 10-битный Y, ограниченный диапазон

    // --- PQ ---
    @Test fun pqIzvestnyeTochki() {
        assertEquals(0f, KartinkaCpu.pqVNity(0f), 1e-6f)
        assertEquals(100f, KartinkaCpu.pqVNity(0.50808f), 0.5f)        // ±0.5%
        assertEquals(1000f, KartinkaCpu.pqVNity(0.75183f), 5f)         // ±0.5%
        assertEquals(10000f, KartinkaCpu.pqVNity(1f), 0.5f)
        assertEquals(203f, KartinkaCpu.pqVNity(0.58069f), 1f)          // белый BT.2408
        for (nit in floatArrayOf(0.01f, 1f, 50f, 203f, 1000f, 4000f)) // обратная сходится
            assertEquals(nit, KartinkaCpu.pqVNity(KartinkaCpu.nityVPq(nit)), nit * 1e-3f + 1e-4f)
    }

    // --- P010 SDR ---
    @Test fun p010OgranichennyjSdr() {
        KartinkaCpu.p010VRgb(64f, 512f, 512f, ID, hdr = false).forEach { assertEquals(0f, it, 1e-6f) }
        KartinkaCpu.p010VRgb(940f, 512f, 512f, ID, hdr = false).forEach { assertEquals(1f, it, 1e-6f) }
        val s = KartinkaCpu.p010VRgb(502f, 512f, 512f, ID, hdr = false)       // (502−64)/876 = 0.5, серый
        s.forEach { assertEquals(0.5f, it, 1e-6f) }
        KartinkaCpu.p010VRgb(10f, 512f, 512f, ID, hdr = false).forEach { assertEquals(0f, it, 0f) }   // ниже 64 — зажим
        val p = Nastrojki(polnyjDiapazon = true)
        KartinkaCpu.p010VRgb(1023f, 512f, 512f, p, hdr = false).forEach { assertEquals(1f, it, 1e-6f) }
        KartinkaCpu.p010VRgb(0f, 512f, 512f, p, hdr = false).forEach { assertEquals(0f, it, 1e-6f) }
        // 100% красный BT.709 в 10 битах (Y=250 Cb=409 Cr=960) — тот же, что 8-битный ×4
        val k = KartinkaCpu.p010VRgb(250f, 409f, 960f, ID, hdr = false)
        assertEquals(1f, k[0], 0.01f); assertEquals(0f, k[1], 0.01f); assertEquals(0f, k[2], 0.01f)
    }

    // --- HDR ---
    @Test fun hdrBelyjBumagi() {
        val w = KartinkaCpu.p010VRgb(yKod(KartinkaCpu.nityVPq(203f)), 512f, 512f, HDR, hdr = true)
        println("203 нит -> ${w.toList()}")
        w.forEach { assertTrue("белый $it", it in 0.95f..1f) }
        assertEquals(w[0], w[1], 1e-3f); assertEquals(w[1], w[2], 1e-3f)      // серый остаётся серым (BT.2020 -> 709)
        // ниже колена — 1:1 относительно белого: 20 нит -> (20/203)^(1/2.2)
        val t = KartinkaCpu.hdrVSdr(KartinkaCpu.nityVPq(20f), KartinkaCpu.nityVPq(20f), KartinkaCpu.nityVPq(20f), 1000f)
        assertEquals((20f / 203f).pow(1f / 2.2f), t[1], 2e-3f)
        KartinkaCpu.p010VRgb(64f, 512f, 512f, HDR, hdr = true).forEach { assertEquals(0f, it, 1e-6f) }   // чёрный
    }

    @Test fun hdrKrivayaMonotonnaISvetaVPredele() {
        var pred = -1f
        var nit = 0.001f
        while (nit <= 10000f) {
            val e = KartinkaCpu.nityVPq(nit)
            val c = KartinkaCpu.hdrVSdr(e, e, e, 1000f)[1]
            assertTrue("$nit нит: $c < $pred", c >= pred - 1e-6f)
            if (nit < 950f && nit > 0.01f) assertTrue("строго растёт до пика: $nit", c > pred)
            assertTrue(c <= 1f)
            pred = c; nit *= 1.05f
        }
        val e1000 = KartinkaCpu.nityVPq(1000f)
        val p = KartinkaCpu.hdrVSdr(e1000, e1000, e1000, 1000f)[1]
        assertTrue("1000 нит при пике 1000: $p", p <= 1f && p > 0.995f)
        val e500 = KartinkaCpu.nityVPq(500f)
        assertTrue(KartinkaCpu.hdrVSdr(e500, e500, e500, 1000f)[1] < p)                         // светлое различимо
        val e4000 = KartinkaCpu.nityVPq(4000f)                                                    // пик 4000: 1000 ещё не потолок
        assertTrue(KartinkaCpu.hdrVSdr(e1000, e1000, e1000, 4000f)[1] < KartinkaCpu.hdrVSdr(e4000, e4000, e4000, 4000f)[1])
        // сама кривая: C1 в колене, пик -> 1
        val k = KartinkaCpu.TON_KOLENO; val xm = 1000f / 203f
        assertEquals(1f, (KartinkaCpu.tonKrivaya(k + 1e-3f, xm) - KartinkaCpu.tonKrivaya(k, xm)) / 1e-3f, 0.01f)
        assertEquals(1f, KartinkaCpu.tonKrivaya(xm, xm), 1e-5f)
        assertEquals(0.5f, KartinkaCpu.tonKrivaya(0.5f, xm), 0f)
        // пик ниже белого: без сжатия до 1, дальше потолок
        assertEquals(0.9f, KartinkaCpu.tonKrivaya(0.9f, 150f / 203f), 1e-6f)
    }

    @Test fun hdrNasyshchennyjKrasnyjDerzhitOttenok() {
        // чистый красный BT.709, 1000 нит в канале R, — через P010 (с квантованием и 4:2:0) и напрямую
        val w = 4; val h = 4
        val nit = FloatArray(w * h * 3) { if (it % 3 == 0) 1000f else 0f }
        val k = Sceny.vP010(nit, w, h)
        val c = KartinkaCpu.p010VKadr(k, HDR, hdr = true).copyOfRange(15, 18)
        println("красный 1000 нит -> ${c.toList()}")
        assertTrue("R ${c[0]}", c[0] > 0.97f)
        assertTrue("G,B ${c[1]} ${c[2]}", c[1] < 0.25f && c[2] < 0.25f)
        assertEquals(c[1], c[2], 0.03f)                                    // не уходит ни в оранжевый, ни в малиновый
        // оранжевый (R:G = 1:0.4) не желтеет: G/R в линейном свете остаётся около 0.4
        val o = Sceny.vP010(FloatArray(w * h * 3) { floatArrayOf(1000f, 400f, 0f)[it % 3] }, w, h)
        val oc = KartinkaCpu.p010VKadr(o, HDR, hdr = true).copyOfRange(15, 18)
        val gr = oc[1].pow(2.2f) / oc[0].pow(2.2f)
        println("оранжевый -> ${oc.toList()}, G/R лин = $gr")
        assertTrue("G/R $gr", gr in 0.38f..0.5f && oc[2] < 0.35f)
    }

    @Test fun hdrStsenaTonMappingProtivObrezki() {
        val w = 160; val h = 90
        val nit = Sceny.hdrNity(w, h)
        val tm = KartinkaCpu.p010VKadr(Sceny.vP010(nit, w, h), HDR, hdr = true)
        val ob = Sceny.hdrObrezka(nit)
        // небо и солнце: у обрезки всё небо — один белый; у тон-маппинга солнце ярче неба
        fun ya(a: FloatArray, u: Float, v: Float): Float { val o = ((v * h).toInt() * w + (u * w).toInt()) * 3; return KartinkaCpu.yarkost(a[o], a[o + 1], a[o + 2]) }
        assertTrue(ya(ob, 0.2f, 0.12f) > 0.99f && ya(ob, 0.75f, 0.22f) > 0.99f)
        assertTrue("солнце ${ya(tm, 0.75f, 0.22f)} > небо ${ya(tm, 0.2f, 0.12f)}", ya(tm, 0.75f, 0.22f) > ya(tm, 0.2f, 0.12f) + 0.01f)
        // переулок: тени 1:1 — как у обрезки (±0.02), фигура отличима от фона
        val f = Sceny.srednyaya(tm, w, h, Sceny.HDR_FIGURA); val fon = Sceny.srednyaya(tm, w, h, floatArrayOf(0.39f, 0.52f, 0.43f, 0.58f))
        assertEquals(Sceny.srednyaya(ob, w, h, Sceny.HDR_FIGURA), f, 0.02f)
        assertTrue("фигура $f фон $fon", f > fon + 0.02f)
    }

    // --- местная ночь ---
    private fun rovnaya(m: Float) = FloatArray(KartinkaCpu.KARTA_W * KartinkaCpu.KARTA_H) { m }
    private fun lok(l: Float, karta: FloatArray, sila: Float, u: Float = 0.5f, v: Float = 0.5f) =
        KartinkaCpu.lokalno(l, l, l, karta, KartinkaCpu.KARTA_W, KartinkaCpu.KARTA_H, u, v, sila)[1]

    @Test fun lokalnoNolTozhdestvo() {
        for (m in floatArrayOf(0.01f, 0.05f, 0.3f, 0.9f)) for (l in floatArrayOf(0f, 0.02f, 0.3f, 0.77f, 1f))
            assertEquals(l, lok(l, rovnaya(m), 0f), 0f)
        val w = 96; val h = 54
        val noch = Sceny.noch(w, h)
        val a = KartinkaCpu.obrabotatKadr(noch, w, h, Nastrojki(lokalno = 0f), 0f)
        for (i in noch.indices) assertEquals(noch[i], a[i], 1f / 255f)
        // средние тона (m = 0.3..0.5) не трогаются и при силе 1
        assertEquals(0.4f, lok(0.4f, rovnaya(0.4f), 1f), 1e-6f)
    }

    @Test fun lokalnoTyomnoePodnimaetSvetloeNet() {
        val t1 = lok(0.05f, rovnaya(0.05f), 1f); val t06 = lok(0.05f, rovnaya(0.05f), 0.6f)
        println("тёмная область 0.05: сила 1 -> $t1, сила 0.6 -> $t06; светлая 0.6 -> ${lok(0.6f, rovnaya(0.6f), 1f)}")
        assertTrue(t1 > 0.12f && t06 > 0.09f)
        assertEquals(0.6f, lok(0.6f, rovnaya(0.6f), 1f), 0.01f)
        assertTrue(lok(0.8f, rovnaya(0.8f), 1f) < 0.8f)                 // очень светлое слегка сжимается
        for (m in floatArrayOf(0.02f, 0.05f, 0.1f, 0.2f, 0.7f, 0.95f)) {
            assertEquals(1f, lok(1f, rovnaya(m), 1f), 1e-5f)            // белое остаётся белым
            var pred = -1f
            for (i in 0..1000) { val y = lok(i / 1000f, rovnaya(m), 1f); assertTrue("m=$m l=${i / 1000f}", y >= pred - 1e-6f); pred = y }
        }
        // яркие детали в тёмной области (окно 0.55 при m = 0.04) не трогаются
        assertEquals(0.55f, lok(0.55f, rovnaya(0.04f), 1f), 1e-6f)
        // цветность: отношения каналов сохраняются
        val c = KartinkaCpu.lokalno(0.06f, 0.04f, 0.02f, rovnaya(0.05f), 32, 18, 0.5f, 0.5f, 1f)
        assertEquals(0.04f / 0.06f, c[1] / c[0], 1e-5f); assertEquals(0.02f / 0.06f, c[2] / c[0], 1e-5f)
        // насыщенный цвет не вылезает за 1
        KartinkaCpu.lokalno(0.3f, 0.01f, 0.01f, rovnaya(0.03f), 32, 18, 0.5f, 0.5f, 1f).forEach { assertTrue(it <= 1f) }
    }

    // Жёсткий край 0.04 | 0.4 в худшем положении (внутри ячейки карты). Ореол = насколько светлая сторона у края
    // поднята сильнее, чем светлая вдали. Тёмная сторона у края должна подниматься почти как вдали (нет каймы).
    @Test fun lokalnoKrajBezOreola() {
        val w = 320; val h = 180; val yy = h / 2
        var hudshij = 0f; var kajma = 1f
        for (kraj in 160..170) {
            val k = FloatArray(w * h * 3) { if ((it / 3) % w < kraj) 0.04f else 0.4f }
            val o = KartinkaCpu.obrabotatKadr(k, w, h, Nastrojki(lokalno = 1f), 0f)
            fun d(x: Int) = o[(yy * w + x) * 3 + 1] - k[(yy * w + x) * 3 + 1]
            for (x in kraj until w) hudshij = max(hudshij, d(x) - d(w - 5))
            kajma = minOf(kajma, (minOf(d(kraj - 1), d(kraj - 3))) / d(20))
        }
        println("край 0.04|0.4: ореол на светлой стороне $hudshij, подъём у края / вдали (тёмная) $kajma")
        assertTrue("ореол $hudshij", hudshij < 0.03f)
        assertTrue("кайма $kajma", kajma > 0.7f)
    }

    @Test fun lokalnoNochnayaStsena() {
        val w = 320; val h = 180
        val noch = Sceny.noch(w, h)
        // светлая витрина 0.45 шириной в несколько ячеек на тёмной стене — жёсткий край
        val vitrina = floatArrayOf(0.6f, 0.45f, 0.76f, 0.62f)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
            if (u in vitrina[0]..vitrina[2] && v in vitrina[1]..vitrina[3]) for (i in 0..2) noch[(y * w + x) * 3 + i] = 0.45f
        }
        val o = KartinkaCpu.obrabotatKadr(noch, w, h, Nastrojki(lokalno = 1f), 0f)
        val do_ = Sceny.srednyaya(noch, w, h); val posle = Sceny.srednyaya(o, w, h)
        val r0 = Sceny.srednyaya(noch, w, h, Sceny.FIGURA) - Sceny.srednyaya(noch, w, h, Sceny.FON)
        val r1 = Sceny.srednyaya(o, w, h, Sceny.FIGURA) - Sceny.srednyaya(o, w, h, Sceny.FON)
        // край витрины: светлая сторона у края поднята не больше, чем середина витрины (+0.03 допуск);
        // яркое (≥ 0.45: окна, фонари, витрина) почти не меняется: < 0.05 (окно, на которое попал центр ячейки карты,
        // получает до ~+0.04 — внутри себя, без каймы). Тусклый ореол фонарей (0.3) поднимается — это не край.
        fun d(p: Int) = KartinkaCpu.yarkost(o[p * 3], o[p * 3 + 1], o[p * 3 + 2]) - KartinkaCpu.yarkost(noch[p * 3], noch[p * 3 + 1], noch[p * 3 + 2])
        val sered = d((h * 535 / 1000) * w + w * 68 / 100)
        var oreol = 0f; var maxSvet = 0f
        for (p in 0 until w * h) {
            val u = (p % w + 0.5f) / w; val v = (p / w + 0.5f) / h
            if (u in vitrina[0]..vitrina[2] && v in vitrina[1]..vitrina[3]) oreol = max(oreol, d(p) - sered)
            if (KartinkaCpu.yarkost(noch[p * 3], noch[p * 3 + 1], noch[p * 3 + 2]) >= 0.45f) maxSvet = max(maxSvet, abs(d(p)))
        }
        println("ночь, местная 1: средняя $do_ -> $posle; фигура-фон $r0 -> $r1; витрина: середина $sered, ореол у края +$oreol; яркое ±$maxSvet")
        assertTrue(posle > do_ * 1.6f)
        assertTrue(r1 > 2f * r0)
        assertTrue("ореол $oreol", oreol < 0.03f)
        assertTrue("яркое $maxSvet", maxSvet < 0.05f)
    }

    @Test fun kartaKakUVedushchego() {
        // одна билинейная выборка в центре ячейки, а не среднее блока: у шахматки 1 px (центр ячейки — на стыке
        // 4 текселей при чётном шаге) это 0.5; у кадра с одной яркой точкой не в центре ячейки — ноль
        val w = 128; val h = 72                                            // ячейка 4×4 px
        val sh = FloatArray(w * h * 3) { val p = it / 3; if ((p % w + p / w) % 2 == 0) 1f else 0f }
        KartinkaCpu.kartaKadra(sh, w, h).forEach { assertEquals(0.5f, it, 1f / 255f) }
        val tochka = FloatArray(w * h * 3); for (i in 0..2) tochka[(0 * w + 0) * 3 + i] = 1f
        assertEquals(0f, KartinkaCpu.kartaKadra(tochka, w, h)[0], 0f)
    }

    // --- уменьшение ---
    private fun shahmatka(w: Int, h: Int) = FloatArray(w * h * 3) { val p = it / 3; if ((p % w + p / w) % 2 == 0) 1f else 0f }

    @Test fun superVyborkaShahmatki() {
        val w = 64; val h = 64
        val sh = shahmatka(w, h)
        // YUY2 (texelFetch): одна выборка — 0 или 1, четыре — ровно серый
        val odna = KartinkaCpu.umenshit(KartinkaCpu.tochno(sh, w, h), w, h, 32, 32, sup = 1f)
        odna.forEach { assertTrue(it == 0f || it == 1f) }
        val chetyre = KartinkaCpu.umenshit(KartinkaCpu.tochno(sh, w, h), w, h, 32, 32)
        chetyre.forEach { assertEquals(0.5f, it, 1e-5f) }
        // GL_LINEAR, дробный шаг 2.4 (3840 -> 1600): рябь одной выборки против четырёх
        val w2 = 96
        val sh2 = shahmatka(w2, w2)
        fun ryab(a: FloatArray) = a.maxOf { abs(it - 0.5f) }
        val r1 = ryab(KartinkaCpu.umenshit(KartinkaCpu.bilinejno(sh2, w2, w2), w2, w2, 40, 40, sup = 1f))
        val r4 = ryab(KartinkaCpu.umenshit(KartinkaCpu.bilinejno(sh2, w2, w2), w2, w2, 40, 40))
        println("шахматка ×2.4 билинейно: отклонение от серого — одна выборка $r1, четыре $r4")
        assertTrue("$r1 / $r4", r4 < 0.2f && r4 < 0.6f * r1)
        // ровное поле и малое уменьшение не меняются
        val rov = FloatArray(30 * 30 * 3) { 0.37f }
        KartinkaCpu.umenshit(KartinkaCpu.bilinejno(rov, 30, 30), 30, 30, 10, 10).forEach { assertEquals(0.37f, it, 1e-6f) }
        assertEquals(KartinkaCpu.vzyatSuper(KartinkaCpu.tochno(sh, w, h), 0.3f, 0.3f, 1f / w, 1f / h, 1.2f).toList(),
            KartinkaCpu.tochno(sh, w, h).rgb(0.3f, 0.3f).toList())
    }

    // --- шейдер ---
    @Test fun shejderP010IKonstanty() {
        val src = Shader.FRAG_P010
        assertTrue(src.startsWith("#version 300 es\n"))
        for (s in Shader.SAMPLERY_P010) assertTrue(s, Regex("""uniform\s+sampler2D\s+$s\s*;""").containsMatchIn(src) && src.contains("texelFetch($s"))
        assertEquals(listOf("uTekP010Y", "uTekP010UV"), Shader.SAMPLERY_P010)
        assertEquals("uKarta", Shader.SAMPLER_KARTA); assertEquals(2, Shader.BLOK_KARTY)
        for (u in listOf("uLokalno", "uSuper", "uHdr", "uPik", "uBelyj")) assertTrue(u in Shader.UNIFORMY)
        // все const float шейдера с тем же именем, что в KartinkaCpu, совпадают по значению
        val cpu = mapOf("K_TENEJ" to KartinkaCpu.K_TENEJ, "PREDEL_REZKOSTI" to KartinkaCpu.PREDEL_REZKOSTI,
            "SUPER_POROG" to KartinkaCpu.SUPER_POROG, "LOK_CEL" to KartinkaCpu.LOK_CEL, "LOK_MAX" to KartinkaCpu.LOK_MAX,
            "LOK_MIN" to KartinkaCpu.LOK_MIN, "LOK_RHO0" to KartinkaCpu.LOK_RHO0, "LOK_LN_RHO" to KartinkaCpu.LOK_LN_RHO,
            "LOK_SHOZH" to KartinkaCpu.LOK_SHOZH, "LOK_EPS" to KartinkaCpu.LOK_EPS, "PQ_M1" to KartinkaCpu.PQ_M1,
            "PQ_M2" to KartinkaCpu.PQ_M2, "PQ_C1" to KartinkaCpu.PQ_C1, "PQ_C2" to KartinkaCpu.PQ_C2, "PQ_C3" to KartinkaCpu.PQ_C3,
            "TON_KOLENO" to KartinkaCpu.TON_KOLENO, "BELENIE" to KartinkaCpu.BELENIE, "GAMMA_EKRANA" to KartinkaCpu.GAMMA_EKRANA)
        val naideno = Regex("""const float (\w+) = ([0-9.]+);""").findAll(src).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
        for ((k, v) in cpu) assertEquals(k, v, naideno[k] ?: Float.NaN, 0f)
        assertEquals(kotlin.math.ln(3.75f / 1.25f), KartinkaCpu.LOK_LN_RHO, 1e-6f)
        // mat3 по столбцам = транспонированная строчная матрица CPU
        val m = Regex("""mat3 M2020_709 = mat3\(([^)]*)\)""").find(src)!!.groupValues[1].split(',').map { it.trim().toFloat() }
        for (r in 0..2) for (c in 0..2) assertEquals(KartinkaCpu.M2020_709[r * 3 + c], m[c * 3 + r], 0f)
        // M709_2020 · M2020_709 ≈ E
        for (r in 0..2) for (c in 0..2) {
            var s = 0f; for (i in 0..2) s += KartinkaCpu.M709_2020[r * 3 + i] * KartinkaCpu.M2020_709[i * 3 + c]
            assertEquals(if (r == c) 1f else 0f, s, 2e-4f)
        }
    }
}
