package com.questclaude.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Часы смеси гостей и их голоса в шлеме (2.5.0): проигрыватель 48 кГц моно, такт — 20 мс
 * (Mikser.tik). Звук помечен «не записывать» (как «в ухо» у Авы): захват звука эфира его не
 * берёт — гости в эфире ровно один раз (их кладёт эфир сам, Mikser.vEfir). Проигрыватель не
 * создался — часы по таймеру, гостей в шлеме не слышно, остальное работает.
 */
class GostiZvuk(private val mikser: Mikser) {
    @Volatile private var rabotaet = false
    private var potok: Thread? = null
    @Volatile var oshibka: String? = null
        private set

    fun start() {
        if (rabotaet) return
        rabotaet = true
        potok = thread(name = "gosti-zvuk", isDaemon = true) { zhit() }
    }

    fun stop() {
        rabotaet = false
        potok?.join(1500)
        potok = null
    }

    private fun zhit() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(Mikser.KADR)
        val track = try {
            val min = AudioTrack.getMinBufferSize(Mikser.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Mikser.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, Mikser.KADR * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build().also { it.play() }
        } catch (e: Exception) {
            oshibka = "проигрыватель: ${e.message ?: e.javaClass.simpleName}"
            null
        }
        var sled = System.nanoTime()
        var t = track
        while (rabotaet) {
            try {
                Hub.gostiZvuchat = mikser.tik(buf)
            } catch (e: Exception) {
                oshibka = "смесь: ${e.message ?: e.javaClass.simpleName}"
                java.util.Arrays.fill(buf, 0)
            }
            val w = t?.write(buf, 0, buf.size) ?: -1                   // ждёт, пока проигрыватель возьмёт: это и есть часы
            if (w < 0) {
                if (t != null) { oshibka = "проигрыватель: запись $w"; try { t.release() } catch (e: Exception) { }; t = null }
                sled += 20_000_000L
                val zhdat = sled - System.nanoTime()
                if (zhdat > 0) Thread.sleep(zhdat / 1_000_000, (zhdat % 1_000_000).toInt())
                else if (zhdat < -200_000_000L) sled = System.nanoTime()
            }
        }
        Hub.gostiZvuchat = false
        try { t?.stop() } catch (e: Exception) { }
        try { t?.release() } catch (e: Exception) { }
    }
}
