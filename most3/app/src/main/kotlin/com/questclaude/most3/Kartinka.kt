package com.questclaude.most3

import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Настройки картинки и эталон шейдера на CPU (та же математика, что в Shader.kt).

data class Nastrojki(
    val yarkost: Float = 0f,     // -0.5..0.5
    val kontrast: Float = 1f,    // 0.5..2
    val nasyshch: Float = 1f,    // 0..2
    val gamma: Float = 1f,       // 0.5..2.5
    val teni: Float = 0f,        // 0..1
    val rezkost: Float = 0f,     // 0..2
    val avtoNoch: Float = 0f,    // сила авто-ночи 0..1
    val polnyjDiapazon: Boolean = false,
    val lokalno: Float = 0f,     // местная авто-ночь 0..1 (uLokalno)
    val hdrPik: Float = 1000f,   // пик мастеринга HDR, нит, 100..10000 (uPik)
) {
    fun ogranichit(): Nastrojki = Nastrojki(
        ogr(yarkost, -0.5f, 0.5f, 0f), ogr(kontrast, 0.5f, 2f, 1f), ogr(nasyshch, 0f, 2f, 1f),
        ogr(gamma, 0.5f, 2.5f, 1f), ogr(teni, 0f, 1f, 0f), ogr(rezkost, 0f, 2f, 0f), ogr(avtoNoch, 0f, 1f, 0f),
        polnyjDiapazon, ogr(lokalno, 0f, 1f, 0f), ogr(hdrPik, 100f, 10000f, 1000f),
    )

    fun poluchit(imya: String): Float? = when (imya) {
        "yarkost" -> yarkost; "kontrast" -> kontrast; "nasyshch" -> nasyshch; "gamma" -> gamma
        "teni" -> teni; "rezkost" -> rezkost; "avtonoch" -> avtoNoch
        "polnyj" -> if (polnyjDiapazon) 1f else 0f
        "lokalno" -> lokalno; "hdrpik" -> hdrPik
        else -> null
    }

    private fun s(imya: String, v: Float): Nastrojki = when (imya) {
        "yarkost" -> copy(yarkost = v); "kontrast" -> copy(kontrast = v); "nasyshch" -> copy(nasyshch = v)
        "gamma" -> copy(gamma = v); "teni" -> copy(teni = v); "rezkost" -> copy(rezkost = v)
        "avtonoch" -> copy(avtoNoch = v); "lokalno" -> copy(lokalno = v); "hdrpik" -> copy(hdrPik = v)
        else -> copy(polnyjDiapazon = v >= 0.5f)
    }

    // znachenie: "N" (задать), "+N"/"-N" (сдвиг), "=N" / "=-N" (задать, можно отрицательное), "def" (по умолчанию).
    // Имена: yarkost kontrast nasyshch gamma teni rezkost avtonoch polnyj lokalno hdrpik. Плохой ввод -> null.
    fun izmenit(imya: String, znachenie: String): Nastrojki? {
        val im = imya.trim().lowercase(Locale.ROOT)
        val tek = poluchit(im) ?: return null
        val z = znachenie.trim().replace(',', '.').lowercase(Locale.ROOT)
        val nov = when {
            z == "def" -> Nastrojki().poluchit(im)!!
            z.startsWith("+") -> tek + (chislo(z.substring(1)) ?: return null)
            z.startsWith("-") -> tek - (chislo(z.substring(1)) ?: return null)
            z.startsWith("=") -> z.substring(1).trim().let { v ->
                if (v.startsWith("-")) -(chislo(v.substring(1)) ?: return null) else chislo(v) ?: return null
            }
            else -> chislo(z) ?: return null
        }
        return s(im, nov).ogranichit()
    }

    fun vStroku(): String = buildString {
        for (im in IMENA) append(im).append('=').append(String.format(Locale.ROOT, "%.4f", poluchit(im)!!)).append('\n')
    }

    companion object {
        val IMENA = listOf("yarkost", "kontrast", "nasyshch", "gamma", "teni", "rezkost", "avtonoch", "polnyj", "lokalno", "hdrpik")
        private val CHISLO = Regex("""\d{1,6}(\.\d*)?|\.\d+""")
        private fun chislo(s: String): Float? = if (CHISLO.matches(s)) s.toFloatOrNull() else null
        private fun ogr(v: Float, a: Float, b: Float, def: Float) = if (v.isNaN()) def else min(b, max(a, v))

        // Строки key=value; мусор и неизвестные ключи пропускаются.
        fun izStroki(s: String): Nastrojki {
            var n = Nastrojki()
            for (str in s.lineSequence()) {
                val i = str.indexOf('=')
                if (i <= 0) continue
                n = n.izmenit(str.substring(0, i), "=" + str.substring(i + 1)) ?: n
            }
            return n
        }
    }
}

val NABORY: Map<String, Nastrojki> = mapOf(
    "obychno" to Nastrojki(),
    "monitor" to Nastrojki(kontrast = 1.2f, nasyshch = 1.16f, teni = 0.25f, lokalno = 0.2f),
    // местная ночь берёт часть подъёма на себя: гамма 1.2 -> 1, тени 0.35 -> 0.2, контраст 1.1 — средняя та же,
    // что у прежнего «noch» без местной, а фигура в тени отделяется от фона заметнее (тест/примеры: svodka2.png)
    "noch" to Nastrojki(kontrast = 1.1f, nasyshch = 1.15f, gamma = 1.0f, teni = 0.2f, avtoNoch = 1f, lokalno = 0.6f),
    "yarko" to Nastrojki(yarkost = 0.02f, kontrast = 1.3f, nasyshch = 1.35f, gamma = 1.05f, teni = 0.2f),
    "rezko" to Nastrojki(kontrast = 1.2f, nasyshch = 1.16f, teni = 0.25f, rezkost = 1f),
    // PS5 HDR: тон-маппинг уже даёт полный SDR — контраст нейтральный, чуть насыщенности (сжатие светов её ест),
    // местная ночь умеренно, авто-ночь вполсилы
    "hdr" to Nastrojki(nasyshch = 1.05f, avtoNoch = 0.5f, lokalno = 0.3f),
    // как есть — для сравнения
    "kino" to Nastrojki(lokalno = 0f),
)

// Авто-ночь: 0 при средней яркости >= 0.25, плавно (smoothstep) до sila при <= 0.05.
fun avtoTeni(srednyaya: Float, sila: Float): Float {
    if (srednyaya.isNaN()) return 0f
    val x = min(1f, max(0f, (0.25f - srednyaya) / 0.20f))
    return sila * x * x * (3f - 2f * x)
}

// Экспоненциальное сглаживание: за tau проходит 63% пути. tau ≈ 1 с.
class Sglazhivanie(val tauSek: Float, nachalo: Float = 0f) {
    var znachenie = nachalo
        private set

    fun shag(znachenie: Float, dtSek: Float): Float {
        if (znachenie.isNaN()) return this.znachenie
        val a = if (tauSek <= 0f) 1f else 1f - exp(-max(0f, dtSek) / tauSek)
        this.znachenie += (znachenie - this.znachenie) * a
        return this.znachenie
    }

    fun sbros(v: Float) { znachenie = v }
}

// Средняя яркость Rec.709 по маленькому RGBA-снимку (glReadPixels), 0..1.
fun srednyayaYarkost(rgba: ByteArray, w: Int, h: Int): Float {
    val n = min(w * h, rgba.size / 4)
    if (n <= 0) return 0f
    var s = 0.0
    for (i in 0 until n) {
        val o = i * 4
        s += 0.2126 * (rgba[o].toInt() and 0xFF) + 0.7152 * (rgba[o + 1].toInt() and 0xFF) + 0.0722 * (rgba[o + 2].toInt() and 0xFF)
    }
    return (s / n / 255.0).toFloat()
}

// Выборка кадра в точке (u, v) (0..1, v сверху вниз) -> RGB 0..1; модель sampler-а шейдера для тестов.
fun interface Vyborka { fun rgb(u: Float, v: Float): FloatArray }

// Кадр P010 как его видит шейдер: 10-битные коды (0..1023), y — w×h, u/v — (w/2)×(h/2).
class P010Kadr(val w: Int, val h: Int, val y: IntArray, val u: IntArray, val v: IntArray)

object KartinkaCpu {
    const val VES_R = 0.2126f
    const val VES_G = 0.7152f
    const val VES_B = 0.0722f
    const val K_TENEJ = 3f
    const val PREDEL_REZKOSTI = 0.1f
    const val SUPER_POROG = 1.2f
    const val BELYJ_NIT = 203f          // белый SDR в нитах (BT.2408), uBelyj
    const val KARTA_W = 32
    const val KARTA_H = 18
    // местная ночь (как в Shader.GLAVNAYA)
    const val LOK_CEL = 0.3f
    const val LOK_MAX = 3f
    const val LOK_MIN = 0.8f
    const val LOK_RHO0 = 1.25f
    const val LOK_LN_RHO = 1.0986123f   // ln(3.75/1.25)
    const val LOK_SHOZH = 24f
    const val LOK_EPS = 0.02f
    // HDR (как в Shader.HDR)
    const val PQ_M1 = 0.1593017578125f
    const val PQ_M2 = 78.84375f
    const val PQ_C1 = 0.8359375f
    const val PQ_C2 = 18.8515625f
    const val PQ_C3 = 18.6875f
    const val TON_KOLENO = 0.8f
    const val BELENIE = 0.03f
    const val GAMMA_EKRANA = 2.2f
    // BT.2020 -> BT.709, линейный свет (строки)
    val M2020_709 = floatArrayOf(1.660491f, -0.587641f, -0.072850f, -0.124550f, 1.132900f, -0.008349f, -0.018151f, -0.100579f, 1.118730f)
    val M709_2020 = floatArrayOf(0.627404f, 0.329283f, 0.043313f, 0.069097f, 0.919540f, 0.011362f, 0.016391f, 0.088013f, 0.895595f)

    fun yarkost(r: Float, g: Float, b: Float) = VES_R * r + VES_G * g + VES_B * b

    // Подъём теней: x + t·3·x·(1−x)^4. Наклон в нуле 1+3t, максимум прибавки при x=0.2 (+0.25t),
    // при x≥0.9 прибавка < 0.0003; монотонна при t≤1 (минимум производной ≈0.35).
    fun teniKrivaya(x: Float, t: Float): Float {
        val o = 1f - x
        val o2 = o * o
        return x + t * K_TENEJ * x * o2 * o2
    }

    // Контраст: S-кривая через (0.5, 0.5) с наклоном k в середине: 0.5·(2x)^k ниже середины,
    // зеркально выше. 0 и 1 остаются на месте — в отличие от линейной (x−0.5)k+0.5 не давит тени в чёрное.
    fun kontrastKrivaya(x: Float, k: Float): Float =
        if (x < 0.5f) 0.5f * max(2f * x, 1e-7f).pow(k) else 1f - 0.5f * max(2f - 2f * x, 1e-7f).pow(k)

    // Конвейер без резкости и местной ночи. Вход — RGB 0..1.
    fun obrabotat(r: Float, g: Float, b: Float, n: Nastrojki, avtoTeni: Float): FloatArray {
        val c = floatArrayOf(kl(r + n.yarkost), kl(g + n.yarkost), kl(b + n.yarkost))
        for (i in 0..2) c[i] = kontrastKrivaya(c[i], n.kontrast)
        val l = yarkost(c[0], c[1], c[2])
        val t = min(1f, max(0f, n.teni + avtoTeni))
        val pg = 1f / n.gamma
        for (i in 0..2) {
            var x = l + (c[i] - l) * n.nasyshch
            x = min(1f, max(0f, x))
            x = max(x, 1e-7f).pow(pg)
            c[i] = teniKrivaya(x, t)
        }
        return c
    }

    // Весь кадр с резкостью и местной ночью (как шейдер при uSuper ≤ 1.2): rgb — w*h*3, 0..1; края — повтор
    // крайнего пикселя. karta — яркости 32×18 (строки сверху вниз); null — построить из кадра, как ведущий.
    fun obrabotatKadr(rgb: FloatArray, w: Int, h: Int, n: Nastrojki, avtoTeni: Float, karta: FloatArray? = null): FloatArray {
        val out = FloatArray(w * h * 3)
        val k = if (n.lokalno > 0f) karta ?: kartaKadra(rgb, w, h) else null
        fun ya(x: Int, y: Int): Float {
            val o = (min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))) * 3
            return yarkost(kl(rgb[o]), kl(rgb[o + 1]), kl(rgb[o + 2]))
        }
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            var r = kl(rgb[o]); var g = kl(rgb[o + 1]); var b = kl(rgb[o + 2])
            var d = 0f
            if (n.rezkost > 0f) {
                val sr = 0.25f * (ya(x + 1, y) + ya(x - 1, y) + ya(x, y + 1) + ya(x, y - 1))
                d = min(PREDEL_REZKOSTI, max(-PREDEL_REZKOSTI, n.rezkost * (yarkost(r, g, b) - sr)))
            }
            if (k != null) {
                val lc = lokalno(r, g, b, k, KARTA_W, KARTA_H, (x + 0.5f) / w, (y + 0.5f) / h, n.lokalno)
                r = lc[0]; g = lc[1]; b = lc[2]
            }
            val c = obrabotat(r, g, b, n, avtoTeni)
            for (i in 0..2) out[o + i] = kl(c[i] + d)
        }
        return out
    }

    // BT.709 YCbCr (байты) -> RGB 0..1, как yuvVRgb в шейдере.
    fun yuvVRgb(y: Int, u: Int, v: Int, polnyj: Boolean): FloatArray {
        var yy = y / 255f; var uu = u / 255f - 128f / 255f; var vv = v / 255f - 128f / 255f
        if (!polnyj) {
            yy = (yy - 16f / 255f) * (255f / 219f)
            uu *= 255f / 224f; vv *= 255f / 224f
        }
        return floatArrayOf(kl(yy + 1.5748f * vv), kl(yy - 0.18732f * uu - 0.46812f * vv), kl(yy + 1.8556f * uu))
    }

    // --- HDR ---

    // PQ (SMPTE ST 2084): код 0..1 -> нит 0..10000.
    fun pqVNity(e: Float): Float {
        val p = kl(e).pow(1f / PQ_M2)
        return 10000f * (max(p - PQ_C1, 0f) / (PQ_C2 - PQ_C3 * p)).pow(1f / PQ_M1)
    }

    // Обратная: нит -> код PQ (для сцен и тестов).
    fun nityVPq(nit: Float): Float {
        val y = (max(nit, 0f) / 10000f).pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * y) / (1f + PQ_C3 * y)).pow(PQ_M2)
    }

    // Тон: x = нит/белый; до колена 0.8 — 1:1, выше — расширенный Reinhard по остатку, xMax (= пик/белый) -> 1.
    fun tonKrivaya(x: Float, xMax: Float): Float {
        if (x <= TON_KOLENO) return x
        val bA = max((xMax - TON_KOLENO) / (1f - TON_KOLENO), 1f)
        val a = (x - TON_KOLENO) / (1f - TON_KOLENO)
        return min(TON_KOLENO + (1f - TON_KOLENO) * a * (1f + a / (bA * bA)) / (1f + a), 1f)
    }

    private fun matr(m: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b, m[3] * r + m[4] * g + m[5] * b, m[6] * r + m[7] * g + m[8] * b)

    // PQ R'G'B' BT.2020 (0..1) -> SDR BT.709, гамма 2.2 (0..1). Как hdrVSdr в шейдере.
    fun hdrVSdr(r: Float, g: Float, b: Float, pik: Float, belyj: Float = BELYJ_NIT): FloatArray {
        val l = matr(M2020_709, pqVNity(r) / belyj, pqVNity(g) / belyj, pqVNity(b) / belyj)
        val y = max(yarkost(l[0], l[1], l[2]), 0f)
        val mn = min(l[0], min(l[1], l[2]))
        if (mn < 0f) { val s = y / max(y - mn, 1e-6f); for (i in 0..2) l[i] = y + (l[i] - y) * s }
        val yt = tonKrivaya(y, pik / belyj)
        val k = yt / max(y, 1e-6f)
        for (i in 0..2) l[i] *= k
        val mx = max(l[0], max(l[1], l[2]))
        if (mx > 1f) { val q = BELENIE * (1f - 1f / mx); for (i in 0..2) l[i] = l[i] / mx * (1f - q) + q }
        for (i in 0..2) l[i] = kl(l[i]).pow(1f / GAMMA_EKRANA)
        return l
    }

    // 10-битные коды (дробные — после билинейки) -> RGB 0..1. hdr: PQ BT.2020 + тон-маппинг; иначе SDR BT.709.
    fun p010VRgb(y10: Float, u10: Float, v10: Float, n: Nastrojki, hdr: Boolean, belyj: Float = BELYJ_NIT): FloatArray {
        val y: Float; val u: Float; val v: Float
        if (n.polnyjDiapazon) { y = y10 / 1023f; u = (u10 - 512f) / 1023f; v = (v10 - 512f) / 1023f }
        else { y = (y10 - 64f) / 876f; u = (u10 - 512f) / 896f; v = (v10 - 512f) / 896f }
        if (hdr) return hdrVSdr(kl(y + 1.4746f * v), kl(y - 0.16455f * u - 0.57135f * v), kl(y + 1.8814f * u), n.hdrPik, belyj)
        return floatArrayOf(kl(y + 1.5748f * v), kl(y - 0.18732f * u - 0.46812f * v), kl(y + 1.8556f * u))
    }

    // Выборка P010 как в шейдере: билинейка по кодам (Y на w×h, цвет на w/2×h/2), края — повтор.
    fun p010Vyborka(k: P010Kadr, n: Nastrojki, hdr: Boolean, belyj: Float = BELYJ_NIT): Vyborka {
        val cw = k.w / 2; val ch = k.h / 2
        return Vyborka { uu, vv ->
            val y = bilin(k.y, k.w, k.h, uu, vv)
            p010VRgb(y, bilin(k.u, cw, ch, uu, vv), bilin(k.v, cw, ch, uu, vv), n, hdr, belyj)
        }
    }

    // Кадр P010 -> RGB w*h*3 (выборка в центрах пикселей) — то, что попадает в общий конвейер.
    fun p010VKadr(k: P010Kadr, n: Nastrojki, hdr: Boolean, belyj: Float = BELYJ_NIT): FloatArray {
        val vyb = p010Vyborka(k, n, hdr, belyj)
        val out = FloatArray(k.w * k.h * 3)
        for (y in 0 until k.h) for (x in 0 until k.w) vyb.rgb((x + 0.5f) / k.w, (y + 0.5f) / k.h).copyInto(out, (y * k.w + x) * 3)
        return out
    }

    private fun bilin(a: IntArray, w: Int, h: Int, u: Float, v: Float): Float {
        val qx = u * w - 0.5f; val qy = v * h - 0.5f
        val ix = floor(qx); val iy = floor(qy); val fx = qx - ix; val fy = qy - iy
        fun t(x: Int, y: Int) = a[min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))].toFloat()
        val x0 = ix.toInt(); val y0 = iy.toInt()
        val r0 = t(x0, y0) + (t(x0 + 1, y0) - t(x0, y0)) * fx
        val r1 = t(x0, y0 + 1) + (t(x0 + 1, y0 + 1) - t(x0, y0 + 1)) * fx
        return r0 + (r1 - r0) * fy
    }

    // --- выборки и уменьшение ---

    // GL_LINEAR + CLAMP_TO_EDGE (NV12/RGBA, карта у ведущего).
    fun bilinejno(rgb: FloatArray, w: Int, h: Int): Vyborka = Vyborka { u, v ->
        val qx = u * w - 0.5f; val qy = v * h - 0.5f
        val x0 = floor(qx).toInt(); val y0 = floor(qy).toInt(); val fx = qx - x0; val fy = qy - y0
        fun t(x: Int, y: Int, i: Int) = rgb[(min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))) * 3 + i]
        FloatArray(3) { i ->
            val r0 = t(x0, y0, i) + (t(x0 + 1, y0, i) - t(x0, y0, i)) * fx
            val r1 = t(x0, y0 + 1, i) + (t(x0 + 1, y0 + 1, i) - t(x0, y0 + 1, i)) * fx
            kl(r0 + (r1 - r0) * fy)
        }
    }

    // texelFetch по floor(uv·размер) (YUY2).
    fun tochno(rgb: FloatArray, w: Int, h: Int): Vyborka = Vyborka { u, v ->
        val x = min(w - 1, max(0, floor(u * w).toInt())); val y = min(h - 1, max(0, floor(v * h).toInt()))
        FloatArray(3) { kl(rgb[(y * w + x) * 3 + it]) }
    }

    // rgbSuper: sup ≤ 1.2 — одна выборка; иначе среднее 2×2 на ±0.25·sup текселя.
    fun vzyatSuper(vyb: Vyborka, u: Float, v: Float, shagU: Float, shagV: Float, sup: Float): FloatArray {
        if (sup <= SUPER_POROG) return vyb.rgb(u, v)
        val du = 0.25f * sup * shagU; val dv = 0.25f * sup * shagV
        val a = vyb.rgb(u - du, v - dv); val b = vyb.rgb(u + du, v - dv); val c = vyb.rgb(u - du, v + dv); val d = vyb.rgb(u + du, v + dv)
        return FloatArray(3) { 0.25f * (a[it] + b[it] + c[it] + d[it]) }
    }

    // Кадр wIn×hIn в окно wOut×hOut через vzyatSuper (sup — как считает ведущий: текселей на пиксель окна).
    fun umenshit(vyb: Vyborka, wIn: Int, hIn: Int, wOut: Int, hOut: Int, sup: Float = wIn.toFloat() / wOut): FloatArray {
        val out = FloatArray(wOut * hOut * 3)
        for (y in 0 until hOut) for (x in 0 until wOut)
            vzyatSuper(vyb, (x + 0.5f) / wOut, (y + 0.5f) / hOut, 1f / wIn, 1f / hIn, sup).copyInto(out, (y * wOut + x) * 3)
        return out
    }

    // --- местная ночь ---

    // Карта 32×18 как у ведущего: сырой кадр рисуется в FBO 32×18 с GL_LINEAR — каждая ячейка = ОДНА билинейная
    // выборка в центре ячейки (не среднее по блоку!), RGBA8 — квантование до байта. Возвращает яркости, сверху вниз.
    fun kartaKadra(rgb: FloatArray, w: Int, h: Int, kw: Int = KARTA_W, kh: Int = KARTA_H): FloatArray {
        val vyb = bilinejno(rgb, w, h)
        return FloatArray(kw * kh) { i ->
            val c = vyb.rgb((i % kw + 0.5f) / kw, (i / kw + 0.5f) / kh)
            yarkost(Math.round(c[0] * 255f) / 255f, Math.round(c[1] * 255f) / 255f, Math.round(c[2] * 255f) / 255f)
        }
    }

    // Местная ночь для пикселя (r,g,b) в точке (u,v); karta — яркости kw×kh, строки сверху вниз. См. Shader.GLAVNAYA.
    fun lokalno(r: Float, g: Float, b: Float, karta: FloatArray, kw: Int, kh: Int, u: Float, v: Float, sila: Float): FloatArray {
        if (sila <= 0f) return floatArrayOf(r, g, b)
        val l = yarkost(r, g, b)
        val qx = u * kw - 0.5f; val qy = v * kh - 0.5f
        val x0 = floor(qx).toInt(); val y0 = floor(qy).toInt(); val fx = qx - x0; val fy = qy - y0
        fun m(x: Int, y: Int) = karta[min(kh - 1, max(0, y)) * kw + min(kw - 1, max(0, x))]
        val m4 = floatArrayOf(m(x0, y0), m(x0 + 1, y0), m(x0, y0 + 1), m(x0 + 1, y0 + 1))
        val wb = floatArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)
        val ll = ln(l + LOK_EPS)
        var sw = 0f; var sm = 0f
        for (i in 0..3) {
            val dl = ln(m4[i] + LOK_EPS) - ll
            val w = wb[i] / (1f + LOK_SHOZH * dl * dl)
            sw += w; sm += w * m4[i]
        }
        val mm = sm / sw
        var gg = if (mm < LOK_CEL) min(LOK_CEL * (1f - mm) / (max(mm, 1e-3f) * (1f - LOK_CEL)), LOK_MAX)
                 else 1f - (1f - LOK_MIN) * smoothstep(0.5f, 0.9f, mm)
        gg = 1f + (gg - 1f) * sila
        val rho = max(l, 1e-4f) / max(mm, LOK_EPS)
        val w = 1f - min(1f, max(0f, ln(rho / LOK_RHO0) / LOK_LN_RHO))
        val bG = gg.pow(w)
        val lo = bG * l / (1f + (bG - 1f) * l)
        val mx = max(r, max(g, b))
        val k = min(lo / max(l, 1e-5f), 1f / max(mx, 1e-5f))
        return floatArrayOf(r * k, g * k, b * k)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = min(1f, max(0f, (x - a) / (b - a)))
        return t * t * (3f - 2f * t)
    }

    private fun kl(x: Float) = if (x.isNaN()) 0f else min(1f, max(0f, x))
}
