package com.questclaude.bridge

import android.content.Context as AContext
import android.graphics.Bitmap as ABitmap
import android.graphics.Canvas as ACanvas
import java.io.File

/**
 * Стенд макета (2.7.0): кадры эфира в стиле «Визор» и сцены — НАСТОЯЩИМ кодом (Nalozhenie → VizorPlashki,
 * раскладка Vizor), места слоёв — как Razvilka.mestoViz, окно камеры — Okno.mesto в золотых скобах Vizor.skobyKamery.
 * Сверка с docs/maket/vizor-1080.png и zastavka-1080.png.
 */
class VizVaryant(val imya: String, val vid: Okno.Vid, val muzyka: Boolean = true, val sotnya: Boolean = false,
                 val niz: List<Triple<String, String, String>> = emptyList(), val lico: String = "plus",
                 val sost: String = "obychno", val morgaet: Boolean = false, val t: Long = 0L,
                 val prisedT: Long = -1L, val ulybka: Boolean = false)

fun kadryVizor(out: File) {
    val ya = Okno.Vid("ugol", "pn", 0.25f, 90, true, 4f)                // айфон стоя, снизу вплотную (как у Александра)
    val bokom = Okno.Vid("ugol", "pn", 0.25f, 0, false, 4f)              // айфон лёжа, с отступами
    val varyanty = listOf(
        VizVaryant("vizor-efir", ya, niz = listOf(
            Triple("plus", "Саша, слева полиция — сверни во двор", "Sasha, police on the left — turn into the yard"),
            Triple("ya", "Держись, сейчас уйдём через мост!", "Hold on, we're losing them over the bridge!"))),
        VizVaryant("vizor-efir-bokom", bokom, muzyka = false, sotnya = true, niz = listOf(
            Triple("ya", "Макс, прикрой слева, я захожу на крышу, а ты держи вертолёт подальше от нас",
                "Max, cover the left, I'm going up to the roof, keep the chopper away from us"))),
        // «Плюс-зверь» (vid lico zver): живой рисунок — покой, речь (пасть открыта), моргает
        VizVaryant("zver-efir", ya, lico = "zver", niz = listOf(
            Triple("plus", "Саша, слева полиция — сверни во двор", "Sasha, police on the left — turn into the yard"))),
        VizVaryant("zver-govorit", ya, lico = "zver", sost = "govorit", t = 90L * 5),
        VizVaryant("zver-morgaet", ya, lico = "zver", morgaet = true),
        // 2.7.2: Плюс во весь рост — речь, моргает, приседания (кадры 2 и нижняя точка)
        VizVaryant("plus-govorit", ya, sost = "govorit", t = 90L * 5),
        VizVaryant("plus-morgaet", ya, morgaet = true),
        VizVaryant("plus-ulybka", ya, ulybka = true),
        VizVaryant("plus-prised-2", ya, prisedT = 130L),
        VizVaryant("plus-prised-niz", ya, prisedT = 400L),
    )
    for (v in varyanty) {
        val f = File(out, v.imya + ".png")
        f.writeBytes(kadrViz(v, File(out, "dir-" + v.imya)).png())
        println("ok: $f")
    }
    for ((imya, s, min, t) in listOf(
        listOf("scena-skoro", "skoro", "5", ""), listOf("scena-otoshyol", "otoshyol", "3", ""),
        listOf("scena-konec", "konec", "", ""), listOf("vizor-itogi", "", "", ""))) {
        val f = File(out, "$imya.png")
        f.writeBytes(scenaViz(s, min, t, File(out, "dir-$imya")).png())
        println("ok: $f")
    }
}

private class Sostav(val n: Nalozhenie, val rz: Razvilka, val titry: Titry, val chat: Chat, val itogi: Itogi)

private fun sostav(dir: File, vid: Okno.Vid, sotnya: Boolean): Sostav {
    dir.deleteRecursively(); dir.mkdirs()
    val rz = pustoy(Razvilka::class.java)
    pole(rz, "vid", vid); pole(rz, "kw", 1920); pole(rz, "kh", 1080); pole(rz, "kameraNs", System.nanoTime())
    pole(rz, "gosti", java.util.concurrent.ConcurrentHashMap<Int, Any>())
    pole(rz, "sloi", LinkedHashMap<String, Any>())
    val titry = Titry()
    val chat = Chat({ null }, { })
    for ((kto, t, pl) in listOf(Triple("marko_hr", "как ты это видишь в шлеме??", "tw"),
        Triple("anna.v", "Плюс лучшая!", "kick"), Triple("dimon", "гони на мост!", "tw"),
        Triple("Rex", "Plus, who wins this round?", "tw")))
        chat.upr(mapOf("kto" to kto, "tekst" to t, "pl" to pl))
    val top = Top({ null }, { }).apply { upr(mapOf("imya" to "kotlet_42", "summa" to "25", "valyuta" to "€")) }
    val igra = Igra().apply { upr(mapOf("stavok" to "5", "popal" to "3", "vyzovov" to "3", "sdelano" to "2", "vyzov" to "pistol only")) }
    val cel = Cel({ null }, { }).apply { upr(mapOf("summa" to "700", "imya" to "Quest 3 for Dad", "sobrano" to "434", "valyuta" to "€")) }
    val nalet = Nalet({ 41L * 3600 }, { })
    if (sotnya) pole(nalet, "sotnyaDoMs", System.currentTimeMillis() + 60_000L)
    val itogi = Itogi().apply { upr(mapOf("stroki" to "Two clean getaways in a row\nThe bridge jump at 1:12\nChat voted Plus MVP")) }
    val n = Nalozhenie(AContext(), nalet, pustoy(Kompas::class.java), rz, { null }, { Nalozhenie.Pokaz() }, titry, cel,
        itogi, igra, top, chat, { }, null)
    return Sostav(n, rz, titry, chat, itogi)
}

private fun kadrViz(v: VizVaryant, dir: File): ABitmap {
    val s = sostav(dir, v.vid, v.sotnya)
    val n = s.n
    for ((kto, t, p) in v.niz) s.titry.dobavit(kto, t, 10.0, p)
    val now = System.currentTimeMillis()
    val viz = zvat(n, "getViz") as VizorPlashki
    val W = 1920; val H = 1080
    // окно камеры (GL → картинка) и места правого столбца — как Razvilka.mestoViz
    val r = Okno.mesto(W, H, Okno.proporcii(1920, 1080, v.vid.povorot), v.vid)
    val kamTop = H - r[1] - r[3]
    val kameraVerhGl = r[1] + r[3] + maxOf(2, H / 360)
    val zazor = Vizor.ZAZOR.toInt()
    val chatNiz = H - kameraVerhGl - zazor                                                      // музыка — слева под розой
    val chatVerh = Vizor.CHAT_VERH.toInt()
    s.rz.chatMesto = (chatNiz - chatVerh).coerceIn(0, Vizor.CH)

    // ---------- плашки: настоящий код ----------
    val vv = ABitmap.createBitmap(W, Vizor.RAMKA_H, ABitmap.Config.ARGB_8888)
    val vn = ABitmap.createBitmap(W, Vizor.RAMKA_H, ABitmap.Config.ARGB_8888)
    viz.ramka(vv, vn)
    Oformlenie.licoUpr(v.lico)
    if (v.morgaet) pole(n, "morgaet", true)
    if (v.ulybka) pole(n, "ulybka", true)
    val tt = if (v.t != 0L) v.t else now
    if (v.prisedT >= 0) pole(n, "prisedS", tt - v.prisedT)
    zvat(n, "risovatViz", Nalozhenie.Pokaz(), v.sost, tt)
    Oformlenie.licoUpr("plus")
    val pv = zvat(n, "getPvV") as ABitmap
    zvat(n, "radar", now + 7_000)
    val lv = zvat(n, "getLvV") as ABitmap
    viz.levyVerh(lv)
    val chatB = zvat(n, "getChatV") as ABitmap
    viz.chat(chatB, s.chat.vidimye(now), now, false, s.rz.chatMesto)
    val ts = s.titry.seychas()
    val titrB = zvat(n, "getTitrV") as ABitmap
    viz.titry(titrB, ts)
    val mzB = zvat(n, "getMzV") as ABitmap
    if (v.muzyka) {
        val fon = Fon(dir, File(dir, "fon-nastroyki"))
        pole(fon, "plashkaTekst", "Night Drive — own playlist")
        floatArrayOf(0.85f, 0.95f, 0.8f, 0.65f, 0.72f, 0.55f, 0.48f, 0.36f, 0.22f, 0.12f).copyInto(fon.miks.spektr)
        viz.muzyka(mzB, fon, now, now)
    }

    // ---------- кадр ----------
    val kadr = ABitmap.createBitmap(W, H, ABitmap.Config.ARGB_8888)
    val kc = ACanvas(kadr)
    val sk = kc.sk()
    igraFon(sk, W, H)
    kc.drawBitmap(vv, 0f, 0f, null)                                                            // vv, vn — под всем
    kc.drawBitmap(vn, 0f, (H - Vizor.RAMKA_H).toFloat(), null)
    chelovek(sk, r[0].toFloat(), kamTop.toFloat(), r[2].toFloat(), r[3].toFloat(), 0xFF3D4656.toInt(), 0xFF1F2430.toInt(),
        shlem = true, radius = 0f)
    val zol = org.jetbrains.skia.Paint().apply { color = Vizor.ZOLOTO }
    for (p in Vizor.skobyKamery(r[0], r[1], r[2], r[3], 1f))                                  // золотые скобы окна
        sk.drawRect(org.jetbrains.skia.Rect.makeXYWH(p[0].toFloat(), (H - p[1] - p[3]).toFloat(), p[2].toFloat(), p[3].toFloat()), zol)
    kc.drawBitmap(pv, Vizor.PV.l, Vizor.PV.t, null)                                            // pv
    kc.drawBitmap(vzyat<ABitmap>(n, "radarBmp"), Vizor.ROZA.l, Vizor.ROZA.t, null)              // ln
    kc.drawBitmap(lv, Vizor.LV.l, Vizor.LV.t, null)                                            // lv
    if (ts.isNotEmpty()) kc.drawBitmap(titrB, Vizor.titryX(minOf(Vizor.PRAVO, r[0].toFloat())), Vizor.TITRY_VERH, null)  // nc
    if (s.rz.chatMesto >= 60) {                                                                 // pc
        kc.save()
        kc.clipRect(Vizor.STOLB_X, chatVerh.toFloat(), Vizor.PRAVO, chatNiz.toFloat())
        kc.drawBitmap(chatB, Vizor.STOLB_X, (chatNiz - Vizor.CH).toFloat(), null)
        kc.restore()
    }
    if (v.muzyka) kc.drawBitmap(mzB, Vizor.MUZ.l, Vizor.MUZ.t, null)                   // mz
    return kadr
}

/** Сцена во весь кадр поверх игры (s — skoro|otoshyol|konec; пусто — итоги вечера в стиле визора). */
private fun scenaViz(sc: String, min: String, t: String, dir: File): ABitmap {
    val s = sostav(dir, Okno.Vid(), false)
    val n = s.n
    val viz = zvat(n, "getViz") as VizorPlashki
    val b = ABitmap.createBitmap(1920, 1080, ABitmap.Config.ARGB_8888)
    val now = System.currentTimeMillis()
    val plyus = { c: ACanvas, r: android.graphics.RectF -> zvat(n, "plyusKrupno", c, r); Unit }
    if (sc.isEmpty()) viz.itogi(b, 47 * 60_000L, plyus = plyus)
    else {
        Oformlenie.scenaUpr(mapOf("s" to sc, "min" to min, "t" to t), now)
        viz.scena(b, sc, Oformlenie.ostalosMs(now) - 13_000, Oformlenie.doMs != 0L, Oformlenie.fraza, 47 * 60_000L, plyus)
        Oformlenie.sbros()
    }
    // сцена — поверх игры: игра под ней (сцены почти непрозрачны, шторка — на 3 %)
    val kadr = ABitmap.createBitmap(1920, 1080, ABitmap.Config.ARGB_8888)
    val kc = ACanvas(kadr)
    igraFon(kc.sk(), 1920, 1080)
    kc.drawBitmap(b, 0f, 0f, null)
    return kadr
}
