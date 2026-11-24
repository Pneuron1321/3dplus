package com.questclaude.bridge

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Шторка окна камеры (2.8.13, Александр 24.11, скетч от руки: у каждого края по паре стрелок — внутрь и наружу).
 * «◀▶ ОКНО КАМЕРЫ» — открывает и закрывает. Внутри:
 *  1) края — крест из четырёх пар: стрелка ВНУТРЬ окна срезает свой край на 5 %, НАРУЖУ — возвращает 5 %
 *     (Oformlenie.fonUpr l/v/p/n, до 45 %); срез работает и без вырезанного фона, окно у края кадра само уходит
 *     за край на срезанное (Okno.sdvigObrezki) — видимая картинка остаётся вплотную;
 *  2) место — ◀ ▲ ▼ ▶ двигают всё окно на 2 % кадра (как `kamera sdvin`, окно становится «своим»), ⟲ — на место
 *     (угол справа внизу).
 */
class OknoShtorka(private val ctx: Context, private val soobshchit: (String) -> Unit) {
    private var otkryta = false
    private val zagolovok = Button(ctx).apply { textSize = 20f; setOnClickListener { otkryt(!otkryta) } }
    private val kraya = TextView(ctx).apply { textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }

    /** Кнопка-стрелка: квадратная, крупная. */
    private fun strelka(t: String, d: () -> Unit) = Button(ctx).apply {
        text = t; textSize = 24f; minWidth = 0; minimumWidth = 0
        setOnClickListener { d() }
    }

    private fun lp(w: Int = 120) = LinearLayout.LayoutParams(w, 120).apply { setMargins(4, 4, 4, 4) }

    private fun ryad(vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        v.forEach { addView(it, if (it is TextView && it !is Button) LinearLayout.LayoutParams(440, LinearLayout.LayoutParams.WRAP_CONTENT) else lp()) }
    }

    private fun pusto() = View(ctx)

    /** Сторона i (0 Л, 1 В, 2 П, 3 Н): +5 — срезать, −5 — вернуть. */
    private fun kray(i: Int, shag: Int) {
        val bylo = Math.round(Oformlenie.fonObrez[i] * 100)
        val stalo = (bylo + shag).coerceIn(0, Math.round(Oformlenie.OBREZ_MAKS * 100))
        Oformlenie.fonUpr(mapOf(listOf("l", "v", "p", "n")[i] to stalo.toString()))
        narisovat()
    }

    private fun sdvin(kuda: String) {
        val k = Hub.kamera ?: return soobshchit("Окно камеры: сначала «Начать захват» (или «ВСЁ СРАЗУ»).")
        k.vid(mapOf("sdvin" to "$kuda:$SHAG_SDVIGA"))
        narisovat()
    }

    private fun domoy() {
        val k = Hub.kamera ?: return soobshchit("Окно камеры: сначала «Начать захват» (или «ВСЁ СРАЗУ»).")
        k.vid(mapOf("v" to "ugol", "gde" to "pn"))
        narisovat()
    }

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(10, 10, 10, 20)
        setBackgroundColor(0x33FFFFFF)
        addView(TextView(ctx).apply {
            text = "Края окна: стрелка внутрь — срезать 5 %, наружу — вернуть 5 %"; textSize = 15f
        })
        // как на скетче: у каждого края пара стрелок, внешняя — «вернуть», внутренняя — «срезать»
        addView(ryad(pusto(), strelka("▲") { kray(1, -5) }, pusto()))
        addView(ryad(pusto(), strelka("▼") { kray(1, +5) }, pusto()))
        addView(ryad(strelka("◀") { kray(0, -5) }, strelka("▶") { kray(0, +5) }, kraya,
            strelka("◀") { kray(2, +5) }, strelka("▶") { kray(2, -5) }))
        addView(ryad(pusto(), strelka("▲") { kray(3, +5) }, pusto()))
        addView(ryad(pusto(), strelka("▼") { kray(3, -5) }, pusto()))
        addView(TextView(ctx).apply { text = "Сдвинуть всё окно (на $SHAG_SDVIGA % кадра); ⟲ — на место"; textSize = 15f; setPadding(0, 20, 0, 0) })
        addView(ryad(pusto(), strelka("▲") { sdvin("vverh") }, pusto()))
        addView(ryad(strelka("◀") { sdvin("vlevo") }, strelka("⟲") { domoy() }, strelka("▶") { sdvin("vpravo") }))
        addView(ryad(pusto(), strelka("▼") { sdvin("vniz") }, pusto()))
    }

    fun vstavit(kuda: LinearLayout) {
        kuda.addView(zagolovok)
        kuda.addView(panel)
        narisovat()
    }

    private fun otkryt(da: Boolean) {
        otkryta = da
        panel.visibility = if (da) View.VISIBLE else View.GONE
        narisovat()
    }

    fun narisovat() {
        val z = Oformlenie.fonObrez
        fun p(i: Int) = Math.round(z[i] * 100)
        zagolovok.text = "◀▶ ОКНО КАМЕРЫ: края и место" + (if (otkryta) " ▾" else " ▸")
        val gde = try { Hub.kamera?.status()?.let { JSONObject(it).optJSONObject("kraya") } } catch (e: Exception) { null }
        kraya.text = "срезано: слева ${p(0)} %, сверху ${p(1)} %,\nсправа ${p(2)} %, снизу ${p(3)} %" +
            (gde?.let { "\nот краёв кадра: слева ${it.optDouble("sleva")} %, снизу ${it.optDouble("snizu")} %" } ?: "")
    }

    companion object {
        const val SHAG_SDVIGA = 2
    }
}
