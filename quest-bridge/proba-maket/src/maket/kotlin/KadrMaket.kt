package com.questclaude.bridge

import android.content.Context as AContext
import android.graphics.Bitmap as ABitmap
import android.graphics.Canvas as ACanvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Shader
import java.io.File

/**
 * Стенд макета (2.5.0): кадр эфира 1080p, который рисует НАСТОЯЩИЙ код плашек (Nalozhenie — лицо Плюса с
 * телефоном, роза, субтитры, плитки и пузыри гостей, чат, топ-донатор, табло) через android.graphics поверх
 * Skia; места слоёв — как в Razvilka.plashkaNa, окна камеры — Okno.mesto, плиток — Raskladka. Игра, камера
 * и видео гостей — нарисованные заглушки (в шлеме там кадры). Запуск: sh quest-bridge/proba-maket/zapusk.sh ANDROID_JAR ПАПКА.
 */
private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    .apply { isAccessible = true }.get(null) as sun.misc.Unsafe

private fun <T> pustoy(c: Class<T>): T = c.cast(unsafe.allocateInstance(c))

private fun polePolya(c0: Class<*>, imya: String): java.lang.reflect.Field {
    var c: Class<*>? = c0
    while (c != null) {
        try { return c.getDeclaredField(imya).apply { isAccessible = true } } catch (e: NoSuchFieldException) { c = c.superclass }
    }
    error("нет поля $imya у ${c0.name}")
}

private fun pole(o: Any, imya: String, v: Any?) = polePolya(o.javaClass, imya).set(o, v)

@Suppress("UNCHECKED_CAST")
private fun <T> vzyat(o: Any, imya: String): T = polePolya(o.javaClass, imya).get(o) as T

private fun zvat(o: Any, imya: String, vararg a: Any?): Any? {
    val m = o.javaClass.declaredMethods.first { it.name == imya && it.parameterCount == a.size }
    m.isAccessible = true
    return m.invoke(o, *a)
}

class Gost(val imya: String, val rol: String, val kamera: Boolean, val govorit: Boolean = false,
           val mikrofon: Boolean = true, val fon: Pair<Int, Int> = 0xFF2D4B5E.toInt() to 0xFF16222C.toInt())

class Varyant(val imya: String, val mest: Int, val gosti: List<Gost>, val niz: List<Triple<String, String, String>>,
              val puzyri: Map<String, Pair<String, String>>, val vnizu: Boolean = false)

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "maket-out" }).apply { mkdirs() }
    val siniy = 0xFF2D4B5E.toInt() to 0xFF16222C.toInt()
    val lilovyy = 0xFF4A3A5E.toInt() to 0xFF221A2E.toInt()
    val varyanty = listOf(
        Varyant("gosti-maket", 2,
            listOf(Gost("Max", "moder", kamera = true, govorit = true, fon = siniy), Gost("Lena", "gost", kamera = false)),
            listOf(Triple("ya", "Макс, прикрой слева, я захожу на крышу", "Max, cover the left, I'm going up to the roof"),
                Triple("igra", "Enemy UAV spotted!", "Замечен вражеский дрон!")),
            mapOf("Max" to ("Вижу двоих на крыше, справа от тебя" to "I see two on the roof, to your right"),
                "Lena" to ("Nice shot! That was insane" to "Отличный выстрел! Это было безумие"))),
        Varyant("gosti-maket-3", 3,
            listOf(Gost("Max", "moder", kamera = true, fon = siniy), Gost("Lena", "gost", kamera = false, govorit = true),
                Gost("Dima", "gost", kamera = true, mikrofon = false, fon = lilovyy)),
            listOf(Triple("plus", "Лена, ставлю на то, что он возьмёт обоих", "Lena, I bet he takes both of them"),
                Triple("ya", "Сейчас посмотрим", "We'll see")),
            mapOf("Lena" to ("He's got no chance, trust me" to "У него нет шансов, поверь"))),
        Varyant("gosti-maket-vnizu", 2,
            listOf(Gost("Max", "moder", kamera = true, govorit = true, fon = siniy), Gost("Lena", "gost", kamera = false)),
            listOf(Triple("ya", "Макс, прикрой слева", "Max, cover the left"),
                Triple("igra", "Enemy UAV spotted!", "Замечен вражеский дрон!")),
            mapOf("Max" to ("Вижу двоих на крыше" to "I see two on the roof")), vnizu = true),
    )
    for (v in varyanty) {
        val f = File(out, v.imya + ".png")
        f.writeBytes(kadr(v, File(out, "dir-" + v.imya)).png())
        println("ok: $f")
    }
}

private fun kadr(v: Varyant, dir: File): ABitmap {
    dir.deleteRecursively(); dir.mkdirs()
    // ---------- развилка без GL: только то, что спрашивают плашки ----------
    val rz = pustoy(Razvilka::class.java)
    val vid = Okno.Vid("ugol", "pn", 0.25f, 90, true, 4f)        // айфон стоя (поворот 90), снизу вплотную
    pole(rz, "vid", vid); pole(rz, "kw", 1920); pole(rz, "kh", 1080); pole(rz, "kameraNs", System.nanoTime())
    pole(rz, "gosti", java.util.concurrent.ConcurrentHashMap<Int, Any>())
    pole(rz, "sloi", LinkedHashMap<String, Any>())
    val kompas = pustoy(Kompas::class.java)

    // ---------- гости: настоящая комната ----------
    val g = Gosti(dir) { null }
    val kl: Komnata.Klyuchi = vzyat(g, "klyuchi")
    val k = g.komnata
    pole(k, "mest", v.mest)
    val sv = object : Komnata.Svyaz { override fun tekst(json: String) {}; override fun zakryt(prichina: String) {} }
    val lyudi = v.gosti.map { gs ->
        val u = k.vhod(sv, if (gs.rol == "moder") kl.moder else kl.gost, gs.imya, gs.kamera, gs.mikrofon)!!
        u to gs
    }
    val moder = lyudi.first { it.first.rol == "moder" }.first
    for ((u, _) in lyudi) if (u.sost != "vnutri") k.upr(moder, "vpustit", u.id)
    val gostKlass = Class.forName("com.questclaude.bridge.Razvilka\$Gost")
    val rgosti: java.util.concurrent.ConcurrentHashMap<Int, Any> = vzyat(rz, "gosti")
    for ((u, gs) in lyudi) {
        u.govorit = gs.govorit
        if (!gs.mikrofon) u.zaglushen = true
        if (gs.kamera) rgosti[u.id] = gostKlass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            .also { pole(it, "kadrNs", System.nanoTime() + 60_000_000_000L) }
    }
    pole(g, "razvilka", rz)
    check(lyudi.all { it.first.vnutri && it.first.cvet >= 0 }) { "не все внутри" }

    // ---------- субтитры, чат, донаты, табло ----------
    val titry = Titry()
    for ((kto, t, p) in v.niz) titry.dobavit(kto, t, 10.0, p)
    for ((u, gs) in lyudi) v.puzyri[gs.imya]?.let { (t, p) -> titry.dobavit(u.golos, t, 10.0, p) }
    val chat = Chat({ null }, { })
    for ((kto, t, c) in listOf(Triple("NightOwl", "this squad is cracked", "#ff7f50"),
        Triple("vika_play", "Лена, привет из Загреба!", "#9acd32"),
        Triple("Rex", "Plus, who wins this round?", "#1e90ff"))) chat.upr(mapOf("kto" to kto, "tekst" to t, "cvet" to c))
    val top = Top({ null }, { }).apply { upr(mapOf("imya" to "Rex", "summa" to "25", "valyuta" to "$")) }
    val igra = Igra().apply { upr(mapOf("stavok" to "5", "popal" to "3", "vyzovov" to "3", "sdelano" to "2", "vyzov" to "pistol only")) }
    val cel = Cel({ null }, { }).apply { upr(mapOf("summa" to "700", "imya" to "Meta Quest 3 for Dad", "sobrano" to "120", "valyuta" to "$")) }
    val nalet = Nalet({ 41L * 3600 }, { })

    val n = Nalozhenie(AContext(), nalet, kompas, rz, { null }, { Nalozhenie.Pokaz() }, titry, cel,
        Itogi(), igra, top, chat, { }, g)
    val now = System.currentTimeMillis()

    // ---------- окно камеры (Okno — как в развилке; GL от низа → картинка от верха) ----------
    val W = 1920; val H = 1080; val m = H / 100
    val r = Okno.mesto(W, H, Okno.proporcii(1920, 1080, vid.povorot), vid)
    val kamTop = H - r[1] - r[3]
    val kameraVerhGl = r[1] + r[3] + maxOf(2, H / 360)
    // чат: между плашкой Плюса и окном камеры (как plashkaNa "pc")
    val zazor = 8
    val chatVerhGl = H - m - Nalozhenie.H - zazor
    val chatNizGl = kameraVerhGl + zazor
    rz.chatMesto = (chatVerhGl - chatNizGl).coerceIn(0, Nalozhenie.CH)

    // ---------- плашки: настоящий код ----------
    pole(rz, "kameraNs", System.nanoTime())
    zvat(n, "risovat", Nalozhenie.Pokaz(), "obychno", now)
    zvat(n, "radar", now + 7_000)                                   // роза — повернувшись, как через 7 с эфира
    val ek = g.naEkrane()
    val plitki = if (v.vnizu) null else ek.mapTo(HashSet()) { it.first.golos }      // как шаг «субтитры»
    val vse = titry.seychas(gostiVnizu = v.vnizu, uPlitok = plitki)
    val ts = vse.filter { !Titry.gost(it.kto) || plitki == null || it.kto !in plitki }
    zvat(n, "titryRisovat", ts)
    zvat(n, "plitkiRisovat", ek)
    val estPuzyri = plitki != null && zvat(n, "puzyri", vse.filter { Titry.gost(it.kto) && it.kto in plitki }) == true
    zvat(n, "levyVerh")
    zvat(n, "chatRisovat", chat.vidimye(now), now, false)

    // ---------- кадр ----------
    val kadr = ABitmap.createBitmap(W, H, ABitmap.Config.ARGB_8888)
    val kc = ACanvas(kadr)
    val sk = kc.sk()
    igraFon(sk, W, H)
    // окно камеры: светлая рамка, внутри — Александр в шлеме (заглушка)
    val b = maxOf(2, H / 360)
    sk.drawRect(org.jetbrains.skia.Rect.makeXYWH((r[0] - b).toFloat(), (kamTop - b).toFloat(), (r[2] + 2 * b).toFloat(),
        (r[3] + 2 * b).toFloat()), org.jetbrains.skia.Paint().apply { color = 0xFFEDEDED.toInt() })
    chelovek(sk, r[0].toFloat(), kamTop.toFloat(), r[2].toFloat(), r[3].toFloat(), 0xFF5A4636.toInt(), 0xFF2A201A.toInt(),
        shlem = true, radius = 0f)
    // видео плиток — скруглённый квадрат (маска как в шейдере плитки)
    for ((u, p) in ek) {
        val gs = lyudi.first { it.first.id == u.id }.second
        if (g.videoVidno(u.id)) chelovek(sk, p.x, p.y, p.s, p.s, gs.fon.first, gs.fon.second, naushniki = true,
            radius = Raskladka.RADIUS)
    }
    val bmp = { imya: String -> vzyat<ABitmap>(n, imya) }
    // слои — в порядке развилки; места — как plashkaNa
    kc.drawBitmap(bmp("bmp"), (W - Nalozhenie.W - m).toFloat(), m.toFloat(), null)                      // pv
    kc.drawBitmap(bmp("radarBmp"), m.toFloat(), (H - m - Nalozhenie.RH).toFloat(), null)                // ln
    kc.drawBitmap(bmp("igraBmp"), m.toFloat(), m.toFloat(), null)                                       // lv
    if (ts.isNotEmpty()) {
        val levo = m + Nalozhenie.RW
        val pravo = minOf(W - m, r[0] - m)
        val c = Raskladka.centrNiza(W, levo, pravo, Nalozhenie.TW)
        kc.drawBitmap(bmp("titrBmp"), (c - Nalozhenie.TW / 2).toFloat(), (H - H * 5 / 100 - Nalozhenie.TH).toFloat(), null)  // nc
    }
    if (rz.chatMesto >= 60) {                                                                            // pc
        kc.save()
        kc.clipRect((W - Nalozhenie.CW - m).toFloat(), (H - chatVerhGl).toFloat(), (W - m).toFloat(), (H - chatNizGl).toFloat())
        kc.drawBitmap(bmp("chatBmp"), (W - Nalozhenie.CW - m).toFloat(), (H - chatNizGl - Nalozhenie.CH).toFloat(), null)
        kc.restore()
    }
    if (ek.isNotEmpty()) kc.drawBitmap(bmp("lgBmp"), 0f, Raskladka.SLOY_Y, null)                         // lg
    if (estPuzyri) kc.drawBitmap(bmp("lbBmp"), 0f, Raskladka.SLOY_Y, null)                              // lb
    return kadr
}

/** Игра — заглушка: вечерний город, улица в перспективе, прицел. */
private fun igraFon(c: org.jetbrains.skia.Canvas, w: Int, h: Int) {
    val gor = h * 0.56f
    val p = org.jetbrains.skia.Paint()
    p.shader = Shader.makeLinearGradient(0f, 0f, 0f, gor, intArrayOf(0xFF1A2540.toInt(), 0xFF5B4A6A.toInt(), 0xFFD08A5C.toInt()),
        floatArrayOf(0f, 0.6f, 1f), GradientStyle(FilterTileMode.CLAMP, true, null))
    c.drawRect(org.jetbrains.skia.Rect.makeWH(w.toFloat(), gor), p)
    p.shader = Shader.makeRadialGradient(w * 0.62f, gor - 20f, 260f, intArrayOf(0xAAFFD9A0.toInt(), 0x00FFD9A0),
        null, GradientStyle(FilterTileMode.CLAMP, true, null))
    c.drawRect(org.jetbrains.skia.Rect.makeWH(w.toFloat(), gor), p)
    p.shader = null
    val rnd = java.util.Random(7)
    for (sloy in 0..1) {                                               // дальние и ближние дома
        var x = -20f
        p.color = if (sloy == 0) 0xFF39344A.toInt() else 0xFF231F2C.toInt()
        while (x < w) {
            val sh = 60f + rnd.nextInt(90)
            val vy = (if (sloy == 0) 120f else 70f) + rnd.nextInt(if (sloy == 0) 160 else 120)
            c.drawRect(org.jetbrains.skia.Rect.makeXYWH(x, gor - vy, sh, vy + 2), p)
            if (sloy == 1) {
                val ok = org.jetbrains.skia.Paint().apply { color = 0x66FFC878 }
                var oy = gor - vy + 14
                while (oy < gor - 16) {
                    var ox = x + 10
                    while (ox < x + sh - 14) { if (rnd.nextInt(3) == 0) c.drawRect(org.jetbrains.skia.Rect.makeXYWH(ox, oy, 7f, 9f), ok); ox += 16 }
                    oy += 20
                }
            }
            x += sh + rnd.nextInt(12)
        }
    }
    p.shader = Shader.makeLinearGradient(0f, gor, 0f, h.toFloat(), intArrayOf(0xFF3B3633.toInt(), 0xFF1C1918.toInt()), null,
        GradientStyle(FilterTileMode.CLAMP, true, null))
    c.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, gor, w.toFloat(), h - gor), p)
    p.shader = null
    val ln = org.jetbrains.skia.Paint().apply { color = 0x40FFFFFF; mode = PaintMode.STROKE; strokeWidth = 2f; isAntiAlias = true }
    val cx = w * 0.5f
    for (i in -8..8) c.drawLine(cx + i * 30f, gor, cx + i * 420f, h.toFloat(), ln)
    var y = gor + 6f; var d = 6f
    while (y < h) { c.drawLine(0f, y, w.toFloat(), y, ln.apply { color = 0x22FFFFFF }); d *= 1.35f; y += d }
    // ящики-укрытия
    val yashik = org.jetbrains.skia.Paint().apply { color = 0xFF5A4630.toInt() }
    for ((x0, y0, s) in listOf(Triple(560f, 700f, 120f), Triple(1240f, 640f, 80f), Triple(690f, 760f, 90f))) {
        c.drawRect(org.jetbrains.skia.Rect.makeXYWH(x0, y0, s, s), yashik)
        c.drawRect(org.jetbrains.skia.Rect.makeXYWH(x0, y0, s, s), org.jetbrains.skia.Paint().apply { color = 0x55000000; mode = PaintMode.STROKE; strokeWidth = 4f })
    }
    // прицел
    val pr = org.jetbrains.skia.Paint().apply { color = 0xE6FFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    val cy = h / 2f
    c.drawLine(cx - 22, cy, cx - 8, cy, pr); c.drawLine(cx + 8, cy, cx + 22, cy, pr)
    c.drawLine(cx, cy - 22, cx, cy - 8, pr); c.drawLine(cx, cy + 8, cx, cy + 22, pr)
    c.drawCircle(cx, cy, 2.5f, pr)
}

/** Заглушка видео: фон и силуэт человека (в шлеме VR — Александр; в наушниках — гость). */
private fun chelovek(c: org.jetbrains.skia.Canvas, x: Float, y: Float, w: Float, h: Float, f1: Int, f2: Int,
                     shlem: Boolean = false, naushniki: Boolean = false, radius: Float = 0f) {
    c.save()
    if (radius > 0f) c.clipRRect(RRect.makeXYWH(x, y, w, h, radius), true) else c.clipRect(org.jetbrains.skia.Rect.makeXYWH(x, y, w, h))
    val p = org.jetbrains.skia.Paint().apply { isAntiAlias = true }
    p.shader = Shader.makeLinearGradient(x, y, x, y + h, intArrayOf(f1, f2), null, GradientStyle(FilterTileMode.CLAMP, true, null))
    c.drawRect(org.jetbrains.skia.Rect.makeXYWH(x, y, w, h), p)
    p.shader = null
    val s = minOf(w, h)
    val cx = x + w / 2
    val golY = y + h * (if (h > w * 1.3f) 0.42f else 0.40f)
    val gr = s * 0.17f
    p.color = 0xFF14100E.toInt()
    c.drawRRect(RRect.makeXYWH(cx - s * 0.42f, golY + gr * 1.25f, s * 0.84f, h, s * 0.2f), p)      // плечи
    c.drawRect(org.jetbrains.skia.Rect.makeXYWH(cx - gr * 0.45f, golY + gr * 0.7f, gr * 0.9f, gr * 0.9f), p)  // шея
    p.color = 0xFF1E1714.toInt()
    c.drawCircle(cx, golY, gr, p)                                                                    // голова
    p.color = 0x55FFE0C0
    p.mode = PaintMode.STROKE; p.strokeWidth = s * 0.012f
    c.drawCircle(cx, golY, gr, p)                                                                    // контровой свет
    p.mode = PaintMode.FILL
    if (shlem) {                                                                                     // Quest на лице
        p.color = 0xFFF2F2F2.toInt()
        c.drawRRect(RRect.makeXYWH(cx - gr * 1.05f, golY - gr * 0.35f, gr * 2.1f, gr * 0.75f, gr * 0.3f), p)
        p.color = 0xFF2B2B2B.toInt()
        for (dx in listOf(-0.5f, 0.5f)) c.drawCircle(cx + dx * gr, golY + gr * 0.02f, gr * 0.12f, p)
    }
    if (naushniki) {
        p.color = 0xFF0A0A0A.toInt(); p.mode = PaintMode.STROKE; p.strokeWidth = gr * 0.22f
        c.drawArc(cx - gr * 1.15f, golY - gr * 1.2f, cx + gr * 1.15f, golY + gr * 1.1f, 190f, 160f, false, p)
        p.mode = PaintMode.FILL
        for (dx in listOf(-1.05f, 1.05f)) c.drawRRect(RRect.makeXYWH(cx + dx * gr - gr * 0.22f, golY - gr * 0.15f, gr * 0.44f, gr * 0.6f, gr * 0.15f), p)
    }
    c.restore()
}
