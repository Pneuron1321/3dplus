package com.questclaude.bridge

import android.app.Activity
import android.os.Bundle

/**
 * Вход карты (2.6.5). Quest не показывает окно «Разрешить доступ» на requestPermission (21.10: «жду разрешения»,
 * окна нет), но показывает свой выбор «Choose an app to use with your video device» при подключении карты.
 * Выбрали мост — Android сам даёт ему доступ; мост выставляет картинку карты и сразу открывает HDMI Link.
 * «Always» — так будет при каждом подключении без вопросов.
 */
class KartaVhod : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        Thread {
            try {
                Hub.kartaZapustit(app, app.filesDir)
                Hub.kartinka?.primenit(sprosit = false)
            } catch (e: Exception) { }
            runOnUiThread {
                try { packageManager.getLaunchIntentForPackage(HDMI_LINK)?.let { startActivity(it) } } catch (e: Exception) { }
                finish()
            }
        }.start()
    }

    companion object {
        const val HDMI_LINK = "com.meta.quest_hard_link"
    }
}
