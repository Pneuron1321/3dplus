package com.questclaude.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Маленькая строка доната (2.8.2, Александр 01.11, пункт 5 «как у ShowSpeed»): «Rex donated $5!» — небольшая
 * пилюля слева вверху, под карточкой топа: въезжает слева за 0,35 с, стоит [DERZHAT_MS], гаснет за 0,5 с; несколько
 * подряд — по очереди. Игру не закрывает. Со своим звуком [ZVUK] (слышат эфир и шлем). Слой развилки "dn". Без
 * эфира — событие пропускается. Голос и субтитр доната — как были (donaty), после звука. Кто шлёт: donaty —
 * GET /donat?kto=НИК&skolko=$5 (биты — «500 bits» → «cheered»).
 */
class DonatStroka(private val ctx: Context, private val razvilka: () -> Razvilka?) {
    private class Sobytie(val kto: String, val skolko: String)

    private val k = VizorKist(ctx)
    private val ochered = LinkedBlockingQueue<Sobytie>(20)
    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)
    private val imya = k.kist(k.zagolovki, Vizor.DONAT_IMYA, Vizor.LED)
    private val tekst = k.kist(k.zagolovki, Vizor.DONAT_TEKST, Vizor.ZOLOTO)
    private val znak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.ZOLOTO }
    private val znakT = k.kist(k.zagolovki, 17f, Vizor.GRAFIT).apply { textAlign = Paint.Align.CENTER }
    @Volatile var pokazano = 0L; private set

    init { thread(name = "donat-stroka", isDaemon = true) { zhit() } }

    /** Положить в очередь; ответ — JSON для /donat. */
    fun pokazat(kto: String, skolko: String): String {
        val n = kto.replace(Regex("[^\\p{L}\\p{N}_.-]"), "").take(25).ifEmpty { "Someone" }
        val s = skolko.replace(Regex("[^\\p{L}\\p{N}\\p{Sc} .,]"), "").trim().take(16)
        if (s.isEmpty()) return "{\"ok\":false,\"oshibka\":\"skolko?\"}"
        val ok = ochered.offer(Sobytie(n, s))
        return "{\"ok\":$ok,\"v_ocheredi\":${ochered.size},\"efir\":${razvilka() != null}}"
    }

    private fun zhit() {
        while (true) {
            val s = try { ochered.poll(1, TimeUnit.SECONDS) } catch (e: InterruptedException) { return } ?: continue
            try { pokazatOdno(s) } catch (e: Exception) { }
        }
    }

    private fun pokazatOdno(s: Sobytie) {
        val rz = razvilka() ?: return
        Opoveshchenie.zvuk(ctx, ZVUK)
        val t0 = System.currentTimeMillis()
        val vsego = VYEZD_MS + DERZHAT_MS + UHOD_MS
        while (true) {
            val t = System.currentTimeMillis() - t0
            if (t >= vsego) break
            val (sdvig, a) = when {
                t < VYEZD_MS -> { val p = t / VYEZD_MS.toFloat(); val e = 1 - (1 - p) * (1 - p); (1 - e) * -W to e }
                t < VYEZD_MS + DERZHAT_MS -> 0f to 1f
                else -> 0f to (1 - (t - VYEZD_MS - DERZHAT_MS) / UHOD_MS.toFloat()).coerceIn(0f, 1f)
            }
            narisovat(s.kto, s.skolko, sdvig, a)
            rz.sloy("dn", bmp)
            Thread.sleep(if (t in VYEZD_MS..(VYEZD_MS + DERZHAT_MS)) 250L else 40L)   // стоит — реже
        }
        rz.sloy("dn", null)
        pokazano++
    }

    private fun narisovat(kto: String, skolko: String, sdvig: Float, a: Float) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val hvost = fraza(skolko)
        // ширина — по тексту: пилюля не длиннее надписи
        val x0 = 8f + 2 * R + 10f
        val w = minOf(W - 4f, x0 + imya.measureText(kto) + tekst.measureText(hvost) + 20f)
        val save = c.saveLayerAlpha(0f, 0f, W.toFloat(), H.toFloat(), (a * 255).toInt())
        c.translate(sdvig, 0f)
        val r = RectF(2f, 3f, w, H - 7f)
        k.karta(c, r, Vizor.ZOLOTO, radius = r.height() / 2)
        val cy = r.centerY()
        c.drawCircle(r.left + 8f + R, cy, R, znak)
        c.drawText("$", r.left + 7f + R, cy + 6f, znakT)
        val n = k.vlezet(imya, kto, W - x0 - tekst.measureText(hvost) - 24f)
        c.drawText(n, x0, cy + 8f, imya)
        c.drawText(hvost, x0 + imya.measureText(n), cy + 8f, tekst)
        c.restoreToCount(save)
    }

    companion object {
        /** Звук доната (Александр 01.11, свой — из его ролика, ~1 с); громкость — как у звука фолловера. */
        const val ZVUK = "zvuki/donat.mp3"
        const val W = 560
        const val H = 48
        private const val R = 12f
        const val VYEZD_MS = 350L
        const val DERZHAT_MS = 6000L
        const val UHOD_MS = 500L

        /** « donated $5!» или « cheered 500 bits!» (биты). */
        fun fraza(skolko: String): String =
            if (skolko.lowercase().endsWith("bits")) " cheered $skolko!" else " donated $skolko!"
    }
}
