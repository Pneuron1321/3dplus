package com.questclaude.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Звук карты захвата (2.6.0, Александр 20.10: в HDMI Link картинка есть, а звука нет — «построим в бридже»).
 * Карта (Elgato 4K X и т. п.) — для Android обычная USB-звуковая карта (UAC): мост пишет с неё звук игры и
 * сразу играет в шлем (USAGE_GAME, низкая задержка). Захват звука эфира (Efir) берёт всё, что играет шлем, —
 * значит игра сама попадает в эфир и в субтитры игры («igra»), ничего отдельно склеивать не надо.
 * Карту вставили — начинает сам; вынули — молчит. Выключить — /karta/upr?vkl=0 (запоминается).
 * Картинку по-прежнему показывает HDMI Link (видео в мосте — потом, если понадобится).
 */
class KartaZvuk(private val ctx: Context, private val dir: File) : FrameServer.KartaUpr {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val lock = Any()
    @Volatile private var vkl = true
    @Volatile private var grom = 1.0f
    @Volatile private var rabotaet = false
    private var potok: Thread? = null
    @Volatile private var ustroystvo: AudioDeviceInfo? = null
    @Volatile private var db = -90f
    @Volatile private var oshibka: String? = null
    @Volatile private var hz = 0
    @Volatile private var kanalov = 0
    @Volatile private var dbMaks = -90f                  // громче всего за последние 0,5 с
    @Volatile private var zaglushen = false              // Android сам глушит нашу запись (фон, чужой микрофон)
    @Volatile private var marshrut = ""                  // с какого входа на самом деле пишет
    private val nastroyki = File(dir, "karta-zvuk")

    private val slushatel = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = proverit()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = proverit()
    }

    fun start() {
        zagruzit()
        am.registerAudioDeviceCallback(slushatel, Handler(Looper.getMainLooper()))
        proverit()
    }

    fun stop() {
        try { am.unregisterAudioDeviceCallback(slushatel) } catch (e: Exception) { }
        ostanovit()
    }

    /** Вход USB есть и включено — пишем; нет — стоп. */
    private fun proverit() = synchronized(lock) {
        val d = if (vkl) vybrat(vhody()) else null
        if (d?.id == ustroystvo?.id && (d == null || rabotaet)) return@synchronized
        ostanovit()
        if (d != null) zapustit(d)
    }

    private fun vhody(): List<AudioDeviceInfo> = try { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() } catch (e: Exception) { emptyList() }

    private fun vybrat(v: List<AudioDeviceInfo>): AudioDeviceInfo? =
        v.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE } ?: v.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    private fun zapustit(d: AudioDeviceInfo) {
        ustroystvo = d
        oshibka = null
        rabotaet = true
        potok = thread(name = "karta-zvuk", isDaemon = true) { cikl(d) }
    }

    private fun ostanovit() {
        rabotaet = false
        potok?.join(1000)
        potok = null
        ustroystvo = null
        db = -90f
    }

    private fun cikl(d: AudioDeviceInfo) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = d.sampleRates.takeIf { it.isNotEmpty() }?.let { r -> if (48000 in r) 48000 else r.maxOrNull() } ?: 48000
        val stereo = d.channelCounts.isEmpty() || d.channelCounts.any { it >= 2 }
        val vhodMaska = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val kusok = rate / 100 * (if (stereo) 2 else 1)                          // 10 мс
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val minR = AudioRecord.getMinBufferSize(rate, vhodMaska, AudioFormat.ENCODING_PCM_16BIT)
            // UNPROCESSED — без шумодава и АРУ микрофона (это игра, не голос); не умеет — обычный MIC
            for (ist in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
                val r = try {
                    AudioRecord(ist, rate, vhodMaska, AudioFormat.ENCODING_PCM_16BIT, maxOf(minR, kusok * 2 * 4))
                } catch (e: Exception) { null }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                r?.release()
            }
            if (rec == null) throw IllegalStateException("Android не дал записать с карты")
            rec.setPreferredDevice(d)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("запись не пошла")
            val minT = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())   // эфир берёт её как звук игры
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minT, rate / 100 * 4 * 3))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            hz = rate; kanalov = if (stereo) 2 else 1
            Pamyat.zapis("карта захвата: звук «${d.productName}» ${rate} Гц ${kanalov} кан. → в шлем и эфир")
            val b = ShortArray(kusok)
            val st = ShortArray(rate / 100 * 2)
            var srednee = 0.0
            var n = 0
            var pik = 0
            marshrut = rec.routedDevice?.let { "${it.productName} (тип ${it.type})" }.orEmpty()
            while (rabotaet) {
                val got = rec.read(b, 0, b.size)
                if (got <= 0) { Thread.sleep(5); continue }
                val g = grom
                var s = 0.0
                val vyhod: ShortArray
                val dlina: Int
                if (stereo) {
                    for (i in 0 until got) {
                        val v = (b[i] * g).toInt().coerceIn(-32768, 32767); b[i] = v.toShort(); s += v.toDouble() * v
                        if (v > pik) pik = v else if (-v > pik) pik = -v
                    }
                    vyhod = b; dlina = got
                } else {
                    for (i in 0 until got) {
                        val v = (b[i] * g).toInt().coerceIn(-32768, 32767)
                        st[2 * i] = v.toShort(); st[2 * i + 1] = v.toShort(); s += v.toDouble() * v
                        if (v > pik) pik = v else if (-v > pik) pik = -v
                    }
                    vyhod = st; dlina = got * 2
                }
                track.write(vyhod, 0, dlina)
                srednee += s / got; n++
                if (n >= 50) {                                                    // раз в 0,5 с — громкость для status
                    val r = sqrt(srednee / n)
                    db = if (r < 1) -90f else (20 * log10(r / 32768.0)).toFloat()
                    dbMaks = if (pik < 1) -90f else (20 * log10(pik / 32768.0)).toFloat()
                    zaglushen = try { rec.activeRecordingConfiguration?.isClientSilenced == true } catch (e: Exception) { false }
                    rec.routedDevice?.let { marshrut = "${it.productName} (тип ${it.type})" }
                    srednee = 0.0; n = 0; pik = 0
                }
            }
        } catch (e: Exception) {
            oshibka = "звук карты: ${e.message ?: e.javaClass.simpleName}"
            Pamyat.zapis("карта захвата: $oshibka")
            rabotaet = false
        } finally {
            try { rec?.stop() } catch (e: Exception) { }
            rec?.release()
            try { track?.stop() } catch (e: Exception) { }
            track?.release()
        }
    }

    // ---------- управление ----------

    override fun status(): String {
        val d = ustroystvo
        val vse = vhody().joinToString(",") { "{\"imya\":${FrameServer.json(it.productName?.toString().orEmpty())},\"tip\":${it.type}," +
            "\"usb\":${it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET}}" }
        return "{\"vkl\":$vkl,\"idyot\":${rabotaet && d != null},\"karta\":${d?.let { FrameServer.json(it.productName?.toString().orEmpty()) } ?: "null"}," +
            "\"hz\":$hz,\"kanalov\":$kanalov,\"db\":${Math.round(db)},\"db_maks\":${Math.round(dbMaks)}," +
            "\"zaglushen\":$zaglushen,\"marshrut\":${FrameServer.json(marshrut)},\"gromkost\":${Math.round(grom * 100)}," +
            "\"oshibka\":${oshibka?.let { FrameServer.json(it) } ?: "null"},\"vhody\":[$vse]}"
    }

    /** vkl=0|1 — звук карты в шлем; g=0..150 — громкость, %. */
    override fun upr(q: Map<String, String>): String {
        q["vkl"]?.let { vkl = it != "0" && it != "vykl" }
        q["g"]?.toFloatOrNull()?.let { grom = (it / 100f).coerceIn(0f, 1.5f) }
        sohranit()
        proverit()
        return status()
    }

    fun kratko(): String {
        val d = ustroystvo
        return when {
            !vkl -> "звук карты: выключен"
            d != null && rabotaet -> "ЗВУК КАРТЫ: «${d.productName}» → шлем и эфир, ${Math.round(db)} дБ"
            oshibka != null -> oshibka!!
            else -> "звук карты: карты нет (USB-звук не найден)"
        }
    }

    private fun zagruzit() {
        val c = try { nastroyki.readText().trim().split(' ') } catch (e: Exception) { emptyList() }
        if (c.isNotEmpty()) vkl = c[0] != "0"
        c.getOrNull(1)?.toFloatOrNull()?.let { grom = it.coerceIn(0f, 1.5f) }
    }

    private fun sohranit() {
        try { nastroyki.writeText("${if (vkl) 1 else 0} $grom") } catch (e: Exception) { }
    }
}
