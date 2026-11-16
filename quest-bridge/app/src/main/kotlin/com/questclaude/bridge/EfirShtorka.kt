package com.questclaude.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Шторка эфира (2.8.0, Александр 29.10: «в шторке три платформы — поставить галочку напротив той, на которую хочу
 * начать стрим»): «📡 ЭФИР ▸» — под ней галочки Twitch, Kick, YouTube, «720p» и кнопка начать / закончить. Галочки —
 * как `efir na …` у Плюса: набор запоминается мостом; во время эфира галочка добавляет или убирает площадку на ходу
 * (последнюю не снять — это «закончить»). У площадки без ключа — подсказка, какой `kluch` сказать Плюсу.
 */
class EfirShtorka(private val ctx: Context, private val soobshchit: (String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var otkryta = false
    private var stavlyu = false                       // галочки ставит код (не Александр) — не слать команду
    private var idyot = false

    private val galki = Ploshchadki.VSE.associateWith { pl ->
        CheckBox(ctx).apply {
            textSize = 20f
            text = EfirVybor.IMYA[pl]
            setOnCheckedChangeListener { _, _ -> if (!stavlyu) smenil(pl) }
        }
    }
    private val e720 = CheckBox(ctx).apply { text = "Эфир в 720p — легче для сети (обычно 1080p)" }
    private val podskazka = TextView(ctx).apply { textSize = 15f }
    private val pusk = Button(ctx).apply { textSize = 20f; setOnClickListener { knopka() } }
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(20, 10, 0, 20)
        addView(TextView(ctx).apply { text = "Где вести эфир (галочки):"; textSize = 15f })
        galki.values.forEach { addView(it) }
        addView(podskazka)
        addView(e720)
        addView(pusk)
    }
    private val zagolovok = Button(ctx).apply { setOnClickListener { otkryt(!otkryta) } }

    fun vstavit(kuda: LinearLayout) {
        kuda.addView(zagolovok)
        kuda.addView(panel)
        narisovat()
    }

    /** Раз в секунду с главного экрана: заголовок (идёт ли эфир) и, если шторка открыта, — галочки. */
    fun obnovit() = narisovat()

    private fun otkryt(da: Boolean) {
        otkryta = da
        panel.visibility = if (da) View.VISIBLE else View.GONE
        narisovat(sGalkami = true)
    }

    private fun status(): JSONObject? = try { Hub.efir?.status()?.let { JSONObject(it) } } catch (e: Exception) { null }

    private fun narisovat(sGalkami: Boolean = false) {
        val s = status()
        idyot = s != null && s.optString("state", "off") != "off"
        zagolovok.text = (if (idyot) "📡 ЭФИР идёт" else "📡 ЭФИР") + (if (otkryta) " ▾" else " ▸")
        pusk.text = if (idyot) "■ ЗАКОНЧИТЬ ЭФИР" else "▶ НАЧАТЬ ЭФИР"
        e720.visibility = if (idyot) View.GONE else View.VISIBLE
        if (!otkryta || s == null) {
            if (otkryta) podskazka.text = "Сначала «Начать захват» (или «ВСЁ СРАЗУ»)."
            return
        }
        val vybor = s.optJSONArray("vybor")?.let { a -> List(a.length()) { a.optString(it) } } ?: listOf("twitch")
        val kluchi = s.optJSONObject("kluchi")
        val sost = HashMap<String, String>()
        s.optJSONArray("ploshchadki")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { sost[it.optString("pl")] = it.optString("sostoyanie") }
        }
        stavlyu = true
        for ((pl, g) in galki) {
            if (sGalkami || idyot) g.isChecked = pl in vybor
            g.text = EfirVybor.podpis(pl, kluchi?.optBoolean(pl, false) ?: false, sost[pl], s.optBoolean("adres_kick", true))
        }
        stavlyu = false
        podskazka.text = EfirVybor.sovet(galki.filterValues { it.isChecked }.keys, kluchi?.let { k -> Ploshchadki.VSE.filter { k.optBoolean(it) }.toSet() } ?: emptySet())
    }

    private fun vybrano() = galki.filterValues { it.isChecked }.keys

    /** Галочку поменял Александр: в эфире — сразу сменить площадки на ходу; без эфира — просто запомнить при пуске. */
    private fun smenil(pl: String) {
        val v = vybrano()
        if (!idyot) return
        val s = EfirVybor.pl(v)
        if (s == null) {                                   // последнюю не снимаем — для этого «закончить»
            stavlyu = true; galki[pl]?.isChecked = true; stavlyu = false
            soobshchit("Эфир: последнюю площадку не снять — чтобы закончить, нажми «ЗАКОНЧИТЬ ЭФИР».")
            return
        }
        val e = Hub.efir ?: return
        thread(isDaemon = true) {
            e.start(mapOf("pl" to s))
            soobshchit("Эфир: теперь на ${EfirVybor.spisok(v)}.")
        }
    }

    private fun knopka() {
        val e = Hub.efir
        if (e == null) { soobshchit("Эфир: сначала «Начать захват» (или «ВСЁ СРАЗУ»)."); return }
        val on = idyot
        val pl = EfirVybor.pl(vybrano())
        if (!on && pl == null) { soobshchit("Эфир: поставь галочку хотя бы у одной площадки."); return }
        soobshchit(if (on) "Эфир: заканчиваю…" else "Эфир: начинаю на ${EfirVybor.spisok(vybrano())}…")
        val razmer = if (e720.isChecked) mapOf("size" to "720", "kbps" to "4500") else emptyMap()
        thread(isDaemon = true) {
            val r = if (on) e.stop() else e.start(razmer + ("pl" to pl!!))
            val err = try { JSONObject(r).optString("oshibka") } catch (x: Exception) { "" }
            soobshchit(when {
                err.isNotEmpty() && err != "null" -> "Эфир: $err"
                on -> "Эфир закончен (площадки получили «трансляция окончена», не обрыв)."
                else -> "Эфир: подключаюсь…"
            })
            ui.post { narisovat(sGalkami = true) }
        }
    }
}

/** Подписи и проверки шторки эфира — чистый Kotlin (EfirVyborTest). */
object EfirVybor {
    val IMYA = mapOf("twitch" to "Twitch", "kick" to "Kick", "youtube" to "YouTube")

    /** Галочки → «twitch,kick» в порядке Twitch, Kick, YouTube; ничего не выбрано — null. */
    fun pl(v: Collection<String>): String? = Ploshchadki.VSE.filter { it in v }.takeIf { it.isNotEmpty() }?.joinToString(",")

    fun spisok(v: Collection<String>) = Ploshchadki.VSE.filter { it in v }.joinToString(", ") { IMYA[it] ?: it }

    /** Подпись у галочки: имя и что с площадкой (идёт / подключается / нет ключа). */
    fun podpis(pl: String, estKluch: Boolean, sostoyanie: String?, adresKick: Boolean = true): String {
        val imya = IMYA[pl] ?: pl
        if (!estKluch) return "$imya — нет ключа (скажи Плюсу: kluch $pl)"
        if (pl == "kick" && !adresKick) return "$imya — нет адреса (Stream URL → kluch kick)"
        return when (sostoyanie) {
            null, "" -> imya
            "efir" -> "$imya — идёт ●"
            "podklyuchayus" -> "$imya — подключаюсь…"
            "obryv" -> "$imya — обрыв, переподключаюсь…"
            else -> "$imya — $sostoyanie"
        }
    }

    /** Под галочками: сколько сети нужно и у кого нет ключа. */
    fun sovet(vybor: Set<String>, sKlyuchom: Set<String>): String {
        if (vybor.isEmpty()) return "Ни одной площадки — поставь галочку."
        val bez = vybor.filter { it !in sKlyuchom }
        val n = vybor.size - bez.size
        val set = if (n > 1) "Нужно примерно ${n * 6} Мбит/с отдачи (по 6 на площадку); не тянет — 720p или меньше площадок." else ""
        val kl = if (bez.isNotEmpty()) "Без ключа не пойдёт: ${spisok(bez)}." else ""
        return listOf(kl, set).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
