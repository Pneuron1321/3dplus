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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Окно игры Мост 3: видео карты на весь экран окна, сверху строка состояния, снизу — наборы картинки и звук,
 * по кнопке «Ручки» — ползунки. Панель прячется через 5 с; коснулся картинки — вернулась.
 */
class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private lateinit var stroka: TextView
    private lateinit var panel: LinearLayout
    private lateinit var polzunki: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val spryatat = Runnable { panel.visibility = View.GONE; polzunki.visibility = View.GONE }
    private val obnovit = object : Runnable {
        override fun run() { stroka.text = strokaSostoyaniya(); ui.postDelayed(this, 1000) }
    }

    private val usb = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { Most.ostanovit(); stroka.text = "Карта отключена" }
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
            setRenderer(Pokaz(Most.polka))
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
        Thread {
            val otvet = Most.podklyuchit(this)
            // Нет доступа — попросить (Quest может не показать окно: тогда путь через «Choose an app»)
            if (Most.video?.rabotaet != true) {
                val um = getSystemService(UsbManager::class.java)
                Karta.najti(um)?.takeIf { !um.hasPermission(it) }?.let {
                    val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    um.requestPermission(it, PendingIntent.getBroadcast(this, 3, Intent(RAZRESHENIE).setPackage(packageName), fl))
                }
            }
            ui.post { stroka.text = otvet }
        }.start()
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

    private fun sobratPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(0x99000000.toInt())
        for ((imya, nadpis) in listOf("obychno" to "Обычно", "monitor" to "Монитор", "noch" to "Ночь", "yarko" to "Ярко", "rezko" to "Резко"))
            addView(knopka(nadpis) { NABORY[imya]?.let { Most.postavit(it); obnovitPolzunki() } })
        addView(knopka("Звук −") { Most.gromkost = Most.gromkost - 0.1f })
        addView(knopka("Звук +") { Most.gromkost = Most.gromkost + 0.1f })
        addView(knopka("Звук вкл/выкл") { Most.zvuk(!Most.zvukVkl) })
        addView(knopka("Клип 2 мин") { Thread { val o = Most.sohranitKlip(120); ui.post { stroka.text = o } }.start() })
        addView(knopka("Ручки") { polzunki.visibility = if (polzunki.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
    }

    /** Ползунок: имя настройки, подпись, диапазон, как взять и как поставить. */
    private class Polzunok(val imya: String, val nadpis: String, val min: Float, val max: Float, val vzyat: (Nastrojki) -> Float)
    private val spisok = listOf(
        Polzunok("yarkost", "Яркость", -0.5f, 0.5f) { it.yarkost },
        Polzunok("kontrast", "Контраст", 0.5f, 2f) { it.kontrast },
        Polzunok("nasyshch", "Насыщенность", 0f, 2f) { it.nasyshch },
        Polzunok("gamma", "Гамма", 0.5f, 2.5f) { it.gamma },
        Polzunok("teni", "Тени", 0f, 1f) { it.teni },
        Polzunok("rezkost", "Резкость", 0f, 2f) { it.rezkost },
        Polzunok("avtonoch", "Авто-ночь", 0f, 1f) { it.avtoNoch })
    private val bary = HashMap<String, SeekBar>()

    private fun sobratPolzunki() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()); setPadding(24, 8, 24, 8)
        for (p in spisok) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = p.nadpis; setTextColor(Color.WHITE); width = 260 })
            addView(SeekBar(context).apply {
                max = 1000; bary[p.imya] = this
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, ot: Boolean) {
                        if (!ot) return
                        val z = p.min + (p.max - p.min) * v / 1000f
                        Most.nastrojki.izmenit(p.imya, "=" + "%.3f".format(java.util.Locale.ROOT, z))?.let { Most.postavit(it) }
                        pokazatPanel()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        post { obnovitPolzunki() }
    }

    private fun obnovitPolzunki() {
        val n = Most.nastrojki
        for (p in spisok) bary[p.imya]?.progress = (((p.vzyat(n) - p.min) / (p.max - p.min)) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun pokazatPanel() {
        panel.visibility = View.VISIBLE
        ui.removeCallbacks(spryatat); ui.postDelayed(spryatat, 5000)
    }

    override fun onResume() { super.onResume(); gl.onResume(); ui.post(obnovit); obnovitPolzunki() }
    override fun onPause() { super.onPause(); gl.onPause(); ui.removeCallbacks(obnovit) }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usb) } catch (e: Exception) { }
        if (isFinishing) Most.ostanovit()
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
