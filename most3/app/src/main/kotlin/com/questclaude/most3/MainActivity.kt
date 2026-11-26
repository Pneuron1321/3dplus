package com.questclaude.most3

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Окно игры Мост 3: видео карты на весь экран окна, сверху строка состояния, снизу — наборы картинки и звук,
 * по кнопке «Ручки» — ползунки. Панель прячется через 5 с; коснулся картинки — вернулась.
 * 3.1.3 (Александр 22.10: полоска «режим, приём, показ, звук» попала в эфир): строка состояния прячется вместе с
 * панелью, пока видео идёт; нет видео или ошибка — строка видна.
 * 3.2.2 (Александр 21.11: в маленьком окне не все кнопки видны — «Ручки» не нажать): ряд кнопок листается вбок,
 * ползунки — вверх-вниз (не выше половины окна), полосы прокрутки видны всегда и толстые — их можно тянуть.
 */
class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private val pokaz = Pokaz(Most.polka)
    private lateinit var stroka: TextView
    private lateinit var panel: HorizontalScrollView
    private lateinit var polzunki: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var panelVidna = true
    private val spryatat = Runnable {
        panel.visibility = View.GONE; polzunki.visibility = View.GONE; panelVidna = false
        if (Most.video?.rabotaet == true) stroka.visibility = View.GONE
    }
    private val obnovit = object : Runnable {
        override fun run() {
            stroka.text = strokaSostoyaniya()
            stroka.visibility = if (panelVidna || Most.video?.rabotaet != true) View.VISIBLE else View.GONE
            ui.postDelayed(this, 1000)
        }
    }

    private val usb = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val nasha = Most.karta?.ustr
                    if (nasha == null || d?.deviceName == nasha.deviceName) {   // другой USB в хабе — не наше дело
                        Most.vFone { Most.ostanovit() }; stroka.text = "Карта отключена"
                    }
                }
                RAZRESHENIE -> if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) podklyuchit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Most.zapusk(this)
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(Konfig)                        // «записываемая» поверхность — для записи игры
            preserveEGLContextOnPause = true                   // окно спрятали и вернули — текстуры и запись живы
            setRenderer(pokaz)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) pokazatPanel(); false }
        }
        stroka = TextView(this).apply {
            setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 0f, Color.BLACK); textSize = 14f; setPadding(16, 8, 16, 8)
        }
        polzunki = sobratPolzunki().apply { visibility = View.GONE }
        panel = sobratPanel()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(stroka, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(polzunki); addView(panel)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        val f = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(RAZRESHENIE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usb, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(usb, f)
        val nado = listOf(Manifest.permission.CAMERA, USB_KAMERA).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (nado.isNotEmpty()) requestPermissions(nado.toTypedArray(), 1) else podklyuchit()
        pokazatPanel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        podklyuchit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) podklyuchit()
    }

    private fun podklyuchit() {
        stroka.text = "Подключаю карту…"
        Most.vFone {
            val otvet = try { Most.podklyuchit(this) } catch (e: Exception) { "сбой: ${e.message ?: e.javaClass.simpleName}" }
            // Нет доступа — попросить (Quest может не показать окно: тогда путь через «Choose an app»)
            if (Most.video?.rabotaet != true) {
                val um = getSystemService(UsbManager::class.java)
                Karta.najti(um)?.takeIf { !um.hasPermission(it) }?.let {
                    val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    um.requestPermission(it, PendingIntent.getBroadcast(this, 3, Intent(RAZRESHENIE).setPackage(packageName), fl))
                }
            }
            ui.post { stroka.text = otvet }
        }
    }

    private fun strokaSostoyaniya(): String {
        val v = Most.video ?: return Most.oshibka ?: "Карта не подключена"
        val z = Most.zvuk
        val zv = when {
            !Most.zvukVkl -> "звук выкл."
            z == null -> "звука нет"
            else -> "звук ${Pcm.db(z.pik)} дБ, ${(z.gromkost * 100).toInt()} %"
        }
        val noch = if (Most.nastrojki.avtoNoch > 0f) " · авто-ночь +${(Most.polka.avtoTeni * 100).toInt()} %" else ""
        return "${v.rezhim.opisanie()} · приём ${"%.0f".format(v.fps)} к/с · показ ${"%.0f".format(Most.polka.pokazFps)} к/с · $zv$noch" +
            (v.oshibka?.let { "\n$it" } ?: "")
    }

    private fun knopka(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 13f; isAllCaps = false; setOnClickListener { f(); pokazatPanel() }
    }

    /** Полоса прокрутки, которую видно и можно тянуть: не гаснет, толстая, светлая на тёмной дорожке. */
    private fun polosa(v: View, vbok: Boolean) {
        v.isScrollbarFadingEnabled = false
        v.scrollBarSize = 22
        v.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY   // полоса — в отступе, кнопки не закрывает
        if (vbok) v.isHorizontalScrollBarEnabled = true else v.isVerticalScrollBarEnabled = true
        if (Build.VERSION.SDK_INT >= 29) {
            fun d(c: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = 11f }
            if (vbok) { v.horizontalScrollbarThumbDrawable = d(0xDDFFFFFF.toInt()); v.horizontalScrollbarTrackDrawable = d(0x55FFFFFF) }
            else { v.verticalScrollbarThumbDrawable = d(0xDDFFFFFF.toInt()); v.verticalScrollbarTrackDrawable = d(0x55FFFFFF) }
        }
        v.setOnScrollChangeListener { _, _, _, _, _ -> pokazatPanel() }   // листает — панель не прячется
    }

    private fun sobratPanel() = HorizontalScrollView(this).apply {
        setBackgroundColor(0x99000000.toInt()); isFillViewport = true; setPadding(0, 0, 0, 26)
        polosa(this, vbok = true)
        addView(sobratKnopki())
    }

    private fun sobratKnopki() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        for ((imya, nadpis) in listOf("obychno" to "Обычно", "monitor" to "Монитор", "noch" to "Ночь", "yarko" to "Ярко", "rezko" to "Резко", "hdr" to "HDR", "kino" to "Кино"))
            addView(knopka(nadpis) { NABORY[imya]?.let { Most.postavit(it); obnovitPolzunki() } })
        addView(knopka("Звук −") { Most.gromkost = Most.gromkost - 0.1f })
        addView(knopka("Звук +") { Most.gromkost = Most.gromkost + 0.1f })
        addView(knopka("Звук вкл/выкл") { val v = !Most.zvukVkl; Most.vFone { Most.zvuk(v) } })
        // 3.2.1: режимы звука одним нажатием; на кнопке — что сейчас
        fun perekl(imya: String, vkl: () -> Boolean, f: (Boolean) -> Unit) {
            lateinit var k: Button
            k = knopka("$imya: ${if (vkl()) "вкл" else "выкл"}") { val v = !vkl(); k.text = "$imya: ${if (v) "вкл" else "выкл"}"; f(v) }
            addView(k)
        }
        perekl("Шаги", { Most.zvukShagi }) { Most.zvukShagi = it }
        perekl("Ровно", { Most.zvukRovno }) { Most.zvukRovno = it }
        perekl("Быстрый звук", { Most.zvukBystro }) { v -> Most.vFone { Most.bystro(v) } }
        perekl("Звук в уши", { Most.zvukPryamo }) { v -> Most.vFone { Most.prostranstvo(v) } }
        addView(knopka("Клип 2,5 мин") { stroka.text = "Сохраняю клип игры…"; Most.vFone { val o = Most.sohranitKlip(150); ui.post { stroka.text = o } } })
        addView(knopka("Режим ▸") {
            val vse = Profil.values(); val sl = vse[(Most.profil.ordinal + 1) % vse.size]
            stroka.text = "Режим: ${sl.nadpis} — переподключаю…"; Most.smenitProfil(sl)
        })
        lateinit var efK: Button                         // 3.2.3: что видят зрители
        efK = knopka(Most.efirRezhimOpisanie()) {
            val vse = Struny.REZHIMY; val sl = vse[(vse.indexOf(Struny.rezhim) + 1) % vse.size]
            efK.text = "…"; Most.vFone { val o = Most.efirRezhim(sl); ui.post { efK.text = Most.efirRezhimOpisanie(); stroka.text = o } }
        }
        addView(efK)
        addView(knopka("Ручки") { polzunki.visibility = if (polzunki.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
    }

    /** Ползунок: имя настройки, подпись, диапазон, как взять и как поставить. */
    private class Polzunok(val imya: String, val nadpis: String, val min: Float, val max: Float, val vzyat: (Nastrojki) -> Float)
    private val spisok = listOf(
        // 3.2.4 (Александр 23.11): сила всего, что настроено, и выдержка
        Polzunok("sila", "СИЛА ВСЕХ РУЧЕК (0 — как пришло, 1 — как настроено, 2 — вдвое)", 0f, 2f) { it.sila },
        Polzunok("vyderzhka", "Выдержка (ступени)", -2f, 2f) { it.vyderzhka },
        // 3.2.8 (Александр 25.11: «насыщеннее и мультяшнее, весь свет ярче — не обелевшая»)
        Polzunok("svet", "Свет цвета (ярче, не белеет)", 0f, 1f) { it.svet },
        Polzunok("multyashno", "Мультяшность", 0f, 1f) { it.multyashno },
        Polzunok("yarkost", "Яркость", -0.5f, 0.5f) { it.yarkost },
        Polzunok("kontrast", "Контраст", 0.5f, 2f) { it.kontrast },
        Polzunok("nasyshch", "Насыщенность", 0f, 2.5f) { it.nasyshch },
        Polzunok("gamma", "Гамма", 0.5f, 2.5f) { it.gamma },
        Polzunok("teni", "Тени", 0f, 1f) { it.teni },
        Polzunok("rezkost", "Резкость", 0f, 8f) { it.rezkost },
        Polzunok("avtonoch", "Авто-ночь", 0f, 1f) { it.avtoNoch },
        // 3.2.2: цвет и уровни (просьба Александра 21.11)
        Polzunok("temperatura", "Температура (холодно — тепло)", -100f, 100f) { it.temperatura },
        Polzunok("balr", "Красный", 0.5f, 1.5f) { it.balR },
        Polzunok("balg", "Зелёный", 0.5f, 1.5f) { it.balG },
        Polzunok("balb", "Синий", 0.5f, 1.5f) { it.balB },
        Polzunok("ton", "Тон (оттенок)", -30f, 30f) { it.ton },
        Polzunok("sveta", "Света", -1f, 1f) { it.sveta },
        Polzunok("chernyj", "Точка чёрного (минус — приподнять)", -0.2f, 0.3f) { it.chernyj },
        Polzunok("belyj", "Уровень белого", 0.7f, 1f) { it.belyj },
        Polzunok("zhivost", "Живость", -1f, 1f) { it.zhivost },
        // 3.3.1 (Александр 26.11): почти белое и серое не раскрашиваются
        Polzunok("belo", "Белое белым", 0f, 1f) { it.belo },
        // 3.2.7 (план К2): чистка кадра для эфира и клипа; в окне видно в «До|После» справа
        Polzunok("chistka", "Чистка для эфира", 0f, 1f) { it.chistka })
    // 3.2.6 (Александр 25.11: «ручки не должны занимать пол-окна, ещё и с затемнением»): две тонкие строки внизу, кадр
    // не затемнён. Сверху — что крутим (короткие названия, листается вбок; там же «Ячейки», «◧ До|После», «↺ Цвет»),
    // снизу — один ползунок выбранной ручки с «−» и «+» (шаг 1 % хода) или ряд ячеек 1–6.
    private val KOROTKO = mapOf("sila" to "Сила", "vyderzhka" to "Выдержка", "yarkost" to "Яркость", "kontrast" to "Контраст",
        "nasyshch" to "Насыщ.", "gamma" to "Гамма", "teni" to "Тени", "rezkost" to "Резкость", "avtonoch" to "Авто-ночь",
        "temperatura" to "Темп.", "balr" to "Красный", "balg" to "Зелёный", "balb" to "Синий", "ton" to "Тон", "sveta" to "Света",
        "chernyj" to "Чёрный", "belyj" to "Белый", "zhivost" to "Живость", "chistka" to "Чистка эфира",
        "svet" to "Свет цвета", "multyashno" to "Мульт", "belo" to "Белое белым")
    /** 3.2.9: второй ряд фишек — цвет и уровни; первый — свет, резкость и остальное. */
    private val GRUPPA_CVET = setOf("temperatura", "balr", "balg", "balb", "ton", "sveta", "chernyj", "belyj", "zhivost", "belo")
    private var vybrana = "sila"                       // что крутим; "yachejki" — ряд ячеек вместо ползунка
    private val fishki = HashMap<String, Button>()
    private lateinit var ryadRuchki: LinearLayout
    private lateinit var ryadYachejki: LinearLayout
    private lateinit var imyaRuchki: TextView
    private lateinit var bar: SeekBar
    private var stavlyuBar = false

    private fun fishka(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(18, 6, 18, 6)
        setOnClickListener { f(); pokazatPanel() }
    }

    private fun tekushchij() = spisok.firstOrNull { it.imya == vybrana }

    private fun sobratPolzunki() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val ten = 0x80000000.toInt()                   // фон только под самими кнопками, кадр не темнеет
        // 3.2.9 (Александр 25.11: «в новом Мосте 3 стало меньше ручек, не вижу цвета»): ручки не пропадали — ряд был один
        // и листался вбок без видимой полосы, а 3.2.8 добавил две фишки, и цвет уехал за край. Теперь два ряда: свет и
        // цвет; у каждого видимая толстая полоса (polosa), если окно уже ряда.
        fun ryadFishek(spis: List<Polzunok>, hvost: LinearLayout.() -> Unit) = HorizontalScrollView(context).apply {
            polosa(this, true)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setBackgroundColor(ten)
                for (p in spis) addView(fishka(KOROTKO[p.imya] ?: p.imya) { vybrana = p.imya; obnovitPolzunki() }.also { fishki[p.imya] = it })
                hvost()
            })
        }
        addView(ryadFishek(spisok.filter { it.imya !in GRUPPA_CVET }) {},
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(ryadFishek(spisok.filter { it.imya in GRUPPA_CVET }) {
                addView(fishka("Ячейки") { vybrana = "yachejki"; obnovitPolzunki() }.also { fishki["yachejki"] = it })
                lateinit var sr: Button
                sr = fishka(if (Most.sravnenie) "◧ До|После: вкл" else "◧ До|После") {
                    Most.sravnenie = !Most.sravnenie; sr.text = if (Most.sravnenie) "◧ До|После: вкл" else "◧ До|После" }
                addView(sr)
                addView(fishka("↺ Цвет как было") {
                    var n = Most.nastrojki
                    for (im in listOf("temperatura", "balr", "balg", "balb", "ton", "sveta", "chernyj", "belyj", "zhivost", "vyderzhka", "sila", "svet", "multyashno", "belo"))
                        n = n.izmenit(im, "def") ?: n
                    Most.postavit(n); obnovitPolzunki()
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // строка ползунка: имя и значение, «−», ползунок, «+»
        ryadRuchki = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(ten); setPadding(12, 0, 12, 0)
            imyaRuchki = TextView(context).apply { setTextColor(Color.WHITE); textSize = 13f; width = 260 }
            addView(imyaRuchki)
            addView(fishka("−") { shag(-1) })
            bar = SeekBar(context).apply {
                max = 1000
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, ot: Boolean) {
                        val p = tekushchij() ?: return
                        if (!ot || stavlyuBar) return
                        postavitRuchku(p, p.min + (p.max - p.min) * v / 1000f)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fishka("+") { shag(+1) })
        }
        addView(ryadRuchki, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ryadYachejki = sobratYachejki().apply { setBackgroundColor(ten); visibility = View.GONE }
        addView(ryadYachejki, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        post { obnovitPolzunki() }
    }

    private fun postavitRuchku(p: Polzunok, z: Float) {
        Most.nastrojki.izmenit(p.imya, "=" + "%.3f".format(java.util.Locale.ROOT, z))?.let { Most.postavit(it) }
        obnovitPolzunki()
        pokazatPanel()
    }

    /** «−» / «+»: 1 % хода ручки. */
    private fun shag(zn: Int) {
        val p = tekushchij() ?: return
        postavitRuchku(p, (p.vzyat(Most.nastrojki) + zn * (p.max - p.min) / 100f).coerceIn(p.min, p.max))
    }

    // 3.2.6 (Александр 24.11): ячейки ручек 1–5 — одной строкой: «Загрузить 1–5», «💾 1–5» (сохранить), «↶» (вернуть).
    // Редактировать — загрузить, подвинуть ручки, сохранить в ту же.
    private val zagrK = HashMap<Int, Button>()
    private lateinit var yachStroka: TextView

    private fun sobratYachejki() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        yachStroka = TextView(context).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(12, 2, 12, 0) }
        addView(yachStroka)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 0, 12, 0)
            addView(TextView(context).apply { text = "Загрузить:"; setTextColor(Color.WHITE); textSize = 12f })
            for (n in 1..Yachejki.SKOLKO) addView(fishka("$n") { yachSoobshchenie = Most.yachejkaZagruzit(n); obnovitPolzunki() }.also { zagrK[n] = it })
            addView(TextView(context).apply { text = "   💾 Сохранить в:"; setTextColor(Color.WHITE); textSize = 12f })
            for (n in 1..Yachejki.SKOLKO) addView(fishka("$n") { yachSoobshchenie = Most.yachejkaSohranit(n); obnovitPolzunki() })
            addView(fishka("↶ вернуть") { yachSoobshchenie = Most.yachejkaVernut(); obnovitPolzunki() })
        })
    }
    private var yachSoobshchenie = ""

    private fun obnovitYachejki() {
        val y = Most.yachejki ?: return
        if (!::yachStroka.isInitialized) return
        for ((n, b) in zagrK) b.text = when { n == y.aktivnaya -> "▸$n"; y.est(n) -> "$n"; else -> "$n—" }
        val sejchas = if (y.aktivnaya == 0) "ячейка не выбрана" else "сейчас ${y.aktivnaya}" +
            (if (y.izmeneno(Most.nastrojki)) " — ручки изменены, «💾 ${y.aktivnaya}» запомнит" else "")
        yachStroka.text = "Ячейки («—» пустая): $sejchas" + (if (yachSoobshchenie.isNotEmpty()) " · $yachSoobshchenie" else "")
    }

    private fun obnovitPolzunki() {
        if (!::bar.isInitialized) return
        obnovitYachejki()
        for ((k, b) in fishki) b.alpha = if (k == vybrana) 1f else 0.6f
        val yach = vybrana == "yachejki"
        ryadYachejki.visibility = if (yach) View.VISIBLE else View.GONE
        ryadRuchki.visibility = if (yach) View.GONE else View.VISIBLE
        val p = tekushchij() ?: return
        val z = p.vzyat(Most.nastrojki)
        imyaRuchki.text = "${KOROTKO[p.imya] ?: p.imya}: ${"%.2f".format(java.util.Locale.ROOT, z)}"
        stavlyuBar = true
        bar.progress = (((z - p.min) / (p.max - p.min)) * 1000).toInt().coerceIn(0, 1000)
        stavlyuBar = false
    }

    private fun pokazatPanel() {
        panel.visibility = View.VISIBLE; stroka.visibility = View.VISIBLE; panelVidna = true
        ui.removeCallbacks(spryatat); ui.postDelayed(spryatat, 5000)
    }

    override fun onResume() { super.onResume(); gl.onResume(); ui.post(obnovit); obnovitPolzunki() }
    override fun onPause() { super.onPause(); gl.onPause(); ui.removeCallbacks(obnovit) }
    // 3.2.3: эфир берёт игру, только пока окно Моста 3 активно (режим «fokus») — ушёл к другому окну или свернул —
    // мост 2 через секунду показывает вид шлема
    override fun onWindowFocusChanged(est: Boolean) { super.onWindowFocusChanged(est); Struny.oknoAktivno = est }
    override fun onStop() { super.onStop(); Struny.oknoAktivno = false }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usb) } catch (e: Exception) { }
        pokaz.zakryt()
        if (isFinishing) Most.vFone { Most.ostanovit() }
    }

    /** RGBA8, OpenGL ES 3, EGL_RECORDABLE_ANDROID (кодировщик берёт ту же конфигурацию); нет такой — без «записываемой». */
    private object Konfig : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, d: javax.microedition.khronos.egl.EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
            fun vybrat(zapis: Boolean): javax.microedition.khronos.egl.EGLConfig? {
                val a = intArrayOf(0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3040, 0x40) +
                    (if (zapis) intArrayOf(0x3142, 1) else intArrayOf()) + intArrayOf(0x3038)
                val c = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1); val n = IntArray(1)
                return if (egl.eglChooseConfig(d, a, c, 1, n) && n[0] > 0) c[0] else null
            }
            return vybrat(true) ?: vybrat(false) ?: throw IllegalStateException("нет конфигурации EGL для OpenGL ES 3")
        }
    }

    companion object {
        const val USB_KAMERA = "horizonos.permission.USB_CAMERA"
        const val RAZRESHENIE = "com.questclaude.most3.RAZRESHENIE"
    }
}
