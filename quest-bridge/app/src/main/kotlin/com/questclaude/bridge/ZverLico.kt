package com.questclaude.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.sin

/**
 * Лицо «Плюс-зверь» в эфире (vid lico zver): сам рисунок Александра (зелёный громила, HEAD DETAIL) — живой.
 * zver-lico.png — голова без нижней челюсти (под ней — тёмная глотка), zver-chelyust.png — нижняя челюсть
 * отдельно; обе 256×256 в тех же точках, что лицо Плюса (подбородок на 221 — встаёт на плечи).
 * Речь — челюсть ходит, слушает — глаза разгораются, думает — пасть прикрыта, моргает; гребень колышется полосками.
 */
class ZverLico(private val golova: Bitmap?, private val chelyust: Bitmap?) {
    val gotov get() = golova != null && chelyust != null

    private val kist = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val svet = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veko = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(62, 100, 32) }
    private val resnica = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 42, 14) }
    private val src = Rect()
    private val dst = RectF()

    fun risovat(c: Canvas, r: RectF, sost: String, now: Long, morgaet: Boolean, alpha: Int = 255) {
        val g = golova ?: return
        val ch = chelyust ?: return
        val k = r.width() / 256f
        kist.alpha = alpha
        // голова: гребень (выше CHELKA) — полосками со сдвигом, ниже — целиком
        val sx = g.width / 256f
        val sy = g.height / 256f
        val n = POLOS
        for (i in 0 until n) {
            val y0 = CHELKA * i / n
            val y1 = CHELKA * (i + 1) / n
            val dx = ZverMimika.kolyhanie((y0 + y1) / 2f, now) * k
            src.set(0, (y0 * sy).toInt(), g.width, (y1 * sy).toInt() + 1)
            dst.set(r.left + dx, r.top + y0 * k, r.right + dx, r.top + (y1 + 1f / sy) * k)
            c.drawBitmap(g, src, dst, kist)
        }
        src.set(0, (CHELKA * sy).toInt(), g.width, g.height)
        dst.set(r.left, r.top + CHELKA * k, r.right, r.bottom)
        c.drawBitmap(g, src, dst, kist)
        // нижняя челюсть
        val dy = ZverMimika.chelyust(sost, now) * k
        dst.set(r.left, r.top + dy, r.right, r.bottom + dy)
        c.drawBitmap(ch, null, dst, kist)
        // глаза: свечение, веки при моргании (и прищур, когда спит)
        val a = (ZverMimika.svechenie(sost, now) * alpha / 255)
        for ((ex, ugol) in listOf(GLAZ_L to UGOL, GLAZ_R to -UGOL)) {
            val x = r.left + ex * k
            val y = r.top + GLAZ_Y * k
            if (a > 0) {
                svet.shader = RadialGradient(x, y, 14f * k, Color.argb(a, 255, 170, 40), Color.argb(0, 255, 90, 0), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, 14f * k, svet)
            }
            if (morgaet || sost == "spit") {
                c.save()
                c.rotate(ugol, x, y)
                c.drawRoundRect(RectF(x - 9f * k, y - 5f * k, x + 9f * k, y + 5f * k), 5f * k, 5f * k, veko)
                c.drawRect(x - 9f * k, y + 0.5f * k, x + 9f * k, y + 2f * k, resnica)
                c.restore()
            }
        }
    }

    companion object {
        /** Места в точках картинки 256×256 (замер вырезки с листа). */
        const val GLAZ_L = 98.1f
        const val GLAZ_R = 156.9f
        const val GLAZ_Y = 122.3f
        const val UGOL = 15f                                       // злой прищур: внутренние углы ниже
        const val CHELKA = 95f                                     // выше — гребень, он колышется
        const val POLOS = 14
        /** Рисунок уже головы Плюса — в эфире крупнее (от подбородка, плечи на месте). */
        const val MASSHTAB = 1.12f
    }
}

/** Чистая мимика зверя (без android): сдвиги в точках картинки 256. */
object ZverMimika {
    /** Сдвиг нижней челюсти вниз (минус — прикрыть пасть). */
    fun chelyust(sost: String, now: Long): Float = when (sost) {
        "govorit" -> (if (sin(now / 90.0) > 0) 7f else 1.5f) + sin(now / 47.0).toFloat() * 1.5f
        "slushaet" -> 2f + sin(now / 400.0).toFloat()
        "dumaet" -> -2.5f
        "spit" -> -4f
        else -> sin(now / 1800.0).toFloat() * 0.8f                 // дышит
    }

    /** Яркость свечения глаз 0…255. */
    fun svechenie(sost: String, now: Long): Int = when (sost) {
        "slushaet" -> (150 + 100 * sin(now / 380.0)).toInt()
        "govorit" -> (175 + 60 * sin(now / 120.0)).toInt()
        "spit" -> 0
        else -> (90 + 30 * sin(now / 1500.0)).toInt()
    }.coerceIn(0, 255)

    /** Сдвиг полоски гребня по x: у макушки сильнее, к чёлке — ноль. */
    fun kolyhanie(y: Float, now: Long): Float {
        val w = max(0f, (ZverLico.CHELKA - y) / ZverLico.CHELKA)
        return 2.4f * w * w * sin(now / 700.0 + y * 0.08).toFloat()
    }
}
