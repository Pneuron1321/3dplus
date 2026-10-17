package com.questclaude.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Пульт. «Всё сразу» — шлем не спит, уши, глаза (одно нажатие «Разрешить»),
 * Плюс в Termux. Остальные кнопки — по отдельности.
 * Открытие приложения само выключает сон шлема: после перезагрузки сюда и так
 * заходят — захват каждый раз требует согласия.
 * Без androidx: меньше APK и сборка без лишних зависимостей.
 */
class MainActivity : Activity() {

    private val requestCapture = 1
    private val requestPerms = 2
    private var allInOne = false
    private var noEyes = false        // «без захвата»: идёт стрим, свой захват оборвал бы BeamXR
    private var sleepStatus = ""
    private var plusStatus = ""
    @Volatile private var efirStatus = ""
    @Volatile private var kameraStatus = ""
    private var kameraDo = 0L                  // адрес с ключом на экране — до этого времени
    private lateinit var efirBtn: Button
    private lateinit var efir720: CheckBox
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            // ключ камеры — только без эфира: экран приложения попадает в кадр
            if (kameraStatus.startsWith("КАМЕРА ТЕЛЕФОНА") && System.currentTimeMillis() > kameraDo) kameraStatus = ""
            status.text = listOf(kameraStatus, efirStatus, CaptureService.status, EarService.status, sleepStatus, plusStatus, Shlem.termuxReply)
                .filter { it.isNotEmpty() }.joinToString("\n\n")
            efirBtn.text = if (efirIdyot()) "ЭФИР: закончить" else "ЭФИР: начать (Twitch)"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noSleep()
        status = TextView(this).apply { textSize = 20f }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
        // Кнопок больше, чем помещается в окно шлема (2.2.9) — окно листается.
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            addView(status)
            addView(button("ВСЁ СРАЗУ: глаза, уши, Плюс") { allInOne = true; noEyes = false; askPermissions() })
            efirBtn = button("ЭФИР: начать (Twitch)") { efirKnopka() }
            addView(efirBtn)
            efir720 = CheckBox(this@MainActivity).apply { text = "Эфир в 720p — легче для сети (обычно 1080p)" }
            addView(efir720)
            addView(button("ИТОГИ ВЕЧЕРА: показать / убрать (тёмный экран в эфире)") { itogiKnopka() })
            addView(button("КАМЕРА ТЕЛЕФОНА: подключить айфон") { kameraKnopka() }.apply {
                setOnLongClickListener { kameraNovyKlyuch(); true }
            })
            addView(button("Уши и Плюс, без захвата (идёт стрим)") { allInOne = true; noEyes = true; askPermissions() })
            addView(button("Начать захват") { askCapture() })
            addView(button("Остановить захват") { stopService(Intent(this@MainActivity, CaptureService::class.java)) })
            addView(button("Включить уши («плюс плюс»)") { allInOne = false; askPermissions() })
            addView(button("Выключить уши") { stopService(Intent(this@MainActivity, EarService::class.java)) })
            addView(button("Запустить Плюса в Termux") { startPlus() })
            addView(button("Шлем засыпает как обычно") {
                Shlem.sleepAsUsual(this@MainActivity)
                sleepStatus = "Сон: как обычно (снял шлем — уснёт)."
            })
        }) })
    }

    private fun efirIdyot() = Hub.efir?.status()?.contains("\"state\":\"off\"") == false

    /**
     * Кнопка эфира — то же, что `efir` / `efir stop` в Termux. Ключ приложение помнит после
     * первого `efir` (из `kluch twitch`); захват должен идти — кадр раздваивается из него.
     */
    private fun efirKnopka() {
        val e = Hub.efir
        if (e == null) {
            efirStatus = "Эфир: сначала «Начать захват» (или «ВСЁ СРАЗУ»)."
            return
        }
        val on = efirIdyot()
        efirStatus = if (on) "Эфир: заканчиваю…" else "Эфир: начинаю…"
        thread(isDaemon = true) {
            val r = if (on) e.stop() else e.start(if (efir720.isChecked) mapOf("size" to "720", "kbps" to "4500") else emptyMap())
            val err = try { org.json.JSONObject(r).optString("oshibka") } catch (x: Exception) { "" }
            efirStatus = when {
                err.isNotEmpty() && err != "null" -> "Эфир: $err"
                on -> "Эфир закончен (Twitch получил «трансляция окончена», не обрыв)."
                else -> "Эфир: подключаюсь к Twitch…"
            }
            if (!on) ui.postDelayed({ if (efirIdyot() && efirStatus.startsWith("Эфир: подключаюсь")) efirStatus = "" }, 15000)
        }
    }

    /** Итоги вечера — тёмный экран во весь кадр эфира (то же, что `itogi` у Плюса). */
    private fun itogiKnopka() {
        val p = Hub.plashki
        if (p == null) { efirStatus = "Итоги: сначала «Начать захват» (или «ВСЁ СРАЗУ»)."; return }
        val vidny = try { org.json.JSONObject(p.itogi(null)).optBoolean("vidny") } catch (e: Exception) { false }
        p.itogi(mapOf("pokaz" to if (vidny) "0" else "1"))
        efirStatus = when {
            vidny -> "Итоги убраны — в эфире снова игра."
            efirIdyot() -> "Итоги вечера — в эфире. Убрать — та же кнопка."
            else -> "Итоги включены — появятся в эфире, когда он начнётся."
        }
    }

    /**
     * Подключить айфон: на 2 минуты открывается страница http://plus.local:8770 — в Safari кнопки
     * «Добавить в Moblin» и «Добавить в Larix». Ключ камеры живёт только на этой странице (на экране шлема — нет: он в кадре).
     * Долгое нажатие — новый ключ (если старый засветился).
     */
    private fun kameraKnopka() {
        val k = CaptureService.kamera
        if (k == null) { kameraStatus = "Камера: сначала «Начать захват» (или «ВСЁ СРАЗУ»)."; return }
        kameraStatus = "Камера: открываю страницу…"
        thread(isDaemon = true) {
            kameraStatus = k.stranicaTekst()
            kameraDo = System.currentTimeMillis() + 120_000
        }
    }

    private fun kameraNovyKlyuch() {
        val k = CaptureService.kamera ?: return
        k.novyKlyuch()
        kameraStatus = "Камера: новый ключ. Старое подключение больше не пускает — нажми кнопку и добавь в Moblin заново."
    }

    private fun noSleep() {
        sleepStatus = try {
            Shlem.noSleep(this)
            "Сон: выключен до перезагрузки — снятый шлем не гаснет."
        } catch (e: Exception) {
            "Сон: не вышло (${e.message})."
        }
    }

    private fun askPermissions() {
        val need = listOf(Manifest.permission.RECORD_AUDIO, Shlem.RUN_COMMAND)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) afterPermissions() else requestPermissions(need.toTypedArray(), requestPerms)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPerms) afterPermissions()
    }

    private fun afterPermissions() {
        val fresh = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEar()
            else { EarService.status = "Уши: микрофон не разрешён."; false }
        // уши только что включились — дать ushi секунду отметиться (идёт ли стрим)
        if (allInOne) ui.postDelayed({ eyesThenPlus() }, if (fresh) 1500L else 0L)
    }

    /**
     * Quest даёт захват экрана одному приложению: наш захват обрывает стрим BeamXR (04.10).
     * Идёт стрим через ретранслятор — Плюс смотрит кадры из него, захват не включаем.
     */
    private fun eyesThenPlus() {
        when {
            noEyes -> { CaptureService.status = "Глаза: захват не включаю (идёт стрим)."; startPlus() }
            Hub.ear.strimLive() && !CaptureService.running -> {
                CaptureService.status = "Глаза: идёт стрим через ретранслятор — Плюс смотрит кадры из него. " +
                    "Захват не включаю, чтобы не оборвать BeamXR."
                startPlus()
            }
            else -> askCapture()              // Плюс — после согласия на захват
        }
    }

    /** Служба микрофона стартует, только пока приложение на экране (правило Android 14). true — включилась сейчас. */
    private fun startEar(): Boolean {
        if (EarService.running) return false
        startForegroundService(Intent(this, EarService::class.java))
        return true
    }

    private fun askCapture() {
        if (CaptureService.running) {
            if (allInOne) startPlus()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    private fun startPlus() {
        allInOne = false
        if (checkSelfPermission(Shlem.RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            plusStatus = "Плюс: нужно разрешение «Termux: выполнять команды» — нажми кнопку ещё раз."
            requestPermissions(arrayOf(Shlem.RUN_COMMAND), requestPerms)
            return
        }
        if (Hub.ear.plusAlive()) {
            // Плюс уже идёт (уши видят его tmux): новый запуск только мелькал окном Termux
            Shlem.showTermux(this)
            plusStatus = "Плюс уже работает — открываю Termux."
            return
        }
        plusStatus = try {
            Shlem.startPlus(this)
            // Termux из фона своё окно не поднимает (Android не даёт) — поднимаем мы,
            // пока сами на экране: через секунду открываем Termux на новой сессии.
            ui.postDelayed({ Shlem.showTermux(this) }, 1500)
            "Плюс: Termux открывает Плюса."
        } catch (e: Exception) {
            "Плюс: Termux не запустил (${e.message}). В Termux один раз: allow-external-apps=true."
        }
    }

    @Deprecated("startActivityForResult — без androidx это прямой путь")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode != RESULT_OK || data == null) {
            CaptureService.status = "Разрешение на захват не дано."
            allInOne = false
            return
        }
        // Токен согласия одноразовый: сразу отдаём его службе.
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
        )
        if (allInOne) startPlus()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }
}
