package com.questclaude.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Оповещение в эфире (2.7.8, Александр 26.10: «реакция на нового фоловера»): плашка сверху по центру кадра —
 * «NEW FOLLOWER» и ник, в стиле визора (графит, золотой кант, фиолетовый значок). Въезжает сверху за 0,4 с,
 * стоит [DERZHAT_MS], уходит за 0,5 с; несколько подряд — по очереди. Слой развилки "al". Без эфира — некуда
 * рисовать, событие пропускается. Кто шлёт: donaty (фоловеры StreamElements, подписки и рейды из чата) —
 * GET /opoveshchenie?vid=follow|sub|raid&kto=НИК.
 */
class Opoveshchenie(ctx: Context, private val razvilka: () -> Razvilka?) {
    private class Sobytie(val vid: String, val kto: String)

    private val k = VizorKist(ctx)
    private val ochered = LinkedBlockingQueue<Sobytie>(20)
    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)
    private val metka = k.metka(22f)
    private val imya = k.kist(k.zagolovki, 40f, Vizor.LED)
    private val znak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.FIOLET }
    private val znakT = k.kist(k.zagolovki, 34f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
    @Volatile var pokazano = 0L; private set

    init { thread(name = "opoveshchenie", isDaemon = true) { zhit() } }

    /** Положить в очередь; ответ — JSON для /opoveshchenie. */
    fun pokazat(vid: String, kto: String): String {
        val n = kto.replace(Regex("[^\\p{L}\\p{N}_.-]"), "").take(25)
        if (n.isEmpty()) return "{\"ok\":false,\"oshibka\":\"кто?\"}"
        if (vid !in METKI) return "{\"ok\":false,\"oshibka\":${FrameServer.json("vid: " + METKI.keys.joinToString("|"))}}"
        val ok = ochered.offer(Sobytie(vid, n))
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
        val t0 = System.currentTimeMillis()
        val vsego = VYEZD_MS + DERZHAT_MS + UHOD_MS
        while (true) {
            val t = System.currentTimeMillis() - t0
            if (t >= vsego) break
            val (sdvig, a) = when {
                t < VYEZD_MS -> { val p = t / VYEZD_MS.toFloat(); val e = 1 - (1 - p) * (1 - p); (1 - e) * -H to e }
                t < VYEZD_MS + DERZHAT_MS -> 0f to 1f
                else -> 0f to (1 - (t - VYEZD_MS - DERZHAT_MS) / UHOD_MS.toFloat()).coerceIn(0f, 1f)
            }
            narisovat(s, sdvig, a)
            rz.sloy("al", bmp)
            Thread.sleep(if (t in VYEZD_MS..(VYEZD_MS + DERZHAT_MS)) 250L else 40L)   // стоит — реже
        }
        rz.sloy("al", null)
        pokazano++
    }

    private fun narisovat(s: Sobytie, sdvig: Float, a: Float) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val save = c.saveLayerAlpha(0f, 0f, W.toFloat(), H.toFloat(), (a * 255).toInt())
        c.translate(0f, sdvig)
        val r = RectF(14f, 10f, W - 14f, H - 18f)
        k.karta(c, r, Vizor.ZOLOTO)
        val cy = r.centerY()
        val kr = 32f
        c.drawCircle(r.left + 22f + kr, cy, kr, znak)
        c.drawText(ZNAKI[s.vid] ?: "+", r.left + 22f + kr, cy + 12f, znakT)
        val x = r.left + 22f + 2 * kr + 22f
        c.drawText(METKI[s.vid] ?: "", x, cy - 10f, metka)
        var n = s.kto
        while (n.length > 1 && imya.measureText(n) > r.right - x - 20f) n = n.dropLast(1)
        if (n.length < s.kto.length) n = n.dropLast(1) + "…"
        c.drawText(n, x, cy + 34f, imya)
        c.restoreToCount(save)
    }

    companion object {
        const val W = 720
        const val H = 130
        const val VYEZD_MS = 400L
        const val DERZHAT_MS = 5500L
        const val UHOD_MS = 500L
        /** Сверху по центру, верх плашки — столько точек 1080p от верха кадра (ниже скоб визора). */
        const val VERH = 44f
        val METKI = mapOf("follow" to "NEW FOLLOWER", "sub" to "NEW SUBSCRIBER", "raid" to "RAID")
        private val ZNAKI = mapOf("follow" to "♥", "sub" to "★", "raid" to "»")
    }
}
