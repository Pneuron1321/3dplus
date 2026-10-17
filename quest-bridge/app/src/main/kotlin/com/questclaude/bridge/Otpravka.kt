package com.questclaude.bridge

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Отправщик эфира: кодировщики кладут кадры в очередь, отдельный поток шлёт их на сервер.
 * Сервер недоступен или оборвал — переподключается каждые [pauzaMs] и начинает с ключевого
 * кадра (просит его у кодировщика). Сеть не успевает — очередь сбрасывается, а не копит
 * минуты задержки. Время каждого подключения — с нуля, с первого ключевого кадра.
 * Обычная JVM: проверяется на столе с поддельным каналом (OtpravkaTest).
 */
class Otpravka(
    private val podklyuchit: () -> Kanal,
    private val nuzhenKlyuchevoy: () -> Unit = {},
    private val pauzaMs: Long = 3000,
    private val limit: Int = 400,
) {
    /** Куда шлём (Rtmp; в тестах — подделка). Бросает IOException при обрыве. */
    interface Kanal {
        fun video(payload: ByteArray, ms: Long)
        fun audio(payload: ByteArray, ms: Long)
        val alive: Boolean
        val sent: Long
        fun close()
        /** Конец эфира по просьбе (не обрыв): сказать серверу «трансляция окончена», потом закрыть. */
        fun zakonchit() = close()
    }

    private class Pkt(val video: Boolean, val payload: ByteArray, val ptsUs: Long, val key: Boolean)

    companion object {
        /** Куда можно слать ключ эфира: только Twitch (и сам шлем — для проверки). */
        fun adresRazreshyon(url: String): Boolean = try {
            val u = java.net.URI(url)
            val h = u.host.orEmpty()
            (u.scheme == "rtmp" || u.scheme == "rtmps") &&
                (h == "live.twitch.tv" || h.endsWith(".twitch.tv") || h.endsWith(".contribute.live-video.net") ||
                    h == "127.0.0.1" || h == "localhost")
        } catch (e: Exception) { false }
    }

    private val q = LinkedBlockingQueue<Pkt>()
    private val sbros = AtomicBoolean(false)
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var kanal: Kanal? = null

    /** Заголовки потоков (Flv.video/audio с config) — ставят кодировщики, когда узнают. */
    @Volatile var videoConfig: ByteArray? = null
    @Volatile var audioConfig: ByteArray? = null

    /** off | podklyuchayus | efir | obryv */
    @Volatile var state = "off"
        private set
    @Volatile var oshibka: String? = null
        private set
    @Volatile var drops = 0L
        private set
    @Volatile var podklyucheniy = 0
        private set
    @Volatile var efirS = 0L                  // когда началось нынешнее подключение, мс
        private set
    private var sentBefore = 0L

    /** Сколько байт ушло за всё время (все подключения). */
    val sent: Long get() = sentBefore + (kanal?.sent ?: 0L)

    fun video(payload: ByteArray, ptsUs: Long, key: Boolean) = put(Pkt(true, payload, ptsUs, key))
    fun audio(payload: ByteArray, ptsUs: Long) = put(Pkt(false, payload, ptsUs, false))

    private fun put(p: Pkt) {
        if (!running) return
        if (q.size >= limit) {                 // сеть не успевает: не копим задержку
            drops += q.size
            q.clear()
            sbros.set(true)
        }
        q.offer(p)
    }

    fun start() {
        running = true
        state = "podklyuchayus"
        worker = thread(name = "efir-otpravka", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(3000)
        kanal?.let { sentBefore += it.sent; it.zakonchit() }      // Twitch: «эфир окончен», не «разрыв связи»
        kanal = null
        q.clear()
        state = "off"
    }

    private fun loop() {
        var needKey = true
        var base = -1L
        var vSent = false
        var aSent = false
        while (running) {
            var k = kanal
            if (k == null || !k.alive) {
                if (k != null) {
                    sentBefore += k.sent
                    k.close()
                    kanal = null
                    state = "obryv"
                }
                k = try {
                    podklyuchit()
                } catch (e: Exception) {
                    oshibka = e.message ?: e.javaClass.simpleName
                    if (state != "obryv") state = "podklyuchayus"
                    try { Thread.sleep(pauzaMs) } catch (e: InterruptedException) { return }
                    continue
                }
                needKey = true; base = -1L; vSent = false; aSent = false
                q.clear()                                       // старое — не в новый эфир
                kanal = k
                podklyucheniy++
                efirS = System.currentTimeMillis()
                oshibka = null
                state = "efir"
                nuzhenKlyuchevoy()
            }
            val p = try { q.poll(300, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { return } ?: continue
            if (sbros.getAndSet(false)) {
                needKey = true
                nuzhenKlyuchevoy()
            }
            try {
                if (p.video) {
                    if (!vSent) {                               // заголовок видео — перед первым кадром
                        val c = videoConfig
                        if (c == null || !p.key) { drops++; continue }
                        k.video(c, 0)
                        vSent = true
                    }
                    if (needKey) {
                        if (!p.key) { drops++; continue }
                        needKey = false
                        if (base < 0) base = p.ptsUs
                    }
                    val ms = (p.ptsUs - base) / 1000
                    if (ms >= 0) k.video(p.payload, ms)
                } else {
                    if (base < 0 || needKey) continue           // звук — после первого ключевого кадра
                    val ms = (p.ptsUs - base) / 1000
                    if (ms < 0) continue
                    if (!aSent) {
                        val c = audioConfig ?: continue
                        k.audio(c, ms)
                        aSent = true
                    }
                    k.audio(p.payload, ms)
                }
            } catch (e: IOException) {
                oshibka = e.message ?: "обрыв"
                sentBefore += k.sent
                k.close()
                kanal = null
                state = "obryv"
            }
        }
    }
}

/**
 * Микрофон ушей (16 кГц моно, куски по 0,1 с) → в звук эфира (48 кГц стерео): кольцо и
 * подмешивание с линейной интерполяцией. Обычная JVM.
 *
 * Запас (2.3.2): куски приходят раз в 0,1 с и не ровно, а звук эфира берёт по 21 мс. Без запаса
 * кольцо пустело посреди слова — в эфире микропауза («когда говорю Плюсу, звук чуть
 * прерывается», 08.10; в тишине их не слышно). Теперь опустело — молчим, пока не наберётся
 * [zapas] (0,15 с), и играем дальше с ним; накопилось больше [MNOGO] — догоняем на 2 % быстрее
 * (на слух незаметно) вместо выброса; больше [SBROS] (сильно отстали) — выбрасываем до запаса.
 */
class Mikrofon(private val cap: Int = 16000, private val zapas: Int = ZAPAS) {
    private val a = ShortArray(cap)
    private var r = 0
    private var w = 0
    private var size = 0
    private var frac = 0.0
    private var igraet = false                               // запас набран — подмешиваем
    /** Сколько раз звук голоса кончился посреди игры (микропауза в эфире) — для /efir. */
    @Volatile var propuski = 0
        private set
    /** Сколько раз выбросили накопившееся (отстали больше чем на 0,5 с). */
    @Volatile var sbrosy = 0
        private set

    @Synchronized fun add(b: ShortArray, n: Int) {
        for (i in 0 until n) {
            a[w] = b[i]
            w = (w + 1) % cap
            if (size < cap) size++ else r = (r + 1) % cap
        }
    }

    /** Подмешать в стерео [out] (L,R,L,R…) [frames] кадров 48 кГц. gain — громкость голоса:
     *  микрофон ушей пишется для распознавания, без автоусиления, — в эфире тихий (06.10);
     *  громкое выше 3/4 шкалы мягко поджимается, а не хрипит. */
    @Synchronized fun mix(out: ShortArray, frames: Int, gain: Float = 1f) {
        if (size > SBROS) {                                  // сильно отстали — догоняем сразу
            val drop = size - zapas
            r = (r + drop) % cap
            size -= drop
            sbrosy++
        }
        if (!igraet) {
            if (size < maxOf(2, zapas)) return               // набираем запас — пока тишина
            igraet = true
        }
        val shag = if (size > MNOGO) SHAG * 1.02 else SHAG   // копится — чуть быстрее
        for (j in 0 until frames) {
            if (size < 2) {                                  // кончился посреди — снова набираем
                igraet = false
                propuski++
                break
            }
            val s0 = a[r].toInt()
            val s1 = a[(r + 1) % cap].toInt()
            val v = myagko(((s0 + (s1 - s0) * frac) * gain).toInt())
            out[2 * j] = (out[2 * j] + v).coerceIn(-32768, 32767).toShort()
            out[2 * j + 1] = (out[2 * j + 1] + v).coerceIn(-32768, 32767).toShort()
            frac += shag
            if (frac >= 1.0) {
                frac -= 1.0
                r = (r + 1) % cap
                size--
            }
        }
    }

    @Synchronized fun available() = size

    companion object {
        /** Запас перед игрой после опустения: 0,15 с — больше куска микрофона (0,1 с). */
        const val ZAPAS = 2400
        /** Больше 0,3 с — догоняем на 2 % быстрее. */
        const val MNOGO = 4800
        /** Больше 0,5 с — выбрасываем до запаса. */
        const val SBROS = 8000
        /** 16 кГц → 48 кГц: на кадр эфира — треть отсчёта микрофона. */
        private const val SHAG = 1.0 / 3
        private const val KOLENO = 24000
        /** Мягкий ограничитель: выше колена — вчетверо тише. */
        fun myagko(v: Int): Int {
            val a = kotlin.math.abs(v)
            if (a <= KOLENO) return v
            val m = KOLENO + (a - KOLENO) / 4
            return if (v < 0) -m else m
        }
    }
}
