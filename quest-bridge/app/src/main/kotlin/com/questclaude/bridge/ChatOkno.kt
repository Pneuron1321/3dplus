package com.questclaude.bridge

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Лента чата для окна «Плюс · Чат» (2.8.1, Александр 30.10: «окно моста я вижу, если оно не свёрнуто — сделай такое
 * же, только чтобы там писался чат»). Пишется из тех же сообщений, что плашка чата в эфире (/chat/upr и /chat/pachka
 * от ушей: Twitch, Kick, YouTube), но живёт всегда — и без захвата, и без эфира; хранит [HRANIT] последних, не гаснет.
 * Удалённое модератором и бан убираются и отсюда. Обычная JVM — ChatLentaTest.
 */
object ChatLenta {
    class Soob(val vremya: Long, val pl: String, val login: String, val kto: String, val tekst: String,
               val cvet: Int, val vazhno: Boolean, val id: String)

    const val HRANIT = 200
    private val K_PLUSU = Regex("(?iu)(^|[^\\p{L}])(плюс|plus)([^\\p{L}]|$)")
    private val spisok = ArrayDeque<Soob>()
    /** Растёт с каждым изменением — окно перерисовывается. */
    @Volatile var versiya = 0L
        private set

    @Synchronized fun upr(q: Map<String, String>, now: Long = System.currentTimeMillis()) {
        if (odno(q, now)) versiya++
    }

    @Synchronized fun pachka(qs: List<Map<String, String>>, now: Long = System.currentTimeMillis()) {
        var bylo = false
        for (q in qs) bylo = odno(q, now) || bylo
        if (bylo) versiya++
    }

    @Synchronized fun vse(): List<Soob> = spisok.toList()

    @Synchronized fun ochistit() { spisok.clear(); versiya++ }

    private fun odno(q: Map<String, String>, now: Long): Boolean {
        var bylo = false
        if (q["ochistit"] == "1") { spisok.clear(); bylo = true }
        q["ubrat"]?.let { id -> if (id.isNotEmpty() && spisok.removeAll { it.id == id }) bylo = true }
        q["ubrat_kto"]?.let { l -> val lg = l.lowercase(); if (spisok.removeAll { it.login == lg }) bylo = true }
        val tekst = q["tekst"]?.let { Chat.chisto(Titry.dekod(it)) }
        if (!tekst.isNullOrEmpty()) {
            val kto = Top.nik(Titry.dekod(q["kto"] ?: "")).ifEmpty { "?" }
            val login = Top.nik(Titry.dekod(q["login"] ?: kto)).lowercase()
            spisok.addLast(Soob(now, q["pl"]?.takeIf { it in Chat.PLOSHCHADKI } ?: "tw", login, kto, tekst,
                Chat.cvet(Titry.dekod(q["cvet"] ?: ""), login), q["vazhno"] == "1" || K_PLUSU.containsMatchIn(tekst),
                q["id"]?.take(64) ?: ""))
            while (spisok.size > HRANIT) spisok.removeFirst()
            bylo = true
        }
        return bylo
    }

    /** Метка площадки и её цвет — как у самих площадок. */
    val METKA = mapOf("tw" to ("Twitch" to 0xFF9146FF.toInt()), "kick" to ("Kick" to 0xFF53FC18.toInt()),
        "yt" to ("YouTube" to 0xFFFF3B3B.toInt()))

    /** Строка сообщения для окна: «12:34 Kick Ник: текст» (без цвета — для проверок). */
    fun stroka(s: Soob): String =
        "%s %s %s: %s".format(java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(s.vremya),
            METKA[s.pl]?.first ?: s.pl, s.kto, s.tekst)
}

/**
 * Окно «Плюс · Чат» — отдельное окно приложения (своя задача, как окно моста): чат всех площадок крупно, новые
 * внизу; прокрутил вверх — лента не дёргается, пока не вернёшься вниз. «A+ / A−» — размер букв (запоминается).
 */
class ChatOkno : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tekst: TextView
    private lateinit var skrol: ScrollView
    private lateinit var pusto: TextView
    private var versiya = -1L
    private var razmer = 22f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pref = getSharedPreferences("chat_okno", Context.MODE_PRIVATE)
        razmer = pref.getFloat("razmer", 22f)
        tekst = TextView(this).apply {
            textSize = razmer; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f); setPadding(24, 12, 24, 24)
        }
        pusto = TextView(this).apply {
            textSize = 18f; setTextColor(Color.GRAY); setPadding(24, 24, 24, 0)
            text = "Сообщений пока нет. Чат Twitch, Kick и YouTube появится здесь сам — уши Плюса шлют его в мост."
        }
        skrol = ScrollView(this).apply { addView(tekst) }
        fun knopka(t: String, d: Float) = Button(this).apply {
            text = t; textSize = 18f
            setOnClickListener {
                razmer = (razmer + d).coerceIn(14f, 40f)
                tekst.textSize = razmer
                pref.edit().putFloat("razmer", razmer).apply()
            }
        }
        val verh = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 0)
            addView(TextView(this@ChatOkno).apply {
                text = "💬 Чат"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(knopka("A−", -2f))
            addView(knopka("A+", 2f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF15161A.toInt())
            addView(verh)
            addView(pusto)
            addView(skrol, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(tik)
    }

    override fun onPause() {
        ui.removeCallbacks(tik)
        super.onPause()
    }

    private val tik = object : Runnable {
        override fun run() {
            narisovat()
            ui.postDelayed(this, 500)
        }
    }

    private fun narisovat() {
        val v = ChatLenta.versiya
        if (v == versiya) return
        versiya = v
        val ss = ChatLenta.vse()
        pusto.visibility = if (ss.isEmpty()) View.VISIBLE else View.GONE
        val vnizu = skrol.getChildAt(0).let { it.bottom - (skrol.height + skrol.scrollY) } < 80
        val sb = SpannableStringBuilder()
        val chasy = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        for (s in ss) {
            if (sb.isNotEmpty()) sb.append('\n')
            kusok(sb, chasy.format(s.vremya) + " ", 0xFF8A8D94.toInt())
            val (imya, cvet) = ChatLenta.METKA[s.pl] ?: (s.pl to Color.LTGRAY)
            kusok(sb, "$imya ", cvet)
            kusok(sb, s.kto, s.cvet, zhirno = true)
            kusok(sb, ": " + s.tekst, if (s.vazhno) 0xFFFFD54F.toInt() else Color.WHITE)
        }
        tekst.text = sb
        if (vnizu) skrol.post { skrol.fullScroll(View.FOCUS_DOWN) }
    }

    private fun kusok(sb: SpannableStringBuilder, t: String, cvet: Int, zhirno: Boolean = false) {
        val a = sb.length
        sb.append(t)
        sb.setSpan(ForegroundColorSpan(cvet), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (zhirno) sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
