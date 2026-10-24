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
        val m = mutableMapOf(
            "топ и цель" to Vizor.Pryam(lv.l, lv.t, lv.l + Vizor.KARTA_TOPA_W, lv.t + Vizor.kartaTopaH(top = true, cel = true)),
            "табло" to Vizor.Pryam(lv.l + Vizor.TABLO_X, lv.t, lv.l + Vizor.LV_W, lv.t + Vizor.tabloH(3)),
            "налёт" to Vizor.NALET_PILYULYA,
            "бюст Плюса" to Vizor.BYUST,
            "плашка Плюса" to Vizor.PLASHKA_PLYUSA,
            "чат" to Vizor.Pryam(Vizor.STOLB_X, Vizor.CHAT_VERH, Vizor.PRAVO, Vizor.NIZ),
            "сейчас играет" to Vizor.MUZ,
            "роза" to Vizor.ROZA,
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
        for ((imya, r) in mapOf("lv" to Vizor.LV, "pv" to Vizor.PV, "верх" to Vizor.RAMKA_VERH, "низ" to Vizor.RAMKA_NIZ))
            assertTrue("$imya $r", r.vnutri(Vizor.KADR))
        // содержимое слоёв — внутри своих картинок
        assertTrue(Vizor.BYUST.vnutri(Vizor.PV.shire(0.5f)))
        assertTrue(Vizor.PLASHKA_PLYUSA.vnutri(Vizor.PV))
        assertTrue(Vizor.NALET_PILYULYA.vnutri(Vizor.PV))
        assertTrue(Vizor.kartaTopaH(top = true, cel = true) <= Vizor.LV_H)
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
        // налёт — слева от бюста, чат — под плашкой Плюса
        assertTrue(Vizor.NALET_PILYULYA.r <= Vizor.BYUST.l)
        assertTrue(Vizor.CHAT_VERH >= Vizor.PLASHKA_PLYUSA.b)
        // субтитры не наезжают на розу
        for ((imya, r) in karty().filterKeys { it.startsWith("субтитры") }) {
            assertFalse(imya, r.peresekaet(Vizor.ROZA)); assertFalse(imya, r.peresekaet(Vizor.MUZ))
        }
        // музыка — под розой (2.7.5); субтитры при айфоне лёжа (окно с 1363) не заходят на камеру
        assertTrue(Vizor.MUZ.t >= Vizor.ROZA.b)
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
}
