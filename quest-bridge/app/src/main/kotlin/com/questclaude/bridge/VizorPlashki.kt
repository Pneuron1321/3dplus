package com.questclaude.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Плашки эфира в стиле «Визор» (2.7.0, Vizor): рисует в картинки слоёв по раскладке Vizor; когда и что
 * перерисовать — решает Nalozhenie (только при изменениях, как старые плашки). Бюст Плюса рисует сам
 * Nalozhenie (мимика), здесь — налёт пилюлей и плашка «PLUS co-host» вокруг него. Подписи — по-английски
 * (Александр 08.10); заголовки сцен — по-английски крупно и по-русски строкой ниже.
 */
class VizorPlashki(
    private val ctx: Context,
    val kist: VizorKist,
    private val cel: Cel,
    private val top: Top,
    private val igra: Igra,
    private val chat: Chat,
    private val nalet: Nalet,
    private val itogi: Itogi,
    /** Имя голоса для субтитров (ALEXANDER, PLUS, GAME, гость). */
    private val imyaGolosa: (String) -> String,
) {
    // ---------- скобы и линия ----------

    /** Слои "vv" (верх кадра) и "vn" (низ): скобы визора по углам и тонкая золотая линия сверху. */
    fun ramka(vv: Bitmap, vn: Bitmap) {
        val o = Vizor.SKOBA_OTSTUP
        val w = Vizor.W.toFloat()
        Canvas(vv).apply {
            drawColor(0, PorterDuff.Mode.CLEAR)
            kist.skoba(this, o, o, 1f, 1f)
            kist.skoba(this, w - o, o, -1f, 1f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val l = Vizor.LINIYA_OT; val r = w - Vizor.LINIYA_OT; val y = Vizor.LINIYA_Y
            p.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0, 0x73F2C230, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            drawRect(l, y - 1.5f, r, y + 1.5f, p.apply { alpha = 60 })                    // свечение
            drawRect(l, y - 0.5f, r, y + 0.5f, p.apply { alpha = 255 })
        }
        val h = vn.height.toFloat()
        Canvas(vn).apply {
            drawColor(0, PorterDuff.Mode.CLEAR)
            kist.skoba(this, o, h - o, 1f, -1f)
            kist.skoba(this, w - o, h - o, -1f, -1f)
        }
    }

    // ---------- левый верх: топ-донатор и цель, табло соведущего ----------

    private val metka = kist.metka()
    private val imyaK = kist.kist(kist.zagolovki, Vizor.IMYA_TOPA)
    private val summaK = kist.kist(kist.zagolovki, Vizor.SUMMA_TOPA, Vizor.ZOLOTO)
    private val tekstK = kist.kist(kist.tekst, Vizor.TEKST)
    private val zhirnoK = kist.kist(kist.tekstZhirno, Vizor.TEKST)

    /** Видно ли что-нибудь в левом верху. */
    fun levyVidno() = top.vidna || cel.vidna || igra.vidna

    /** Ключ левого верха: что нарисовано (перерисовать — только когда меняется). */
    fun levyKlyuch() = if (!levyVidno()) "net" else "${top.status()} ${cel.status()} ${igra.status()}"

    fun levyVerh(b: Bitmap) {
        val c = Canvas(b)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        if (top.vidna || cel.vidna) kartaTopa(c)
        if (igra.vidna) tablo(c)
    }

    private fun kartaTopa(c: Canvas) {
        val w = Vizor.KARTA_TOPA_W
        val p = Vizor.POLE
        val h = Vizor.kartaTopaH(top.vidna, cel.vidna)
        kist.karta(c, RectF(0f, 0f, w, h))
        c.drawText(if (top.vidna) "TOP DONOR" else "DONATION GOAL", p + 4f, p + 20f, metka)
        var y = p + 26f
        if (top.vidna) {
            val sk = " · " + top.skolko()
            val sw = summaK.measureText(sk)
            val imya = kist.vlezet(imyaK, top.imya, w - 2 * p - 8f - sw)
            c.drawText(imya, p + 4f, y + 36f, imyaK)
            c.drawText(sk, p + 4f + imyaK.measureText(imya), y + 36f, summaK)
            y += 44f
        }
        if (cel.vidna) {
            val summa = cel.tekst()
            zhirnoK.color = Vizor.ZOLOTO
            val sw = zhirnoK.measureText(summa)
            tekstK.color = Vizor.LED
            c.drawText(kist.vlezet(tekstK, cel.imya.ifEmpty { "Goal" }, w - 2 * p - 8f - sw - 16f), p + 4f, y + 28f, tekstK)
            c.drawText(summa, w - p - 4f - sw, y + 28f, zhirnoK)
            y += 38f
            kist.polosa(c, RectF(p + 4f, y + 4f, w - p - 4f, y + 16f), cel.dolya())
        }
    }

    /** Табло: ставки Плюса «PLUS CALLS 3/5», вызовы зрителей «CHALLENGES 2/3», текущий вызов. */
    private fun tablo(c: Canvas) {
        val ryady = mutableListOf<Pair<String, String>>()
        if (igra.stavok > 0) ryady += "PLUS CALLS" to "${igra.popal}/${igra.stavok}"
        if (igra.vyzovov > 0) ryady += "CHALLENGES" to "${igra.sdelano}/${igra.vyzovov}"
        if (igra.vyzov.isNotEmpty()) ryady += "CHALLENGE" to igra.vyzov
        if (ryady.isEmpty()) return
        val x0 = Vizor.TABLO_X
        val p = Vizor.POLE
        val shir = Vizor.LV_W - x0
        // ширина — по содержимому, не шире слоя
        val nado = ryady.maxOf { (m, z) -> metka.measureText(m) + 14f + zhirnoK.measureText(z) } + 2 * p + 8f
        val w = minOf(shir, maxOf(260f, nado))
        kist.karta(c, RectF(x0, 0f, x0 + w, Vizor.tabloH(ryady.size)))
        var y = p
        for ((m, z) in ryady) {
            c.drawText(m, x0 + p + 4f, y + 27f, metka)
            val zx = x0 + p + 4f + metka.measureText(m) + 14f
            zhirnoK.color = Vizor.LED
            c.drawText(kist.vlezet(zhirnoK, z, x0 + w - p - zx), zx, y + 28f, zhirnoK)
            y += Vizor.TABLO_STROKA
        }
    }

    // ---------- правый верх: налёт пилюлей и плашка Плюса (бюст рисует Nalozhenie) ----------

    private val naletK = kist.kist(kist.zagolovki, Vizor.NALET)
    private val naletMalo = kist.kist(kist.tekst, Vizor.NALET, 0xD9F2C230.toInt())
    private val moneta = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Налёт: монета, всего, «+N this stream»; сотня — «AIRTIME N» и золотой кант. (dx, dy) — начало слоя в кадре. */
    fun nalet(c: Canvas, dx: Float, dy: Float, now: Long) {
        val m = Vizor.NALET_PILYULYA.sdvig(-dx, -dy)
        val vsego = Nalet.koinyEn(nalet.vsegoSek)
        val sotnya = nalet.sotnyaVidna(now)
        val zaEfir = if (sotnya) "" else "+" + Nalet.koinyEn(nalet.efirSek) + " this stream"   // сотня — вместо «за эфир»
        val podpis = "AIRTIME ${nalet.sotnya}"
        val mr = 11f
        var w = 18f + 2 * mr + 10f + naletK.measureText(vsego) + (if (zaEfir.isEmpty()) 0f else 14f + naletMalo.measureText(zaEfir)) + 18f
        if (sotnya) w += metka.measureText(podpis) + 16f
        w = minOf(w, m.w)
        val r = RectF(m.r - w, m.t, m.r, m.b)
        kist.karta(c, r, if (sotnya) Vizor.ZOLOTO else Vizor.KANT, r.height() / 2)
        var x = r.left + 18f
        val cy = r.centerY()
        val baza = cy + 10f
        if (sotnya) { c.drawText(podpis, x, cy + 8f, metka); x += metka.measureText(podpis) + 16f }
        moneta.style = Paint.Style.FILL; moneta.color = Vizor.ZOLOTO
        c.drawCircle(x + mr, cy, mr, moneta)
        moneta.style = Paint.Style.STROKE; moneta.strokeWidth = 2f; moneta.color = 0xFF8A6A10.toInt()
        c.drawCircle(x + mr, cy, mr * 0.6f, moneta)
        x += 2 * mr + 10f
        c.drawText(vsego, x, baza, naletK)
        x += naletK.measureText(vsego) + 14f
        if (zaEfir.isNotEmpty()) c.drawText(kist.vlezet(naletMalo, zaEfir, r.right - 18f - x), x, baza, naletMalo)
    }

    /** Знак «++3» (картинка Александра): маленький «+» сверху слева, крупный «+», «3» внизу справа; фиолетовый
     *  (наш, Vizor.ZNAK) с тонким чёрным контуром (Александр 24.10). */
    private fun znakK(sz: Float, kontur: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = sz
        if (kontur) { style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }
        else color = Vizor.ZNAK
    }
    private val znakMal = znakK(24f, false)
    private val znakBol = znakK(50f, false)
    private val znakTri = znakK(30f, false)
    private val konturMal = znakK(24f, true)
    private val konturBol = znakK(50f, true)
    private val konturTri = znakK(30f, true)

    /** Знак «++3» над Плюсом слева — вместо прежней плашки «PLUS co-host»; (dx, dy) — начало слоя в кадре. */
    fun plashkaPlyusa(c: Canvas, dx: Float, dy: Float) {
        val m = Vizor.PLASHKA_PLYUSA
        val l = m.l - dx; val t = m.t - dy
        val xb = l + znakMal.measureText("+") * 0.75f
        val x3 = xb + znakBol.measureText("+") - 1f
        c.drawText("+", l, t + 17f, konturMal); c.drawText("+", l, t + 17f, znakMal)          // контур, поверх — цвет
        c.drawText("+", xb, t + 41f, konturBol); c.drawText("+", xb, t + 41f, znakBol)
        c.drawText("3", x3, t + 46f, konturTri); c.drawText("3", x3, t + 46f, znakTri)
    }

    // ---------- правый столбец: чат ----------

    private val nikK = kist.kist(kist.zagolovki, Vizor.NIK, Vizor.FIOLET)
    private val chatK = kist.kist(kist.tekst, Vizor.TEKST)
    private val plK = kist.kist(kist.zagolovki, Vizor.ZNACHOK, Color.WHITE)
    private val plPod = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sloyK = Paint()

    /**
     * Чат: снизу вверх, новые внизу; карточка на сообщение во всю ширину столбца — метка площадки (TW/KICK/YT),
     * ник фиолетом, ниже текст (до 4 строк; шквал — 1). Обращённые к Плюсу и донаты — золотой кант. Влезают
     * только целые сообщения выше [potolok]. Ответ — сколько нарисовано.
     */
    fun chat(b: Bitmap, ss: List<Chat.Soob>, now: Long, bystro: Boolean, mesto: Int): Int {
        val c = Canvas(b)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val w = b.width.toFloat()
        val potolok = (b.height - mesto).toFloat()
        val p = 12f
        var niz = b.height.toFloat()
        var n = 0
        for (s in ss.asReversed()) {
            val a = chat.yarkost(s, now)
            if (a <= 0f) continue
            val stroki = kist.perenos(chatK, s.tekst, w - 2 * p, if (bystro) 1 else 4)
            val h = p + 30f + stroki.size * 36f + 8f
            val verh = niz - h
            if (verh < potolok) break
            val r = RectF(0f, verh, w, niz)
            val sloy = if (a < 1f) c.saveLayer(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, sloyK.apply { alpha = (255 * a).toInt() }) else -1
            kist.karta(c, r, if (s.vazhno) 0xCCF2C230.toInt() else Vizor.KANT)
            val (pl, cvet) = Vizor.ploshchadka(s.pl)
            val pw = plK.measureText(pl) + 12f
            plPod.color = cvet
            val py = verh + p + 3f
            c.drawRoundRect(RectF(p, py, p + pw, py + 22f), 5f, 5f, plPod)
            plK.color = if (s.pl == "kick") Color.BLACK else Color.WHITE
            c.drawText(pl, p + 6f, py + 16f, plK)
            c.drawText(kist.vlezet(nikK, s.kto, w - 2 * p - pw - 8f), p + pw + 8f, verh + p + 22f, nikK)
            stroki.forEachIndexed { i, l -> c.drawText(l, p, verh + p + 30f + 28f + i * 36f, chatK) }
            if (sloy >= 0) c.restoreToCount(sloy)
            niz = verh - 8f
            n++
        }
        return n
    }

    // ---------- низ по центру: субтитры ----------

    private val ktoK = kist.metka()
    private val titrK = kist.kist(kist.tekstZhirno, Vizor.TEKST_TITROV)
    private val perevodK = kist.kist(kist.tekst, Vizor.TEKST, 0xC0EEF2F7.toInt())
    private val kratkoK = kist.kist(kist.tekst, Vizor.TEKST, 0xD9EEF2F7.toInt())

    /**
     * Субтитры пилюлей: сверху кто говорит (Unbounded золотом), ниже как сказано и перевод. Новая реплика —
     * целиком (до 2 строк и строка перевода), прежние — короткой строкой над ней, сколько влезет в высоту слоя
     * (не выше нижней границы центра кадра). Ответ — нарисовано ли что-нибудь.
     */
    fun titry(b: Bitmap, ts: List<Titry.Stroka>): Boolean {
        val c = Canvas(b)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        if (ts.isEmpty()) return false
        val w = b.width.toFloat()
        val shir = w - 60f
        val nov = ts.last()
        var ryady = kist.perenos(titrK, nov.tekst, shir, 2)
        var per = if (nov.perevod.isEmpty()) emptyList() else kist.perenos(perevodK, nov.perevod, shir, 2)
        fun vys() = 12f + 26f + ryady.size * 40f + per.size * 34f + 12f
        if (vys() > b.height && per.size > 1) per = per.take(1).map { kist.vlezet(perevodK, it, shir) }
        if (vys() > b.height && ryady.size > 1) ryady = listOf(kist.vlezet(titrK, ryady[0] + " " + ryady[1], shir))
        val h = vys()
        val imya = imyaGolosa(nov.kto)
        val pw = (listOf(ktoK.measureText(imya)) + ryady.map { titrK.measureText(it) } + per.map { perevodK.measureText(it) }).max() + 52f
        val r = RectF((w - pw) / 2, b.height - h, (w + pw) / 2, b.height.toFloat())
        kist.karta(c, r)
        var y = r.top + 12f + 20f
        c.drawText(imya, (w - ktoK.measureText(imya)) / 2, y, ktoK)
        y += 6f
        for (l in ryady) { y += 40f; c.drawText(l, (w - titrK.measureText(l)) / 2, y - 8f, titrK) }
        for (l in per) { y += 34f; c.drawText(l, (w - perevodK.measureText(l)) / 2, y - 6f, perevodK) }
        // прежние — короткой строкой «ИМЯ  текст», пока влезают
        var niz = r.top - 8f
        for (st in ts.dropLast(1).asReversed()) {
            if (niz - 44f < 0f) break
            val im = imyaGolosa(st.kto)
            val t = kist.vlezet(kratkoK, st.tekst, shir - ktoK.measureText(im) - 70f)
            val sw = ktoK.measureText(im) + 12f + kratkoK.measureText(t) + 40f
            val rr = RectF((w - sw) / 2, niz - 44f, (w + sw) / 2, niz)
            kist.karta(c, rr, radius = 22f)
            c.drawText(im, rr.left + 20f, rr.centerY() + 8f, ktoK)
            c.drawText(t, rr.left + 20f + ktoK.measureText(im) + 12f, rr.centerY() + 10f, kratkoK)
            niz = rr.top - 8f
        }
        return true
    }

    // ---------- «сейчас играет» ----------

    private val mzK = kist.kist(kist.tekst, Vizor.TEKST)
    private val eqK = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.ZOLOTO }

    /** Карточка «сейчас играет»: эквалайзер золотом (4 полосы из спектра FonMiks) и трек — бежит, если не влезает. */
    fun muzyka(b: Bitmap, f: Fon, now: Long, nachalo: Long) {
        val c = Canvas(b)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val w = b.width.toFloat(); val h = b.height.toFloat()
        kist.karta(c, RectF(0f, 0f, w, h), ten = false)
        val sp = f.miks.spektr
        val n = FonMiks.POLOS / 4
        for (i in 0 until 4) {
            var v = 0f
            for (j in i * n until minOf(sp.size, (i + 1) * n)) v = maxOf(v, sp[j])
            val vy = 6f + 22f * v.coerceIn(0.1f, 1f)
            val x = 14f + i * 8f
            c.drawRoundRect(RectF(x, h / 2 + 14f - vy, x + 5f, h / 2 + 14f), 2.5f, 2.5f, eqK)
        }
        val t = f.plashkaTekst
        val l = 56f; val r = w - 12f
        val tw = mzK.measureText(t)
        val baza = h / 2 + 10f
        c.save()
        c.clipRect(l, 0f, r, h)
        if (tw <= r - l) c.drawText(t, l, baza, mzK)
        else {
            val krug = tw + 60f
            val x = l - ((now - nachalo) / 1000f * 40f) % krug
            c.drawText(t, x, baza, mzK); c.drawText(t, x + krug, baza, mzK)
        }
        c.restore()
    }

    // ---------- во весь кадр: итоги и сцены ----------

    private var rost: Bitmap? = null                  // Плюс в полный рост — только пока сцена на экране

    /** Сцена прочь — картинку Плюса в полный рост отпустить. */
    fun osvobodit() { rost?.recycle(); rost = null }

    private fun rostPlyusa(): Bitmap? = rost ?: try {
        ctx.assets.open("plus-rost.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }.also { rost = it }

    /** Фон сцен: почти чёрный, тёплое пятно за Плюсом, золотая сетка 64, скобы крупнее, чем в эфире. */
    private fun fonSceny(c: Canvas, shtorka: Boolean = false) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawColor(if (shtorka) 0xF70B0C10.toInt() else 0xFF0B0C10.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(1340f, 590f, 640f, 0x2EF2C230, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.shader = RadialGradient(380f, 220f, 520f, 0x1FFF4A2B, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.shader = null
        p.color = 0x0FF2C230
        var x = 0f
        while (x < 1920f) { c.drawRect(x, 0f, x + 1f, 1080f, p); x += 64f }
        var y = 0f
        while (y < 1080f) { c.drawRect(0f, y, 1920f, y + 1f, p); y += 64f }
        if (shtorka) {                                                  // шторка: косые золотые полосы
            p.color = 0x0AF2C230
            var d = -1080f
            while (d < 1920f) {
                val path = android.graphics.Path()
                path.moveTo(d, 1080f); path.lineTo(d + 1080f, 0f); path.lineTo(d + 1120f, 0f); path.lineTo(d + 40f, 1080f); path.close()
                c.drawPath(path, p)
                d += 160f
            }
        }
        for ((x0, y0, dx, dy) in listOf(floatArrayOf(40f, 40f, 1f, 1f), floatArrayOf(1880f, 40f, -1f, 1f),
                floatArrayOf(40f, 1040f, 1f, -1f), floatArrayOf(1880f, 1040f, -1f, -1f)))
            kist.skoba(c, x0, y0, dx, dy, 70f, 4f)
    }

    private val sceMetka = kist.metka(24f).apply { letterSpacing = 0.2f }
    private val h1 = kist.kist(kist.zagolovki, Vizor.ZAGOLOVOK_SCENY)
    private val h1z = kist.kist(kist.zagolovki, Vizor.ZAGOLOVOK_SCENY, Vizor.ZOLOTO)
    private val poRusski = kist.kist(kist.tekst, 36f, 0xD9EEF2F7.toInt())
    private val tajmerK = kist.kist(kist.zagolovki, Vizor.TAJMER, Vizor.ZOLOTO)
    private val frazaK = kist.kist(kist.tekst, 32f)

    /** Сцена во весь кадр [b] (1920×1080): skoro, otoshyol, konec. */
    fun scena(b: Bitmap, scena: String, ostalosMs: Long, tajmer: Boolean, fraza: String, efirMs: Long,
              plyus: (Canvas, RectF) -> Unit) {
        val c = Canvas(b)
        if (scena == "konec") { itogi(b, efirMs, konec = true, plyus = plyus); return }
        val skoro = scena == "skoro"
        fonSceny(c, shtorka = !skoro)
        val x = 150f
        var y = 250f
        c.drawText(if (skoro) "GTA V · VR · AI CO-HOST" else "BE RIGHT BACK", x, y, sceMetka)
        y += 130f
        c.drawText(if (skoro) "Starting" else "Sasha will be", x, y, h1)
        y += 110f
        c.drawText(if (skoro) "soon" else "right back", x, y, h1z)
        y += 66f
        c.drawText(if (skoro) "Скоро начнём" else "Саша скоро вернётся", x + 4f, y, poRusski)
        y += 40f
        if (tajmer) {
            val t = Vizor.tajmer(ostalosMs)
            val podpis = when {
                ostalosMs <= 0 -> "any second now"
                skoro -> "Sasha is putting on the headset"
                else -> "until Sasha is back"
            }
            val tw = tajmerK.measureText(t)
            val r = RectF(x, y, x + 56f + tw + 20f + tekstK.measureText(podpis) + 28f, y + 108f)
            kist.karta(c, r, 0x66F2C230)
            c.drawText(t, r.left + 28f, r.centerY() + 24f, tajmerK)
            tekstK.color = 0xCCEEF2F7.toInt()
            c.drawText(podpis, r.left + 28f + tw + 20f, r.centerY() + 10f, tekstK)
            tekstK.color = Vizor.LED
            y = r.bottom + 40f
        }
        val f = fraza.ifEmpty { if (skoro) "I'm already here. Make some tea — tonight we drive in style." else "" }
        if (f.isNotEmpty()) {
            val stroki = kist.perenos(frazaK, "«$f» — Plus", 760f, 3)
            val r = RectF(x, y, x + 24f + stroki.maxOf { frazaK.measureText(it) } + 24f, y + 22f + stroki.size * 44f + 14f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x990E1014.toInt() }
            c.drawRoundRect(r, 14f, 14f, p)
            c.drawRect(r.left, r.top, r.left + 14f, r.bottom, p)                          // левый край — прямой
            p.color = Vizor.VIZOR
            c.drawRect(r.left, r.top, r.left + 4f, r.bottom, p)
            stroki.forEachIndexed { i, l -> c.drawText(l, r.left + 24f, r.top + 22f + 32f + i * 44f, frazaK) }
        }
        val rp = rostPlyusa()
        if (skoro && rp != null) {
            val vys = 900f
            val shir = vys * rp.width / rp.height
            c.drawBitmap(rp, null, RectF(1440f - shir / 2, 1020f - vys, 1440f + shir / 2, 1020f), Paint(Paint.FILTER_BITMAP_FLAG))
        } else plyus(c, RectF(1190f, 300f, 1650f, 760f))
    }

    private val ryadPodpis = kist.kist(kist.tekst, 30f, 0xB3EEF2F7.toInt())
    private val ryadZnach = kist.kist(kist.tekstZhirno, 36f)
    private val strokaK = kist.kist(kist.tekst, 32f)
    private val tochka = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Vizor.ZOLOTO }

    /** Итоги вечера в стиле визора: цифры карточкой, строки Плюса, справа Плюс; konec — сцена «конец эфира». */
    fun itogi(b: Bitmap, efirMs: Long, konec: Boolean = false, plyus: (Canvas, RectF) -> Unit) {
        val c = Canvas(b)
        fonSceny(c)
        val x = 150f
        c.drawText(if (konec) "THAT'S A WRAP" else "STREAM SUMMARY", x, 170f, sceMetka)
        c.drawText("Tonight on", x, 270f, h1.apply { textSize = 76f })
        c.drawText("the stream", x, 356f, h1z.apply { textSize = 76f })
        h1.textSize = Vizor.ZAGOLOVOK_SCENY; h1z.textSize = Vizor.ZAGOLOVOK_SCENY
        if (konec) c.drawText("Конец эфира — спасибо, что были с нами!", x + 2f, 412f, poRusski)
        val ryady = Itogi.ryady(efirMs, nalet.efirSek, nalet.vsegoSek, itogi.vyzovov, cel, igra)
        var y = 446f
        val kr = RectF(x, y, x + 940f, y + 20f + ryady.size * 56f + 12f)
        kist.karta(c, kr, 0x66F2C230)
        y += 20f
        for ((podpis, znach) in ryady) {
            c.drawText(podpis, kr.left + 32f, y + 38f, ryadPodpis)
            c.drawText(kist.vlezet(ryadZnach, znach, kr.right - 32f - (kr.left + 330f)), kr.left + 330f, y + 39f, ryadZnach)
            y += 56f
        }
        y = kr.bottom + 52f
        for (l in itogi.stroki) {
            if (y > 975f) break
            c.drawCircle(x + 12f, y - 11f, 7f, tochka)
            c.drawText(kist.vlezet(strokaK, l, 1000f), x + 36f, y, strokaK)
            y += 52f
        }
        tekstK.color = 0x99EEF2F7.toInt()
        c.drawText("Thanks for watching!", x, 1032f, tekstK)
        tekstK.color = Vizor.LED
        val rp = rostPlyusa()
        if (rp != null) {
            val vys = 860f
            val shir = vys * rp.width / rp.height
            c.drawBitmap(rp, null, RectF(1470f - shir / 2, 1000f - vys, 1470f + shir / 2, 1000f), Paint(Paint.FILTER_BITMAP_FLAG))
        } else plyus(c, RectF(1180f, 280f, 1700f, 800f))
    }
}
