package com.questclaude.bridge

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Развилка кадра через видеокарту. Захват экрана (VirtualDisplay) рисует в одну поверхность —
 * [input], а развилка раздаёт каждый кадр нескольким выходам: кодировщику эфира (до 30 к/с, со
 * временем кадра) и ImageReader глаз Плюса (несколько кадров в секунду). Второй VirtualDisplay
 * на ту же проекцию Android 14 не даёт — раздваиваем сами. Всё GL — на своём потоке.
 * Поверх кадра эфира (выходы со sloi) — окно камеры телефона (Kamera): своя текстура, её
 * кадры приходят от декодера; и плашки (Nalozhenie) — картинки с прозрачностью по углам:
 * лицо Плюса и налёт справа вверху, радар слева внизу. Глаза Плюса ни окна, ни плашек не видят.
 *
 * 2.3.9 (план эфира III.1–III.2): плашки — через почтовый ящик (Yashchik): sloy() кладёт копию и
 * сразу возвращается, поток развилки забирает в начале кадра экрана не больше одной, только новое.
 * Счётчики для /efir и чёрного ящика: кадры экрана в секунду, время загрузок плашек, частота глаз.
 */
class Razvilka(private val w: Int, private val h: Int) {
    private val thread = HandlerThread("razvilka").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMtx = 0
    private lateinit var st: SurfaceTexture
    lateinit var input: Surface
        private set
    private val mtx = FloatArray(16)
    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        .let { ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it).apply { position(0) } }

    /** Выход: своя поверхность EGL и размер; кадр не чаще раза в gapNs; stamp — ставить время кадра (кодировщику);
     *  sloi — рисовать поверх окно камеры (эфир). */
    private class Out(val surface: Surface, val egl: EGLSurface, var gapNs: Long, val stamp: Boolean, val w: Int, val h: Int,
                      val sloi: Boolean) {
        var next = 0L
        var lastTs = 0L
    }

    /** Окно камеры: где и как (Okno.Vid); меняется на ходу. */
    @Volatile var vid = Okno.Vid()
    /** Сколько места под чат справа (точки кадра 1080p): плашка рисует только влезающие сообщения. */
    @Volatile var chatMesto = Nalozhenie.CH

    private var ktex = 0
    private var kst: SurfaceTexture? = null
    private var ksurf: Surface? = null
    private val kmtx = FloatArray(16)
    private val kmtxPov = FloatArray(16)                     // kmtx × поворот окна (Okno.povorot)
    @Volatile private var kameraNs = 0L
    @Volatile private var kw = 0
    @Volatile private var kh = 0
    private var lastScreenNs = 0L
    private var program2 = 0
    private var aPos2 = 0
    private var aTex2 = 0
    private var uMtx2 = 0
    /**
     * Плашки поверх эфира: "pv" — правый верх (лицо и налёт), "ln" — левый низ (роза ветров),
     * "lv" — левый верх (топ-донатор и табло соведущего), "nc" — низ по центру (субтитры), "pc" — чат справа,
     * "vse" — весь кадр (итоги).
     */
    private class Sloy(val tex: Int) { var w = 0; var h = 0 }
    private val sloi = linkedMapOf<String, Sloy>()
    private val outs = mutableListOf<Out>()
    private var released = false

    /** Кадров от захвата — для статуса. */
    @Volatile var frames = 0L
        private set

    private val yashchik = Yashchik<Bitmap>()
    private val chernoviki = HashMap<String, Bitmap>()          // буферы писателя (Nalozhenie), по углу
    @Volatile private var zhdetRisovaniya = false
    /** Всего на загрузки плашек в видеокарту (нс) и сколько их было. */
    @Volatile var plashkiNs = 0L
        private set
    @Volatile var zagruzok = 0L
        private set
    /** За последнюю секунду: кадров экрана (то, что видит игрок), мс загрузок плашек, загрузок. */
    @Volatile private var ekranFpsS = 0f
    @Volatile var plashkiMsSek = 0f
        private set
    @Volatile var zagruzokSek = 0
        private set
    /** Сколько кадров в секунду отдаём глазам Плюса (ставит CaptureService). */
    @Volatile var glazaFps = 0
        private set
    private var okno0 = 0L
    private var oknoKadrov = 0L
    private var oknoNs = 0L
    private var oknoZagruzok = 0L

    /** Кадров экрана в секунду; экран стоит дольше секунды — 0. */
    val ekranFps: Float get() = if (System.nanoTime() - lastScreenNs > 1_000_000_000L) 0f else ekranFpsS

    init {
        sync { setup() }
    }

    /** Добавить выход: fps — сколько кадров в секунду ему отдавать; ow×oh — его размер (масштаб — видеокарта). */
    fun add(surface: Surface, fps: Int, stamp: Boolean, ow: Int = w, oh: Int = h, sloi: Boolean = false) = sync {
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(egl != EGL14.EGL_NO_SURFACE) { "EGL: поверхность выхода не создана (0x${Integer.toHexString(EGL14.eglGetError())})" }
        outs += Out(surface, egl, 1_000_000_000L / fps.coerceIn(1, 120), stamp, ow, oh, sloi)
    }

    /** Поверхность для декодера камеры (одна; повторный вызов — та же). */
    fun kamera(): Surface = sync {
        ksurf ?: run {
            EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
            ktex = oesTexture()
            val k = SurfaceTexture(ktex)
            k.setOnFrameAvailableListener({ kadrKamery() }, handler)
            kst = k
            Surface(k).also { ksurf = it }
        }
    }

    /** Размер кадра камеры (от декодера) — для пропорций окна. */
    fun kameraRazmer(w: Int, h: Int) {
        if (w > 0 && h > 0) { kw = w; kh = h }
    }

    /** Плашка в правом верхнем углу (лицо и налёт). */
    fun plashka(b: Bitmap?) = sloy("pv", b)

    /** Частота выхода (глаза Плюса: меньше во время разговора — 2.3.9); поменяется со следующего кадра. */
    fun fps(surface: Surface, fps: Int) {
        if (surface === glazaSurface) glazaFps = fps
        handler.post { outs.firstOrNull { it.surface === surface }?.gapNs = 1_000_000_000L / fps.coerceIn(1, 120) }
    }

    /** Какой выход — глаза Плюса (для glazaFps). */
    @Volatile var glazaSurface: Surface? = null
        set(v) { field = v; glazaFps = 0 }

    /**
     * Плашка в кадре эфира: "pv", "ln", "lv", "nc", "pc", "vse" (см. Sloy; размер задан для 1080p,
     * в 720p — меньше). null — убрать. Кладёт копию в почтовый ящик и сразу возвращается (2.3.9):
     * картинку можно менять сразу после вызова; в видеокарту её отнесёт поток развилки.
     */
    fun sloy(ugol: String, b: Bitmap?) {
        if (released) return
        if (b == null) {
            yashchik.polozhit(ugol, null)?.let { it.recycle() }
            synchronized(chernoviki) { chernoviki.remove(ugol) }?.recycle()
        } else {
            val ch = synchronized(chernoviki) { chernoviki.remove(ugol) }
                ?.takeIf { !it.isRecycled && it.width == b.width && it.height == b.height }
                ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            Canvas(ch).drawBitmap(b, 0f, 0f, KOPIYA)
            yashchik.polozhit(ugol, ch)?.let { staroe ->
                val lishnee = synchronized(chernoviki) { chernoviki.put(ugol, staroe) }
                if (lishnee != null && lishnee !== staroe) lishnee.recycle()
            }
        }
        // экран стоит — без кадров захвата новая плашка не попала бы в эфир: развилка заберёт сама
        if (System.nanoTime() - lastScreenNs > 50_000_000L && !zhdetRisovaniya) {
            zhdetRisovaniya = true
            handler.post {
                zhdetRisovaniya = false
                if (!released && System.nanoTime() - lastScreenNs > 50_000_000L) {
                    EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
                    var n = 0
                    while (n < 8 && zabrat()) n++                    // экран стоит — забрать всё сразу
                    schet(System.nanoTime())
                    risovat(System.nanoTime(), tolkoSloi = true)
                }
            }
        }
    }

    /** Из ящика — одну плашку в видеокарту (самую давнюю из новых). Поток развилки. false — нового нет. */
    private fun zabrat(): Boolean = yashchik.zabrat { ugol, b ->
        val t0 = System.nanoTime()
        if (b == null || b.isRecycled) sloi[ugol]?.let { it.w = 0; it.h = 0 }
        else zagruzit(ugol, b)
        plashkiNs += System.nanoTime() - t0
        zagruzok++
    }

    /** Счётчики за секунду: кадры экрана, время и число загрузок плашек. */
    private fun schet(now: Long) {
        if (okno0 == 0L) { okno0 = now; oknoKadrov = frames; oknoNs = plashkiNs; oknoZagruzok = zagruzok; return }
        val dt = now - okno0
        if (dt < 1_000_000_000L) return
        ekranFpsS = (frames - oknoKadrov) * 1e9f / dt
        plashkiMsSek = (plashkiNs - oknoNs) / 1e6f * (1e9f / dt)
        zagruzokSek = ((zagruzok - oknoZagruzok) * 1_000_000_000L / dt).toInt()
        okno0 = now; oknoKadrov = frames; oknoNs = plashkiNs; oknoZagruzok = zagruzok
    }

    /** Картинку угла — в текстуру (поток развилки, контекст уже текущий). */
    private fun zagruzit(ugol: String, b: Bitmap) {
        val sl = sloi.getOrPut(ugol) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            Sloy(t[0])
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sl.tex)
        if (b.width == sl.w && b.height == sl.h) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
        else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        sl.w = b.width; sl.h = b.height
    }

    /** Камеры больше нет: окно прочь, текстуру — освободить (после остановки декодера). */
    fun kameraUbrat() = sync {
        if (released) return@sync
        kameraNs = 0
        kst?.setOnFrameAvailableListener(null)
        ksurf?.release()
        kst?.release()
        if (ktex != 0) {
            EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
            GLES20.glDeleteTextures(1, intArrayOf(ktex), 0)
        }
        ktex = 0; kst = null; ksurf = null
    }

    /** Убрать выход (до release поверхности у её хозяина). */
    fun remove(surface: Surface) = sync {
        val o = outs.firstOrNull { it.surface === surface } ?: return@sync
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
        EGL14.eglDestroySurface(display, o.egl)
        outs -= o
    }

    fun release() {
        sync {
            released = true
            EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
            for (o in outs) EGL14.eglDestroySurface(display, o.egl)
            outs.clear()
            st.setOnFrameAvailableListener(null)
            input.release()
            st.release()
            kst?.setOnFrameAvailableListener(null)
            ksurf?.release()
            kst?.release()
            if (ktex != 0) GLES20.glDeleteTextures(1, intArrayOf(ktex), 0)
            for (sl in sloi.values) GLES20.glDeleteTextures(1, intArrayOf(sl.tex), 0)
            sloi.clear()
            for (b in yashchik.vse()) b.recycle()
            synchronized(chernoviki) { chernoviki.values.forEach { it.recycle() }; chernoviki.clear() }
            if (program2 != 0) GLES20.glDeleteProgram(program2)
            if (program != 0) GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuf)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
        thread.quitSafely()
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL не запустился" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,                 // поверхность кодировщика
            EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "EGL: нет подходящего формата" }
        config = cfgs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL: контекст не создан" }
        pbuf = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)

        program = program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMtx = GLES20.glGetUniformLocation(program, "uMtx")
        program2 = program(VERTEX, FRAGMENT_2D)
        aPos2 = GLES20.glGetAttribLocation(program2, "aPos")
        aTex2 = GLES20.glGetAttribLocation(program2, "aTex")
        uMtx2 = GLES20.glGetUniformLocation(program2, "uMtx")
        tex = oesTexture()
        st = SurfaceTexture(tex)
        st.setDefaultBufferSize(w, h)
        st.setOnFrameAvailableListener({ draw() }, handler)
        input = Surface(st)
    }

    private fun oesTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun draw() {
        if (released) return
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
        st.updateTexImage()
        st.getTransformMatrix(mtx)
        val ts = st.timestamp.takeIf { it > 0 } ?: System.nanoTime()
        frames++
        lastScreenNs = System.nanoTime()
        zabrat()                                     // не больше одной плашки за кадр экрана
        schet(lastScreenNs)
        risovat(ts, tolkoSloi = false)
    }

    /** Новый кадр камеры. Экран стоит (захват молчит) — рисуем выход эфира сами, иначе окно застыло бы. */
    private fun kadrKamery() {
        val k = kst ?: return
        if (released) return
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
        k.updateTexImage()
        k.getTransformMatrix(kmtx)
        kameraNs = System.nanoTime()
        if (kameraNs - lastScreenNs > 50_000_000L) {
            zabrat()                                 // экран стоит — плашки забирает кадр камеры
            schet(kameraNs)
            risovat(kameraNs, tolkoSloi = true)
        }
    }

    private fun risovat(ts0: Long, tolkoSloi: Boolean) {
        for (o in outs) {
            if (tolkoSloi && !o.sloi) continue
            if (ts0 < o.next) continue
            // ровно fps в среднем: следующий слот — через gap; отстали больше чем на слот — с этого кадра
            o.next = if (ts0 - o.next > o.gapNs) ts0 + o.gapNs else o.next + o.gapNs
            val ts = if (ts0 <= o.lastTs) o.lastTs + 1_000_000 else ts0     // время кадра кодировщику — только вперёд
            o.lastTs = ts
            if (!EGL14.eglMakeCurrent(display, o.egl, o.egl, context)) continue
            GLES20.glViewport(0, 0, o.w, o.h)
            if (o.sloi && vid.rezhim == "krupno" && kameraVidna()) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            } else kvadrat(tex, mtx)
            if (o.sloi) {
                okno(o)
                for ((ugol, sl) in sloi) if (ugol != "vse") plashkaNa(o, ugol, sl)
                sloi["vse"]?.let { plashkaNa(o, "vse", it) }               // итоги вечера — поверх всего
            }
            if (o.stamp) EGLExt.eglPresentationTimeANDROID(display, o.egl, ts)
            EGL14.eglSwapBuffers(display, o.egl)
        }
    }

    private fun kvadrat(t: Int, m: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
        GLES20.glUniformMatrix4fv(uMtx, 1, false, m, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Плашка в своём углу — в самом углу, отступ 1 % высоты (Александр 08.10: налёт «в самом верху
     * правого угла», компас «в самом углу левом»); "nc" — внизу по центру (субтитры, 5 % от низа);
     * "pc" — чат справа между плашкой Плюса и окном камеры; "vse" — во весь кадр (итоги вечера).
     * Начало координат GL — внизу слева.
     */
    private fun plashkaNa(o: Out, ugol: String, sl: Sloy) {
        if (sl.w == 0) return
        val s = o.h / 1080f
        var w = (sl.w * s).toInt()
        var h = (sl.h * s).toInt()
        val m = o.h / 100
        var x = if (ugol.startsWith("l")) m else o.w - w - m
        var y = if (ugol.endsWith("n")) m else o.h - h - m
        when (ugol) {
            "nc" -> { x = (o.w - w) / 2; y = o.h * 5 / 100 }
            "vse" -> { x = 0; y = 0; w = o.w; h = o.h }
            "pc" -> {                         // чат: справа, между плашкой Плюса и окном камеры
                val zazor = (8 * s).toInt()
                val pv = sloi["pv"]?.takeIf { it.w > 0 }
                val verh = o.h - m - (pv?.let { (it.h * s).toInt() } ?: 0) - zazor
                val niz = kameraVerh(o)?.let { it + zazor }
                    ?: (o.h * 5 / 100 + (Nalozhenie.TH * s).toInt() + zazor)     // без камеры — над субтитрами
                chatMesto = ((verh - niz) / s).toInt().coerceIn(0, Nalozhenie.CH)
                if (verh - niz < (60 * s).toInt()) return
                y = niz                       // новые сообщения — внизу картинки; лишнее срежет сверху
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(x, niz, w, verh - niz)
            }
        }
        GLES20.glViewport(x, y, w, h)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)   // Bitmap — с умноженной альфой
        GLES20.glUseProgram(program2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sl.tex)
        GLES20.glUniformMatrix4fv(uMtx2, 1, false, PEREVOROT, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos2, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos2)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex2, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** Верх окна камеры (GL — от низа кадра, с рамкой), если окно справа внизу и видно; иначе null. */
    private fun kameraVerh(o: Out): Int? {
        val v = vid
        if (!kameraVidna() || v.rezhim != "ugol" || v.gde != "pn") return null
        val r = Okno.mesto(o.w, o.h, Okno.proporcii(kw, kh, v.povorot), v)
        return r[1] + r[3] + maxOf(2, o.h / 360)
    }

    /** Камера шлёт кадры (не молчит дольше секунды) и окно не скрыто. */
    private fun kameraVidna() = kst != null && kw > 0 && vid.rezhim != "skryt" &&
        System.nanoTime() - kameraNs < 1_000_000_000L

    /** Камера телефона в эфире: кадры идут, окно не скрыто (с любого потока) — Плюс снимает (2.3.7). */
    fun kameraIdet(): Boolean {
        val ns = kameraNs
        return ns != 0L && kw > 0 && vid.rezhim != "skryt" && System.nanoTime() - ns < 1_000_000_000L
    }

    /** Окно камеры стоя (айфон вертикально, с поворотом окна) — Плюс держит телефон стоя (2.4.0). */
    fun kameraStoya(): Boolean = Okno.stoya(kw, kh, vid.povorot)

    /** Окно камеры поверх кадра эфира (начало координат GL — внизу слева). */
    private fun okno(o: Out) {
        if (!kameraVidna()) return
        val v = vid
        val ar = Okno.proporcii(kw, kh, v.povorot)
        val r = Okno.mesto(o.w, o.h, ar, v)
        if (v.rezhim != "krupno") {
            val b = maxOf(2, o.h / 360)                     // рамка
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(r[0] - b, r[1] - b, r[2] + 2 * b, r[3] + 2 * b)
            GLES20.glClearColor(0.93f, 0.93f, 0.93f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        GLES20.glViewport(r[0], r[1], r[2], r[3])
        android.opengl.Matrix.multiplyMM(kmtxPov, 0, kmtx, 0, Okno.povorot(v.povorot), 0)
        kvadrat(ktex, kmtxPov)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "шейдер: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "программа GL: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    /** Выполнить на потоке развилки и дождаться; исключение — наружу. */
    private fun <T> sync(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post {
            result = runCatching(block)
            done.countDown()
        }
        check(done.await(3, TimeUnit.SECONDS)) { "развилка не ответила" }
        return result!!.getOrThrow()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        /** Копия картинки как есть (с умноженной альфой) — в буфер ящика. */
        private val KOPIYA = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        private val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMtx;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uMtx * aTex).xy; }
        """.trimIndent()
        /** Bitmap в текстуре — строкой 0 вниз: переворачиваем t → 1 − t. */
        private val PEREVOROT = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        private val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        private val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
    }
}
