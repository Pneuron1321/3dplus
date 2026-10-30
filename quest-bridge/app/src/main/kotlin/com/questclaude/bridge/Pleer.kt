package com.questclaude.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

/**
 * Шторка мини-плеера (2.5.7, Александр 17.10): «назад / вперёд», громкость ползунком, плейлисты, треки
 * активного плейлиста, стоп и «сейчас играет» с автором. Рисует то, что прислал плеер muzyka
 * (Hub.fon.pult), и кладёт ему команды — те же, что голосом; голосовое управление остаётся.
 */
class Pleer(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var otkryta = false
    private var bylSpisok = ""
    private var bylPleylisty = ""
    private var tyanu = false
    private var igraet = false

    private val seychas = TextView(ctx).apply { textSize = 18f }
    private val pusk = knopka("▶") { if (igraet) komanda("stop") else komanda("vkl") }
    private val gromkost = SeekBar(ctx).apply {
        max = 100
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, ot: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { tyanu = true }
            override fun onStopTrackingTouch(s: SeekBar) { tyanu = false; komanda("gromkost", s.progress.toString()) }
        })
    }
    private var seychasTrek = ""
    private val udalitKnopka = knopka("🗑 удалить этот трек") { if (seychasTrek.isNotEmpty()) udalit(seychasTrek) }
    private val pleylisty = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val treki = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, 10, 0, 20)
        addView(seychas)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(knopka("⏮ назад") { komanda("pred") })
            addView(pusk)
            addView(knopka("вперёд ⏭") { komanda("sled") })
        })
        addView(udalitKnopka)
        addView(TextView(ctx).apply { text = "Громкость музыки"; textSize = 15f })
        addView(gromkost)
        addView(TextView(ctx).apply { text = "Плейлисты"; textSize = 15f })
        addView(HorizontalScrollView(ctx).apply { addView(pleylisty) })
        addView(TextView(ctx).apply { text = "Треки (нажми — заиграет, держи — удалить)"; textSize = 15f })
        addView(treki)
    }
    private val zagolovok = knopka("🎵 МУЗЫКА ▸") { otkryt(!otkryta) }

    /** Кнопка-шторка и сама шторка — в главный экран. */
    fun vstavit(kuda: LinearLayout) {
        kuda.addView(zagolovok)
        kuda.addView(panel)
    }

    private fun otkryt(da: Boolean) {
        otkryta = da
        panel.visibility = if (da) View.VISIBLE else View.GONE
        zagolovok.text = if (da) "🎵 МУЗЫКА ▾" else "🎵 МУЗЫКА ▸"
        if (da) obnovit()
    }

    private val tik = object : Runnable {
        override fun run() {
            if (!otkryta) return
            obnovit()
            ui.postDelayed(this, 2000)
        }
    }

    private fun obnovit() {
        ui.removeCallbacks(tik)
        narisovat(Hub.fon?.pult)
        if (otkryta) ui.postDelayed(tik, 2000)
    }

    private fun narisovat(p: PleerPult?) {
        if (p == null || !p.zhiv()) {
            seychas.text = if (Hub.fon == null) "Музыка: мост ещё не запущен («ВСЁ СРАЗУ» или уши)." else
                "Плеер не отвечает: он живёт у Плюса в Ubuntu — запусти Плюса (или скажи «включи музыку»)."
            return
        }
        val j = try { JSONObject(p.sostoyanie) } catch (e: Exception) { return }
        igraet = j.optBoolean("igraet")
        val trek = j.optString("trek")
        val avtor = j.optString("avtor")
        seychasTrek = if (igraet) trek else ""
        udalitKnopka.visibility = if (seychasTrek.isNotEmpty()) View.VISIBLE else View.GONE
        seychas.text = when {
            igraet && trek.isNotEmpty() -> "▶ $trek — $avtor"
            j.optBoolean("vkl") -> "Включена, пауза: ${j.optString("prichina").ifEmpty { "ждёт" }}"
            else -> "Музыка выключена"
        }
        pusk.text = if (igraet) "⏹ стоп" else "▶ играть"
        if (!tyanu) gromkost.progress = j.optInt("vol", gromkost.progress)
        val akt = j.optString("aktivnyy")
        val pl = j.optJSONArray("pleylisty")
        val plSpisok = (0 until (pl?.length() ?: 0)).map { pl!!.optString(it) }
        if ("$akt|$plSpisok" != bylPleylisty) {
            bylPleylisty = "$akt|$plSpisok"
            pleylisty.removeAllViews()
            pleylisty.addView(knopka(if (akt.isEmpty()) "• все" else "все") { komanda("vse") })
            for (n in plSpisok) pleylisty.addView(knopka(if (n == akt) "• $n" else n) { komanda("pleylist", n) })
        }
        val tr = j.optJSONArray("treki")
        val spisok = (0 until (tr?.length() ?: 0)).map { tr!!.optString(it) }
        if ("$trek|$spisok" != bylSpisok) {
            bylSpisok = "$trek|$spisok"
            treki.removeAllViews()
            for (n in spisok.take(80)) treki.addView(knopka(if (n == trek) "▶ $n" else n) { komanda("vkl", n) }.apply {
                setOnLongClickListener { udalit(n); true }
            })
        }
    }

    /** Удалить трек — только после «да» с его названием (17.10 удалился не тот). */
    private fun udalit(trek: String) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Удалить трек?")
            .setMessage("«$trek» уйдёт из папки и из всех плейлистов.")
            .setPositiveButton("Удалить") { _, _ -> komanda("udalit", trek) }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun komanda(k: String, a: String = "") {
        val p = Hub.fon?.pult
        if (p == null) { seychas.text = "Музыка: мост ещё не запущен."; return }
        p.komanda(k, a)
        ui.postDelayed({ if (otkryta) obnovit() }, 700)
    }

    private fun knopka(t: String, action: () -> Unit) = Button(ctx).apply {
        text = t
        isAllCaps = false
        setOnClickListener { action() }
    }
}
