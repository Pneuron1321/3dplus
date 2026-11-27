package com.questclaude.most3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Служба переднего плана «подключённое устройство» (проверка 22.10, находка 14): пока карта открыта, Android 14
 * не замораживает процесс, когда окно Моста 3 спрятано, — звук игры, запись и пульт Плюса живут.
 * Не встала (запрет запуска из фона и т. п.) — не страшно: всё работает, пока окно на виду.
 */
class Sluzhba : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(KANAL, "Мост 3", NotificationManager.IMPORTANCE_MIN))
            val n = Notification.Builder(this, KANAL).setContentTitle("Мост 3: карта захвата открыта")
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build()
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Most.zametkaSluzhby = "служба не встала: ${e.javaClass.simpleName}"; stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val KANAL = "most3"
        fun vkl(ctx: Context) = try { ctx.startForegroundService(Intent(ctx, Sluzhba::class.java)); Unit } catch (e: Exception) {
            Most.zametkaSluzhby = "служба не запустилась: ${e.javaClass.simpleName}"
        }
        fun vykl(ctx: Context) = try { ctx.stopService(Intent(ctx, Sluzhba::class.java)); Unit } catch (e: Exception) { }
    }
}
