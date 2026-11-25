package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

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
        assertEquals(0.4f, n.izmenit("lokalno", "0.4")!!.lokalno, 1e-6f)
        assertEquals(1f, n.izmenit("lokalno", "+3")!!.lokalno, 1e-6f)                 // зажим 0..1
        assertEquals(4000f, n.izmenit("HdrPik", "4000")!!.hdrPik, 1e-3f)
        assertEquals(1500f, n.izmenit("hdrpik", "+500")!!.hdrPik, 1e-3f)
        assertEquals(100f, n.izmenit("hdrpik", "5")!!.hdrPik, 1e-3f)
        assertEquals(10000f, n.izmenit("hdrpik", "99999")!!.hdrPik, 1e-3f)
        assertEquals(1000f, Nastrojki(hdrPik = 300f).izmenit("hdrpik", "def")!!.hdrPik, 0f)
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
        for (n in NABORY.values + Nastrojki(yarkost = -0.123f, polnyjDiapazon = true, rezkost = 1.5f, lokalno = 0.37f, hdrPik = 4321f))
            assertEquals(n, Nastrojki.izStroki(n.vStroku()))
        val m = Nastrojki.izStroki("мусор\n=1\nkontrast=1.4\nteni=abc\nnasyshch = 1.5 \nchto=3\ngamma=99\n\u0000")
        assertEquals(Nastrojki(kontrast = 1.4f, nasyshch = 1.5f, gamma = 2.5f), m)
        assertEquals(Nastrojki(), Nastrojki.izStroki(""))
    }

    @Test fun nabory() {
        for (k in listOf("obychno", "monitor", "noch", "yarko", "rezko", "hdr", "kino")) assertNotNull(NABORY[k])
        NABORY.values.forEach { assertEquals(it, it.ogranichit()) }
        assertEquals(Nastrojki(), NABORY["obychno"])
        assertEquals(1f, NABORY["rezko"]!!.rezkost, 0f)
        assertEquals(1f, NABORY["noch"]!!.avtoNoch, 0f)
        assertEquals(0.6f, NABORY["noch"]!!.lokalno, 0f)
        assertEquals(0.2f, NABORY["monitor"]!!.lokalno, 0f)
        assertEquals(0.3f, NABORY["hdr"]!!.lokalno, 0f); assertEquals(1f, NABORY["hdr"]!!.kontrast, 0f)
        assertEquals(Nastrojki(), NABORY["kino"])
    }

    // --- шейдеры ---
    @Test fun shejdery() {
        val varianty = mapOf(Shader.FRAG_YUY2 to Shader.SAMPLERY_YUY2, Shader.FRAG_NV12 to Shader.SAMPLERY_NV12,
            Shader.FRAG_RGBA to Shader.SAMPLERY_RGBA, Shader.FRAG_P010 to Shader.SAMPLERY_P010)
        val tolkoP010 = setOf("uHdr", "uPik", "uBelyj")
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
                val nuzhen = !(src === Shader.FRAG_RGBA && u == "uPolnyj") && (src === Shader.FRAG_P010 || u !in tolkoP010)
                if (nuzhen) assertTrue(u, Regex("""\b$u\b""").findAll(src).count() >= 2)   // объявлен и используется
            }
            val k = Shader.SAMPLER_KARTA
            assertTrue(Regex("""uniform\s+sampler2D\s+$k\s*;""").containsMatchIn(src) && Regex("""\b$k\b""").findAll(src).count() >= 2)
            assertTrue(src.contains("void main()") && src.contains("fragColor"))
        }
        // константы шейдера = константы CPU
        val f = Shader.FRAG_RGBA
        assertTrue(f.contains("K_TENEJ = ${KartinkaCpu.K_TENEJ}") && f.contains("PREDEL_REZKOSTI = ${KartinkaCpu.PREDEL_REZKOSTI}"))
    }

    // --- 3.2.2: цвет и уровни ---
    private fun o(r: Float, g: Float, b: Float, n: Nastrojki) = KartinkaCpu.obrabotat(r, g, b, n, 0f)

    @Test fun cvetPoUmolchaniyuNichegoNeMenyaet() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val r = rnd.nextFloat(); val g = rnd.nextFloat(); val b = rnd.nextFloat()
            val a = o(r, g, b, Nastrojki())
            assertEquals(r, a[0], 2e-5f); assertEquals(g, a[1], 2e-5f); assertEquals(b, a[2], 2e-5f)
        }
    }

    @Test fun temperaturaIBalans() {
        val t = o(0.5f, 0.5f, 0.5f, Nastrojki(temperatura = 100f))
        assertTrue("теплее: красный вверх, синий вниз", t[0] > 0.55f && t[2] < 0.45f && kotlin.math.abs(t[1] - 0.5f) < 1e-5f)
        val h = o(0.5f, 0.5f, 0.5f, Nastrojki(temperatura = -100f))
        assertTrue("холоднее", h[0] < 0.45f && h[2] > 0.55f)
        val b = o(0.4f, 0.4f, 0.4f, Nastrojki(balG = 1.5f))
        assertEquals(0.6f, b[1], 1e-4f); assertEquals(0.4f, b[0], 1e-5f)
    }

    @Test fun tonPovorachivaetOttenokNoNeSeroe() {
        val sero = o(0.4f, 0.4f, 0.4f, Nastrojki(ton = 30f))
        for (i in 0..2) assertEquals(0.4f, sero[i], 1e-4f)
        val k = o(0.8f, 0.2f, 0.2f, Nastrojki(ton = 30f))     // красный → к жёлтому: зелёный растёт, синий падает
        assertTrue(k[1] > 0.3f && k[2] < 0.2f)
        val k2 = o(0.8f, 0.2f, 0.2f, Nastrojki(ton = -30f))   // → к пурпурному
        assertTrue(k2[2] > 0.3f && k2[1] < 0.2f)
    }

    @Test fun svetaTrogayutSvetlyeNeTeni() {
        val n = Nastrojki(sveta = 1f)
        assertTrue(o(0.8f, 0.8f, 0.8f, n)[0] > 0.84f)                       // светлое — ярче
        assertEquals(0.2f, o(0.2f, 0.2f, 0.2f, n)[0], 0.002f)               // тени не вымываются
        assertEquals(1f, o(1f, 1f, 1f, n)[0], 1e-5f)
        assertTrue(o(0.8f, 0.8f, 0.8f, Nastrojki(sveta = -1f))[0] < 0.76f)   // минус — света приглушены
        for (s in listOf(-1f, 1f)) {                                           // монотонна
            var pred = -1f
            for (i in 0..100) { val v = KartinkaCpu.svetaKrivaya(i / 100f, s); assertTrue(v >= pred); pred = v }
        }
    }

    @Test fun urovni() {
        val n = Nastrojki(chernyj = 0.1f, belyj = 0.9f)
        assertEquals(0f, o(0.1f, 0.1f, 0.1f, n)[0], 1e-5f)
        assertEquals(1f, o(0.9f, 0.9f, 0.9f, n)[0], 1e-5f)
        assertEquals(0.5f, o(0.5f, 0.5f, 0.5f, n)[0], 1e-5f)
        assertTrue(o(0.3f, 0.3f, 0.3f, n)[0] < 0.3f)                          // блёклое тёмное — гуще
    }

    @Test fun zhivostBlyokloeSilnee() {
        val n = Nastrojki(zhivost = 1f)
        fun nas(c: FloatArray) = (c.max() - c.min()) / c.max()
        val blek = floatArrayOf(0.5f, 0.45f, 0.4f); val yark = floatArrayOf(0.9f, 0.2f, 0.1f)
        val b2 = o(blek[0], blek[1], blek[2], n); val y2 = o(yark[0], yark[1], yark[2], n)
        val prirostB = nas(b2) / nas(blek); val prirostY = nas(y2) / nas(yark)
        assertTrue("блёклое: $prirostB, яркое: $prirostY", prirostB > 1.6f && prirostY < 1.25f)
        assertTrue(nas(o(blek[0], blek[1], blek[2], Nastrojki(zhivost = -1f))) < 0.4f * nas(blek))   // минус — блёклое к серому
        assertTrue(nas(o(yark[0], yark[1], yark[2], Nastrojki(zhivost = -1f))) > 0.6f * nas(yark))   // яркое почти цело
    }

    @Test fun novyeRuchkiKomandyIZhim() {
        val n = Nastrojki()
        assertEquals(-40f, n.izmenit("temperatura", "=-40")!!.temperatura, 1e-4f)
        assertEquals(100f, n.izmenit("temperatura", "500")!!.temperatura, 1e-4f)
        assertEquals(1.5f, n.izmenit("balr", "9")!!.balR, 1e-6f)
        assertEquals(-30f, n.izmenit("ton", "=-99")!!.ton, 1e-4f)
        assertEquals(0.3f, n.izmenit("chernyj", "0.9")!!.chernyj, 1e-6f)
        assertEquals(0.7f, n.izmenit("belyj", "0.1")!!.belyj, 1e-6f)
        assertEquals(2.5f, n.izmenit("nasyshch", "9")!!.nasyshch, 1e-6f)                     // 3.2.2: до 2.5
        assertEquals(0.5f, n.izmenit("zhivost", "+0.5")!!.zhivost, 1e-6f)
        val v = Nastrojki(temperatura = -12f, balB = 1.2f, ton = 7f, sveta = 0.3f, chernyj = 0.05f, belyj = 0.95f, zhivost = 0.4f)
        assertEquals(v, Nastrojki.izStroki(v.vStroku()))
        assertEquals(Nastrojki(), Nastrojki.izStroki("yarkost=0\nkontrast=1"))                   // файл 3.2.1 — цвет как был
    }

    @Test fun shejderZnaetNovyeRuchki() {
        val f = Shader.FRAG_NV12
        assertTrue(f.contains("K_SVETOV = ${KartinkaCpu.K_SVETOV}") && f.contains("MIN_UROVNYA = ${KartinkaCpu.MIN_UROVNYA}"))
        for (u in listOf("uMnR", "uMnG", "uMnB", "uTon", "uSveta", "uUrChernyj", "uUrBelyj", "uZhivost", "uVyderzhka", "uSila", "uSravnenie"))
            assertTrue(u, u in Shader.UNIFORMY)
        assertTrue(f.contains("mix(vhod, obr, uSila)") && f.contains("c * uVyderzhka"))
    }

    // 3.2.4: выдержка — ступень = свет ×2 в линейном (гамма 2.2); белое не темнеет от плюса, ноль на месте.
    @Test fun vyderzhkaStupeni() {
        val lin = { x: Float -> x.toDouble().pow(2.2).toFloat() }
        val plus1 = o(0.4f, 0.4f, 0.4f, Nastrojki(vyderzhka = 1f))[0]
        assertEquals(2f * lin(0.4f), lin(plus1), 0.002f)
        val minus2 = o(0.8f, 0.8f, 0.8f, Nastrojki(vyderzhka = -2f))[0]
        assertEquals(lin(0.8f) / 4f, lin(minus2), 0.002f)
        assertEquals(1f, o(0.9f, 0.9f, 0.9f, Nastrojki(vyderzhka = 1f))[0], 1e-5f)        // светлое упирается в белое
        assertEquals(0f, o(0f, 0f, 0f, Nastrojki(vyderzhka = 2f))[0], 1e-6f)
        assertEquals(2f, Nastrojki().izmenit("vyderzhka", "7")!!.vyderzhka, 1e-6f)
        assertEquals(-2f, Nastrojki().izmenit("vyderzhka", "=-7")!!.vyderzhka, 1e-6f)
    }

    // 3.2.4: точка чёрного в минус — чёрное приподнято (−0.2 → 0.2/1.2), в плюс — как уровень чёрного.
    @Test fun tochkaChernogoVObeStorony() {
        assertEquals(0.2f / 1.2f, o(0f, 0f, 0f, Nastrojki(chernyj = -0.2f))[0], 1e-5f)
        assertEquals(1f, o(1f, 1f, 1f, Nastrojki(chernyj = -0.2f))[0], 1e-5f)
        assertEquals(-0.2f, Nastrojki().izmenit("chernyj", "=-0.9")!!.chernyj, 1e-6f)
        assertEquals(0f, o(0.1f, 0.1f, 0.1f, Nastrojki(chernyj = 0.1f))[0], 1e-5f)
    }

    // 3.2.4: сила всех ручек — 0: кадр как пришёл; 1: как настроено; 2: отклонение от входа вдвое (до 0..1).
    @Test fun silaVsehRuchek() {
        val nastr = NABORY["yarko"]!!.copy(temperatura = 30f, vyderzhka = 0.5f, zhivost = 0.4f)
        val vh = floatArrayOf(0.35f, 0.3f, 0.25f)
        val odin = o(vh[0], vh[1], vh[2], nastr)
        val nol = o(vh[0], vh[1], vh[2], nastr.copy(sila = 0f))
        val dva = o(vh[0], vh[1], vh[2], nastr.copy(sila = 2f))
        val pol = o(vh[0], vh[1], vh[2], nastr.copy(sila = 0.5f))
        for (i in 0..2) {
            assertEquals(vh[i], nol[i], 1e-6f)
            assertEquals(vh[i] + 2f * (odin[i] - vh[i]), dva[i], 1e-5f)
            assertEquals(vh[i] + 0.5f * (odin[i] - vh[i]), pol[i], 1e-5f)
        }
        assertEquals(1f, o(0.9f, 0.9f, 0.9f, NABORY["yarko"]!!.copy(vyderzhka = 2f, sila = 2f))[0], 1e-6f)   // не выше белого
        val v = Nastrojki(vyderzhka = -0.7f, sila = 1.4f, chernyj = -0.1f)
        assertEquals(v, Nastrojki.izStroki(v.vStroku()))
        assertEquals(1f, Nastrojki.izStroki("yarkost=0").sila, 0f)                                  // файл 3.2.3 — сила 1
        // весь кадр: сила 0 при резкости и местной ночи — ровно вход
        val w = 8; val h = 6; val rgb = FloatArray(w * h * 3) { ((it * 37) % 100) / 100f }
        val k = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(rezkost = 1f, lokalno = 0.6f, kontrast = 1.4f, sila = 0f), 0.2f)
        for (i in rgb.indices) assertEquals(rgb[i], k[i], 1e-6f)
    }

    // 3.2.7 (план К2): чистка для эфира — шум на ровном меньше, край резче, 0 — ничего не меняет
    @Test fun chistkaShumIKraj() {
        val w = 32; val h = 16
        val r = kotlin.random.Random(3)
        // левая половина — ровное серое с шумом ±0,01, правая — ступенька 0,3 → 0,7 посередине правой части
        val shumTochki = FloatArray(w * h) { (r.nextFloat() * 2 - 1) * 0.01f }      // шум яркости: во всех каналах один
        val rgb = FloatArray(w * h * 3) { i -> val x = (i / 3) % w
            if (x < 16) 0.5f + shumTochki[i / 3] else if (x < 24) 0.3f else 0.7f }
        val bez = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(), 0f)
        val s = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(chistka = 1f), 0f)
        fun razbros(k: FloatArray): Float { var mn = 1f; var mx = 0f
            for (y in 2 until h - 2) for (x in 2 until 14) { val v = k[(y * w + x) * 3]; mn = minOf(mn, v); mx = maxOf(mx, v) }; return mx - mn }
        assertTrue("шум ${razbros(bez)} → ${razbros(s)}", razbros(s) < 0.75f * razbros(bez))
        val y = 8
        assertTrue(s[(y * w + 23) * 3] < bez[(y * w + 23) * 3] - 0.01f)    // тёмная сторона края — темнее
        assertTrue(s[(y * w + 24) * 3] > bez[(y * w + 24) * 3] + 0.01f)    // светлая — светлее
        assertEquals(bez[(y * w + 28) * 3], s[(y * w + 28) * 3], 1e-6f)     // ровное без шума — как было
        val n0 = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(chistka = 0f), 0f)
        for (i in rgb.indices) assertEquals(bez[i], n0[i], 0f)
        val v = Nastrojki(chistka = 0.4f)
        assertEquals(v, Nastrojki.izStroki(v.vStroku()))
        assertEquals(0f, Nastrojki.izStroki("yarkost=0").chistka, 0f)       // файл 3.2.6 — без чистки
        assertTrue("uChistka" in Shader.UNIFORMY)
    }

    // --- 3.2.8: свет цвета и мультяшность (Александр 25.11: «насыщеннее и мультяшнее, весь свет ярче — не обелевшая») ---
    private fun hsv(c: FloatArray): FloatArray {
        val mx = maxOf(c[0], c[1], c[2]); val mn = minOf(c[0], c[1], c[2]); val d = mx - mn
        val h = when { d < 1e-6f -> 0f; mx == c[0] -> ((c[1] - c[2]) / d).mod(6f); mx == c[1] -> (c[2] - c[0]) / d + 2f
            else -> (c[0] - c[1]) / d + 4f }
        return floatArrayOf(h, if (mx > 0f) d / mx else 0f, mx)
    }

    @Test fun svetTotZheTonINasyshchennost() {
        val r = kotlin.random.Random(8)
        repeat(500) {
            val c = floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat())
            val o = c.copyOf(); KartinkaCpu.svet(o, 0.6f)
            val a = hsv(c); val b = hsv(o)
            if (a[2] > 0.01f && a[1] > 0.01f) assertEquals(a[0], b[0], 1e-3f)   // оттенок тот же
            if (a[2] > 0.01f) assertEquals(a[1], b[1], 1e-4f)                     // насыщенность та же — не белеет
            assertTrue(b[2] >= a[2] - 1e-6f && b[2] <= 1f)                        // ярче, но не выше 1
        }
        val ch = floatArrayOf(0f, 0f, 0f); KartinkaCpu.svet(ch, 1f); assertEquals(0f, ch[0], 0f)
        val bel = floatArrayOf(1f, 1f, 1f); KartinkaCpu.svet(bel, 1f); assertEquals(1f, bel[0], 1e-6f)
        val nol = floatArrayOf(0.3f, 0.5f, 0.2f); KartinkaCpu.svet(nol, 0f); assertEquals(0.5f, nol[1], 0f)
        // середина: 0,5 → 1 − 0,5^2,2 ≈ 0,782 при 0,6; монотонно по яркости
        val sr = floatArrayOf(0.5f, 0.25f, 0.1f); KartinkaCpu.svet(sr, 0.6f)
        assertEquals(1f - 0.5f.pow(2.2f), sr[0], 1e-5f)
        var pred = 0f
        for (k in 1..100) { val x = floatArrayOf(k / 100f, 0f, 0f); KartinkaCpu.svet(x, 0.8f); assertTrue(x[0] >= pred); pred = x[0] }
        // в конвейере: свет цвета — после всего остального
        val v = KartinkaCpu.obrabotat(0.4f, 0.2f, 0.1f, Nastrojki(svet = 0.5f), 0f)
        val bez = KartinkaCpu.obrabotat(0.4f, 0.2f, 0.1f, ID, 0f)
        assertTrue(v[0] > bez[0] + 0.1f)
        assertEquals(hsv(bez)[1], hsv(v)[1], 1e-4f)
    }

    @Test fun multyashnoRovnoeGladkoNaKrayahObvodka() {
        val w = 40; val h = 20
        val r = kotlin.random.Random(5)
        // левая часть — ровный цвет с мелкой рябью ±0,015, правая — ступенька 0,25 → 0,75 на x = 30
        val ryab = FloatArray(w * h) { (r.nextFloat() * 2 - 1) * 0.015f }
        val rgb = FloatArray(w * h * 3) { i -> val x = (i / 3) % w
            if (x < 20) 0.5f + ryab[i / 3] else if (x < 30) 0.25f else 0.75f }
        val bez = KartinkaCpu.obrabotatKadr(rgb, w, h, ID, 0f)
        val m = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(multyashno = 1f), 0f)
        fun razbros(k: FloatArray): Float {                                     // СКО ряби на ровном
            val v = ArrayList<Float>(); for (y in 3 until h - 3) for (x in 3 until 17) v += k[(y * w + x) * 3]
            val sr = v.average().toFloat(); return kotlin.math.sqrt(v.map { (it - sr) * (it - sr) }.average()).toFloat() }
        assertTrue("рябь ${razbros(bez)} → ${razbros(m)}", razbros(m) < 0.5f * razbros(bez))
        val y = 10
        assertTrue(m[(y * w + 29) * 3] < bez[(y * w + 29) * 3] - 0.05f)     // у края — обводка темнее
        assertTrue(m[(y * w + 30) * 3] < bez[(y * w + 30) * 3] - 0.1f)
        assertEquals(bez[(y * w + 36) * 3], m[(y * w + 36) * 3], 1e-6f)      // ровное вдали от края — как было
        assertEquals(bez[(y * w + 25) * 3], m[(y * w + 25) * 3], 1e-6f)
        val n0 = KartinkaCpu.obrabotatKadr(rgb, w, h, Nastrojki(multyashno = 0f, svet = 0f), 0f)
        for (i in rgb.indices) assertEquals(bez[i], n0[i], 0f)
        val v = Nastrojki(svet = 0.6f, multyashno = 0.5f)
        assertEquals(v, Nastrojki.izStroki(v.vStroku()))
        assertEquals(0f, Nastrojki.izStroki("yarkost=0").svet, 0f)            // файл 3.2.7 — без новых ручек
        assertEquals(1f, ID.izmenit("multyashno", "3")!!.multyashno, 0f)       // не больше 1
        assertTrue("uSvet" in Shader.UNIFORMY && "uMulty" in Shader.UNIFORMY)
    }
}
