package com.questclaude.most3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig as EglKonfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Показ кадра карты (Мост 3): кадр с полки → текстуры по формату → шейдер картинки (Shader.kt) → окно с полями
 * под 16:9. Раз в 6 кадров тот же кадр без обработки рисуется в крошку 32×18 и читается обратно — средняя
 * яркость сцены для авто-ночи (Kartinka.kt: avtoTeni + Sglazhivanie ≈ 1 с).
 * Форматы: YUY2 и NV12 — прямо; I420 — перекладка U/V в NV12 на CPU; MJPEG — разжатие в нескольких потоках;
 * P010 (10 бит, HDR с PS5) — две плоскости как есть, тон-маппинг PQ → SDR в шейдере.
 * Заход 2: крошка 32×18 — ещё и карта яркости (uKarta) для местной авто-ночи; uSuper — сколько точек кадра
 * на точку окна (1440p/4K в окно меньше кадра — 4 выборки вместо ряби).
 */
class Pokaz(private val polka: Polka) : GLSurfaceView.Renderer {
    private var progYuy2 = 0; private var progNv12 = 0; private var progRgba = 0; private var progP010 = 0
    private var kartaEst = false                             // крошка уже снята — местной ночи есть на что смотреть
    private val tek = IntArray(3)
    private var vao = 0
    private var fbo = 0; private var fboTek = 0
    private val kroshka = ByteBuffer.allocateDirect(KW * KH * 4).order(ByteOrder.nativeOrder())
    private val kroshkaArr = ByteArray(KW * KH * 4)
    private var ekranW = 1; private var ekranH = 1
    private var tekW = 0; private var tekH = 0; private var tekVid: UvcVid? = null
    private var poslednij = -1L
    private var schet = 0
    private val sglazh = Sglazhivanie(1f)
    private var t0 = System.nanoTime(); private var kadrovPokaz = 0
    private var tPred = System.nanoTime()
    private var nv12: ByteArray? = null
    private val mjpeg = Mjpeg(polka)
    // Запись игры (Klip3): та же картинка — ещё и в поверхность кодировщика (EGL14 поверх контекста GLSurfaceView)
    private var eglD: EGLDisplay? = null; private var eglK: EGLContext? = null; private var eglCfg: EglKonfig? = null
    private var kodPov: EGLSurface? = null; private var kodSurface: android.view.Surface? = null
    private var kodNelzya = false
    // Струны: тот же кадр — в эфир моста 2 (поверхность его развилки, 1920×1080)
    private var efPov: EGLSurface? = null; private var efSurface: android.view.Surface? = null
    private var efW = 0; private var efH = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progYuy2 = programma(Shader.FRAG_YUY2); progNv12 = programma(Shader.FRAG_NV12); progRgba = programma(Shader.FRAG_RGBA)
        progP010 = programma(Shader.FRAG_P010)
        glGenTextures(3, tek, 0)
        for (t in tek) {
            glBindTexture(GL_TEXTURE_2D, t)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        val a = IntArray(1); glGenVertexArrays(1, a, 0); vao = a[0]
        glGenFramebuffers(1, a, 0); fbo = a[0]
        glGenTextures(1, a, 0); fboTek = a[0]
        glBindTexture(GL_TEXTURE_2D, fboTek)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, KW, KH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        // без этого текстура «неполная» (по умолчанию ждёт мип-уровни) и шейдер читал бы из карты чёрное
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTek, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        tekVid = null; poslednij = -1; kartaEst = false
        razmery.clear()                                      // новый контекст — у текстур нет хранилища
        eglD = EGL14.eglGetCurrentDisplay(); eglK = EGL14.eglGetCurrentContext()
        val id = IntArray(1)
        EGL14.eglQueryContext(eglD, eglK, EGL14.EGL_CONFIG_ID, id, 0)
        val cfgs = arrayOfNulls<EglKonfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglD, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        eglCfg = cfgs[0]; kodPov = null; kodSurface = null; kodNelzya = false
        efPov = null; efSurface = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { ekranW = w; ekranH = h }

    override fun onDrawFrame(gl: GL10?) {
        val t = System.nanoTime()
        val dt = ((t - tPred) / 1e9f).coerceIn(0f, 0.5f); tPred = t
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, ekranW, ekranH)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        val r = polka.rezhim ?: return
        val k = polka.kadr
        var novyj = false
        if (k != null && k.nomer != poslednij) { poslednij = k.nomer; novyj = zagruzit(r, k) }
        if (r.vid == UvcVid.MJPEG) mjpeg.vzyat()?.let { bmp -> zagruzitBitmap(bmp); novyj = true }
        if (tekVid == null) return
        val prog = when (tekVid) { UvcVid.MJPEG -> progRgba; UvcVid.YUY2 -> progYuy2; UvcVid.P010 -> progP010; else -> progNv12 }
        val n = Most.nastrojki

        if (novyj && ++schet % 6 == 0) {                         // замер средней яркости сцены
            glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, KW, KH)
            risovat(prog, n, 0f, syroe = true, oknoW = KW, oknoH = KH)
            kroshka.position(0)
            glReadPixels(0, 0, KW, KH, GL_RGBA, GL_UNSIGNED_BYTE, kroshka)
            kroshka.position(0); kroshka.get(kroshkaArr)
            polka.srednyaya = srednyayaYarkost(kroshkaArr, KW, KH)
            kartaEst = true                                    // fboTek теперь — карта яркости кадра (uKarta)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        }
        polka.avtoTeni = sglazh.shag(avtoTeni(polka.srednyaya, n.avtoNoch), dt)

        // окно — с полями под пропорции кадра
        val kw = tekW.toFloat(); val kh = tekH.toFloat()
        val m = minOf(ekranW / kw, ekranH / kh)
        val vw = (kw * m).toInt(); val vh = (kh * m).toInt()
        glViewport((ekranW - vw) / 2, (ekranH - vh) / 2, vw, vh)
        risovat(prog, n, polka.avtoTeni, syroe = false, oknoW = vw, oknoH = vh)
        if (novyj) { zapisat(prog, n); vEfir(prog, n) }

        kadrovPokaz++
        if (t - t0 >= 1_000_000_000L) { polka.pokazFps = kadrovPokaz * 1e9f / (t - t0); kadrovPokaz = 0; t0 = t }
    }

    /** Кадр — в кодировщик записи игры: переключиться на его поверхность, нарисовать, отметить время, вернуться. */
    private fun zapisat(prog: Int, n: Nastrojki) {
        val k = Most.klip
        val s = k?.poverhnost
        if (s !== kodSurface) {
            kodPov?.let { EGL14.eglDestroySurface(eglD, it) }
            kodPov = null; kodSurface = s; kodNelzya = false
            if (s != null) kodPov = try {
                EGL14.eglCreateWindowSurface(eglD, eglCfg, s, intArrayOf(EGL14.EGL_NONE), 0).takeIf { it != EGL14.EGL_NO_SURFACE }
            } catch (e: Exception) { null }
            if (s != null && kodPov == null) { kodNelzya = true; Most.oshibkaPokaza = "запись игры: поверхность кодировщика не создалась" }
        }
        val pov = kodPov ?: return
        if (kodNelzya || k == null) return
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(eglD, pov, pov, eglK)) { kodNelzya = true; return }
        glViewport(0, 0, k.shirina, k.vysota)
        risovat(prog, n, polka.avtoTeni, syroe = false, oknoW = k.shirina, oknoH = k.vysota)
        EGLExt.eglPresentationTimeANDROID(eglD, pov, System.nanoTime())
        EGL14.eglSwapBuffers(eglD, pov)
        EGL14.eglMakeCurrent(eglD, draw, read, eglK)
    }

    /**
     * Кадр — в эфир моста 2 (Струны): своя поверхность EGL поверх его развилки; поля под 16:9, если кадр не 16:9.
     * Не вышло нарисовать (эфир кончился, развилку закрыли) — поверхность прочь, Struny спросит новую.
     */
    private fun vEfir(prog: Int, n: Nastrojki) {
        val s = Struny.poverhnost
        if (s !== efSurface) {
            efPov?.let { EGL14.eglDestroySurface(eglD, it) }
            efPov = null; efSurface = s
            if (s != null) {
                efPov = try {
                    EGL14.eglCreateWindowSurface(eglD, eglCfg, s, intArrayOf(EGL14.EGL_NONE), 0).takeIf { it != EGL14.EGL_NO_SURFACE }
                } catch (e: Exception) { null }
                val pov = efPov ?: run { Struny.slomana(); efSurface = null; return }
                val v = IntArray(1)
                EGL14.eglQuerySurface(eglD, pov, EGL14.EGL_WIDTH, v, 0); efW = v[0]
                EGL14.eglQuerySurface(eglD, pov, EGL14.EGL_HEIGHT, v, 0); efH = v[0]
            }
        }
        val pov = efPov ?: return
        if (efW <= 0 || efH <= 0 || tekW <= 0) return
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var ok = EGL14.eglMakeCurrent(eglD, pov, pov, eglK)
        if (ok) {
            glViewport(0, 0, efW, efH)
            glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
            val m = minOf(efW / tekW.toFloat(), efH / tekH.toFloat())
            val vw = (tekW * m).toInt(); val vh = (tekH * m).toInt()
            glViewport((efW - vw) / 2, (efH - vh) / 2, vw, vh)
            risovat(prog, n, polka.avtoTeni, syroe = false, oknoW = vw, oknoH = vh)
            EGLExt.eglPresentationTimeANDROID(eglD, pov, System.nanoTime())
            ok = EGL14.eglSwapBuffers(eglD, pov)
        }
        EGL14.eglMakeCurrent(eglD, draw, read, eglK)
        if (ok) Struny.kadrov++
        else {
            EGL14.eglDestroySurface(eglD, pov)
            efPov = null; efSurface = null
            Struny.slomana()
        }
    }

    /** oknoW×oknoH — куда рисуем (точки): для uSuper; замер в крошку — 32×18 и uSuper = 1. */
    private fun risovat(prog: Int, n: Nastrojki, avto: Float, syroe: Boolean, oknoW: Int, oknoH: Int) {
        glUseProgram(prog)
        fun u(imya: String, v: Float) = glUniform1f(glGetUniformLocation(prog, imya), v)
        u("uYarkost", n.yarkost); u("uKontrast", n.kontrast); u("uNasyshch", n.nasyshch); u("uGamma", n.gamma)
        u("uTeni", n.teni); u("uAvtoTeni", avto); u("uRezkost", n.rezkost)
        u("uPolnyj", if (n.polnyjDiapazon) 1f else 0f); u("uSyroe", if (syroe) 1f else 0f)
        u("uLokalno", if (kartaEst && !syroe) n.lokalno else 0f)
        u("uSuper", if (syroe || oknoW <= 0 || oknoH <= 0) 1f else maxOf(tekW / oknoW.toFloat(), tekH / oknoH.toFloat()))
        u("uHdr", if (tekVid == UvcVid.P010) 1f else 0f)        // P010 у Elgato — HDR10 (PQ, BT.2020) с PS5
        u("uPik", n.hdrPik); u("uBelyj", KartinkaCpu.BELYJ_NIT)
        // карта яркости — блок 2; в замере крошка сама цель рисования: тогда не привязывать (петля чтения-записи)
        glActiveTexture(GL_TEXTURE0 + Shader.BLOK_KARTY)
        glBindTexture(GL_TEXTURE_2D, if (syroe || !kartaEst) 0 else fboTek)
        glUniform1i(glGetUniformLocation(prog, Shader.SAMPLER_KARTA), Shader.BLOK_KARTY)
        when (prog) {
            progYuy2 -> { glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[0]); glUniform1i(glGetUniformLocation(prog, "uTekYuy2"), 0) }
            progNv12 -> {
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[0]); glUniform1i(glGetUniformLocation(prog, "uTekY"), 0)
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tek[1]); glUniform1i(glGetUniformLocation(prog, "uTekUV"), 1)
            }
            progP010 -> {
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[0]); glUniform1i(glGetUniformLocation(prog, "uTekP010Y"), 0)
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tek[1]); glUniform1i(glGetUniformLocation(prog, "uTekP010UV"), 1)
            }
            else -> { glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[2]); glUniform1i(glGetUniformLocation(prog, "uTekRgba"), 0) }
        }
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Кадр с полки в текстуры. true — есть что показать. */
    private fun zagruzit(r: UvcRezhim, k: Polka.Kadr): Boolean {
        val w = r.shirina; val h = r.vysota
        when (r.vid) {
            UvcVid.YUY2 -> {
                if (k.dlina < w * h * 2) return false
                tekstura(tek[0], GL_RGBA8, GL_RGBA, w / 2, h, ByteBuffer.wrap(k.buf, 0, w * h * 2), UvcVid.YUY2, w, h)
            }
            UvcVid.NV12, UvcVid.I420 -> {
                if (k.dlina < w * h * 3 / 2) return false
                // карта может дополнять высоту (1088 вместо 1080): плоскость цвета — после ВСЕХ строк яркости
                val hp = if (k.dlina > w * h * 3 / 2) (k.dlina * 2 / 3 / w).coerceAtLeast(h) else h
                val src = if (r.vid == UvcVid.I420) perelozhit(k.buf, w, h, hp) else k.buf
                val uv = if (r.vid == UvcVid.I420) w * h else w * hp
                tekstura(tek[0], GL_R8, GL_RED, w, h, ByteBuffer.wrap(src, 0, w * h), r.vid, w, h)
                tekstura(tek[1], GL_RG8, GL_RG, w / 2, h / 2, ByteBuffer.wrap(src, uv, w * h / 2).slice(), r.vid, w, h, novoe = false)
            }
            UvcVid.P010 -> {                                   // 16 бит на отсчёт (10 значащих), Y — потом UV через один
                if (k.dlina < w * h * 3) return false
                val hp = if (k.dlina > w * h * 3) (k.dlina / 3 / w).coerceAtLeast(h) else h
                tekstura(tek[0], GL_RG8, GL_RG, w, h, ByteBuffer.wrap(k.buf, 0, w * h * 2), r.vid, w, h)
                tekstura(tek[1], GL_RGBA8, GL_RGBA, w / 2, h / 2, ByteBuffer.wrap(k.buf, w * hp * 2, w * h).slice(), r.vid, w, h, novoe = false)
            }
            UvcVid.MJPEG -> { mjpeg.podat(k.buf, k.dlina); return false }
            else -> return false
        }
        return true
    }

    private var razmery = HashMap<Int, Pair<Int, Int>>()

    private fun tekstura(t: Int, vnutr: Int, fmt: Int, w: Int, h: Int, b: ByteBuffer, vid: UvcVid, kw: Int, kh: Int, novoe: Boolean = true) {
        glBindTexture(GL_TEXTURE_2D, t)
        if (razmery[t] != (w to h) || tekVid != vid) {
            glTexImage2D(GL_TEXTURE_2D, 0, vnutr, w, h, 0, fmt, GL_UNSIGNED_BYTE, b)
            razmery[t] = w to h
        } else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, fmt, GL_UNSIGNED_BYTE, b)
        if (novoe) { tekW = kw; tekH = kh }
        if (!novoe || vid != UvcVid.NV12 && vid != UvcVid.I420 && vid != UvcVid.P010) tekVid = vid    // двухплоскостные — после второй
    }

    private fun zagruzitBitmap(bmp: Bitmap) {
        glBindTexture(GL_TEXTURE_2D, tek[2])
        if (razmery[tek[2]] != (bmp.width to bmp.height)) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); razmery[tek[2]] = bmp.width to bmp.height
        } else GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bmp)
        tekW = bmp.width; tekH = bmp.height; tekVid = UvcVid.MJPEG
        mjpeg.vernut(bmp)
    }

    /** I420 (Y, U, V плоскостями; hp — высота с дополнением) → NV12 (Y, затем UV через один) без дополнения. */
    private fun perelozhit(src: ByteArray, w: Int, h: Int, hp: Int): ByteArray {
        val n = w * h; val q = n / 4
        val u0 = w * hp; val v0 = u0 + (w / 2) * (hp / 2)
        val d = nv12?.takeIf { it.size >= n * 3 / 2 } ?: ByteArray(n * 3 / 2).also { nv12 = it }
        System.arraycopy(src, 0, d, 0, n)
        for (i in 0 until q) { d[n + 2 * i] = src[u0 + i]; d[n + 2 * i + 1] = src[v0 + i] }
        return d
    }

    /** Окно закрывается: остановить поток MJPEG. */
    fun zakryt() = mjpeg.zakryt()

    private fun programma(frag: String): Int {
        fun shader(tip: Int, kod: String): Int {
            val s = glCreateShader(tip); glShaderSource(s, kod); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Most.oshibkaPokaza = "шейдер: " + glGetShaderInfoLog(s)
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, Shader.VERSHINNYJ))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, frag))
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Most.oshibkaPokaza = "шейдер: " + glGetProgramInfoLog(p)
        return p
    }

    /**
     * MJPEG: разжатие в нескольких потоках (заход 2: 1440p/4K в MJPEG одному ядру не по силам — ~25–40 мс на кадр).
     * Свежий кадр ждёт в одном окошке; свободный поток берёт его, лишний старый пропускается. Готовых картинок
     * может быть несколько сразу — GL берёт самую новую по номеру, опоздавшая (старше показанной) уходит в запас.
     */
    private class Mjpeg(private val polka: Polka) {
        private val zamok = Object()
        private var vhod: ByteArray? = null; private var vhodN = 0; private var vhodNomer = 0L
        private var gotovo: Bitmap? = null; private var gotovoNomer = 0L
        private val zapas = ArrayDeque<Bitmap>()
        private val potoki = List(POTOKOV) { i ->
            Thread({ try { cikl() } catch (e: InterruptedException) { } }, "most3-mjpeg-$i").apply { isDaemon = true; start() }
        }
        private var nomer = 0L
        private var msSumma = 0.0; private var msN = 0

        fun zakryt() = potoki.forEach { it.interrupt() }

        fun podat(b: ByteArray, n: Int) = synchronized(zamok) {
            if (vhod != null) polka.mjpegPropuskov++           // прежний так никто и не взял — все потоки заняты
            val c = ByteArray(n)                               // свой массив: поток держит его, пока разжимает
            System.arraycopy(b, 0, c, 0, n); vhod = c; vhodN = n; vhodNomer = ++nomer; zamok.notify()
        }

        fun vzyat(): Bitmap? = synchronized(zamok) { gotovo.also { gotovo = null } }
        fun vernut(b: Bitmap) { synchronized(zamok) { if (zapas.size < POTOKOV + 1) zapas.addLast(b) } }

        private fun cikl() {
            val o = BitmapFactory.Options()
            while (true) {
                val b: ByteArray; val n: Int; val moj: Long
                synchronized(zamok) {
                    while (vhod == null) zamok.wait()
                    b = vhod!!; n = vhodN; moj = vhodNomer; vhod = null
                    o.inMutable = true; o.inBitmap = zapas.removeFirstOrNull()
                }
                val t = System.nanoTime()
                val bmp = try { BitmapFactory.decodeByteArray(b, 0, n, o) } catch (e: Exception) {
                    o.inBitmap = null; try { BitmapFactory.decodeByteArray(b, 0, n, o) } catch (e2: Exception) { null }
                }
                if (bmp == null) continue
                val ms = (System.nanoTime() - t) / 1e6
                synchronized(zamok) {
                    msSumma += ms; msN++
                    if (msN >= 30) { polka.mjpegMs = (msSumma / msN).toFloat(); msSumma = 0.0; msN = 0 }
                    if (moj > gotovoNomer) {                   // новее того, что ждёт показа, — заменяет его
                        gotovo?.let { if (zapas.size < POTOKOV + 1) zapas.addLast(it) }
                        gotovo = bmp; gotovoNomer = moj
                    } else if (zapas.size < POTOKOV + 1) zapas.addLast(bmp)
                }
            }
        }
    }

    companion object {
        const val KW = 32; const val KH = 18
        /** Потоков MJPEG: на XR2 Gen 2 шесть ядер; два оставить игре звука, приёму и GL. */
        val POTOKOV = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
    }
}
