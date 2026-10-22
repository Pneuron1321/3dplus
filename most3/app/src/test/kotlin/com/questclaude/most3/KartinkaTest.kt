package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class KartinkaTest {
    private val E = 1f / 255f
    private val ID = Nastrojki()

    private fun obr(x: Float, n: Nastrojki, a: Float = 0f) = KartinkaCpu.obrabotat(x, x, x, n, a)[0]

    // --- YUV ---
    @Test fun ogranichennyjDiapazon() {
        KartinkaCpu.yuvVRgb(16, 128, 128, false).forEach { assertEquals(0f, it, 1e-6f) }
        KartinkaCpu.yuvVRgb(235, 128, 128, false).forEach { assertEquals(1f, it, 1e-5f) }
        val s = KartinkaCpu.yuvVRgb(126, 128, 128, false)
        assertEquals(s[0], s[1], 1e-6f); assertEquals(s[1], s[2], 1e-6f); assertEquals(110f / 219f, s[0], 1e-5f)
        KartinkaCpu.yuvVRgb(0, 128, 128, false).forEach { assertEquals(0f, it, 0f) }   // ниже 16 — зажим
    }

    @Test fun bt709Tsveta() {
        // 100% красный BT.709, ограниченный: Y=63 Cb=102 Cr=240
        val k = KartinkaCpu.yuvVRgb(63, 102, 240, false)
        assertEquals(1f, k[0], 0.01f); assertEquals(0f, k[1], 0.01f); assertEquals(0f, k[2], 0.01f)
        // 100% синий: Y=32 Cb=240 Cr=118
        val s = KartinkaCpu.yuvVRgb(32, 240, 118, false)
        assertEquals(0f, s[0], 0.01f); assertEquals(0f, s[1], 0.01f); assertEquals(1f, s[2], 0.01f)
        // полный диапазон: 255 -> белый, 0 -> чёрный, 128/128 — серый
        KartinkaCpu.yuvVRgb(255, 128, 128, true).forEach { assertEquals(1f, it, 1e-6f) }
        KartinkaCpu.yuvVRgb(0, 128, 128, true).forEach { assertEquals(0f, it, 1e-6f) }
    }

    // --- конвейер ---
    @Test fun tozhdestvo() {
        val tsveta = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.5f, 0.2f, 0.9f),
            floatArrayOf(0.03f, 0.06f, 0.01f), floatArrayOf(0.98f, 0.1f, 0.4f), floatArrayOf(0.25f, 0.75f, 0.5f))
        for (c in tsveta) {
            val o = KartinkaCpu.obrabotat(c[0], c[1], c[2], ID, 0f)
            for (i in 0..2) assertEquals(c[i], o[i], E)
        }
        val w = 9; val h = 7
        val kadr = FloatArray(w * h * 3) { ((it * 37) % 101) / 100f }
        val o = KartinkaCpu.obrabotatKadr(kadr, w, h, ID, 0f)
        for (i in kadr.indices) assertEquals(kadr[i], o[i], E)
    }

    @Test fun nasyshchNol() {
        val o = KartinkaCpu.obrabotat(0.9f, 0.2f, 0.4f, Nastrojki(nasyshch = 0f), 0f)
        assertEquals(o[0], o[1], 1e-6f); assertEquals(o[1], o[2], 1e-6f)
        assertEquals(KartinkaCpu.yarkost(0.9f, 0.2f, 0.4f), o[0], 1e-6f)
    }

    @Test fun kontrastVokrugSerogo() {
        val n = Nastrojki(kontrast = 1.5f)
        assertEquals(0.5f, obr(0.5f, n), 1e-6f)
        assertEquals(0.3578f, obr(0.4f, n), 1e-4f); assertEquals(0.6422f, obr(0.6f, n), 1e-4f)   // симметрично
        assertEquals(0.4472f, obr(0.4f, Nastrojki(kontrast = 0.5f)), 1e-4f)
        assertEquals(1.5f, (obr(0.501f, n) - obr(0.499f, n)) / 0.002f, 0.01f)   // наклон в середине = k
        val k2 = Nastrojki(kontrast = 2f)
        assertTrue(obr(0.05f, k2) > 0.004f && obr(0.05f, k2) < 0.05f)           // тень темнее, но не в ноль
        assertEquals(0f, obr(0f, k2), E); assertEquals(1f, obr(1f, k2), E)
    }

    @Test fun yarkostSdvig() {
        assertEquals(0.4f, obr(0.3f, Nastrojki(yarkost = 0.1f)), 1e-5f)
        assertEquals(0f, obr(0.05f, Nastrojki(yarkost = -0.1f)), 1e-6f)
    }

    @Test fun gammaPodnimaetSerediny() {
        assertEquals(0.5f, obr(0.25f, Nastrojki(gamma = 2f)), 1e-5f)
        assertTrue(obr(0.25f, Nastrojki(gamma = 0.5f)) < 0.25f)
        assertEquals(1f, obr(1f, Nastrojki(gamma = 2.5f)), 1e-6f)
        assertEquals(0f, obr(0f, Nastrojki(gamma = 2.5f)), E)
    }

    @Test fun podyomTenej() {
        val n = Nastrojki(teni = 1f)
        assertTrue("0.05 -> ${obr(0.05f, n)}", obr(0.05f, n) > 0.15f)        // в 3+ раза
        assertTrue(obr(0.9f, n) - 0.9f < 0.01f)
        assertTrue(obr(0.9f, n) >= 0.9f)
        assertEquals(1f, obr(1f, n), 1e-6f); assertEquals(0f, obr(0f, n), E)
        var pred = -1f
        for (i in 0..1000) { val y = obr(i / 1000f, n); assertTrue(y >= pred); pred = y }  // монотонно
        // авто-тени складываются с ручными и зажимаются до 1
        assertEquals(obr(0.05f, n), obr(0.05f, Nastrojki(teni = 0.7f), 0.8f), 1e-6f)
    }

    @Test fun vyhodVsegdaVDiapazone() {
        val n = Nastrojki(yarkost = 0.5f, kontrast = 2f, nasyshch = 2f, gamma = 0.5f, teni = 1f)
        for (r in 0..10) for (b in 0..10) {
            val o = KartinkaCpu.obrabotat(r / 10f, 0.5f, b / 10f, n, 1f)
            o.forEach { assertTrue(it in 0f..1f) }
        }
    }

    // --- авто-ночь ---
    @Test fun avtoTeniKrivaya() {
        assertEquals(0f, avtoTeni(0.3f, 1f), 0f)
        assertEquals(0f, avtoTeni(0.25f, 1f), 0f)
        assertTrue(avtoTeni(0.03f, 1f) > 0.99f)
        assertEquals(0.5f, avtoTeni(0.03f, 0.5f), 1e-3f)
        assertEquals(0f, avtoTeni(0.01f, 0f), 0f)
        var pred = 2f
        for (i in 0..100) { val a = avtoTeni(i / 100f, 1f); assertTrue(a <= pred); pred = a }
    }

    @Test fun srednyayaYarkostSnimka() {
        val b = ByteArray(4 * 4) { if (it % 4 == 3) -1 else 0 }
        for (i in 0..2) b[i] = -1                                   // один белый пиксель из 4
        assertEquals(0.25f, srednyayaYarkost(b, 2, 2), 1e-4f)
        assertEquals(0f, srednyayaYarkost(ByteArray(0), 0, 0), 0f)
    }

    @Test fun avtoNochSvetlayaStsenaNeTrogaetsya() {
        val w = 320; val h = 180
        val den = Sceny.den(w, h)
        assertTrue(Sceny.srednyaya(den, w, h) > 0.3f)
        val o = Sceny.sAvtoNochyu(den, w, h, Nastrojki(avtoNoch = 1f))
        var s = 0.0
        for (i in den.indices) s += abs(o[i] - den[i])
        assertTrue("средняя разница ${s / den.size}", s / den.size < 0.02)
    }

    @Test fun avtoNochTyomnayaStsenaVidna() {
        val w = 320; val h = 180
        val noch = Sceny.noch(w, h)
        val do_ = Sceny.srednyaya(noch, w, h)
        assertTrue("средняя ночи $do_", do_ in 0.04f..0.12f)
        val o = Sceny.sAvtoNochyu(noch, w, h, Nastrojki(avtoNoch = 1f))
        val posle = Sceny.srednyaya(o, w, h)
        assertTrue("средняя $do_ -> $posle", posle > do_ * 1.8f && posle - do_ > 0.06f)
        // спрятанная фигура: разница с фоном растёт минимум втрое (0.03 -> ~0.09)
        val r0 = Sceny.srednyaya(noch, w, h, Sceny.FIGURA) - Sceny.srednyaya(noch, w, h, Sceny.FON)
        val r1 = Sceny.srednyaya(o, w, h, Sceny.FIGURA) - Sceny.srednyaya(o, w, h, Sceny.FON)
        println("ночь: средняя $do_ -> $posle; фигура-фон $r0 -> $r1")
        assertTrue("фигура-фон $r0 -> $r1", r0 in 0.025f..0.035f && r1 > 3f * r0 - 0.005f)
        // светлые места (лампы) почти не меняются
        val l = noch.indices.filter { it % 3 == 1 && noch[it] > 0.9f }
        assertTrue(l.isNotEmpty())
        l.forEach { assertTrue(o[it] - noch[it] < 0.01f) }
    }

    // --- резкость ---
    private fun stupenka(w: Int, h: Int, a: Float, b: Float) = FloatArray(w * h * 3) { if ((it / 3) % w < w / 2) a else b }

    @Test fun rezkostKraj() {
        val w = 16; val h = 8
        val k = stupenka(w, h, 0.3f, 0.7f)
        val o = KartinkaCpu.obrabotatKadr(k, w, h, Nastrojki(rezkost = 1f), 0f)
        val y = 4; fun p(a: FloatArray, x: Int) = a[(y * w + x) * 3]
        assertTrue(p(o, 8) - p(o, 7) > p(k, 8) - p(k, 7) + 0.1f)           // перепад круче
        assertEquals(0.2f, p(o, 7), 1e-5f); assertEquals(0.8f, p(o, 8), 1e-5f)
        assertEquals(0.3f, p(o, 2), 1e-6f); assertEquals(0.7f, p(o, 13), 1e-6f)   // ровное место не тронуто
        val o2 = KartinkaCpu.obrabotatKadr(k, w, h, Nastrojki(rezkost = 2f), 0f)
        for (i in o2.indices) assertTrue(o2[i] >= 0.2f - 1e-5f && o2[i] <= 0.8f + 1e-5f)   // без выбросов за предел ±0.1
    }

    @Test fun rezkostRovnoePoleITsvet() {
        val w = 6; val h = 5
        val k = FloatArray(w * h * 3) { floatArrayOf(0.6f, 0.3f, 0.1f)[it % 3] }
        val o = KartinkaCpu.obrabotatKadr(k, w, h, Nastrojki(rezkost = 2f), 0f)
        for (i in k.indices) assertEquals(k[i], o[i], 1e-6f)
    }

    // --- сглаживание ---
    @Test fun sglazhivanie() {
        val s = Sglazhivanie(1f)
        repeat(100) { s.shag(1f, 0.01f) }
        assertEquals(1f - exp(-1f), s.znachenie, 0.005f)
        val s2 = Sglazhivanie(1f); s2.shag(1f, 1f)
        assertEquals(s.znachenie, s2.znachenie, 1e-3f)                       // не зависит от частоты замеров
        repeat(1000) { s.shag(1f, 0.01f) }
        assertEquals(1f, s.znachenie, 1e-3f)
        s.shag(Float.NaN, 0.1f); assertEquals(1f, s.znachenie, 1e-3f)
        s.shag(0f, -5f); assertEquals(1f, s.znachenie, 1e-3f)
    }

    // --- настройки ---
    @Test fun izmenitKomandy() {
        val n = Nastrojki()
        assertEquals(1.3f, n.izmenit("kontrast", "1.3")!!.kontrast, 1e-6f)
        assertEquals(1.2f, n.izmenit("kontrast", "+0.2")!!.kontrast, 1e-6f)
        assertEquals(-0.1f, n.izmenit("yarkost", "-0.1")!!.yarkost, 1e-6f)
        assertEquals(-0.2f, n.izmenit("yarkost", "=-0.2")!!.yarkost, 1e-6f)
        assertEquals(0.5f, n.izmenit("teni", "0,5")!!.teni, 1e-6f)
        assertEquals(2.5f, n.izmenit("gamma", "9")!!.gamma, 1e-6f)          // зажим
        assertEquals(0f, n.izmenit("nasyshch", "-5")!!.nasyshch, 1e-6f)
        assertEquals(1f, Nastrojki(gamma = 2f).izmenit("Gamma", " def ")!!.gamma, 1e-6f)
        assertEquals(1f, n.izmenit("avtonoch", "1")!!.avtoNoch, 1e-6f)
        assertTrue(n.izmenit("polnyj", "1")!!.polnyjDiapazon)
        for (plohoe in listOf("", "abc", "1.2.3", "+", "--1", "NaN", "Infinity", "1e5", "0x10", "1f"))
            assertNull("'$plohoe'", n.izmenit("kontrast", plohoe))
        assertNull(n.izmenit("chto", "1"))
    }

    @Test fun ogranichit() {
        val n = Nastrojki(-3f, 9f, -1f, 0f, 5f, 7f, -2f).ogranichit()
        assertEquals(Nastrojki(-0.5f, 2f, 0f, 0.5f, 1f, 2f, 0f), n)
        assertEquals(1f, Nastrojki(kontrast = Float.NaN).ogranichit().kontrast, 0f)
    }

    @Test fun stroka() {
        for (n in NABORY.values + Nastrojki(yarkost = -0.123f, polnyjDiapazon = true, rezkost = 1.5f))
            assertEquals(n, Nastrojki.izStroki(n.vStroku()))
        val m = Nastrojki.izStroki("мусор\n=1\nkontrast=1.4\nteni=abc\nnasyshch = 1.5 \nchto=3\ngamma=99\n\u0000")
        assertEquals(Nastrojki(kontrast = 1.4f, nasyshch = 1.5f, gamma = 2.5f), m)
        assertEquals(Nastrojki(), Nastrojki.izStroki(""))
    }

    @Test fun nabory() {
        for (k in listOf("obychno", "monitor", "noch", "yarko", "rezko")) assertNotNull(NABORY[k])
        NABORY.values.forEach { assertEquals(it, it.ogranichit()) }
        assertEquals(Nastrojki(), NABORY["obychno"])
        assertEquals(1f, NABORY["rezko"]!!.rezkost, 0f)
        assertEquals(1f, NABORY["noch"]!!.avtoNoch, 0f)
    }

    // --- шейдеры ---
    @Test fun shejdery() {
        val varianty = mapOf(Shader.FRAG_YUY2 to Shader.SAMPLERY_YUY2, Shader.FRAG_NV12 to Shader.SAMPLERY_NV12,
            Shader.FRAG_RGBA to Shader.SAMPLERY_RGBA)
        for ((src, samplery) in varianty + (Shader.VERSHINNYJ to emptyList())) {
            assertTrue(src.startsWith("#version 300 es\n"))
            assertEquals(src.count { it == '{' }, src.count { it == '}' })
            assertEquals(src.count { it == '(' }, src.count { it == ')' })
            assertTrue(!src.contains('$'))
            for (s in samplery) assertTrue(s, Regex("""uniform\s+sampler2D\s+$s\s*;""").containsMatchIn(src))
        }
        for ((src, _) in varianty) {
            for (u in Shader.UNIFORMY) {
                assertTrue(u, Regex("""uniform\s+float\s+$u\s*;""").containsMatchIn(src))
                if (src !== Shader.FRAG_RGBA || u != "uPolnyj")                 // в RGBA uPolnyj не нужен
                    assertTrue(u, Regex("""\b$u\b""").findAll(src).count() >= 2)   // объявлен и используется
            }
            assertTrue(src.contains("void main()") && src.contains("fragColor"))
        }
        // константы шейдера = константы CPU
        val f = Shader.FRAG_RGBA
        assertTrue(f.contains("K_TENEJ = ${KartinkaCpu.K_TENEJ}") && f.contains("PREDEL_REZKOSTI = ${KartinkaCpu.PREDEL_REZKOSTI}"))
    }
}
