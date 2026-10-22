package com.questclaude.most3

import java.util.Locale
import kotlin.math.exp
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
) {
    fun ogranichit(): Nastrojki = Nastrojki(
        ogr(yarkost, -0.5f, 0.5f, 0f), ogr(kontrast, 0.5f, 2f, 1f), ogr(nasyshch, 0f, 2f, 1f),
        ogr(gamma, 0.5f, 2.5f, 1f), ogr(teni, 0f, 1f, 0f), ogr(rezkost, 0f, 2f, 0f), ogr(avtoNoch, 0f, 1f, 0f),
        polnyjDiapazon,
    )

    fun poluchit(imya: String): Float? = when (imya) {
        "yarkost" -> yarkost; "kontrast" -> kontrast; "nasyshch" -> nasyshch; "gamma" -> gamma
        "teni" -> teni; "rezkost" -> rezkost; "avtonoch" -> avtoNoch
        "polnyj" -> if (polnyjDiapazon) 1f else 0f
        else -> null
    }

    private fun s(imya: String, v: Float): Nastrojki = when (imya) {
        "yarkost" -> copy(yarkost = v); "kontrast" -> copy(kontrast = v); "nasyshch" -> copy(nasyshch = v)
        "gamma" -> copy(gamma = v); "teni" -> copy(teni = v); "rezkost" -> copy(rezkost = v)
        "avtonoch" -> copy(avtoNoch = v)
        else -> copy(polnyjDiapazon = v >= 0.5f)
    }

    // znachenie: "N" (задать), "+N"/"-N" (сдвиг), "=N" / "=-N" (задать, можно отрицательное), "def" (по умолчанию).
    // Имена: yarkost kontrast nasyshch gamma teni rezkost avtonoch polnyj. Плохой ввод -> null.
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
        val IMENA = listOf("yarkost", "kontrast", "nasyshch", "gamma", "teni", "rezkost", "avtonoch", "polnyj")
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
    "monitor" to Nastrojki(kontrast = 1.2f, nasyshch = 1.16f, teni = 0.25f),
    "noch" to Nastrojki(kontrast = 1.0f, nasyshch = 1.1f, gamma = 1.2f, teni = 0.35f, avtoNoch = 1f),
    "yarko" to Nastrojki(yarkost = 0.02f, kontrast = 1.3f, nasyshch = 1.35f, gamma = 1.05f, teni = 0.2f),
    "rezko" to Nastrojki(kontrast = 1.2f, nasyshch = 1.16f, teni = 0.25f, rezkost = 1f),
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

object KartinkaCpu {
    const val VES_R = 0.2126f
    const val VES_G = 0.7152f
    const val VES_B = 0.0722f
    const val K_TENEJ = 3f
    const val PREDEL_REZKOSTI = 0.1f

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

    // Конвейер без резкости. Вход — RGB 0..1.
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

    // Весь кадр с резкостью (как шейдер): rgb — w*h*3, 0..1; края — повтор крайнего пикселя.
    fun obrabotatKadr(rgb: FloatArray, w: Int, h: Int, n: Nastrojki, avtoTeni: Float): FloatArray {
        val out = FloatArray(w * h * 3)
        fun ya(x: Int, y: Int): Float {
            val o = (min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))) * 3
            return yarkost(kl(rgb[o]), kl(rgb[o + 1]), kl(rgb[o + 2]))
        }
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val r = kl(rgb[o]); val g = kl(rgb[o + 1]); val b = kl(rgb[o + 2])
            var d = 0f
            if (n.rezkost > 0f) {
                val sr = 0.25f * (ya(x + 1, y) + ya(x - 1, y) + ya(x, y + 1) + ya(x, y - 1))
                d = min(PREDEL_REZKOSTI, max(-PREDEL_REZKOSTI, n.rezkost * (yarkost(r, g, b) - sr)))
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

    private fun kl(x: Float) = if (x.isNaN()) 0f else min(1f, max(0f, x))
}
