package com.questclaude.bridge

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
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
 * внизу; прокрутил вверх — лента не дёргается, пока не вернёшься вниз.
 * 2.8.3 (Александр 03.11): без слов — сверху только три точки площадок (красная YouTube, зелёная Kick, фиолетовая
 * Twitch), маленькие «−» и «+» (размер букв, запоминается) и «◐» — прозрачное окно: видно только сообщения и точки
 * (запоминается; если шлем не умеет прозрачные окна — будет просто тёмное). Сообщение — точка площадки, ник, текст.
 * 2.8.4: рядом с точками — сколько смотрят (красная точка и число, как в кадре; есть, пока идёт захват и zriteli шлёт).
 */
class ChatOkno : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var koren: LinearLayout
    private lateinit var tekst: TextView
    private lateinit var skrol: ScrollView
    private lateinit var knopki: List<Button>
    private lateinit var zriteli: TextView
    private var versiya = -1L
    private var razmer = 22f
    private var prozrachno = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pref = getSharedPreferences("chat_okno", Context.MODE_PRIVATE)
        razmer = pref.getFloat("razmer", 22f)
        prozrachno = pref.getBoolean("prozrachno", false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        tekst = TextView(this).apply {
            textSize = razmer; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f); setPadding(20, 4, 20, 20)
        }
        skrol = ScrollView(this).apply { addView(tekst) }
        fun knopka(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 14f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(18, 4, 18, 4)
            setOnClickListener { f() }
        }
        fun shrift(d: Float) {
            razmer = (razmer + d).coerceIn(14f, 40f)
            tekst.textSize = razmer
            pref.edit().putFloat("razmer", razmer).apply()
        }
        knopki = listOf(knopka("−") { shrift(-2f) }, knopka("+") { shrift(2f) }, knopka("◐") {
            prozrachno = !prozrachno
            pref.edit().putBoolean("prozrachno", prozrachno).apply()
            vid()
        })
        val verh = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 6, 8, 0)
            for (pl in listOf("yt", "kick", "tw")) addView(TextView(this@ChatOkno).apply {
                text = "●"; textSize = 18f; setTextColor(ChatLenta.METKA[pl]?.second ?: Color.LTGRAY); setPadding(0, 0, 10, 0)
            })
            // 2.8.4 (Александр 04.11): сколько смотрят — как в кадре: красная точка и число (все площадки вместе)
            zriteli = TextView(this@ChatOkno).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(14, 0, 0, 0) }
            addView(zriteli)
            addView(View(this@ChatOkno), LinearLayout.LayoutParams(0, 1, 1f))
            for (k in knopki) addView(k)
        }
        koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(verh)
            addView(skrol, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(koren)
        vid()
    }

    /** Тёмное окно или прозрачное (только сообщения с тенью и точки). */
    private fun vid() {
        koren.setBackgroundColor(if (prozrachno) Color.TRANSPARENT else 0xFF15161A.toInt())
        tekst.setShadowLayer(if (prozrachno) 6f else 0f, 0f, 2f, Color.BLACK)
        for (k in knopki) k.alpha = if (prozrachno) 0.45f else 1f
        versiya = -1L
        narisovat()
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
        val zr = Hub.schet?.takeIf { it.pokazZr }?.zriteli().orEmpty()
        zriteli.text = if (zr.isEmpty()) "" else SpannableStringBuilder().also {
            kusok(it, "● ", Vizor.VIZOR)
            kusok(it, Schet.krasivo(zr.values.sum()), Color.WHITE, zhirno = true)
        }
        zriteli.setShadowLayer(if (prozrachno) 6f else 0f, 0f, 2f, Color.BLACK)
        val v = ChatLenta.versiya
        if (v == versiya) return
        versiya = v
        val ss = ChatLenta.vse()
        val vnizu = skrol.getChildAt(0).let { it.bottom - (skrol.height + skrol.scrollY) } < 80
        val sb = SpannableStringBuilder()
        for (s in ss) {
            if (sb.isNotEmpty()) sb.append('\n')
            kusok(sb, "● ", ChatLenta.METKA[s.pl]?.second ?: Color.LTGRAY)
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
