package com.questclaude.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Уши Плюса: слушает микрофон шлема в фоне, без интернета и без токенов.
 * Кодовые фразы — assets/triggers.json, модель Vosk — русская (она же ловит украинские
 * «плюс плюс», «зум зум»); английскую убрали в 2.2.2 — память. Код умеет и несколько
 * моделей: каждая из triggers.json, что лежит в APK.
 *
 *  - «плюс плюс» — сигнал «говори»; фразу слушает модель без грамматики (если моделей
 *    несколько — берётся та, что уверенней на слово), и она уходит в Hub.ear как "text";
 *  - «зум зум» (×2), «зум зум зум» (×4), «зум зум центр» — свой сигнал, событие
 *    "zoom": Termux (ushi) сам берёт крупный кадр и просит Плюса посмотреть;
 *  - «чек чек» (7 кадров, ±1,5 с) и «зум чек» (13, ±3 с) — событие "plenka": ushi берёт
 *    плёнку момента команды и просит Плюса сказать, что произошло;
 *  - «чек чек плюс» — как «плюс плюс», но фраза — ровно 1,5 с после команды, событие
 *    "chek" (с аудио): ushi отдаёт Плюсу плёнку 7 кадров и распознанный вопрос;
 *  - «плюс тихо» / «plus quiet» и двойное «тише» кнопкой громкости — Ава замолкает;
 *  - «плюс плюс компакт» — подготовка к сжатию контекста, «… компакт плюс» — сжатие;
 *  - двойное «громче» и play/pause Bluetooth-кнопки — как «плюс плюс», без слов.
 *
 * Служба переднего плана типа microphone: Android даёт фоновому приложению микрофон,
 * только если такая служба запущена, пока приложение на экране. Даст ли Quest
 * микрофон во время игры с голосовым чатом — открыто; micDb в /status покажет.
 */
class EarService : Service() {

    companion object {
        const val ACTION_STOP = "com.questclaude.bridge.EAR_STOP"
        const val RATE = 16000
        private const val CHANNEL = "ear"
        private const val SILENT_START_MS = 5000L
        /** Звук для Whisper — с начала слов вызова и ещё на столько раньше (0,25 с). */
        private const val LEAD_MARGIN = RATE / 4L
        /** Слова вызова длиннее не бывают: дальше кольцо не отдаём. */
        private const val LEAD_MAX = RATE * 3L
        /** Секунда после ответа: фраза — с начала громкого и ещё 0,3 с до него (0,5 с назад). */
        private const val DOSLUH_OT = RATE / 2L
        /** Заговорил после ответа, но слов так и нет — столько ждём, потом молча слушаем дальше. */
        private const val DOSLUH_TIHO_MS = 2500L
        /** 2.5.2: «в ухо» Vosk сам не исполняет — он подгонял под него «уши» в разговоре после ложного
         *  вызова («с плюсом»). Точная фраза «плюс плюс в ухо» — вызов, «в ухо» уходит фразой, а Termux
         *  меняет режим, только если и Whisper услышал «плюс плюс» и «ухо»/«уши». */
        private const val UHO = "rezhim:uho"

        @Volatile var running = false
        @Volatile var status = "Уши выключены."
        @Volatile private var wakeRequested = false

        /** Рация: кнопка нажата — слушаем фразу без кодового слова. */
        fun wake() { wakeRequested = true }
    }

    private var session: MediaSession? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(2, notification("Уши: «плюс плюс», «зум зум»"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        Pamyat.start(this)
        Pamyat.zapis("уши: включены")
        try {
            if (Hub.say == null) Hub.say = Golos(cacheDir)
            Hub.acquire("ear")
        } catch (e: Exception) {
            status = "Порт ${Hub.PORT} занят: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startMediaButton()
        startVolumeTrigger()
        worker = thread(name = "ear") { listen() }
        return START_NOT_STICKY
    }

    // ---------- слушание ----------

    private class Listener(val lang: String, val model: Model, var keyword: Recognizer, var base: Long = 0L)

    /** Кусок микрофона и когда он прочитан (мс, как у Dosluh). */
    private class Kus(val pcm: ShortArray, val ms: Long)

    /** Грамматика заново, а не reset(): время слов Vosk считается от создания распознавателя,
     *  base — сэмпл кольца, с которого он слушает. */
    private fun fresh(l: Listener, triggers: Triggers, at: Long) {
        l.keyword.close()
        l.keyword = grammarRecognizer(l.model, triggers, l.lang)
        l.base = at
    }

    private fun grammarRecognizer(m: Model, triggers: Triggers, lang: String) =
        Recognizer(m, RATE.toFloat(), triggers.grammar(lang)).also { it.setWords(true); it.setPartialWords(true) }

    private fun listen() {
        status = "Уши: загружаю модели…"
        val (triggers, assetsByLang) = try { loadTriggers() } catch (e: Exception) {
            status = "Уши: triggers.json не прочитан: ${e.message}"
            return
        }
        val ears = mutableListOf<Listener>()
        for ((lang, asset) in assetsByLang) {
            try {
                val m = Model(unpackModel(asset).absolutePath)
                ears += Listener(lang, m, grammarRecognizer(m, triggers, lang))
            } catch (e: Exception) {
                status = "Уши: модель $lang не загрузилась: ${e.message}"
            }
        }
        if (ears.isEmpty()) return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // буфер 4 с: пока распознаватели догоняют кольцо после вызова, микрофон не теряет звук
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2 * 4))
        } catch (e: SecurityException) {
            status = "Уши: нет разрешения на микрофон."
            ears.forEach { it.keyword.close(); it.model.close() }
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            status = "Уши: микрофон не открылся."
            rec.release()
            ears.forEach { it.keyword.close(); it.model.close() }
            return
        }
        val chooser = Triggers.Chooser()
        val stopWord = Triggers.StopWord(triggers.stop, one = triggers.stopOne)   // свободный текст: варианты
        val early = Triggers.EarlyWake(triggers, triggers.earlyChunks)
        val kolco = Kolco(30, RATE)                    // последние 30 с: фраза с конца слов вызова
        var pending: Triggers.Hit? = null              // голый «плюс плюс»: ждём, не скажет ли другая
        var pendingAt = 0L                             // модель точную команду («… вслух»)
        var pendingFrom: Long? = null                  // его начало слов вызова
        var pendingLeft = 0
        var phrase: List<Pair<String, Recognizer>>? = null     // фразу слушают обе модели
        var phraseStart = 0L
        var heardSomething = false
        val firstPart = HashMap<String, Boolean>()     // по языку: первая часть фразы — может быть короткой командой
        var stopPending = false                        // «копи копи» уже было в звуке из кольца
        var skip = 0                                   // вызов кнопкой: сигнал во фразу не пишем
        val phraseAudio = java.io.ByteArrayOutputStream()
        var phraseLead = 0                             // мс слов вызова в начале phraseAudio
        var phraseHow = ""                             // каким путём сработал вызов
        var loudChunks = 0
        var chekMs = 0L                                // «чек чек плюс»: фраза — столько мс, без пауз
        var dosluhFraza = false                        // фраза без вызова — в секунду после ответа Плюса
        val buf = ShortArray(RATE / 10)              // 0,1 с
        rec.startRecording()
        // Микрофон читает свой поток (2.2.9): голос Александра уходит в эфир сразу, а распознавание
        // берёт куски из очереди. Раньше всё шло одним потоком — после «плюс плюс» распознаватели
        // фразы догоняли кольцо и тяжелели, чтение вставало, и голос в эфире рвался («когда говорю
        // Плюсу, звук чуть прерывается», 09.10). Кольцо и вызов — по-прежнему в порядке распознавания.
        val ochered = java.util.concurrent.ArrayBlockingQueue<Kus>(300)   // 30 с
        val slushat = java.util.concurrent.atomic.AtomicBoolean(true)
        val chitatel = thread(name = "ear-mic", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val b = ShortArray(RATE / 10)
            while (slushat.get()) {
                val n = rec.read(b, 0, b.size)
                if (n <= 0) { Thread.sleep(10); continue }
                val c = b.copyOf(n)
                Hub.micTap?.invoke(c, n)                 // эфир: голос Александра — без задержки
                for (t in Hub.micTapy) t(c, n)           // 2.5.0: гости (их смесь) и субтитры (Rech «ya»)
                val k = Kus(c, Dosluh.seychas())         // время — для секунды после ответа
                if (!ochered.offer(k)) { ochered.poll(); ochered.offer(k) }   // распознавание отстало на 30 с
            }
        }
        Hub.ear.state = "listening"
        val langs = ears.joinToString("+") { it.lang }
        status = "Уши ($langs): слушаю «плюс плюс», «зум зум»."

        /** Короткая команда в начале фразы — исполнить сразу, фразу закрыть. */
        fun command(cmd: Triggers.Hit) {
            phrase?.forEach { it.second.close() }
            phrase = null
            ears.forEach { fresh(it, triggers, kolco.total) }
            Hub.ear.state = "listening"
            beep(Signal.Kind.HEARD)
            val (kind, arg) = cmd.action.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            Hub.ear.post(kind, arg)
            status = "Уши: команда ${cmd.action}."
        }

        /** Итог грамматики во фразе: первая часть — короткая команда? «копи копи» в конце? */
        fun grammarPart(g: Triggers.Heard): Pair<Triggers.Hit?, Boolean> {
            var cmd: Triggers.Hit? = null
            // у каждой модели своя «первая часть»: русская могла услышать в «loud» своё слово
            // раньше, чем английская — команду
            if (firstPart[g.lang] != false && g.text.split(' ').any { it.isNotBlank() && it != "[unk]" }) {
                cmd = triggers.command(g.lang, g.text, g.confs)?.takeIf { it.action != UHO }   // «в ухо» — решит Whisper
                firstPart[g.lang] = false
            }
            return cmd to triggers.endsWithStop(g.lang, g.text, g.confs)
        }

        /** Фраза пошла: свободные распознаватели, звук — [lead] (слова вызова, для Whisper) и [pre]
         *  (сказанное сразу после вызова, из кольца). Грамматика уже начата заново с начала [pre]. */
        fun nachatFrazu(pre: ShortArray, lead: ShortArray, how: String, chek: Boolean) {
            phraseAudio.reset()
            phraseLead = (lead.size * 1000L / RATE).toInt()
            phraseHow = how
            dosluhFraza = how == "dosluh"
            phraseAudio.write(pcm(lead, lead.size))
            val fr = ears.map { l ->
                l.lang to Recognizer(l.model, RATE.toFloat()).also {
                    it.setWords(true)
                    it.setEndpointerDelays(5f, Hub.ear.pauseSec, Hub.ear.maxSec)
                }
            }
            phrase = fr
            // Сказанное сразу после «плюс плюс» — из кольца: во фразу (для Whisper)
            // и распознавателям (грамматике — целиком, свободным — последние 3 с)
            phraseAudio.write(pcm(pre, pre.size))
            loudChunks = loud(pre)
            firstPart.clear()
            stopPending = false
            var cmdNow: Triggers.Hit? = null
            for (g in feedGrammar(pre, ears)) {         // итоги внутри кольца — не теряем
                val (c, st) = grammarPart(g)
                if (c != null && cmdNow == null) cmdNow = c
                if (st) stopPending = true
            }
            feed(pre.copyOfRange(maxOf(0, pre.size - RATE * 3), pre.size), fr.map { it.second })
            phraseStart = SystemClock.uptimeMillis()
            // «чек чек плюс»: 1,5 с после команды, часть уже в кольце (pre)
            chekMs = if (chek) maxOf(1L, Triggers.CHEK_MS - pre.size * 1000L / RATE) else 0L
            heardSomething = false
            stopWord.reset()
            Hub.ear.state = "hearing"
            status = if (dosluhFraza) "Уши: слушаю ответ Плюсу…" else "Уши: говори…"
            if (cmdNow != null) command(cmdNow)
        }
        try {
            while (running) {
                val kus = ochered.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val c = kus.pcm
                val n = c.size
                System.arraycopy(c, 0, buf, 0, n)
                kolco.add(buf, n)
                Hub.ear.micDb = level(buf, n)
                val dosluh = Hub.ear.dosluh
                if (Hub.say?.govorit == true) dosluh.zakryt()      // Ава снова говорит — секунда кончилась
                val p = phrase
                if (p == null) {
                    // Секунда после ответа Плюса (2.3.1): заговорил громко — фраза без «плюс плюс».
                    // Слова вызова в ней ловятся как обычно: «плюс плюс» сильнее (ниже).
                    val zagovoril = dosluh.kusok(kus.ms, Hub.ear.micDb)
                    // Вызов — тремя путями: точная фраза в итоге грамматики; «плюс плюс» в начале
                    // итога, а дальше уже фраза; рано — «плюс плюс» и следующее слово в промежуточном
                    // тексте. wakeAt — сэмпл конца слов вызова: с него берём фразу из кольца.
                    val wakeAt = HashMap<Triggers.Hit, Long>()
                    val wakeFrom = HashMap<Triggers.Hit, Long>()   // начало слов вызова
                    val how = HashMap<Triggers.Hit, String>()
                    val hits = mutableListOf<Triggers.Hit>()
                    val bare = mutableSetOf<Triggers.Hit>()     // точный голый вызов (после паузы)
                    for (e in ears) {
                        if (e.keyword.acceptWaveForm(buf, n)) {
                            early.check(e.lang, emptyList())
                            val json = e.keyword.result
                            val heard = heardOf(e.lang, json)
                            val words = wordsOf(json, "result")
                            val h0 = triggers.match(e.lang, heard.text, heard.confs)
                            // «плюс плюс в ухо» — вызов, а «в ухо» — уже фраза после него (для Whisper)
                            val uho = h0?.action == UHO
                            val h = if (uho) Triggers.Hit("wake", e.lang, h0!!.conf) else h0
                            if (h != null) {
                                if (h.action != "stop") hits += h          // «копи копи» — только внутри фразы
                                if (h.action == "wake" && !uho) bare += h
                                if (h.action == "wake" || h.action == Triggers.CHEK) {
                                    (if (uho) words.getOrNull(1) ?: words.lastOrNull() else words.lastOrNull())
                                        ?.let { wakeAt[h] = e.base + (it.end * RATE).toLong() }
                                    words.firstOrNull()?.let { wakeFrom[h] = e.base + (it.start * RATE).toLong() }
                                    how[h] = if (uho) "точно, в ухо" else "точно"
                                }
                            } else {
                                // «плюс плюс …» или «чек чек плюс …» в начале итога, дальше уже фраза
                                for (a in listOf("wake", Triggers.CHEK)) {
                                    val end = triggers.wakeAtStart(e.lang, words, action = a) ?: continue
                                    val w = Triggers.Hit(a, e.lang, triggers.minConf)
                                    hits += w
                                    wakeAt[w] = e.base + (end * RATE).toLong()
                                    wakeFrom[w] = e.base + (words[0].start * RATE).toLong()
                                    how[w] = "начало итога"
                                    break
                                }
                            }
                        } else {
                            val pw = wordsOf(e.keyword.partialResult, "partial_result")
                            early.check(e.lang, pw)?.let { end ->
                                val w = Triggers.Hit("wake", e.lang, triggers.minConf)
                                hits += w
                                wakeAt[w] = e.base + (end * RATE).toLong()
                                pw.firstOrNull()?.let { wakeFrom[w] = e.base + (it.start * RATE).toLong() }
                                how[w] = "рано"
                            }
                        }
                    }
                    // 2.5.0: гостя слышно в шлеме — голосом Плюса не будим: его «плюс плюс» (и любая команда)
                    // из динамиков не должно звать Плюса с инструментами. Кнопки — как всегда.
                    if (Hub.gostiZvuchat) { hits.clear(); bare.clear(); pending = null }
                    val button = wakeRequested
                    if (button) {
                        wakeRequested = false
                        hits += Triggers.Hit("wake", ears.first().lang, 1.0)
                    }
                    // Точная команда («плюс плюс вслух») сильнее голого вызова: другая модель
                    // могла уверенно услышать только «плюс плюс» — и раньше на несколько кусков.
                    // Поэтому голый вызов после паузы ждёт bareWaitChunks кусков (0,5 с); ранний и
                    // кнопка — сразу.
                    val nowMs = SystemClock.uptimeMillis()
                    val specific = hits.filter { it.action != "wake" }
                    val other = hits.filter { it.action == "wake" && it !in bare }
                    var h: Triggers.Hit? = null
                    when {
                        specific.isNotEmpty() -> { h = chooser.pick(specific, nowMs); pending = null }
                        other.isNotEmpty() -> { h = chooser.pick(other, nowMs); pending = null }
                        bare.isNotEmpty() && pending == null -> {
                            pending = bare.maxByOrNull { it.conf }
                            pendingAt = wakeAt[pending] ?: kolco.total
                            pendingFrom = wakeFrom[pending]
                            pendingLeft = triggers.bareWaitChunks
                        }
                    }
                    if (h == null && pending != null && --pendingLeft <= 0) {
                        h = chooser.pick(listOf(pending!!), nowMs)
                        if (h != null) {
                            wakeAt[h] = pendingAt
                            val pf = pendingFrom
                            if (pf != null) wakeFrom[h] = pf
                            how[h] = "точно, после паузы"
                        }
                        pending = null
                    }
                    if (h == null) {
                        if (zagovoril && pending == null && !Hub.gostiZvuchat) {
                            early.reset()
                            val from = maxOf(0L, kolco.total - DOSLUH_OT)
                            val pre = kolco.since(from)
                            ears.forEach { fresh(it, triggers, from) }
                            val wake = Hub.ear.post("wake", "dosluh")   // Ава ждёт, пока говоришь (без сигнала)
                            Hub.lastWakeMs = wake.ms
                            skip = 0
                            nachatFrazu(pre, ShortArray(0), "dosluh", false)
                        }
                        continue
                    }
                    dosluh.zakryt()
                    early.reset()
                    val from = wakeAt[h]?.coerceAtMost(kolco.total)
                    val pre = if (from == null) ShortArray(0) else kolco.since(from)
                    ears.forEach { fresh(it, triggers, from ?: kolco.total) }   // вторая модель не повторит то же
                    when {
                        h.action == "wake" || h.action == Triggers.CHEK -> {
                            Hub.say?.stop()                  // голос через приложение — замолкает сразу
                            val wake = Hub.ear.post("wake")          // Ава замолкает; момент — для плёнки
                            Hub.lastWakeMs = wake.ms
                            beep(Signal.Kind.WAKE)
                            podnyat(wake.id)
                            skip = if (from == null) 3 else 0
                            // Whisper слышит и сами слова вызова (Termux срежет «плюс плюс» в тексте):
                            // слитно «плюс плюсокно» Vosk ставил конец вызова внутрь «окно» — Whisper
                            // получал «кно» («Так, ну»). Начало вызова надёжно, конец — нет.
                            val leadFrom = if (h.action != "wake" || from == null) null
                                else wakeFrom[h]?.let { maxOf(0L, it - LEAD_MARGIN, from - LEAD_MAX) }
                            val lead = if (leadFrom == null || leadFrom >= from!!) ShortArray(0)
                                else kolco.since(leadFrom).let { it.copyOfRange(0, maxOf(0, it.size - pre.size)) }
                            nachatFrazu(pre, lead, how[h].orEmpty(), h.action == Triggers.CHEK)
                        }
                        h.action.startsWith("rezhim:") -> {
                            beep(Signal.Kind.HEARD)
                            Hub.ear.post("rezhim", h.action.removePrefix("rezhim:"))
                            status = "Уши: ${h.action}."
                        }
                        h.action.startsWith("zoom:") -> {
                            beep(Signal.Kind.ZOOM)
                            Hub.ear.post("zoom", h.action.removePrefix("zoom:"))
                            status = "Уши: ${h.action}."
                        }
                        h.action.startsWith("plenka:") -> {       // «чек чек» — 7 кадров, «зум чек» — 13
                            beep(Signal.Kind.ZOOM)
                            Hub.ear.post("plenka", h.action.removePrefix("plenka:"))
                            status = "Уши: плёнка ${h.action.removePrefix("plenka:")} кадров."
                        }
                        h.action == "tiho" -> {
                            Hub.say?.stop()
                            beep(Signal.Kind.TIHO)
                            Hub.ear.post("tiho")
                            status = "Уши: тихо."
                        }
                        h.action.startsWith("compact:") -> {
                            beep(Signal.Kind.COMPACT)
                            Hub.ear.post("compact", h.action.removePrefix("compact:"))
                            status = "Уши: ${h.action}."
                        }
                    }
                } else {
                    if (skip > 0) { skip--; continue }
                    phraseAudio.write(pcm(buf, n))
                    if (Hub.ear.micDb in 0..45) loudChunks++     // громче −45 дБ — речь, не тишина
                    val ended = p.map { (_, r) -> r.acceptWaveForm(buf, n) }
                    if (!heardSomething) heardSomething = p.any { text(it.second.partialResult, "partial").isNotBlank() }
                    // Грамматика слушает ту же фразу. «Копи копи» в конце её итога, каждое слово
                    // уверенно, — фраза уходит сразу. Промежуточный текст грамматики не берём:
                    // обрывал на полуслове (CI). Первая часть фразы целиком — короткая команда
                    // («в ухо», «вслух», «компакт») — исполняем сразу, без паузы тишины.
                    var cmd: Triggers.Hit? = null
                    val byGrammar = ears.map { e ->
                        if (!e.keyword.acceptWaveForm(buf, n)) return@map false
                        val (c, st) = grammarPart(heardOf(e.lang, e.keyword.result))
                        if (c != null && cmd == null) cmd = c
                        st
                    }
                    val c = cmd
                    if (c != null) {
                        command(c)
                        continue
                    }
                    val byFree = p.map { (lang, r) -> stopWord.check(lang, text(r.partialResult, "partial")) }
                    val stopped = !ended.any { it } && (byGrammar.any { it } || byFree.any { it } || stopPending)
                    val elapsed = SystemClock.uptimeMillis() - phraseStart
                    val chek = chekMs > 0
                    if (ended.any { it } || stopped || elapsed > Hub.ear.maxSec * 1000 || (chek && elapsed >= chekMs) ||
                        (!heardSomething && elapsed > (if (dosluhFraza) DOSLUH_TIHO_MS else SILENT_START_MS))) {
                        val heard = p.mapIndexed { i, (lang, r) -> heardOf(lang, if (ended[i]) r.result else r.finalResult) }
                        val best = triggers.bestPhrase(heard)
                        val raw = best?.text?.trim().orEmpty()
                        val t = best?.let { stopWord.strip(it.lang, raw) }.orEmpty()
                        val byStop = stopped || t != raw          // «копи копи» было — Termux срежет и у Whisper
                        p.forEach { it.second.close() }
                        phrase = null
                        ears.forEach { fresh(it, triggers, kolco.total) }
                        Hub.ear.state = "listening"
                        // Фразу отдаём и с аудио: Whisper в Termux распознаёт точнее Vosk.
                        // Vosk ничего не понял, но речь была — всё равно отдаём, пусть Whisper.
                        chekMs = 0L
                        if (chek) {                    // плёнку — и без слов: вопрос тогда «что произошло»
                            Hub.ear.post("chek", t, phraseAudio.toByteArray(), byStop)
                            beep(Signal.Kind.HEARD)
                            status = "Уши: чек чек плюс «$t»."
                        } else if (t.isNotEmpty() || (loudChunks >= 3 && !dosluhFraza)) {   // после ответа — только со словами
                            Hub.ear.post("text", t, phraseAudio.toByteArray(), byStop, lead = phraseLead, how = phraseHow)
                            beep(Signal.Kind.HEARD)
                            status = "Уши: услышал «$t» (${best?.lang})" + (if (byStop) ", копи копи." else ".")
                        } else if (dosluhFraza) {
                            status = "Уши: после ответа слов не было. Слушаю."   // не к Плюсу — молча
                        } else {
                            beep(Signal.Kind.NOTHING)
                            status = "Уши: фразы не было. Слушаю."
                        }
                        dosluhFraza = false
                    }
                }
            }
        } finally {
            slushat.set(false)
            chitatel.join(1000)                            // read ждёт не дольше куска (0,1 с)
            rec.stop()
            rec.release()
            phrase?.forEach { it.second.close() }
            ears.forEach { it.keyword.close(); it.model.close() }
            Hub.ear.state = "off"
        }
    }

    /**
     * Плюс был жив и пропал (Quest убивает Termux по кнопке Meta в игре) — на «плюс плюс»
     * поднимаем его сами, с тем же разговором; фраза дойдёт, когда Claude готов (~15–20 с).
     * Не чаще раза в минуту; Плюса ещё не было в этом запуске — не трогаем.
     */
    private fun podnyat(s: Long) {
        val now = SystemClock.elapsedRealtime()
        if (Hub.ear.plusSeenMs == 0L || Hub.ear.plusAlive() || now - lastPodyom < 60_000) return
        lastPodyom = now
        try {
            Shlem.startPlus(this, podnyat = true, s = s)
            podyomov++
            beep(Signal.Kind.PODYOM)
            status = "Уши: Плюс пропал — поднимаю с тем же разговором (раз: $podyomov)."
        } catch (e: Exception) {
            status = "Уши: Плюс пропал, поднять не вышло: ${e.message}"
        }
    }

    private var lastPodyom = -60_000L
    private var podyomov = 0

    /** PCM 16 бит → байты (little endian), как в WAV. */
    private fun pcm(a: ShortArray, n: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort(a[i])
        return b.array()
    }

    /** Сколько кусков по 0,1 с громче −45 дБ — речь, не тишина. */
    private fun loud(a: ShortArray): Int {
        var k = 0
        var i = 0
        while (i + RATE / 10 <= a.size) {
            if (level(a.copyOfRange(i, i + RATE / 10), RATE / 10) in 0..45) k++
            i += RATE / 10
        }
        return k
    }

    /** Догнать грамматику звуком из кольца; итоги, случившиеся внутри, — по порядку. */
    private fun feedGrammar(a: ShortArray, ears: List<Listener>): List<Triggers.Heard> {
        val out = mutableListOf<Triggers.Heard>()
        var i = 0
        while (i < a.size) {
            val k = minOf(RATE / 10, a.size - i)
            val piece = a.copyOfRange(i, i + k)
            for (e in ears) if (e.keyword.acceptWaveForm(piece, k)) out += heardOf(e.lang, e.keyword.result)
            i += k
        }
        return out
    }

    /** Догнать распознаватели звуком из кольца — кусками по 0,1 с, как с микрофона. */
    private fun feed(a: ShortArray, rs: List<Recognizer>) {
        var i = 0
        while (i < a.size) {
            val k = minOf(RATE / 10, a.size - i)
            val piece = a.copyOfRange(i, i + k)
            rs.forEach { it.acceptWaveForm(piece, k) }
            i += k
        }
    }

    /** Слова Vosk со временем: итог ("result") или промежуточный текст ("partial_result"). */
    private fun wordsOf(json: String, key: String): List<Triggers.Word> = try {
        val a = JSONObject(json).optJSONArray(key)
        (0 until (a?.length() ?: 0)).map {
            val w = a!!.getJSONObject(it)
            Triggers.Word(w.optString("word"), w.optDouble("start", 0.0), w.optDouble("end", 0.0), w.optDouble("conf", 1.0))
        }
    } catch (e: Exception) { emptyList() }

    /** Результат Vosk: текст и уверенность каждого слова. */
    private fun heardOf(lang: String, json: String): Triggers.Heard = try {
        val o = JSONObject(json)
        val words = o.optJSONArray("result")
        val confs = (0 until (words?.length() ?: 0)).map { words!!.getJSONObject(it).optDouble("conf", 0.0) }
        Triggers.Heard(lang, o.optString("text", ""), confs)
    } catch (e: Exception) { Triggers.Heard(lang, "", emptyList()) }

    /** assets/triggers.json → правила и модели по языкам (только те, что лежат в APK). */
    private fun loadTriggers(): Pair<Triggers, Map<String, String>> {
        val o = JSONObject(assets.open("triggers.json").bufferedReader().readText())
        val models = o.getJSONObject("models")
        val phrases = mutableMapOf<String, Map<String, String>>()
        val decoys = mutableMapOf<String, List<String>>()
        val stop = mutableMapOf<String, Set<String>>()
        val stopOne = mutableMapOf<String, Set<String>>()
        val commands = mutableMapOf<String, Map<String, String>>()
        val assetsByLang = linkedMapOf<String, String>()
        val present = assets.list("")?.toSet() ?: emptySet()
        for (lang in models.keys()) {
            val m = models.getJSONObject(lang)
            val ph = m.getJSONObject("phrases")
            phrases[lang] = ph.keys().asSequence().associateWith { ph.getString(it) }
            val d = m.optJSONArray("decoys")
            decoys[lang] = (0 until (d?.length() ?: 0)).map { d!!.getString(it) }
            val st = m.optJSONArray("stop")
            stop[lang] = (0 until (st?.length() ?: 0)).map { st!!.getString(it) }.toSet()
            val so = m.optJSONArray("stopOne")
            stopOne[lang] = (0 until (so?.length() ?: 0)).map { so!!.getString(it) }.toSet()
            val cm = m.optJSONObject("commands")
            commands[lang] = cm?.keys()?.asSequence()?.associateWith { cm.getString(it) } ?: emptyMap()
            val asset = m.getString("asset")
            if (asset in present) assetsByLang[lang] = asset
        }
        val bonus = o.optJSONObject("phraseBonus")
        val phraseBonus = bonus?.keys()?.asSequence()?.associateWith { bonus.getDouble(it) } ?: emptyMap()
        return Triggers(o.optDouble("minConf", 0.6), phrases, decoys, phraseBonus, stop, stopOne, commands,
            o.optInt("earlyChunks", 3), o.optInt("bareWaitChunks", 5)) to assetsByLang
    }

    private fun text(json: String, key: String = "text"): String =
        try { JSONObject(json).optString(key, "") } catch (e: Exception) { "" }

    /** Уровень, дБ ниже полной шкалы (0 — громче некуда, 90 — тишина). */
    private fun level(buf: ShortArray, n: Int): Int {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n)
        return if (rms < 1.0) 90 else (-20 * log10(rms / 32768.0)).toInt()
    }

    /** Свой сигнал через AudioTrack: системный ToneGenerator на Quest не звучал. */
    private fun beep(kind: Signal.Kind) {
        val pcm = Signal.pcm(kind)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Signal.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            thread(isDaemon = true) {
                Thread.sleep(pcm.size * 1000L / Signal.RATE + 200)
                track.release()
            }
        } catch (e: Exception) {
            status = "Уши: сигнал не сыграл: ${e.message}"
        }
    }

    /** Модели лежат в APK (assets); Vosk нужен путь к файлам — копируем один раз на версию модели. */
    private fun unpackModel(asset: String): File {
        val dir = File(filesDir, asset)
        val want = assets.open("$asset/uuid").bufferedReader().readText().trim()
        val have = File(dir, "uuid").takeIf { it.exists() }?.readText()?.trim()
        if (have == want) return dir
        dir.deleteRecursively()
        copyAssets(asset, dir)
        return dir
    }

    private fun copyAssets(path: String, to: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (c in children) copyAssets("$path/$c", File(to, c))
    }

    // ---------- кнопки-рации ----------

    /**
     * play/pause Bluetooth-кнопки или наушников. Android отдаёт медиакнопку сессии
     * приложения, которое последним играло звук, — поэтому при старте играем
     * короткую тишину. Работает ли так на Quest во время игры — проверить.
     */
    private fun startMediaButton() {
        val s = MediaSession(this, "QuestClaudeEar")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.keyCode in setOf(
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    wake()
                    return true
                }
                return super.onMediaButtonEvent(intent)
            }
        })
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .build())
        s.isActive = true
        session = s
        playSilence()
    }

    private fun playSilence() {
        val samples = RATE / 5                        // 0,2 с
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        track.write(ShortArray(samples), 0, samples)
        track.play()
        thread(isDaemon = true) {
            Thread.sleep(500)
            track.release()
        }
    }

    /**
     * Кнопка громкости шлема, двойное нажатие за 0,7 с: «громче» — как «плюс плюс»,
     * «тише» — как «плюс тихо». Громкость возвращаем, как была до первого нажатия.
     */
    private fun startVolumeTrigger() {
        val audio = getSystemService(AudioManager::class.java)
        val r = object : BroadcastReceiver() {
            private var firstMs = 0L
            private var firstUp = false
            private var before = -1
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
                val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                val prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                if (now < 0 || prev < 0 || now == prev) return
                val up = now > prev
                val t = SystemClock.uptimeMillis()
                if (t - firstMs < 700 && up == firstUp) {
                    firstMs = 0
                    if (before >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                    if (up) wake() else {
                        beep(Signal.Kind.TIHO)
                        Hub.ear.post("tiho")
                    }
                } else {
                    firstMs = t
                    firstUp = up
                    before = prev
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, filter)
        volumeReceiver = r
    }

    // ---------- служба ----------

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Уши", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Quest Claude Bridge 2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Pamyat.trim(level)
    }

    override fun onDestroy() {
        Pamyat.zapis("уши: выключены")
        running = false
        worker?.join(2000)
        worker = null
        volumeReceiver?.let { unregisterReceiver(it) }
        volumeReceiver = null
        session?.release()
        session = null
        Hub.release("ear")
        status = "Уши выключены."
        super.onDestroy()
    }
}
