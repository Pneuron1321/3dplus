package com.questclaude.bridge

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Замер памяти. 06.10 в Onward Quest закрыл приложение («почти достигли лимита используемой
 * памяти устройства») — эфир, уши и глаза кончились разом. Каждые 10 с — сколько свободно у
 * шлема и сколько держит приложение (java, native, графика); просьбы системы отдать память
 * (onTrimMemory) — сразу. Пишется в файл приложения: переживает закрытие, после него видно,
 * что было перед ним. GET /pamyat — хвост записи.
 */
object Pamyat {
    private const val MB = 1024 * 1024
    private const val MAKS = 400_000L          // байт: больше — оставляем последнюю половину
    @Volatile private var file: File? = null
    private val vremya = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)

    @Synchronized fun start(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "pamyat.log")
        file = f
        zapis("— приложение запущено (pid ${android.os.Process.myPid()}); прошлая запись выше —")
        // 18.10: мост закрылся дважды без нагрузки, причины не видно — падение пишем сюда же, потом как обычно
        val bylo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                zapis("ПАДЕНИЕ в потоке «${t.name}»: " + sw.toString().take(3000).replace("\n", " ⏎ "))
            } catch (x: Throwable) { }
            // 2.7.6: ошибка в фоновом потоке (страница, сеть, гость) больше не закрывает весь мост — эфир, гости
            // и уши живут дальше; падает только главный поток или нехватка памяти, как раньше.
            if (t === android.os.Looper.getMainLooper().thread || e is VirtualMachineError) bylo?.uncaughtException(t, e)
            else try { zapis("мост жив: упал только поток «${t.name}»") } catch (x: Throwable) { }
        }
        val am = ctx.applicationContext.getSystemService(ActivityManager::class.java)
        thread(name = "pamyat", isDaemon = true) {
            while (true) {
                try { zapis(zamer(am)) } catch (e: Exception) { zapis("замер не вышел: ${e.message}") }
                Thread.sleep(10_000)
            }
        }
    }

    private fun zamer(am: ActivityManager): String {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val d = Debug.MemoryInfo()
        Debug.getMemoryInfo(d)
        val st = d.memoryStats
        fun kb(k: String) = (st[k]?.toIntOrNull() ?: 0) / 1024
        val efir = Hub.efir?.status()?.let { if (it.contains("\"state\":\"off\"")) "нет" else "идёт" } ?: "нет"
        return "шлем: свободно ${mi.availMem / MB} МБ из ${mi.totalMem / MB}, порог ${mi.threshold / MB}" +
            (if (mi.lowMemory) ", МАЛО" else "") +
            " · приложение ${d.totalPss / 1024} МБ (java ${kb("summary.java-heap")}, native ${kb("summary.native-heap")}," +
            " графика ${kb("summary.graphics")}, код ${kb("summary.code")}) · эфир $efir · захват ${if (Hub.capturing) "да" else "нет"}"
    }

    private var lastTrim = -1 to 0L

    /** Система просит отдать память — уровень и что он значит (обе службы слышат одно и то же — пишем раз). */
    @Synchronized fun trim(level: Int) {
        val now = System.currentTimeMillis()
        if (lastTrim.first == level && now - lastTrim.second < 2000) return
        lastTrim = level to now
        zapis("система просит память: " + when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (следующие на закрытие)"
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN (окно скрыто)"
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL (памяти почти нет)"
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            else -> "RUNNING_MODERATE"
        } + " ($level)")
    }

    @Synchronized fun zapis(s: String) {
        val f = file ?: return
        try {
            f.appendText("${vremya.format(Date())} $s\n")
            if (f.length() > MAKS) {
                val t = f.readText()
                f.writeText(t.substring(t.indexOf('\n', t.length / 2) + 1))
            }
        } catch (e: Exception) { }
    }

    /** Последние n строк записи. */
    fun hvost(n: Int): String = try {
        file?.readLines()?.takeLast(n.coerceIn(1, 2000))?.joinToString("\n", postfix = "\n") ?: "замер не запущен\n"
    } catch (e: Exception) { "запись не читается: ${e.message}\n" }
}
