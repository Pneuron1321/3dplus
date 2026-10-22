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
 * Форматы: YUY2 и NV12 — прямо; I420 — перекладка U/V в NV12 на CPU; MJPEG — разжатие в отдельном потоке.
 */
class Pokaz(private val polka: Polka) : GLSurfaceView.Renderer {
    private var progYuy2 = 0; private var progNv12 = 0; private var progRgba = 0
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
    private val mjpeg = Mjpeg()
    // Запись игры (Klip3): та же картинка — ещё и в поверхность кодировщика (EGL14 поверх контекста GLSurfaceView)
    private var eglD: EGLDisplay? = null; private var eglK: EGLContext? = null; private var eglCfg: EglKonfig? = null
    private var kodPov: EGLSurface? = null; private var kodSurface: android.view.Surface? = null
    private var kodNelzya = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progYuy2 = programma(Shader.FRAG_YUY2); progNv12 = programma(Shader.FRAG_NV12); progRgba = programma(Shader.FRAG_RGBA)
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
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTek, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        tekVid = null; poslednij = -1
        razmery.clear()                                      // новый контекст — у текстур нет хранилища
        eglD = EGL14.eglGetCurrentDisplay(); eglK = EGL14.eglGetCurrentContext()
        val id = IntArray(1)
        EGL14.eglQueryContext(eglD, eglK, EGL14.EGL_CONFIG_ID, id, 0)
        val cfgs = arrayOfNulls<EglKonfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglD, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        eglCfg = cfgs[0]; kodPov = null; kodSurface = null; kodNelzya = false
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
        val prog = when (tekVid) { UvcVid.MJPEG -> progRgba; UvcVid.YUY2 -> progYuy2; else -> progNv12 }
        val n = Most.nastrojki

        if (novyj && ++schet % 6 == 0) {                         // замер средней яркости сцены
            glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, KW, KH)
            risovat(prog, n, 0f, syroe = true)
            kroshka.position(0)
            glReadPixels(0, 0, KW, KH, GL_RGBA, GL_UNSIGNED_BYTE, kroshka)
            kroshka.position(0); kroshka.get(kroshkaArr)
            polka.srednyaya = srednyayaYarkost(kroshkaArr, KW, KH)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        }
        polka.avtoTeni = sglazh.shag(avtoTeni(polka.srednyaya, n.avtoNoch), dt)

        // окно — с полями под пропорции кадра
        val kw = tekW.toFloat(); val kh = tekH.toFloat()
        val m = minOf(ekranW / kw, ekranH / kh)
        val vw = (kw * m).toInt(); val vh = (kh * m).toInt()
        glViewport((ekranW - vw) / 2, (ekranH - vh) / 2, vw, vh)
        risovat(prog, n, polka.avtoTeni, syroe = false)
        if (novyj) zapisat(prog, n)

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
        risovat(prog, n, polka.avtoTeni, syroe = false)
        EGLExt.eglPresentationTimeANDROID(eglD, pov, System.nanoTime())
        EGL14.eglSwapBuffers(eglD, pov)
        EGL14.eglMakeCurrent(eglD, draw, read, eglK)
    }

    private fun risovat(prog: Int, n: Nastrojki, avto: Float, syroe: Boolean) {
        glUseProgram(prog)
        fun u(imya: String, v: Float) = glUniform1f(glGetUniformLocation(prog, imya), v)
        u("uYarkost", n.yarkost); u("uKontrast", n.kontrast); u("uNasyshch", n.nasyshch); u("uGamma", n.gamma)
        u("uTeni", n.teni); u("uAvtoTeni", avto); u("uRezkost", n.rezkost)
        u("uPolnyj", if (n.polnyjDiapazon) 1f else 0f); u("uSyroe", if (syroe) 1f else 0f)
        when (prog) {
            progYuy2 -> { glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[0]); glUniform1i(glGetUniformLocation(prog, "uTekYuy2"), 0) }
            progNv12 -> {
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tek[0]); glUniform1i(glGetUniformLocation(prog, "uTekY"), 0)
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tek[1]); glUniform1i(glGetUniformLocation(prog, "uTekUV"), 1)
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
        if (!novoe || vid != UvcVid.NV12 && vid != UvcVid.I420) tekVid = vid
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

    /** MJPEG: разжатие самого свежего кадра в своём потоке; GL забирает готовую картинку. */
    private class Mjpeg {
        private val zamok = Object()
        private var vhod: ByteArray? = null; private var vhodN = 0
        @Volatile private var gotovo: Bitmap? = null
        private var zapas: Bitmap? = null
        private val potok = Thread({ try { cikl() } catch (e: InterruptedException) { } }, "most3-mjpeg").apply { isDaemon = true; start() }

        fun zakryt() = potok.interrupt()

        fun podat(b: ByteArray, n: Int) = synchronized(zamok) {
            val c = vhod?.takeIf { it.size >= n } ?: ByteArray(n)
            System.arraycopy(b, 0, c, 0, n); vhod = c; vhodN = n; zamok.notify()
        }

        fun vzyat(): Bitmap? { val b = gotovo; gotovo = null; return b }
        fun vernut(b: Bitmap) { synchronized(zamok) { zapas = b } }

        private fun cikl() {
            val o = BitmapFactory.Options()
            while (true) {
                val (b, n) = synchronized(zamok) { while (vhod == null) zamok.wait(); val r = vhod!! to vhodN; vhod = null; r }
                o.inMutable = true; o.inBitmap = synchronized(zamok) { zapas.also { zapas = null } }
                val bmp = try { BitmapFactory.decodeByteArray(b, 0, n, o) } catch (e: Exception) {
                    o.inBitmap = null; try { BitmapFactory.decodeByteArray(b, 0, n, o) } catch (e2: Exception) { null }
                }
                if (bmp != null) gotovo = bmp
            }
        }
    }

    companion object { const val KW = 32; const val KH = 18 }
}
