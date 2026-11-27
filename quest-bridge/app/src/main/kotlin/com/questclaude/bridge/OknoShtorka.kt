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
 * «◀▶ ОКНО КАМЕРЫ» — открывает и закрывает. Внутри два одинаковых креста из кнопок одного размера:
 *  1) «Обрезать края» — как крест «сдвинуть», только у каждого края пара стрелок (2.8.16, Александр 25.11: «стрелочка не
 *     одинарная, а 2»): сверху ▲ над ▼, снизу ▲ над ▼, слева ◀ ▶, справа ◀ ▶. Стрелка — куда сдвинется этот край:
 *     внутрь окна — срезать 5 %, наружу — вернуть 5 % (Oformlenie.fonUpr l/v/p/n, до 45 %). В середине — «ПОЛНЫЙ КАДР»:
 *     все края назад. Срез работает и без вырезанного фона; окно у края кадра само уходит за край на срезанное
 *     (Okno.sdvigObrezki) — видимая картинка остаётся вплотную. Сколько срезано — строкой под крестом (в 2.8.13 эта
 *     строка стояла в середине креста и разносила стрелки — «белиберда»);
 *  2) «Сдвинуть всё окно» — ◀ ▲ ▼ ▶ на 2 % кадра (как `kamera sdvin`, окно становится «своим»), ⟲ — на место
 *     (угол справа внизу).
 */
class OknoShtorka(private val ctx: Context, private val soobshchit: (String) -> Unit) {
    private var otkryta = false
    private val zagolovok = Button(ctx).apply { textSize = 20f; setOnClickListener { otkryt(!otkryta) } }
    private val kraya = TextView(ctx).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, 6, 0, 0) }

    /** Кнопка-стрелка: квадратная, крупная. */
    private fun strelka(t: String, d: () -> Unit) = Button(ctx).apply {
        text = t; textSize = 24f; minWidth = 0; minimumWidth = 0
        setOnClickListener { d() }
    }

    /** Кнопка в середине креста: слова в две строки, без полей, чтобы влезли в ту же клетку. */
    private fun seredina(t: String, d: () -> Unit) = Button(ctx).apply {
        text = t; textSize = 12f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(2, 2, 2, 2); maxLines = 2
        setOnClickListener { d() }
    }

    private fun lp() = LinearLayout.LayoutParams(KLETKA, KLETKA).apply { setMargins(4, 4, 4, 4) }

    /** Крест из клеток одного размера: ряды сверху вниз, null — пустая клетка. */
    private fun krest(vararg ryady: List<View?>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        ryady.forEach { r ->
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                r.forEach { addView(it ?: View(ctx), lp()) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun zagolovokKresta(t: String, sverhu: Int) = TextView(ctx).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, sverhu, 0, 4)
    }

    /** Сторона i (0 Л, 1 В, 2 П, 3 Н): +5 — срезать, −5 — вернуть. */
    private fun kray(i: Int, shag: Int) {
        val bylo = Math.round(Oformlenie.fonObrez[i] * 100)
        val stalo = (bylo + shag).coerceIn(0, Math.round(Oformlenie.OBREZ_MAKS * 100))
        Oformlenie.fonUpr(mapOf(listOf("l", "v", "p", "n")[i] to stalo.toString()))
        narisovat()
    }

    /** Середина креста краёв: вся картинка камеры — срез краёв 0 и своему окну форма картинки (Kamera.vid polnyj). */
    private fun polnyj() {
        val k = Hub.kamera
        if (k != null) k.vid(mapOf("polnyj" to "1")) else Oformlenie.fonUpr(mapOf("obrez" to "0,0,0,0"))
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
        // как на скетче: крест, у каждого края пара стрелок; стрелка — куда сдвинется край (внутрь — срезать)
        addView(zagolovokKresta("ОБРЕЗАТЬ КРАЯ — стрелка двигает свой край на 5 %", 0))
        addView(krest(
            listOf(null, null, strelka("▲") { kray(1, -5) }, null, null),
            listOf(null, null, strelka("▼") { kray(1, +5) }, null, null),
            listOf(strelka("◀") { kray(0, -5) }, strelka("▶") { kray(0, +5) }, seredina("ПОЛНЫЙ\nКАДР") { polnyj() },
                strelka("◀") { kray(2, +5) }, strelka("▶") { kray(2, -5) }),
            listOf(null, null, strelka("▲") { kray(3, +5) }, null, null),
            listOf(null, null, strelka("▼") { kray(3, -5) }, null, null)))
        addView(kraya)
        addView(zagolovokKresta("СДВИНУТЬ ВСЁ ОКНО — на $SHAG_SDVIGA % кадра, ⟲ — на место", 24))
        addView(krest(
            listOf(null, strelka("▲") { sdvin("vverh") }, null),
            listOf(strelka("◀") { sdvin("vlevo") }, strelka("⟲") { domoy() }, strelka("▶") { sdvin("vpravo") }),
            listOf(null, strelka("▼") { sdvin("vniz") }, null)))
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
        val st = try { Hub.kamera?.status()?.let { JSONObject(it) } } catch (e: Exception) { null }
        val gde = st?.optJSONObject("kraya")
        val kart = st?.optJSONObject("kartinka")
        // 2.8.16: сколько картинки не видно — с формой окна (её раньше не было видно ни тут, ни Плюсу)
        val zap = kart?.optJSONArray("zapolnenie")
        fun r(x: Double) = Math.round(x).toString()
        kraya.text = (when {
            kart == null -> if (z.all { it == 0f }) "края не срезаны" else "срезано кнопками: слева ${p(0)} %, сверху ${p(1)} %, справа ${p(2)} %, снизу ${p(3)} %"
            kart.optBoolean("celikom") -> "картинка камеры видна целиком"
            else -> "не видно картинки: слева ${r(kart.optDouble("sleva"))} %, сверху ${r(kart.optDouble("sverhu"))} %, " +
                "справа ${r(kart.optDouble("sprava"))} %, снизу ${r(kart.optDouble("snizu"))} %"
        }) + (if (zap != null && (0..3).any { zap.optDouble(it) >= 0.5 })
            "\n(часть срезает форма окна — ПОЛНЫЙ КАДР вернёт)" else "") +
            (gde?.let { "\nот краёв кадра: слева ${it.optDouble("sleva")} %, снизу ${it.optDouble("snizu")} %" } ?: "")
    }

    companion object {
        const val SHAG_SDVIGA = 2
        /** Клетка креста, точки: обе крестовины из одинаковых клеток (5 клеток краёв ≈ 640 — влезает в окно моста). */
        const val KLETKA = 120
    }
}
