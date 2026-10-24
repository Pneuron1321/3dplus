package com.questclaude.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Шрифты и кисти стиля «Визор» (Vizor): Unbounded (заголовки, 800) и Manrope (текст, 600 и 800) из assets/shrifty —
 * вариативные TTF, вес задаём осью «wght» (Typeface.Builder); не вышло — тот же файл как есть, потом системный жирный.
 * Карточка — графит 80 % с тонким золотым кантом и тенью; тень — кэшированный битмап по размеру карточки (размытие
 * считается раз на размер, не на каждый кадр).
 */
class VizorKist(ctx: Context) {
    val zagolovki: Typeface = shrift(ctx, Vizor.SHRIFT_ZAGOLOVKOV, Vizor.VES_ZAGOLOVKOV, Typeface.DEFAULT_BOLD)
    val tekst: Typeface = shrift(ctx, Vizor.SHRIFT_TEKSTA, Vizor.VES_TEKSTA, Typeface.DEFAULT)
    val tekstZhirno: Typeface = shrift(ctx, Vizor.SHRIFT_TEKSTA, Vizor.VES_TEKSTA_ZHIRNO, Typeface.DEFAULT_BOLD)

    /** Кисть текста: шрифт, кегль, цвет. Отдельная на каждое место — без гонок потоков. */
    fun kist(tf: Typeface, sz: Float, cvet: Int = Vizor.LED) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = sz; color = cvet
    }

    /** Метка: Unbounded заглавными, разрядка 0,08 em, золотом. */
    fun metka(sz: Float = Vizor.METKA, cvet: Int = Vizor.ZOLOTO) = kist(zagolovki, sz, cvet).apply { letterSpacing = 0.08f }

    private val pod = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.PODLOZHKA }
    private val kant = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Vizor.KANT_SHIR }
    private val tenKist = Paint(Paint.FILTER_BITMAP_FLAG)
    private val teni = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?): Boolean = (size > 24).also { if (it) e?.value?.recycle() }
    }

    /** Карточка: тень (кэш), подложка графит 80 %, кант (по умолчанию — золото 35 %). */
    fun karta(c: Canvas, r: RectF, cvetKanta: Int = Vizor.KANT, radius: Float = Vizor.RADIUS, ten: Boolean = true) {
        if (ten) ten(r.width().toInt(), r.height().toInt(), radius)?.let {
            c.drawBitmap(it, r.left - Vizor.TEN * 2, r.top - Vizor.TEN * 2 + Vizor.TEN_DY, tenKist)
        }
        c.drawRoundRect(r, radius, radius, pod)
        if (cvetKanta != 0) {
            kant.color = cvetKanta
            val p = Vizor.KANT_SHIR / 2
            c.drawRoundRect(RectF(r.left + p, r.top + p, r.right - p, r.bottom - p), radius, radius, kant)
        }
    }

    /** Битмап тени карточки w×h: чёрный скруглённый прямоугольник, размытый на TEN; поля — 2·TEN с каждой стороны. */
    @Synchronized private fun ten(w: Int, h: Int, radius: Float): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val kl = (w.toLong() shl 32) or (h.toLong() shl 8) or radius.toLong()
        teni[kl]?.takeIf { !it.isRecycled }?.let { return it }
        val p = (Vizor.TEN * 2).toInt()
        val b = Bitmap.createBitmap(w + 2 * p, h + 2 * p, Bitmap.Config.ARGB_8888)
        val k = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Vizor.TEN_A shl 24
            maskFilter = BlurMaskFilter(Vizor.TEN, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(b).drawRoundRect(RectF(p.toFloat(), p.toFloat(), (p + w).toFloat(), (p + h).toFloat()), radius, radius, k)
        teni[kl] = b
        return b
    }

    /** Полоса цели: дорожка лёд 12 %, заполнение градиентом визор → золото и мягкое свечение. */
    fun polosa(c: Canvas, r: RectF, dolya: Float) {
        val rad = r.height() / 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FEEF2F7 }
        c.drawRoundRect(r, rad, rad, p)
        if (dolya <= 0f) return
        val x1 = r.left + maxOf(r.height(), r.width() * dolya.coerceAtMost(1f))
        p.shader = LinearGradient(r.left, 0f, r.right, 0f, Vizor.VIZOR, Vizor.ZOLOTO, Shader.TileMode.CLAMP)
        for ((d, a) in listOf(6f to 40, 3f to 70)) {                       // свечение — два мягких кольца
            p.alpha = a
            c.drawRoundRect(RectF(r.left - d, r.top - d, x1 + d, r.bottom + d), rad + d, rad + d, p)
        }
        p.alpha = 255
        c.drawRoundRect(RectF(r.left, r.top, x1, r.bottom), rad, rad, p)
        p.shader = null
    }

    /** Скоба визора: два плеча из угла (x, y) в стороны dx, dy (±1), со свечением золота. */
    fun skoba(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, d: Float = Vizor.SKOBA, t: Float = Vizor.SKOBA_T) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.ZOLOTO; style = Paint.Style.FILL }
        fun plecho(a: Float, sz: Float) {
            val g = RectF(minOf(x, x + dx * d), minOf(y, y + dy * t), maxOf(x, x + dx * d), maxOf(y, y + dy * t))
            val v = RectF(minOf(x, x + dx * t), minOf(y, y + dy * d), maxOf(x, x + dx * t), maxOf(y, y + dy * d))
            p.alpha = (a * 255).toInt()
            c.drawRoundRect(RectF(g.left - sz, g.top - sz, g.right + sz, g.bottom + sz), sz + 1, sz + 1, p)
            c.drawRoundRect(RectF(v.left - sz, v.top - sz, v.right + sz, v.bottom + sz), sz + 1, sz + 1, p)
        }
        for ((a, sz) in listOf(0.07f to Vizor.SKOBA_SVET, 0.12f to Vizor.SKOBA_SVET * 0.6f, 0.2f to Vizor.SKOBA_SVET * 0.3f)) plecho(a, sz)
        plecho(1f, 0f)
    }

    /** Строка, обрезанная по ширине с «…». */
    fun vlezet(p: Paint, t: String, shir: Float): String {
        if (p.measureText(t) <= shir) return t
        var s = t
        while (s.isNotEmpty() && p.measureText("$s…") > shir) s = s.dropLast(1)
        return s.trimEnd() + "…"
    }

    /** Перенос по словам в ширину; больше n строк — последняя с «…»; слишком длинное слово — по буквам. */
    fun perenos(p: Paint, t: String, shir: Float, n: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (slovo in t.split(' ').filter { it.isNotEmpty() }) {
            var sl = slovo
            while (p.measureText(sl) > shir && sl.length > 1) {
                var k = sl.length - 1
                while (k > 1 && p.measureText(sl.substring(0, k)) > shir) k--
                if (cur.isNotEmpty()) { out += cur; cur = "" }
                out += sl.substring(0, k)
                sl = sl.substring(k)
            }
            val probe = if (cur.isEmpty()) sl else "$cur $sl"
            if (p.measureText(probe) <= shir || cur.isEmpty()) cur = probe else { out += cur; cur = sl }
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size <= n) return out
        return out.take(n - 1) + vlezet(p, out[n - 1] + " " + out[n], shir).let { if (it.endsWith("…")) it else "$it…" }
    }

    companion object {
        /**
         * Вариативный TTF из assets с весом [ves] (ось wght); Builder не смог — тот же файл как есть (вес по
         * умолчанию), файла нет — [zapas].
         */
        fun shrift(ctx: Context, put: String, ves: Int, zapas: Typeface): Typeface {
            try {
                Typeface.Builder(ctx.assets, put).setFontVariationSettings("'wght' $ves").setWeight(ves).build()?.let { return it }
            } catch (e: Exception) { }
            return try { Typeface.createFromAsset(ctx.assets, put) } catch (e: Exception) { zapas }
        }
    }
}
