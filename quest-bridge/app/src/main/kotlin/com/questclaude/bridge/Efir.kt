package com.questclaude.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Эфир прямо из приложения, одновременно с глазами Плюса. Картинка — тот же захват экрана:
 * развилка (Razvilka) отдаёт кадр аппаратному кодировщику H.264; звук — звук игры (захват
 * воспроизведения на той же проекции) и микрофон ушей (EarService), AAC; отправка — свой
 * RTMP (Rtmp) через очередь с переподключением (Otpravka). Termux для эфира не нужен:
 * Quest убивает Termux по Meta в игре — эфир это переживает.
 *
 * 2.5.0: гости — в звук эфира (Mikser.vEfir); игра — в смесь гостей (Mikser.fon), а Ава и голос
 * донатеру — отдельно (Mikser.golos, 2.5.3: «Только голоса» гасит гостю игру, но не Плюса);
 * звук игры — в субтитры (Rech «igra», 16 кГц).
 */
class Efir(
    private val projection: MediaProjection,
    private val razvilka: Razvilka,
    private val o: Opts,
    key: String,
) {
    class Opts(val url: String, val w: Int, val h: Int, val fps: Int, val kbps: Int, val akbps: Int = 160,
               val mic: Float = MIC) {
        companion object {
            const val TWITCH = "rtmp://live.twitch.tv/app"
            /** Голос в эфире по умолчанию — втрое громче записи ушей. */
            const val MIC = 3f

            /** Из запроса /efir/start: size=720|1080, fps, kbps, url (только Twitch или свой шлем). */
            fun iz(q: Map<String, String>, mic: Float = MIC): Opts {
                val url = q["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: TWITCH
                require(razreshyon(url)) { "адрес эфира — только Twitch (ключ не уходит чужим)" }
                val p = if (q["size"] == "720") 1280 to 720 else 1920 to 1080
                return Opts(url, p.first, p.second, q["fps"]?.toIntOrNull()?.coerceIn(15, 60) ?: 30,
                    q["kbps"]?.toIntOrNull()?.coerceIn(1000, 8000) ?: 6000,
                    mic = q["mic"]?.toFloatOrNull()?.let { glush(it) } ?: mic)
            }

            fun glush(g: Float) = g.coerceIn(0.5f, 8f)

            fun razreshyon(url: String) = Otpravka.adresRazreshyon(url)
        }
    }

    /** Громкость голоса в эфире — меняется на ходу (`efir mic 4`). */
    @Volatile var mikrofon = o.mic

    private var venc: MediaCodec? = null
    private var vsurf: Surface? = null
    private var aenc: MediaCodec? = null
    private var rec: AudioRecord? = null
    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()
    private val mic = Mikrofon()
    private val vstavka = Vstavka()                      // голос только в эфир (ответ донатеру)
    private val vstavkaGolos = Vstavka()                 // Ава «вслух» прямо в эфир (2.3.9, «oba»)
    private val otpravka = Otpravka({
        val r = Rtmp(o.url, key)
        r.connect()
        r.meta(o.w, o.h, o.fps, o.kbps, o.akbps, RATE)
        r
    }, { klyuchevoy() })

    @Volatile var zvuk = "нет"
        private set
    /** Начало этого эфира — для налёта («за эфир»). */
    val nachaloMs = System.currentTimeMillis()
    /** Twitch принял поток (не подключаемся и не в обрыве) — налёт капает. */
    val vEfire: Boolean get() = otpravka.state == "efir"
    @Volatile private var kadrov = 0L
    /** Потерянные кадры эфира всего — диспетчеру «тесно». */
    val drops: Long get() = otpravka.drops
    private var lastSent = 0L
    private var lastT = System.currentTimeMillis()

    fun start() {
        running = true
        startVideo()
        startAudio()
        otpravka.start()
    }

    fun stop() {
        running = false
        Hub.micTap = null
        if (Hub.vstavka === vstavka) Hub.vstavka = null
        if (Hub.vstavkaGolos === vstavkaGolos) Hub.vstavkaGolos = null
        vstavka.ochistit()
        vstavkaGolos.ochistit()
        otpravka.stop()
        vsurf?.let { try { razvilka.remove(it) } catch (e: Exception) { } }
        try { rec?.stop() } catch (e: Exception) { }
        threads.forEach { it.join(1500) }
        for (c in listOf(venc, aenc)) try { c?.stop() } catch (e: Exception) { }
        for (c in listOf(venc, aenc)) try { c?.release() } catch (e: Exception) { }
        rec?.release()
        vsurf?.release()
        venc = null; aenc = null; rec = null; vsurf = null
    }

    /** Состояние для /efir и экрана приложения. */
    fun status(): String {
        val now = System.currentTimeMillis()
        val sent = otpravka.sent
        val kbps = if (now > lastT) ((sent - lastSent) * 8 / (now - lastT)).toInt() else 0
        lastSent = sent; lastT = now
        val s = otpravka.state
        val sek = if (s == "efir") (now - otpravka.efirS) / 1000 else 0
        return "{\"state\":\"$s\",\"sec\":$sek,\"kbps\":$kbps,\"kadrov\":$kadrov,\"drops\":${otpravka.drops}," +
            "\"podklyucheniy\":${otpravka.podklyucheniy},\"zvuk\":${FrameServer.json(zvuk)}," +
            "\"razmer\":\"${o.w}x${o.h}@${o.fps}\",\"tsel_kbps\":${o.kbps},\"mic\":$mikrofon," +
            "\"mic_propuski\":${mic.propuski},\"mic_sbrosy\":${mic.sbrosy}," +
            // 2.3.9: игра или эфир — кадры экрана (то, что видит игрок), загрузки плашек, глаза Плюса
            "\"ekran_fps\":${String.format(java.util.Locale.ROOT, "%.1f", razvilka.ekranFps)}," +
            "\"plashki_ms\":${String.format(java.util.Locale.ROOT, "%.1f", razvilka.plashkiMsSek)}," +
            "\"zagruzok\":${razvilka.zagruzokSek},\"glaza_fps\":${razvilka.glazaFps}," +
            "\"tesno\":${Hub.dispetcher.tesno},\"tesno_pochemu\":${FrameServer.json(Hub.dispetcher.pochemu)}," +
            "\"teplo\":${Hub.teplo}," +
            "\"oshibka\":${otpravka.oshibka?.let { FrameServer.json(it) } ?: "null"}}"
    }

    /** Коротко — для уведомления и экрана: «ЭФИР 12:34 · 5980 кбит/с». */
    fun kratko(): String = when (otpravka.state) {
        "efir" -> "ЭФИР ${(System.currentTimeMillis() - otpravka.efirS) / 60000} мин · звук: $zvuk"
        "obryv" -> "ЭФИР: обрыв, переподключаюсь… ${otpravka.oshibka.orEmpty()}"
        "podklyuchayus" -> "ЭФИР: подключаюсь… ${otpravka.oshibka.orEmpty()}"
        else -> "эфир выключен"
    }

    // ---------- видео ----------

    private fun startVideo() {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fun format(high: Boolean, cbr: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, o.w, o.h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, o.kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, o.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)                  // Twitch: ключевой раз в 2 с
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)    // экран стоит — кадр повторяется
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)                      // время показа = время кодирования
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        var ok = false
        for ((high, cbr) in listOf(true to true, false to true, false to false)) {
            try {
                c.configure(format(high, cbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ok = true
                break
            } catch (e: Exception) {
                c.reset()
            }
        }
        check(ok) { "кодировщик H.264 не принял настройки" }
        val s = c.createInputSurface()
        c.start()
        venc = c
        vsurf = s
        razvilka.add(s, o.fps, stamp = true, ow = o.w, oh = o.h, sloi = true)   // поверх — окно камеры
        threads += thread(name = "efir-video", isDaemon = true) { videoLoop(c) }
    }

    private fun klyuchevoy() {
        try { venc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (e: Exception) { }
    }

    private fun videoLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val i = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: IllegalStateException) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val csd = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"))
                    .fold(ByteArray(0)) { acc, b -> acc + ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                zagolovok(csd)
            } else if (i >= 0) {
                val b = c.getOutputBuffer(i)
                val a = ByteArray(info.size)
                if (b != null) { b.position(info.offset); b.get(a) }
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) zagolovok(a)
                else if (a.isNotEmpty()) {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    kadrov++
                    otpravka.video(Flv.video(Flv.avcc(a), key), info.presentationTimeUs, key)
                }
            }
        }
    }

    private fun zagolovok(annexB: ByteArray) {
        val (sps, pps) = Flv.spsPps(annexB)
        if (sps != null && pps != null) otpravka.videoConfig = Flv.video(Flv.avcConfig(sps, pps), key = true, config = true)
    }

    // ---------- звук ----------

    private fun startAudio() {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, o.akbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        aenc = c
        // Звук игры: захват воспроизведения на той же проекции (игра может запретить — тогда без него).
        rec = try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(CHUNK * 4 * 8)
                .build().also { r ->
                    r.startRecording()
                    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); throw IllegalStateException("не пишет") }
                }
        } catch (e: Exception) {
            zvukOshibka = e.message ?: e.javaClass.simpleName
            null
        }
        Hub.micTap = { b, n -> mic.add(b, n) }
        Hub.vstavka = vstavka
        Hub.vstavkaGolos = vstavkaGolos
        zvuk = (if (rec != null) "игра + микрофон" else "микрофон (звук игры не дали: $zvukOshibka)")
        threads += thread(name = "efir-zvuk", isDaemon = true) { audioLoop(c) }
    }

    private var zvukOshibka = ""
    private var fon = ShortArray(CHUNK)
    private var vst = ShortArray(CHUNK * 2)                  // вставки эфира (Ава, ответ донатеру) — отдельно, для гостей
    private val igra16 = ShortArray(CHUNK / 3 + 2)
    private var igraOst = 0                                  // сумма и счёт недобранной тройки (48 → 16 кГц)
    private var igraOstN = 0

    /** Стерео 48 кГц игры → моно 16 кГц (среднее по три) → Rech «igra»; не слушают — сразу мимо. */
    private fun igraVRech(b: ShortArray, fr: Int) {
        val r = Hub.rechi
        if (!r.efir || !r.igra || !r.demonZhiv) return
        var n = 0
        for (j in 0 until fr) {
            igraOst += (b[2 * j] + b[2 * j + 1]) / 2
            if (++igraOstN == 3) { igra16[n++] = (igraOst / 3).toShort(); igraOst = 0; igraOstN = 0 }
        }
        if (n > 0) r.podat("igra", igra16, n)
    }

    private fun audioLoop(c: MediaCodec) {
        // Звук эфира — впереди распознавания речи и кодировщиков: иначе в тяжёлые моменты рвётся.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(CHUNK * 2)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var base = System.nanoTime() / 1000
        while (running) {
            val r = rec
            val got: Int
            if (r != null) {
                got = r.read(buf, 0, buf.size)
                if (got <= 0) { Thread.sleep(5); continue }
            } else {                                         // без звука игры — часы сами
                val due = base + frames * 1_000_000L / RATE
                val wait = due - System.nanoTime() / 1000
                if (wait > 0) Thread.sleep(wait / 1000, ((wait % 1000) * 1000).toInt())
                buf.fill(0)
                got = buf.size
            }
            val fr = got / 2
            if (r != null) igraVRech(buf, fr)                // звук игры — в субтитры (если демон слушает)
            val m = Hub.mikser
            if (m != null) {                                 // гостям — игра отдельно от голоса Плюса (2.5.3: «🔇 Только голоса»)
                if (fon.size < fr) fon = ShortArray(fr)
                for (j in 0 until fr) fon[j] = ((buf[2 * j] + buf[2 * j + 1]) / 2).toShort()
                m.fon(fon, fr)
                if (vst.size < 2 * fr) vst = ShortArray(2 * fr) else java.util.Arrays.fill(vst, 0, 2 * fr, 0)
                vstavka.mix(vst, fr)
                vstavkaGolos.mix(vst, fr)
                for (j in 0 until fr) fon[j] = ((vst[2 * j] + vst[2 * j + 1]) / 2).toShort()
                m.golos(fon, fr)                             // Ава и ответ донатеру — и тишина, чтобы шли в ногу с игрой
                for (i in 0 until 2 * fr) buf[i] = (buf[i] + vst[i]).coerceIn(-32768, 32767).toShort()
            } else {
                vstavka.mix(buf, fr)
                vstavkaGolos.mix(buf, fr)
            }
            mic.mix(buf, fr, mikrofon)
            Hub.mikser?.vEfir(buf, fr)                       // гости — в эфир
            var pts = base + frames * 1_000_000L / RATE
            val real = System.nanoTime() / 1000 - fr * 1_000_000L / RATE
            if (abs(real - pts) > 80_000) { base += real - pts; pts = real }   // часы звука уплыли
            frames += fr
            try {
                val i = c.dequeueInputBuffer(20_000)
                if (i >= 0) {
                    val ib = c.getInputBuffer(i)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, got)
                    c.queueInputBuffer(i, 0, got * 2, pts, 0)
                }
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        c.outputFormat.getByteBuffer("csd-0")?.let { b ->
                            val asc = ByteArray(b.remaining()).also { b.duplicate().get(it) }
                            otpravka.audioConfig = Flv.audio(asc, config = true)
                        }
                    } else if (o >= 0) {
                        val b = c.getOutputBuffer(o)
                        val a = ByteArray(info.size)
                        if (b != null) { b.position(info.offset); b.get(a) }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) otpravka.audioConfig = Flv.audio(a, config = true)
                        else if (a.isNotEmpty()) otpravka.audio(Flv.audio(a), info.presentationTimeUs)
                    } else break
                }
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    companion object {
        const val RATE = 48000
        const val CHUNK = 1024          // кадров звука за раз (21 мс)
    }
}
