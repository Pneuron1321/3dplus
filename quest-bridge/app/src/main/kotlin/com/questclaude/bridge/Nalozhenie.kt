package com.questclaude.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Плашки эфира. Только в кадре эфира (развилка рисует их в выход кодировщика), глаза Плюса их
 * не видят. Подписи в эфире — по-английски (Александр 08.10).
 *
 * Правый верх (в самом углу): тонкая строка налёта, под ней лицо Плюса (на 15 % меньше прежнего),
 * под плечами — цель доната шириной в плечи (2.2.8). Левый верх: в самом углу — топ-донатор
 * шириной в плечи Плюса (2.3.0), под ним — табло соведущего (ставки, вызов). Лицо — бюст робота из рендера 3dplus (голова и его же
 * плечи: шея, грудь, наплечники — Александр: «плечи такими, как были у модели») со строчной
 * развёрткой «как в телевизоре», как у старого лица студии (Screen Studio): тёмные строки
 * ложатся только на пиксели бюста. Живое: моргает раз в 3–6 с, дышит и
 * покачивается, часто улыбается (уголки вверх и зубы); говорит — рот открывается в такт и голова
 * кивает (и когда Ава в ухо: Александр так хочет); слушает — светится пришелец на макушке;
 * думает — взгляд вбок и точки над головой; Плюса нет — бледное. Налёт: всего и за этот эфир
 * одной строкой; сотня — золотая надпись и звон (звон слышит и эфир).
 *
 * Плюс снимает (2.3.7, Александр 11.10: «пусть держит телефон перед собой двумя руками, как бы
 * снимает; когда камера включена — снимает, когда нет — телефона в руках нет; REC и таймер не
 * надо»): пока камера телефона в эфире, Плюс двумя руками держит перед грудью iPhone
 * горизонтально, спинкой с камерами к зрителю. Руки и телефон — рендер той же модели 3dplus тем
 * же ракурсом, что лицо и плечи (plus-ruki-telefon.png поверх, plus-plechi-bez-ruk.png под
 * головой); дышит и покачивается вместе с бюстом. 2.3.8: телефон чёрный, на спинке вместо яблока —
 * жёлтый пришелец с макушки Плюса, надкушенный (дорисовано на картинке, docs/zagotovki/znachok.py).
 * 2.4.0 (Александр 13.10): окно камеры стоя (айфон вертикально) — и телефон у Плюса стоя
 * (plus-ruki-telefon-v.png, docs/zagotovki/ruki_telefon.py); пришелец надкушен с обеих сторон.
 *
 * Левый низ (в самом углу) — роза ветров в 3D (2.3.5, Roza3D): лучи жёлтые и розово-фиолетовые,
 * обод красный, контуры тонкие чёрные; крутится и переворачивается сама, как попало (2.3.6) — это
 * украшение, не компас.
 *
 * Живёт, пока идёт эфир; раз в секунду начисляет налёт (Nalet.tik), если платформа приняла поток.
 *
 * Бережливый фон (2.3.9, план эфира III.3–III.4, IV.2–IV.3): роза — 5 кадров в секунду, лицо — 14 только
 * пока Ава говорит (рот), слушает — 10, иначе 5 (покачивание), моргание и улыбка — сразу. Разговор
 * (слушает, думает, говорит) — роза стоит, глаза Плюса — 1 кадр в секунду (первые 2 с после «плюс
 * плюс» — 2: кадр вызова в плёнке), вне разговора — 2. Чат перерисовывается не чаще 4 раз в секунду;
 * больше 2 сообщений в секунду — быстрый режим: по строке на сообщение, последние 5, обращённые
 * к Плюсу и донаты — в первую очередь. Раз в секунду шагает диспетчер «тесно» (Dispetcher); тесно самой
 * игре — роза стоит, лицо реже (10 кадров в речи, иначе 5), глаза — 1 кадр в секунду.
 */
class Nalozhenie(
    ctx: Context,
    private val nalet: Nalet,
    private val kompas: Kompas,
    private val razvilka: Razvilka,
    private val efir: () -> Efir?,
    private val pokaz: () -> Pokaz,
    private val titry: Titry = Titry(),
    private val cel: Cel = Cel({ null }, { }),
    private val itogi: Itogi = Itogi(),
    private val igra: Igra = Igra(),
    private val top: Top = Top({ null }, { }),
    private val chat: Chat = Chat({ null }, { }),
    /** Сколько кадров в секунду отдавать глазам Плюса (CaptureService меняет выход развилки). */
    private val glaza: (Int) -> Unit = { },
) {
    /** Что показывать (запоминает CaptureService): лицо, налёт, радар; telefon — Плюс снимает, когда видно окно камеры. */
    class Pokaz(val lico: Boolean = true, val nalet: Boolean = true, val radar: Boolean = true, val telefon: Boolean = true)

    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)
    private val radarBmp = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888)
    private val rc = Canvas(radarBmp)
    private val titrBmp = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
    private val tc = Canvas(titrBmp)
    private val igraBmp = Bitmap.createBitmap(IW, LVH, Bitmap.Config.ARGB_8888)      // левый верх: топ-донатор и табло
    private val ic = Canvas(igraBmp)
    private val chatBmp = Bitmap.createBitmap(CW, CH, Bitmap.Config.ARGB_8888)    // справа: чат Twitch
    private val chc = Canvas(chatBmp)
    private val chp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = CSZ }
    private var itogiBmp: Bitmap? = null                         // 1920×1080 — только пока итоги на экране
    private val lico = kartinka(ctx, "plus-lico.png")
    private val svet = kartinka(ctx, "plus-lico-svet.png")       // пришелец светится
    private val plechiImg = kartinka(ctx, "plus-plechi.png")     // плечи модели, тот же масштаб
    private val plechiBez = kartinka(ctx, "plus-plechi-bez-ruk.png")    // плечи без рук — руки держат телефон
    private val rukiTel = kartinka(ctx, "plus-ruki-telefon.png")        // обе руки с телефоном перед грудью, тот же масштаб
    private val rukiTelV = kartinka(ctx, "plus-ruki-telefon-v.png")     // то же, телефон стоя — окно камеры стоя (2.4.0)
    private val nachalo = System.currentTimeMillis()                  // время розы
    private val dvizhenie = Roza3D.Dvizhenie()                        // как крутится роза в этом эфире
    @Volatile private var rabotaet = false
    private var potok: Thread? = null
    private val rnd = java.util.Random()
    private var morgDo = 0L
    private var ulybkaS = 0L
    private var ulybkaDo = 0L
    private var otvetNa = 0L                                      // на какой вызов Ава уже ответила

    private val kist = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 14, 16, 24) }
    private val chern = Paint().apply { color = Color.rgb(24, 20, 18) }
    private val glaz = Paint().apply { color = Color.rgb(19, 2, 0) }
    private val steklo = Paint().apply { color = Color.rgb(87, 8, 0) }          // очки вокруг глаз
    private val zhelt = Paint().apply { color = Color.rgb(193, 163, 42) }        // лицо у уголков рта
    private val zuby = Paint().apply { color = Color.rgb(246, 244, 236) }
    private val razvyortka = Paint().apply {
        color = Color.argb(66, 0, 0, 0)                                          // 0,26 — как в студии
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)                  // только на пиксели лица
    }
    private val tochki = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val moneta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tekst = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private val obvodka = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; style = Paint.Style.STROKE
        strokeWidth = 6f; color = Color.argb(220, 0, 0, 0); strokeJoin = Paint.Join.ROUND
    }
    private val tt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }   // субтитры, цель, итоги
    private val tto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        color = Color.argb(230, 0, 0, 0)
    }
    private val pod = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rk = Paint(Paint.ANTI_ALIAS_FLAG)                                // радар
    private val rt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val rKontur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Roza3D.CHERN
    }
    private val rPut = Path()

    fun start() {
        rabotaet = true
        kompas.start()
        itogi.novyEfir()
        potok = thread(name = "nalozhenie", isDaemon = true) {
            var tikMs = System.currentTimeMillis() + 1000
            var bylo = ""
            var radarBylo = ""
            var titrBylo = ""
            var igraBylo = ""
            var itogiBylo = ""
            var chatBylo = ""
            var vyzovBylo = Hub.lastWakeMs                            // вызовы до эфира не считаем
            var sostBylo = ""
            var liceMs = 0L
            var mimikaBylo = ""
            var rozaMs = 0L
            var chatMs = 0L
            var glazaBylo = 0
            while (rabotaet) {
                val now = System.currentTimeMillis()
                val sost = sostoyanie(now)
                val razgovor = sost == "govorit" || sost == "slushaet" || sost == "dumaet"
                val tesnoIgre = Hub.dispetcher.igre                   // тесно игре — бридж сбрасывает своё
                mimika(now, sost == "govorit")
                shag("глаза") {                                       // глаза Плюса: в разговоре — реже
                    val g = if ((razgovor || tesnoIgre) && now - Hub.lastWakeMs !in 0L..2_000L) GLAZA_RAZGOVOR else GLAZA_EFIR
                    if (g != glazaBylo) { glaza(g); glazaBylo = g }
                }
                if (now >= tikMs) {
                    tikMs = if (now - tikMs > 5000) now + 1000 else tikMs + 1000
                    val e = efir()
                    if (e != null && e.vEfire && nalet.tik(e.nachaloMs, now)) zvon()
                    shag("диспетчер") { Hub.dispetcher.shag(now, razvilka.ekranFps, e?.drops ?: 0L, Hub.teplo, chat.skorost(now)) }
                }
                val p = pokaz()
                val anim = p.lico || nalet.sotnyaVidna(now)               // лицо живёт всегда
                val klyuch = "${p.lico} ${p.nalet} ${nalet.vsegoSek} ${nalet.efirSek} ${nalet.sotnyaVidna(now)} ${cel.status()}"
                // каждая плашка — сама по себе: споткнулась одна (лицо, роза) — чат, субтитры и остальные живут
                shag("лицо") {
                    if (!p.lico && !p.nalet && !cel.vidna) { if (bylo != "net") razvilka.plashka(null); bylo = "net" }
                    else {
                        val mim = "$morgaet $ulybka"
                        val shagMs = when {
                            tesnoIgre -> if (sost == "govorit") 100L else 200L
                            sost == "govorit" -> 0L
                            sost == "slushaet" -> 100L
                            else -> 200L
                        }
                        if (klyuch != bylo || sost != sostBylo || mim != mimikaBylo || (anim && now - liceMs >= shagMs)) {
                            risovat(p, sost, now)
                            razvilka.plashka(bmp)
                            bylo = klyuch; sostBylo = sost; mimikaBylo = mim; liceMs = now
                        }
                    }
                }
                shag("роза") {
                    if (!p.radar) { if (radarBylo != "net") razvilka.sloy("ln", null); radarBylo = "net" }
                    else if (radarBylo == "net" || (!razgovor && !tesnoIgre && now - rozaMs >= ROZA_MS)) {   // разговор, тесно — стоит
                        radar(now); razvilka.sloy("ln", radarBmp); radarBylo = ""; rozaMs = now
                    }
                }
                val w = Hub.lastWakeMs
                if (w != vyzovBylo) { itogi.vyzovov++; vyzovBylo = w }
                shag("субтитры") {
                    val ts = titry.seychas()                          // субтитры — внизу по центру
                    val tKlyuch = ts.joinToString("|") { it.kto + it.doMs + it.tekst }
                    if (tKlyuch != titrBylo) {
                        if (ts.isEmpty()) razvilka.sloy("nc", null) else { titryRisovat(ts); razvilka.sloy("nc", titrBmp) }
                        titrBylo = tKlyuch
                    }
                }
                shag("табло") {
                    val lv = igra.vidna || top.vidna                   // слева вверху: топ-донатор, под ним табло
                    val gKlyuch = if (lv) "${top.status()} ${igra.status()}" else "net"
                    if (gKlyuch != igraBylo) {
                        if (!lv) razvilka.sloy("lv", null) else { levyVerh(); razvilka.sloy("lv", igraBmp) }
                        igraBylo = gKlyuch
                    }
                }
                shag("чат") {
                    val bystro = chat.skorost(now) > Chat.BYSTRO_V_SEK      // шквал — по строке, важные первыми
                    val cs = chat.vidimye(now).let { if (bystro) Chat.bystryyVybor(it) else it }
                    val chKlyuch = if (cs.isEmpty()) "net"
                        else "${chat.versiya} $bystro ${razvilka.chatMesto} " + cs.joinToString(",") { (chat.yarkost(it, now) * 10).toInt().toString() }
                    // не чаще 4 раз в секунду: в шквал плашка не перерисовывается на каждое сообщение
                    if (chKlyuch != chatBylo && (cs.isEmpty() || now - chatMs >= CHAT_MS)) {
                        if (cs.isEmpty()) { razvilka.sloy("pc", null); chatNaEkrane = 0 }
                        else { chatRisovat(cs, now, bystro); razvilka.sloy("pc", chatBmp) }
                        chatBylo = chKlyuch; chatMs = now
                    }
                }
                shag("итоги") {
                    val e = efir()                                    // итоги — во весь кадр, раз в секунду
                    val iKlyuch = if (!itogi.vidny) "net" else "${itogi.versiya} ${now / 1000} ${cel.status()}"
                    if (iKlyuch != itogiBylo) {
                        if (!itogi.vidny) {
                            razvilka.sloy("vse", null)
                            itogiBmp?.recycle(); itogiBmp = null
                        } else {
                            val b = itogiBmp ?: Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { itogiBmp = it }
                            itogiRisovat(b, if (e != null && e.nachaloMs > 0) now - e.nachaloMs else 0L)
                            razvilka.sloy("vse", b)
                        }
                        itogiBylo = iKlyuch
                    }
                }
                Thread.sleep(if (anim || p.radar) 70 else 200)                // шаг 70 мс; что перерисовать — решают плашки
            }
        }
    }

    /** Сколько сообщений чата нарисовано сейчас (0 — слоя чата нет); ошибка плашек за последнюю минуту — для /chat. */
    @Volatile var chatNaEkrane = 0
        private set
    @Volatile private var oshibkaTekst: String? = null
    @Volatile private var oshibkaMs = 0L
    val oshibka: String? get() = oshibkaTekst.takeIf { System.currentTimeMillis() - oshibkaMs < 60_000L }

    private inline fun shag(imya: String, f: () -> Unit) {
        try { f() } catch (x: Exception) {
            oshibkaTekst = "$imya: ${x.javaClass.simpleName} ${x.message ?: ""}".trim()
            oshibkaMs = System.currentTimeMillis()
        }
    }

    fun stop() {
        rabotaet = false
        potok?.join(1000)
        kompas.stop()
        try { for (u in listOf("pv", "ln", "lv", "nc", "pc", "vse")) razvilka.sloy(u, null) } catch (e: Exception) { }
        itogiBmp?.recycle(); itogiBmp = null
        itogi.konecEfira()
        nalet.zapisat()
    }

    /** govorit — Ава говорит (вслух или в ухо); slushaet — идёт фраза после вызова;
     *  dumaet — вопрос ушёл, ответа нет; spit — Плюса нет; obychno. */
    private fun sostoyanie(now: Long): String {
        val rezhim = (Hub.say as? Golos)?.govoritSeychas                // и когда говорит сама, через Termux
        if (rezhim != null) otvetNa = Hub.lastWakeMs
        return when {
            rezhim != null -> "govorit"
            Hub.ear.state == "hearing" -> "slushaet"
            Hub.lastWakeMs > otvetNa && now - Hub.lastWakeMs in 0L..15_000L -> "dumaet"
            !Hub.ear.plusAlive() -> "spit"
            else -> "obychno"
        }
    }

    // ---------- правый верх: лицо и налёт ----------

    private fun risovat(p: Pokaz, sost: String, now: Long) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val snimaet = p.telefon && razvilka.kameraIdet() && plechiBez != null && rukiTel != null   // камера в эфире — снимает
        val ruki = if (!snimaet) null else if (razvilka.kameraStoya() && rukiTelV != null) Ruki(rukiTelV, RUKI_V_X, RUKI_V_Y)
            else Ruki(rukiTel!!, RUKI_X, RUKI_Y)                      // телефон стоя, когда и камера стоя
        if (p.lico) lico?.let { golova(it, sost, now, ruki) }
        if (p.nalet) poloska(now)
        if (cel.vidna) celPodPlechami()
    }

    /**
     * Цель доната — под плечами Плюса, шириной в плечи (Александр 08.10): название сверху, ниже
     * полоса собранного с суммой «$0 / $700» внутри. Не качается вместе с бюстом.
     */
    private fun celPodPlechami() {
        val k = D / 256f
        val x0 = W - D - PLECHI_VBOK * k - 4 - PLECHI_VBOK * k     // левый край плеч
        val x1 = x0 + 319 * k
        val y0 = SH + 8f - 26 * k + 298.2f * k + 4f                 // низ плеч
        tt.textSize = 15f
        var imya = cel.imya.ifEmpty { "Goal" }
        while (imya.length > 1 && tt.measureText(imya) > x1 - x0 - 8) imya = imya.dropLast(1)
        if (imya.length < cel.imya.length) imya = imya.dropLast(1) + "…"
        val iw = tt.measureText(imya)
        pod.color = Color.argb(205, 14, 16, 24)
        c.drawRoundRect(RectF(x0, y0, x1, y0 + 42f), 10f, 10f, pod)
        tto.textSize = 15f; tto.strokeWidth = 3f
        c.drawText(imya, (x0 + x1 - iw) / 2f, y0 + 16f, tto)
        tt.color = Color.WHITE
        c.drawText(imya, (x0 + x1 - iw) / 2f, y0 + 16f, tt)
        val bar = RectF(x0 + 6f, y0 + 21f, x1 - 6f, y0 + 37f)
        pod.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(bar, 8f, 8f, pod)
        val d = cel.dolya()
        if (d > 0f) {
            pod.color = if (d >= 1f) Color.rgb(255, 205, 40) else Color.rgb(90, 210, 130)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + maxOf(16f, bar.width() * d), bar.bottom), 8f, 8f, pod)
        }
        val summa = cel.tekst()
        tt.textSize = 13f
        val sw = tt.measureText(summa)
        tto.textSize = 13f; tto.strokeWidth = 3f
        c.drawText(summa, bar.centerX() - sw / 2f, bar.bottom - 3.5f, tto)
        tt.color = Color.WHITE
        c.drawText(summa, bar.centerX() - sw / 2f, bar.bottom - 3.5f, tt)
    }

    /**
     * Налёт — тонкой строкой в самом верху угла, над Плюсом (2.2.6): монета, всего, «+N this
     * stream»; сотня — золотая надпись в строке и золотой кант.
     */
    private fun poloska(now: Long) {
        val vsego = Nalet.koinyEn(nalet.vsegoSek)
        val zaEfir = "+" + Nalet.koinyEn(nalet.efirSek) + " this stream"
        val sotnya = nalet.sotnyaVidna(now)
        val podpis = "AIRTIME ${nalet.sotnya}"
        val y = SH / 2f + 7f                                           // базовая линия
        var w = 12f + razmer(16f, zaEfir) + 12f + razmer(21f, vsego) + 8f + 2 * MR + 12f
        if (sotnya) w += razmer(17f, podpis) + 12f
        val pl = RectF(W - w, 0f, W.toFloat(), SH)
        c.drawRoundRect(pl, SH / 2f, SH / 2f, fon)
        if (sotnya) {
            moneta.style = Paint.Style.STROKE; moneta.strokeWidth = 2f; moneta.color = Color.rgb(255, 205, 40)
            c.drawRoundRect(pl, SH / 2f, SH / 2f, moneta)
        }
        var x = W - 12f                                                // текст — к правому краю
        stroka(zaEfir, x, y, 16f, Color.argb(230, 170, 230, 190))
        x -= razmer(16f, zaEfir) + 12f
        stroka(vsego, x, y, 21f, Color.WHITE)
        x -= razmer(21f, vsego) + 8f + MR
        moneta.style = Paint.Style.FILL; moneta.color = Color.rgb(240, 180, 30)
        c.drawCircle(x, SH / 2f, MR, moneta)
        moneta.style = Paint.Style.STROKE; moneta.strokeWidth = 2f; moneta.color = Color.rgb(255, 230, 140)
        c.drawCircle(x, SH / 2f, MR * 0.62f, moneta)
        if (sotnya) stroka(podpis, x - MR - 12f, y, 17f, Color.rgb(255, 205, 40))
    }

    /**
     * Бюст с мимикой, поверх — развёртка. Места — в точках картинки головы 256×256
     * (plus-lico.png): голова 6–250 × 34–221, глаза за очками 92–117 и 148–169 × 129–150,
     * рот — полоса 90–166 × 181–189, уголки 86–90 / 165–170 × 171–181. Плечи (plus-plechi.png,
     * 319×80, тот же масштаб рендера) встают в точку (−31,5; 218,2) — под подбородок.
     */
    /** Моргание и улыбка сейчас — считаются на каждом шаге: лицо перерисуется ровно в их начало и конец. */
    private var morgaet = false
    private var ulybka = false

    private fun mimika(now: Long, govorit: Boolean) {
        // моргание: раз в 3–6 с, 140 мс — как в студии
        if (now > morgDo + 140) morgDo = now + 3000 + rnd.nextInt(3000)
        morgaet = morgDo - now in 1L..140L
        // улыбка: часто — раз в 5–12 с на 1,8 с; в речи рот занят
        if (now > ulybkaDo) { ulybkaS = now + 5000 + rnd.nextInt(7000); ulybkaDo = ulybkaS + 1800 }
        ulybka = !govorit && now in ulybkaS..ulybkaDo
    }

    /** Руки с телефоном: картинка и её левый верх в точках картинки головы 256. */
    private class Ruki(val bmp: Bitmap, val x: Float, val y: Float)

    private fun golova(f: Bitmap, sost: String, now: Long, ruki: Ruki?) {
        val snimaet = ruki != null
        val govorit = sost == "govorit"

        val k = D / 256f
        val left = W - D - PLECHI_VBOK * k - 4                       // плечи шире головы — не обрезать справа
        val top = SH + 8f - 26 * k                                     // под строкой налёта: «…» думает — сразу под ней
        val cx = left + D / 2f
        val podborodok = top + 221 * k

        val levo = left + minOf(RUKI_X, -PLECHI_VBOK) * k - 6
        val sloy = c.saveLayer(levo, 0f, W.toFloat(), H.toFloat(), null)
        c.translate(sin(now / 2600.0).toFloat() * 3f, sin(now / 1400.0).toFloat() * 3f)   // покачивание и дыхание
        (if (snimaet) plechiBez else plechiImg)?.let {                 // плечи модели — под головой, не кивают
            c.drawBitmap(it, null, RectF(left - PLECHI_VBOK * k, top + 218.2f * k,
                left + (319 - PLECHI_VBOK) * k, top + (218.2f + 80) * k), kist)
        }
        c.save()
        var naklon = 0f
        if (govorit) naklon += sin(now / 150.0).toFloat() * 2.5f      // кивок в речи
        if (sost == "dumaet") naklon += 4f                             // голова чуть набок
        c.rotate(naklon, cx, podborodok)
        val dst = RectF(left, top, left + D, top + D)
        kist.alpha = if (sost == "spit") 110 else 255
        c.drawBitmap(f, null, dst, kist)
        if (sost == "slushaet") svet?.let {
            kist.alpha = (150 + 105 * sin(now / 380.0)).toInt().coerceIn(0, 255)
            c.drawBitmap(it, null, dst, kist)
        }
        kist.alpha = 255
        fun pr(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) =
            c.drawRect(dst.left + x0 * k, dst.top + y0 * k, dst.left + x1 * k, dst.top + y1 * k, p)
        if (morgaet || sost == "dumaet") {                             // закрыть глаза стеклом
            pr(92f, 129f, 117f, 150f, steklo); pr(148f, 129f, 169f, 150f, steklo)
        }
        when {
            morgaet -> { pr(92f, 138f, 117f, 141f, glaz); pr(148f, 138f, 169f, 141f, glaz) }      // веки
            sost == "dumaet" -> { pr(100f, 129f, 122f, 150f, glaz); pr(156f, 129f, 175f, 150f, glaz) } // взгляд вбок
        }
        val r = ROT                                                    // рот (2.2.6) — на ряд кирпичей ниже
        when {
            govorit -> {
                pr(85f, 170f + r, 91f, 181f + r, zhelt); pr(164f, 170f + r, 171f, 181f + r, zhelt)  // уголки прочь
                val otkryt = if (sin(now / 90.0) > 0) 14f else 6f                                 // как в студии
                pr(90f, 181f + r, 166f, 189f + r + otkryt, chern)
            }
            ulybka -> {
                pr(80f, 165f + r, 87f, 182f + r, chern); pr(169f, 165f + r, 176f, 182f + r, chern)  // уголки вверх
                pr(86f, 175f + r, 170f, 197f + r, chern)                       // губы: верхняя = нижняя (7 точек, 11.10)
                pr(91f, 182f + r, 165f, 190f + r, zuby)
                for (x in listOf(103f, 116f, 128f, 140f, 153f)) pr(x, 182f + r, x + 1.6f, 190f + r, chern)  // зубы
            }
        }
        c.restore()
        ruki?.let {                                                    // руки с телефоном — перед грудью, поверх
            c.drawBitmap(it.bmp, null, RectF(left + it.x * k, top + it.y * k,
                left + (it.x + it.bmp.width) * k, top + (it.y + it.bmp.height) * k), kist)
        }
        // строчная развёртка: 2 точки линия, 2 — нет; только на пикселях головы и плеч
        var y = 0f
        while (y < H) { c.drawRect(levo, y, W.toFloat(), y + 2f, razvyortka); y += 4f }
        c.restoreToCount(sloy)
        if (sost == "dumaet") for (i in 0..2) {                       // «…» над головой, по очереди
            val m = D / 190f
            tochki.alpha = if ((now / 300) % 3 == i.toLong()) 255 else 110
            c.drawCircle(cx + (i - 1) * 22f * m, top + 26 * k, 6f * m, tochki)
        }
    }

    private fun razmer(sz: Float, s: String): Float { tekst.textSize = sz; return tekst.measureText(s) }

    private fun stroka(s: String, x: Float, y: Float, sz: Float, cvet: Int) {
        obvodka.textSize = sz
        obvodka.strokeWidth = maxOf(3f, sz * 0.14f)
        tekst.textSize = sz
        tekst.color = cvet
        c.drawText(s, x, y, obvodka)
        c.drawText(s, x, y, tekst)
    }

    // ---------- низ по центру: субтитры на двух языках ----------

    /**
     * Субтитры на двух языках: первый ряд — имя (ALEXANDER голубым, PLUS золотым) и как сказано,
     * белым; второй — перевод, светло-голубым; оба на тёмной подложке. Длинное — перенос, не
     * больше TSTROK строк, новые внизу; старая реплика уходит целиком.
     */
    /**
     * Чат Twitch (2.3.4): снизу вверх — новые внизу; у каждого сообщения полупрозрачная подложка
     * по правому краю (чат стоит у правого края кадра), ник своим цветом, текст белый; не больше
     * 4 строк на сообщение; не влезло — старые не рисуются (развилка ещё и подрежет сверху).
     */
    private fun chatRisovat(ss: List<Chat.Soob>, now: Long, bystro: Boolean = false) {
        chc.drawColor(0, PorterDuff.Mode.CLEAR)
        val potolok = (CH - razvilka.chatMesto).toFloat()           // выше — не влезет (камера, Плюс)
        var niz = CH.toFloat()
        var n = 0
        for (s in ss.asReversed()) {
            val a = chat.yarkost(s, now)
            if (a <= 0f) continue
            val stroki = chatPerenos(s.kto + ": " + s.tekst, CW - 2 * CPAD, if (bystro) 1 else 4)
            val h = stroki.size * CLH + 10f
            val verh = niz - h
            if (verh < potolok) break                                  // только целые сообщения
            val shir = stroki.maxOf { chp.measureText(it) } + 2 * CPAD
            val x = CW - shir
            pod.color = Color.argb((120 * a).toInt(), 0, 0, 0)
            chc.drawRoundRect(RectF(x, verh, CW.toFloat(), niz), 10f, 10f, pod)
            stroki.forEachIndexed { i, l ->
                val y = verh + 5f + CSZ + i * CLH
                chp.color = Color.WHITE
                chp.alpha = (255 * a).toInt()
                chc.drawText(l, x + CPAD, y, chp)
                val nik = s.kto + ":"
                if (i == 0 && l.startsWith(nik)) {
                    chp.color = s.cvet
                    chp.alpha = (255 * a).toInt()
                    chc.drawText(nik, x + CPAD, y, chp)
                }
            }
            niz = verh - CGAP
            n++
        }
        chatNaEkrane = n
    }

    /** Перенос чата: по словам, слишком длинное слово (спам) — по буквам; больше n строк — «…». */
    private fun chatPerenos(t: String, shir: Float, n: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (slovo in t.split(' ')) {
            var sl = slovo
            while (chp.measureText(sl) > shir) {                       // длинное слово — режем
                var k = sl.length - 1
                while (k > 1 && chp.measureText(sl, 0, k) > shir) k--
                if (cur.isNotEmpty()) { out += cur; cur = "" }
                out += sl.substring(0, k)
                sl = sl.substring(k)
            }
            val probe = if (cur.isEmpty()) sl else "$cur $sl"
            if (chp.measureText(probe) <= shir || cur.isEmpty()) cur = probe
            else { out += cur; cur = sl }
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size <= n) return out
        val r = out.take(n).toMutableList()
        var last = r[n - 1]
        while (last.isNotEmpty() && chp.measureText("$last…") > shir) last = last.dropLast(1)
        r[n - 1] = "$last…"
        return r
    }

    private fun titryRisovat(ts: List<Titry.Stroka>) {
        tc.drawColor(0, PorterDuff.Mode.CLEAR)
        tt.textSize = TSZ
        class Ryad(val imya: String, val cvetImeni: Int, val cvet: Int, val tekst: String)
        val repliki = ts.map { st ->
            val imya = if (st.kto == "plus") "PLUS" else "ALEXANDER"
            val ci = if (st.kto == "plus") Color.rgb(255, 205, 70) else Color.rgb(130, 200, 255)
            perenos(imya + "  " + st.tekst, TW - 40f, 2).mapIndexed { i, l -> Ryad(if (i == 0) imya else "", ci, Color.WHITE, l) } +
                (if (st.perevod.isEmpty()) emptyList()
                else perenos(st.perevod, TW - 40f, 2).map { Ryad("", ci, Color.rgb(190, 225, 255), it) })
        }
        val vse = mutableListOf<Ryad>()
        for (r in repliki.asReversed()) {
            if (vse.size + r.size > TSTROK) break
            vse.addAll(0, r)
        }
        var y = TH - 10f - (vse.size - 1) * TLH
        for (r in vse) {
            val shir = tt.measureText(r.tekst)
            val x = (TW - shir) / 2f
            pod.color = Color.argb(165, 0, 0, 0)
            tc.drawRoundRect(RectF(x - 14, y - TSZ - 2, x + shir + 14, y + 12), 10f, 10f, pod)
            tto.textSize = TSZ; tto.strokeWidth = 5f
            tc.drawText(r.tekst, x, y, tto)
            tt.color = r.cvet
            tc.drawText(r.tekst, x, y, tt)
            if (r.imya.isNotEmpty() && r.tekst.startsWith(r.imya)) { tt.color = r.cvetImeni; tc.drawText(r.imya, x, y, tt) }
            y += TLH
        }
    }

    /** Перенос по словам в ширину; больше n строк — последняя с «…». */
    private fun perenos(t: String, shir: Float, n: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (slovo in t.split(' ')) {
            val probe = if (cur.isEmpty()) slovo else "$cur $slovo"
            if (tt.measureText(probe) <= shir || cur.isEmpty()) cur = probe
            else { out += cur; cur = slovo }
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size <= n) return out
        var last = out[n - 1]
        while (last.isNotEmpty() && tt.measureText("$last…") > shir) last = last.dropLast(1)
        return out.take(n - 1) + "${last.trimEnd()}…"
    }

    // ---------- левый верх: топ-донатор и табло соведущего ----------

    /** В самом углу — топ-донатор (если есть), под ним — табло соведущего (если есть). */
    private fun levyVerh() {
        ic.drawColor(0, PorterDuff.Mode.CLEAR)
        var dy = 0f
        if (top.vidna) { topDonator(); dy = TOPH + ZAZOR }
        if (igra.vidna) { ic.save(); ic.translate(0f, dy); igraRisovat(); ic.restore() }
    }

    /**
     * Топ-донатор — шириной в плечи Плюса (Александр 09.10), в том же масштабе, что бюст и цель:
     * сверху золотом «TOP DONOR», ниже ник и сумма «Rex · $25». Длинный ник — с многоточием.
     */
    private fun topDonator() {
        val shir = 319 * D / 256f
        pod.color = Color.argb(205, 14, 16, 24)
        ic.drawRoundRect(RectF(0f, 0f, shir, TOPH), 10f, 10f, pod)
        pod.color = Color.rgb(255, 205, 40)
        ic.drawRoundRect(RectF(0f, 0f, 4f, TOPH), 2f, 2f, pod)                    // золотой кант слева
        fun poCentru(t: String, sz: Float, cvet: Int, y: Float) {
            tt.textSize = sz
            val w = tt.measureText(t)
            tto.textSize = sz; tto.strokeWidth = 3f
            ic.drawText(t, (shir - w) / 2f, y, tto)
            tt.color = cvet
            ic.drawText(t, (shir - w) / 2f, y, tt)
        }
        poCentru("TOP DONOR", 12f, Color.rgb(255, 205, 40), 15f)
        val skolko = " · " + top.skolko()
        tt.textSize = 16f
        var imya = top.imya
        val mesto = shir - 16f - tt.measureText(skolko)
        while (imya.length > 1 && tt.measureText(imya) > mesto) imya = imya.dropLast(1)
        if (imya.length < top.imya.length) imya = imya.dropLast(1) + "…"
        poCentru(imya + skolko, 16f, Color.WHITE, 35f)
    }

    /** «PLUS CALLS 3/5   CHALLENGES 2/3» и ниже текущий вызов «CHALLENGE: pistol only». */
    private fun igraRisovat() {
        val dve = igra.vyzov.isNotEmpty()
        val h = if (dve) IH.toFloat() else 34f
        pod.color = Color.argb(205, 14, 16, 24)
        ic.drawRoundRect(RectF(0f, 0f, IW.toFloat(), h), 14f, 14f, pod)
        var x = 14f
        fun kusok(t: String, sz: Float, cvet: Int, y: Float) {
            tt.textSize = sz; tt.color = cvet
            ic.drawText(t, x, y, tt)
            x += tt.measureText(t)
        }
        if (igra.stavok > 0) {
            kusok("PLUS CALLS ", 17f, Color.rgb(255, 205, 40), 23f)
            kusok("${igra.popal}/${igra.stavok}", 19f, Color.WHITE, 23f)
            x += 22f
        }
        if (igra.vyzovov > 0) {
            kusok("CHALLENGES ", 17f, Color.rgb(130, 200, 255), 23f)
            kusok("${igra.sdelano}/${igra.vyzovov}", 19f, Color.WHITE, 23f)
        }
        if (dve) {
            x = 14f
            kusok("CHALLENGE: ", 16f, Color.rgb(130, 200, 255), 54f)
            tt.textSize = 17f
            var v = igra.vyzov
            while (v.length > 1 && x + tt.measureText(v) > IW - 12) v = v.dropLast(1)
            if (v.length < igra.vyzov.length) v = v.dropLast(1) + "…"
            kusok(v, 17f, Color.WHITE, 54f)
        }
    }

    // ---------- весь кадр: итоги вечера ----------

    /** Тёмный экран: заголовок, цифры вечера, строки от Плюса, справа — Плюс. */
    private fun itogiRisovat(b: Bitmap, efirMs: Long) {
        val k = Canvas(b)
        k.drawColor(Color.rgb(8, 10, 16))
        pod.color = Color.argb(40, 255, 205, 40)                   // тёплое пятно за Плюсом
        k.drawCircle(1500f, 520f, 420f, pod)
        lico?.let { k.drawBitmap(it, null, RectF(1270f, 270f, 1730f, 730f), kist) }
        plechiImg?.let {
            val m = 460f / 256f
            k.drawBitmap(it, null, RectF(1270f - PLECHI_VBOK * m, 270f + 218.2f * m, 1270f + (319 - PLECHI_VBOK) * m,
                270f + (218.2f + 80) * m), kist)
        }
        tt.color = Color.rgb(255, 205, 40); tt.textSize = 30f
        k.drawText("STREAM SUMMARY", 140f, 170f, tt)
        tt.color = Color.WHITE; tt.textSize = 72f
        k.drawText("Tonight on the stream", 140f, 250f, tt)
        var y = 360f
        for ((podpis, znach) in Itogi.ryady(efirMs, nalet.efirSek, nalet.vsegoSek, itogi.vyzovov, cel, igra)) {
            tt.color = Color.argb(190, 200, 205, 220); tt.textSize = 30f
            k.drawText(podpis, 140f, y, tt)
            tt.color = Color.WHITE; tt.textSize = 44f
            k.drawText(znach, 520f, y, tt)
            y += 70f
        }
        y += 20f
        tt.textSize = 34f
        for (l in itogi.stroki) {
            pod.color = Color.rgb(255, 205, 40)
            k.drawCircle(152f, y - 11f, 7f, pod)
            tt.color = Color.argb(240, 235, 238, 245)
            k.drawText(perenos(l, 1000f, 1).first(), 180f, y, tt)
            y += 54f
        }
        tt.color = Color.argb(150, 200, 205, 220); tt.textSize = 26f
        k.drawText("Thanks for watching!", 140f, 1000f, tt)
        var sy = 0f                                                  // строчная развёртка, как у лица
        pod.color = Color.argb(28, 0, 0, 0)
        while (sy < 1080f) { k.drawRect(0f, sy, 1920f, sy + 2f, pod); sy += 4f }
    }

    // ---------- левый низ: роза ветров в 3D ----------

    /**
     * Роза ветров в самом углу (2.3.5): объёмная (Roza3D); крутится сама как попало, не по компасу (2.3.6).
     * Грани — дальние первыми, у каждой — тонкий чёрный контур по своим рёбрам, у обода — деления.
     */
    private fun radar(now: Long) {
        rc.drawColor(0, PorterDuff.Mode.CLEAR)
        val kadr = Roza3D.kadr((now - nachalo) / 1000.0, dvizhenie, RW / 2f, RH / 2f, RMASSHTAB)
        for (g in kadr.grani) {
            rPut.reset()
            rPut.moveTo(g.x[0], g.y[0])
            for (i in 1 until g.x.size) rPut.lineTo(g.x[i], g.y[i])
            rPut.close()
            rk.style = Paint.Style.FILL; rk.color = g.cvet
            rc.drawPath(rPut, rk)
            rk.style = Paint.Style.STROKE; rk.strokeWidth = 0.9f                 // шов цветом грани — без щелей
            rc.drawPath(rPut, rk)
            for (i in g.x.indices) if (g.kontur[i]) {
                val j = (i + 1) % g.x.size
                rc.drawLine(g.x[i], g.y[i], g.x[j], g.y[j], rKontur)
            }
            g.cherty?.let { rc.drawLine(it[0], it[1], it[2], it[3], rKontur) }
        }
        kadr.sever.let { (x, y, fs) ->
            rt.textSize = fs
            rt.style = Paint.Style.STROKE; rt.strokeWidth = fs * 0.22f; rt.color = Color.argb(230, 10, 10, 10)
            rc.drawText("N", x, y + fs * 0.36f, rt)
            rt.style = Paint.Style.FILL; rt.color = Roza3D.KRASN
            rc.drawText("N", x, y + fs * 0.36f, rt)
        }
    }

    /** Звон сотни — обычным звуком (MEDIA): его пишет и эфир. */
    private fun zvon() = thread(isDaemon = true) {
        val pcm = Signal.pcm(Signal.Kind.SOTNYA, 0.45)
        try {
            val tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Signal.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            tr.write(pcm, 0, pcm.size)
            tr.play()
            Thread.sleep(pcm.size * 1000L / Signal.RATE + 200)
            tr.release()
        } catch (e: Exception) { }
    }

    companion object {
        private fun kartinka(ctx: Context, imya: String): Bitmap? =
            try { ctx.assets.open(imya).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }

        /**
         * Плашка лица и налёта для кадра 1080p (в 720p развилка уменьшит); D — сторона картинки лица
         * (2.2.6 — на 15 % меньше прежних 190), SH — высота строки налёта, MR — монета в ней.
         */
        const val W = 520
        const val H = 262
        const val D = 162
        const val SH = 30f
        const val MR = 8f
        /** Рот в plus-lico.png опущен на ряд кирпичей (9 точек из 256) — подбородок меньше; мимика — за ним. */
        const val ROT = 9f
        /** На сколько плечи модели выходят за картинку головы с каждой стороны (точки картинки 256). */
        const val PLECHI_VBOK = 31.5f
        /** Субтитры (низ по центру): картинка TW×TH, кегль TSZ, строка TLH, не больше TSTROK строк. */
        const val TW = 1100
        const val TH = 194
        const val TSZ = 30f
        const val TLH = 44f
        const val TSTROK = 4
        /** Табло соведущего (левый верх). */
        const val IW = 440
        const val IH = 68
        /** Топ-донатор (левый верх, над табло): высота плашки и зазор до табло; картинка слоя — LVH. */
        const val TOPH = 42f
        const val ZAZOR = 8f
        const val LVH = 118                                             // TOPH + ZAZOR + IH
        /** Чат Twitch (справа, под Плюсом, над камерой): картинка CW×CH, кегль CSZ, строка CLH. */
        const val CW = 420
        const val CH = 400
        const val CSZ = 19f
        const val CLH = 25f
        const val CPAD = 9f
        const val CGAP = 6f
        /** Роза ветров в 3D: картинка RW×RH, RMASSHTAB — точек на длинный луч (переваливаясь, влезает с N). */
        const val RW = Roza3D.KARTINKA
        const val RH = Roza3D.KARTINKA
        const val RMASSHTAB = Roza3D.MASSHTAB
        /** Роза — раз в столько мс (5 кадров в секунду; было 14). */
        const val ROZA_MS = 200L
        /** Плашка чата — не чаще раза в столько мс (4 в секунду). */
        const val CHAT_MS = 250L
        /** Глазам Плюса во время эфира — кадров в секунду; в разговоре (кроме 2 с после вызова) — меньше. */
        const val GLAZA_EFIR = 2
        const val GLAZA_RAZGOVOR = 1
        /**
         * Руки с телефоном (plus-ruki-telefon.png, 325×79) — в точках картинки головы 256, как плечи:
         * левый верх картинки; сняты рендером модели 3dplus тем же ракурсом, что лицо и плечи.
         */
        const val RUKI_X = -34.47f
        const val RUKI_Y = 219.92f
        /** Телефон стоя (plus-ruki-telefon-v.png, 307×86, 2.4.0): верх телефона — под открытым ртом (212). */
        const val RUKI_V_X = -25.67f
        const val RUKI_V_Y = 212.88f
    }
}
