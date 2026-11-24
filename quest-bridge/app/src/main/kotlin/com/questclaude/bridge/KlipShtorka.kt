package com.questclaude.bridge

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Шторка клипов (2.8.13, Александр 24.11: «сделай шторку с галочками вкл/выкл» — одним нажатием вернуться к тому,
 * что было, и обратно). «🎬 КЛИПЫ ▸» — под ней три галочки KlipNastroyki: высокое качество, чистая игра из Моста 3,
 * камера телефона. Во время эфира галочка качества действует сразу (круг клипа начинается заново), иначе —
 * со следующего эфира. То же голосом: `efir klip kachestvo|most3|kamera vkl|vykl`.
 */
class KlipShtorka(private val ctx: Context, private val soobshchit: (String) -> Unit) {
    private var otkryta = false
    private var stavlyu = false                       // галочки ставит код — не применять
    private val zagolovok = Button(ctx).apply { textSize = 20f; setOnClickListener { otkryt(!otkryta) } }

    private fun galka(t: String, d: (Boolean) -> Unit) = CheckBox(ctx).apply {
        textSize = 18f; text = t
        setOnCheckedChangeListener { _, v -> if (!stavlyu) d(v) }
    }

    private val kachestvo = galka("Клип шлема в высоком качестве: 1080p, 60 к/с, 20 Мбит/с, со всем оформлением и окном " +
        "камеры (выкл — как было: копия эфира, 6 Мбит/с, 30 к/с)") { v -> pomenyat { KlipNastroyki.postavit(kachestvo = v) } }
    private val most3 = galka("Чистая игра из Моста 3: 16 Мбит/с, 60 к/с, без оформления") { v ->
        pomenyat { KlipNastroyki.postavit(most3 = v) } }
    private val kamera = galka("Камера телефона (как пришла с телефона)") { v -> pomenyat { KlipNastroyki.postavit(kamera = v) } }
    private val podskazka = TextView(ctx).apply { textSize = 15f }

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(20, 10, 0, 20)
        addView(TextView(ctx).apply { text = "Что сохраняет «клип клип клип» (галочки):"; textSize = 15f })
        addView(kachestvo); addView(most3); addView(kamera)
        addView(podskazka)
    }

    private fun pomenyat(d: () -> Boolean) {
        if (!d()) return
        narisovat()
        val f = Hub.klipPrimenit
        if (f == null) { podskazka.text = "Запомнил. Подействует с захватом и эфиром."; return }
        thread(isDaemon = true) {
            val o = try { f() } catch (e: Exception) { "не вышло: ${e.message}" }
            soobshchit("Клипы: $o.")
            podskazka.post { podskazka.text = "Применено: $o." }
        }
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
        val chto = listOfNotNull(if (KlipNastroyki.kachestvo) "шлем HQ" else "шлем (копия эфира)",
            if (KlipNastroyki.most3) "игра Моста 3" else null, if (KlipNastroyki.kamera) "камера" else null)
        zagolovok.text = "🎬 КЛИПЫ: " + chto.joinToString(" + ") + (if (otkryta) " ▾" else " ▸")
        stavlyu = true
        kachestvo.isChecked = KlipNastroyki.kachestvo
        most3.isChecked = KlipNastroyki.most3
        kamera.isChecked = KlipNastroyki.kamera
        stavlyu = false
    }
}
