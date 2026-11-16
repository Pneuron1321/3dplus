package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/** Стиль «Визор» (2.7.0): раскладка (центр свободен, всё в кадре, текст не мельче минимума), стиль и сцены. */
class VizorTest {
    @After fun zanovo() = Oformlenie.zanovo()

    /** Всё, что рисуется поверх игры в стиле viz, — в точках 1080p (наибольшие размеры). */
    private fun karty(): Map<String, Vizor.Pryam> {
        val lv = Vizor.LV
        val k = Vizor.LV_K                                            // 2.7.9: левый верх вдвое меньше
        val m = mutableMapOf(
            "топ и цель" to Vizor.Pryam(lv.l, lv.t, lv.l + Vizor.KARTA_TOPA_W * k, lv.t + Vizor.kartaTopaH(top = true, cel = true) * k),
            "табло" to Vizor.Pryam(lv.l + Vizor.TABLO_X * k, lv.t, lv.l + Vizor.LV_W * k, lv.t + Vizor.tabloH(3) * k),
            "налёт" to Vizor.NALET_PILYULYA,
            "бюст Плюса" to Vizor.BYUST,
            "плашка Плюса" to Vizor.PLASHKA_PLYUSA,
            "чат" to Vizor.Pryam(Vizor.STOLB_X, Vizor.CHAT_VERH, Vizor.PRAVO, Vizor.NIZ),
            "сейчас играет" to Vizor.MUZ,
        )
        // субтитры — при любом месте окна камеры справа (и без него)
        for (pravo in listOf(Vizor.PRAVO, 1563f, 1363f, 1000f)) {
            val x = Vizor.titryX(pravo)
            m["субтитры до $pravo"] = Vizor.Pryam(x, Vizor.TITRY_VERH, x + Vizor.TW, Vizor.NIZ)
        }
        Vizor.skoby().forEachIndexed { i, s -> m["скоба $i"] = s }
        return m
    }

    @Test fun centrKadraSvoboden() {
        val c = Vizor.CENTR
        assertEquals(288f, c.l, 0.01f); assertEquals(1632f, c.r, 0.01f)
        assertEquals(216f, c.t, 0.01f); assertEquals(864f, c.b, 0.01f)
        for ((imya, r) in karty()) assertFalse("$imya $r залезает в центр $c", r.peresekaet(c))
    }

    @Test fun nichtoNeVylezaetZaKadr() {
        for ((imya, r) in karty()) assertTrue("$imya $r за кадром", r.vnutri(Vizor.KADR))
        // слои целиком в кадре
        for ((imya, r) in mapOf("lv" to Vizor.LV, "pv" to Vizor.PV, "pl" to Vizor.PL, "верх" to Vizor.RAMKA_VERH, "низ" to Vizor.RAMKA_NIZ))
            assertTrue("$imya $r", r.vnutri(Vizor.KADR))
        // содержимое слоёв — внутри своих картинок
        assertTrue(Vizor.BYUST.vnutri(Vizor.PL.shire(0.5f)))
        assertTrue(Vizor.PLASHKA_PLYUSA.vnutri(Vizor.PL))
        // фигура с покачиванием (±3) и шириной ×1,08 — внутри слоя Плюса
        val w = Vizor.TELO_W * Vizor.KT * Vizor.TELO_SHIRE
        val cx = Vizor.TELO_X + Vizor.TELO_W * Vizor.KT / 2f
        assertTrue(cx - w / 2f - 3f >= Vizor.PL.l && cx + w / 2f + 3f <= Vizor.PL.r)
        assertTrue(Vizor.TELO_Y + Vizor.TELO_H * Vizor.KT + 2f <= Vizor.PL.b)
        assertTrue(Vizor.NALET_PILYULYA.vnutri(Vizor.PV))
        assertTrue(Vizor.kartaTopaH(top = true, cel = true) <= Vizor.LV_H)
        assertEquals(Vizor.LV_W * Vizor.LV_K, Vizor.LV.w, 0.01f)
        assertTrue(Vizor.tabloH(3) <= Vizor.LV_H)
    }

    @Test fun kartyNeNalezayutDrugNaDruga() {
        val k = karty().filterKeys { !it.startsWith("субтитры") && !it.startsWith("скоба") && it != "бюст Плюса" }
        val spisok = k.entries.toList()
        for (i in spisok.indices) for (j in i + 1 until spisok.size) {
            val (a, ra) = spisok[i]; val (b, rb) = spisok[j]
            if (setOf(a, b) == setOf("плашка Плюса", "бюст Плюса")) continue
            assertFalse("$a $ra и $b $rb", ra.peresekaet(rb))
        }
        // чат — под налётом (2.7.9)
        assertTrue(Vizor.CHAT_VERH >= Vizor.NALET_PILYULYA.b)
        // субтитры не наезжают на Плюса и музыку
        for ((imya, r) in karty().filterKeys { it.startsWith("субтитры") }) {
            assertFalse(imya, r.peresekaet(Vizor.PL)); assertFalse(imya, r.peresekaet(Vizor.MUZ))
        }
        // 2.7.9: Плюс — в самом низу; музыка — наверху по центру, выше скоб визора; оповещение — под музыкой;
        // нижних скоб нет; субтитры при айфоне лёжа (окно с 1363) не заходят на камеру
        assertTrue(Vizor.PL.b >= Vizor.H - 10f)
        assertTrue(Vizor.MUZ.t < Vizor.SKOBA_OTSTUP && Vizor.MUZ.b <= Vizor.VERH + 12f)
        assertEquals(Vizor.W / 2f, (Vizor.MUZ.l + Vizor.MUZ.r) / 2f, 0.01f)
        assertTrue(Opoveshchenie.VERH >= Vizor.MUZ.b)
        assertTrue(Vizor.skoby().all { it.b < Vizor.H / 2f })
        assertTrue(Vizor.titryX(1363f) + Vizor.TW <= 1363f)
        // левый верх не доходит до налёта
        assertTrue(Vizor.LV.r <= Vizor.NALET_PILYULYA.l)
    }

    @Test fun tekstNeMelcheMinimuma() {
        assertEquals(28f, Vizor.MIN_TEKST, 0f)
        for ((imya, kegl) in Vizor.KEGLI) {
            val (sz, metka) = kegl
            assertTrue("$imya: $sz", sz >= if (metka) Vizor.MIN_METKA else Vizor.MIN_TEKST)
        }
        assertTrue(Vizor.MIN_METKA >= 22f)
        // строки чата и субтитров влезают в свои карточки по высоте
        assertTrue(Vizor.TH >= 12 + 26 + 40 + 34 + 12)
    }

    @Test fun skobyKameryVokrugOkna() {
        val p = Vizor.skobyKamery(1000, 100, 400, 300, 1f)
        assertEquals(8, p.size)
        val v = Vizor.KSKOBA_VYNOS.toInt()
        // все плечи — за пределами окна или по его краю, в квадратах углов
        assertTrue(p.all { it[0] >= 1000 - v && it[0] + it[2] <= 1400 + v && it[1] >= 100 - v && it[1] + it[3] <= 400 + v })
        assertTrue(p.none { it[0] > 1000 && it[0] + it[2] < 1400 && it[1] > 100 && it[1] + it[3] < 400 })   // внутрь окна не лезут
        // в 720p — тоньше, но не меньше двух точек
        assertTrue(Vizor.skobyKamery(0, 0, 100, 100, 720f / 1080f).all { minOf(it[2], it[3]) >= 2 })
    }

    @Test fun tajmerIPloshchadki() {
        assertEquals("05:00", Vizor.tajmer(300_000))
        assertEquals("04:59", Vizor.tajmer(299_000))
        assertEquals("05:00", Vizor.tajmer(299_001))           // отсчёт — вверх: 4:59,001 ещё «05:00»
        assertEquals("00:01", Vizor.tajmer(1))
        assertEquals("00:00", Vizor.tajmer(0))
        assertEquals("00:00", Vizor.tajmer(-5000))
        assertEquals("1:00:00", Vizor.tajmer(3_600_000))
        assertEquals("TW", Vizor.ploshchadka("tw").first)
        assertEquals("KICK", Vizor.ploshchadka("kick").first)
        assertEquals("YT", Vizor.ploshchadka("yt").first)
        assertEquals("TW", Vizor.ploshchadka("что-то").first)
    }

    @Test fun stilPoUmolchaniyuVizIZapominaetsya() {
        val d = Files.createTempDirectory("vizor").toFile()
        Oformlenie.zagruzit(d)
        assertTrue(Oformlenie.viz)
        assertTrue(Oformlenie.stilUpr(null).contains("\"stil\":\"viz\""))
        val v0 = Oformlenie.versiya
        assertTrue(Oformlenie.stilUpr("staryj").contains("\"stil\":\"staryj\""))
        assertFalse(Oformlenie.viz)
        assertTrue(Oformlenie.versiya > v0)
        assertEquals("staryj", File(d, "efir-stil").readText())
        Oformlenie.stilUpr("chepuha")                                  // чужое — как было
        assertEquals("staryj", Oformlenie.stil)
        Oformlenie.zanovo(); Oformlenie.zagruzit(d)                    // после перезапуска — запомненный
        assertEquals("staryj", Oformlenie.stil)
        Oformlenie.stilUpr("viz")
        Oformlenie.zanovo(); Oformlenie.zagruzit(d)
        assertTrue(Oformlenie.viz)
        d.deleteRecursively()
    }

    @Test fun sceny() {
        val t0 = 1_000_000L
        var s = Oformlenie.scenaUpr(mapOf("s" to "skoro", "min" to "5", "t" to "%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82"), t0)
        assertEquals("skoro", Oformlenie.scena)
        assertTrue(s, s.contains("\"ostalos_sek\":300") && s.contains("\"tajmer\":true") && s.contains("\"fraza\":\"Привет\""))
        assertEquals(60_000L, Oformlenie.ostalosMs(t0 + 240_000))
        assertEquals(0L, Oformlenie.ostalosMs(t0 + 400_000))           // прошло — ноль, не минус
        s = Oformlenie.scenaUpr(mapOf("s" to "otoshyol"), t0)
        assertEquals("otoshyol", Oformlenie.scena)
        assertTrue(s.contains("\"tajmer\":false") && s.contains("\"fraza\":\"\""))
        Oformlenie.scenaUpr(mapOf("s" to "konec", "min" to "3"), t0)
        assertEquals(0L, Oformlenie.doMs)                              // у конца эфира таймера нет
        s = Oformlenie.scenaUpr(mapOf("s" to "zastavka"), t0)
        assertTrue(s.contains("oshibka"))
        assertEquals("konec", Oformlenie.scena)                        // чужое — сцена прежняя
        assertTrue(Oformlenie.scenaUpr(emptyMap(), t0).contains("\"scena\":\"konec\""))
        Oformlenie.scenaUpr(mapOf("s" to "net"), t0)
        assertEquals("", Oformlenie.scena)
        assertTrue(Oformlenie.status(t0).contains("\"scena\":\"net\""))
        // конец эфира — сцену прочь; дольше суток не ставим; минуты дробью
        Oformlenie.scenaUpr(mapOf("s" to "skoro", "min" to "99999"), t0)
        assertEquals(t0 + Oformlenie.MAKS_MIN * 60_000L, Oformlenie.doMs)
        Oformlenie.scenaUpr(mapOf("s" to "otoshyol", "min" to "0,5"), t0)
        assertEquals(t0 + 30_000L, Oformlenie.doMs)
        val v = Oformlenie.versiya
        Oformlenie.sbros()
        assertEquals("", Oformlenie.scena); assertEquals(0L, Oformlenie.doMs)
        assertTrue(Oformlenie.versiya > v)
        // фраза — без управляющих знаков и не длиннее предела
        Oformlenie.scenaUpr(mapOf("s" to "skoro", "t" to "a%0Ab\"c" + "x".repeat(300)), t0)
        assertTrue(Oformlenie.fraza.startsWith("a b\"c") && Oformlenie.fraza.length == Oformlenie.MAKS_FRAZA)
        assertTrue(Oformlenie.status(t0).contains("a b\\\"c"))
    }

    /** Маршруты моста: /efir/scena и /efir/stil — и без эфира (сцену ставят до начала). */
    @Test fun marshrutyMosta() {
        val port = 18791
        val server = FrameServer(port, { null }, { false })
        server.start()
        fun get(put: String): String {
            val c = URI("http://127.0.0.1:$port$put").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 2000; c.readTimeout = 2000
            assertEquals(200, c.responseCode)
            return c.inputStream.readBytes().toString(Charsets.UTF_8)
        }
        try {
            assertTrue(get("/efir/stil").contains("\"stil\":\"viz\""))
            assertTrue(get("/efir/stil?v=staryj").contains("\"stil\":\"staryj\""))
            assertFalse(Oformlenie.viz)
            assertTrue(get("/efir/stil?v=viz").contains("\"stil\":\"viz\""))
            val s = get("/efir/scena?s=skoro&min=5&t=%D0%A7%D0%B0%D0%B9")
            assertTrue(s, s.contains("\"scena\":\"skoro\"") && s.contains("\"fraza\":\"Чай\"") && s.contains("\"tajmer\":true"))
            assertTrue(get("/efir/scena").contains("\"scena\":\"skoro\""))
            assertTrue(get("/efir/scena?s=konec").contains("\"scena\":\"konec\""))
            assertTrue(get("/efir/scena?s=net").contains("\"scena\":\"net\""))
        } finally { server.stop() }
    }

    /** Метка площадки у сообщения чата: tw по умолчанию, kick и yt — когда уши скажут; чужое — tw. */
    @Test fun ploshchadkaSoobshcheniya() {
        val ch = Chat({ null }, { })
        ch.upr(mapOf("kto" to "a", "tekst" to "privet"))
        ch.upr(mapOf("kto" to "b", "tekst" to "hej", "pl" to "kick"))
        ch.upr(mapOf("kto" to "c", "tekst" to "yo", "pl" to "yt"))
        ch.upr(mapOf("kto" to "d", "tekst" to "zz", "pl" to "<script>"))
        assertEquals(listOf("tw", "kick", "yt", "tw"), ch.vidimye().map { it.pl })
    }

    /** 2.8.8: раз в 377 с — восемь ступеней по 0,5 с: 110/120/130, рывок 155 → 130, 140/150/160, финал 180 → 100 %. */
    @Test fun pulsPlashkiSsylok() {
        assertEquals(377_000L, Vizor.NK_PULS_RAZ_MS)
        assertEquals(4000L, Vizor.NK_PULS_VSEGO)
        assertEquals(-1L, Vizor.nkPulsT(4000))
        assertEquals(0L, Vizor.nkPulsT(3 * 377_000L))
        assertEquals(-1L, Vizor.nkPulsT(300_000L))
        assertEquals(1f, Vizor.nkPuls(0), 1e-4f)
        val posle = mapOf(499L to 1.10f, 999L to 1.20f, 1499L to 1.30f, 1650L to 1.55f, 1999L to 1.30f,
            2499L to 1.40f, 2999L to 1.50f, 3499L to 1.60f, 3650L to 1.80f, 3999L to 1.00f)
        for ((t, k) in posle) assertEquals("t=$t", k, Vizor.nkPuls(t), 0.01f)
        assertEquals(1f, Vizor.nkPuls(4000), 0f)
        for (t in 0L until 4000L step 10) assertTrue(Vizor.nkPuls(t) in 0.99f..1.81f)
        // Плюс: сел за 0,36 с, сидит до конца пульса (4 с), встаёт за 0,36 с
        assertEquals(0, Nalozhenie.prisedPuls(0)); assertEquals(3, Nalozhenie.prisedPuls(400))
        assertEquals(3, Nalozhenie.prisedPuls(3999)); assertEquals(2, Nalozhenie.prisedPuls(4000))
        assertEquals(0, Nalozhenie.prisedPuls(4300)); assertEquals(-1, Nalozhenie.prisedPuls(4360))
    }

    /** 2.8.10: субтитры между плашкой ссылок (~535) и окном камеры (~1380) не влезают — меньше и на окно не заходят. */
    @Test fun titryNeNaOkno() {
        val okno = 1380f
        val m = Vizor.titryMesto(okno, 535f)
        assertTrue("масштаб ${m[1]}", m[1] < 1f && m[1] >= Vizor.TITRY_MIN)
        assertTrue(m[0] >= 535f + Vizor.ZAZOR)
        assertTrue("правый край ${m[0] + Vizor.TW * m[1]}", m[0] + Vizor.TW * m[1] <= okno - Vizor.ZAZOR + 0.5f)
        val polno = Vizor.titryMesto(Vizor.PRAVO, Vizor.PL.r)                // места хватает — как было
        assertEquals(1f, polno[1], 0f)
        assertEquals(Vizor.titryX(Vizor.PRAVO, Vizor.PL.r), polno[0], 0f)
    }

    /** 2.8.11: края окна камеры прочь (проценты, до 45), запоминаются; сила больше — порог ниже (вырез смелее). */
    @Test fun fonObrezIPorog() {
        val d = Files.createTempDirectory("vizor").toFile()
        Oformlenie.zagruzit(d)
        assertEquals(0.15f, Oformlenie.fonPorog(), 1e-4f)
        assertTrue(Oformlenie.fonUpr(mapOf("v" to "zel", "obrez" to "12,0,8,0")).contains("\"obrez\":[12,0,8,0]"))
        assertEquals(0.12f, Oformlenie.fonObrez[0], 1e-4f); assertEquals(0.08f, Oformlenie.fonObrez[2], 1e-4f)
        assertTrue(Oformlenie.fonUpr(mapOf("n" to "1")).contains("\"obrez\":[12,0,8,1]"))     // 1 — это 1 %, не «целиком»
        assertTrue(Oformlenie.fonUpr(mapOf("v" to "90")).contains("\"obrez\":[12,45,8,1]"))   // не больше 45 %
        Oformlenie.fonUpr(mapOf("obrez" to "чепуха"))                                          // чужое — как было
        assertEquals(0.12f, Oformlenie.fonObrez[0], 1e-4f)
        Oformlenie.fonUpr(mapOf("sila" to "0.25"))
        assertEquals(0.05f, Oformlenie.fonPorog(), 1e-4f)                                      // смелее
        Oformlenie.fonUpr(mapOf("sila" to "0.05"))
        assertEquals(0.25f, Oformlenie.fonPorog(), 1e-4f)                                      // бережнее
        Oformlenie.zanovo(); Oformlenie.zagruzit(d)                                            // после перезапуска — запомненное
        assertEquals("zel", Oformlenie.fon)
        assertEquals(0.12f, Oformlenie.fonObrez[0], 1e-4f); assertEquals(0.45f, Oformlenie.fonObrez[1], 1e-4f)
        File(d, "efir-fon").writeText("zel 0.15")                                              // файл от 2.8.10 — без краёв
        Oformlenie.zanovo(); Oformlenie.zagruzit(d)
        assertEquals(0f, Oformlenie.fonObrez.sum(), 0f)
        d.deleteRecursively()
    }
}
