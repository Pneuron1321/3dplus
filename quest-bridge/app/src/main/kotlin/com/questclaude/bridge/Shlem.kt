package com.questclaude.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Шлем и Termux из приложения глаз — чтобы не вводить команды руками.
 *
 *  - Сон. Команда Meta для автотестов выключает датчик приближения до перезагрузки:
 *    снятый шлем не засыпает. 04.10 проверено: шлем принимает её и от обычного
 *    приложения (Termux `am broadcast`), adb не нужен. Цена — батарея.
 *  - Плюс. Termux RUN_COMMAND: Termux сам открывает окно и запускает `plus2` —
 *    скрипт Termux: `proot-distro login plus2 -- /usr/local/bin/plus` (своя Ubuntu
 *    второй версии; первая, «ubuntu», остаётся версии 1.0). Нужно разрешение
 *    com.termux.permission.RUN_COMMAND (спросит Android) и один раз в Termux
 *    `allow-external-apps=true` в ~/.termux/termux.properties.
 */
object Shlem {
    const val RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX = "/data/data/com.termux/files"

    fun noSleep(ctx: Context) = ctx.sendBroadcast(Intent("com.oculus.vrpowermanager.prox_close"))

    fun sleepAsUsual(ctx: Context) = ctx.sendBroadcast(Intent("com.oculus.vrpowermanager.automation_disable"))

    /** Окно Termux на передний план (04.10: из фона Termux мелькал значком и не открывался). */
    fun showTermux(ctx: Context) = try {
        ctx.startActivity(Intent().setClassName("com.termux", "com.termux.app.TermuxActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        termuxReply = "Termux не открылся: ${e.message}"
    }

    /** Ответ Termux на RUN_COMMAND: ошибку запуска (allow-external-apps и т. п.) показываем на пульте. */
    @Volatile var termuxReply = ""

    class Reply : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val r = intent.getBundleExtra("result") ?: return
            val err = r.getInt("err", 0)
            val msg = r.getString("errmsg").orEmpty()
            // Ответ приходит, когда команда кончилась: Плюс в окне Termux работает — ответа нет
            termuxReply = if (err == -1) "Плюс: окно Termux закрылось, код ${r.getInt("exitCode", -1)}."  // -1 = RESULT_OK
                else "Плюс: Termux отказал ($err): ${msg.take(200)}"
        }
    }

    /**
     * Бросает исключение, если Android не дал позвать Termux (нет разрешения).
     * Отказ самого Termux (нет allow-external-apps) приходит в Reply.
     *
     * podnyat — Плюс пропал (Quest убил Termux по кнопке Meta в игре), поднимаем его сами:
     * тот же разговор (`claude --continue`), уши Termux берут фразы с события s, окно
     * Termux поверх игры не выводим (сессия в фоне, SESSION_ACTION 1).
     */
    fun startPlus(ctx: Context, podnyat: Boolean = false, s: Long = 0) {
        val i = Intent("com.termux.RUN_COMMAND")
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX/usr/bin/plus2")
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "$TERMUX/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", if (podnyat) "1" else "0")   // 0 — новое окно сразу на экран
        if (podnyat) i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("--continue", "--s", s.toString()))
        val reply = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Reply::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // Termux дописывает результат
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
        termuxReply = ""
        // Так советует Termux для Android 8+: обычный startService к спящему Termux не доходит.
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
}
