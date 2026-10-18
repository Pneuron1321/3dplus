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
 *
 * 2.5.0 (гости эфира): плитки гостей слева посередине (Raskladka) — видео гостя (свой декодер H.264
 * рисует в свою поверхность, как камера) или картинка JPEG; квадрат со скруглёнными углами,
 * поверх — плашка "lg" (рамка, имя, свечение, когда говорит), справа — "lb" (пузыри субтитров).
 * Нижние субтитры "nc" — по центру между розой и окном камеры. Картинка эфира гостям — ещё один
 * выход (маленький кодировщик Prevyu) со всеми слоями.
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

    /** Плитка гостя: видео (OES от декодера) или картинка (JPEG, 2D) — своя текстура на участника. */
    private class Gost {
        var oes = 0
        var st: SurfaceTexture? = null
        var surf: Surface? = null
        val mtx = FloatArray(16)
        val mtxKrop = FloatArray(16)
        @Volatile var vw = 0
        @Volatile var vh = 0
        @Volatile var kadrNs = 0L
        var tex2d = 0
        var kw = 0
        var kh = 0
    }
    private val gosti = java.util.concurrent.ConcurrentHashMap<Int, Gost>()
    /** Плитки гостей в кадре: id участника и место (Raskladka, точки 1080p); ставит Gosti по составу комнаты. */
    @Volatile var plitki: List<Pair<Int, Raskladka.Plitka>> = emptyList()
    /** Гости с выключенной камерой (кнопка у гостя): плитка — сразу круг с буквой, последний кадр не висит. */
    @Volatile var bezKamery: Set<Int> = emptySet()
    private var shOes = Sh(0, 0, 0, 0, 0, 0)                  // программы плиток: видео (OES) и картинка (2D)
    private var sh2d = Sh(0, 0, 0, 0, 0, 0)
    private var shKamera = Sh(0, 0, 0, 0, 0, 0)               // окно камеры: уменьшение в 4 выборки (0 — не собралась)
    private var uShag = 0
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
            for (g in gosti.values) gostOsvobodit(g)
            gosti.clear()
            for (sl in sloi.values) GLES20.glDeleteTextures(1, intArrayOf(sl.tex), 0)
            sloi.clear()
            for (b in yashchik.vse()) b.recycle()
            synchronized(chernoviki) { chernoviki.values.forEach { it.recycle() }; chernoviki.clear() }
            if (program2 != 0) GLES20.glDeleteProgram(program2)
            if (shOes.p != 0) GLES20.glDeleteProgram(shOes.p)
            if (sh2d.p != 0) GLES20.glDeleteProgram(sh2d.p)
            if (shKamera.p != 0) GLES20.glDeleteProgram(shKamera.p)
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
        fun sh(p: Int) = Sh(p, GLES20.glGetAttribLocation(p, "aPos"), GLES20.glGetAttribLocation(p, "aTex"),
            GLES20.glGetUniformLocation(p, "uMtx"), GLES20.glGetUniformLocation(p, "uRazmer"), GLES20.glGetUniformLocation(p, "uR"))
        shOes = sh(program(VERTEX_PLITKA, FRAGMENT_PLITKA))
        sh2d = sh(program(VERTEX_PLITKA, FRAGMENT_PLITKA_2D))
        try {                                         // не собралась — окно камеры рисуем как раньше
            shKamera = sh(program(VERTEX_KAMERA, FRAGMENT_KAMERA))
            uShag = GLES20.glGetUniformLocation(shKamera.p, "uShag")
        } catch (e: Exception) { shKamera = Sh(0, 0, 0, 0, 0, 0) }
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
                plitkiNa(o)
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
     * "pc" — чат справа между плашкой Плюса и окном камеры; "vse" — во весь кадр (итоги вечера);
     * "mz" — «сейчас играет» (2.5.6): справа над окном камеры, чат — над ней.
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
            "nc" -> { x = nizCentr(o, w) - w / 2; y = o.h * 5 / 100 }
            "lg", "lb" -> { x = 0; y = o.h - ((Raskladka.SLOY_Y + sl.h) * s).toInt() }   // слои гостей: от точки (0; SLOY_Y) 1080p
            "vse" -> { x = 0; y = 0; w = o.w; h = o.h }
            "mz" -> {                         // музыка: справа над окном камеры (без камеры — над субтитрами)
                val zazor = (8 * s).toInt()
                y = kameraVerh(o)?.let { it + zazor } ?: (o.h * 5 / 100 + (Nalozhenie.TH * s).toInt() + zazor)
            }
            "pc" -> {                         // чат: справа, между плашкой Плюса и окном камеры (и музыкой)
                val zazor = (8 * s).toInt()
                val pv = sloi["pv"]?.takeIf { it.w > 0 }
                val verh = o.h - m - (pv?.let { (it.h * s).toInt() } ?: 0) - zazor
                val mz = sloi["mz"]?.takeIf { it.w > 0 }?.let { (it.h * s).toInt() + zazor } ?: 0
                val niz = (kameraVerh(o)?.let { it + zazor }
                    ?: (o.h * 5 / 100 + (Nalozhenie.TH * s).toInt() + zazor)) + mz    // без камеры — над субтитрами
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

    /**
     * Центр нижних субтитров (2.5.0): между розой (если она есть) и окном камеры внизу справа (или
     * слева) — длинная строка не уходит под окно.
     */
    private fun nizCentr(o: Out, shir: Int): Int {
        val s = o.h / 1080f
        val m = o.h / 100
        var levo = sloi["ln"]?.takeIf { it.w > 0 }?.let { m + (it.w * s).toInt() } ?: m
        var pravo = o.w - m
        val v = vid
        if (kameraVidna() && v.rezhim == "ugol" && v.gde.endsWith("n")) {
            val r = Okno.mesto(o.w, o.h, Okno.proporcii(kw, kh, v.povorot), v)
            if (v.gde.startsWith("p")) pravo = minOf(pravo, r[0] - m) else levo = maxOf(levo, r[0] + r[2] + m)
        }
        return Raskladka.centrNiza(o.w, levo, pravo, shir)
    }

    // ---------- гости (2.5.0) ----------

    /** Поверхность для декодера видео гостя [id] (одна на гостя; повторный вызов — та же). */
    fun gostPoverhnost(id: Int): Surface = sync {
        val g = gosti.getOrPut(id) { Gost() }
        g.surf ?: run {
            EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
            g.oes = oesTexture()
            val t = SurfaceTexture(g.oes)
            t.setOnFrameAvailableListener({ kadrGostya(g) }, handler)
            g.st = t
            Surface(t).also { g.surf = it }
        }
    }

    /** Размер видео гостя (от декодера) — для обрезки в квадрат. */
    fun gostRazmer(id: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        handler.post { gosti[id]?.let { it.vw = w; it.vh = h } }
    }

    /** Кадр-картинка гостя (JPEG, без WebCodecs у браузера): в текстуру на потоке развилки, потом recycle. */
    fun gostKartinka(id: Int, b: Bitmap) {
        if (released) { b.recycle(); return }
        handler.post {
            if (released) { b.recycle(); return@post }
            EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
            val g = gosti.getOrPut(id) { Gost() }
            if (g.tex2d == 0) {
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                g.tex2d = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.tex2d)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.tex2d)
            if (b.width == g.kw && b.height == g.kh) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
            else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            g.kw = b.width; g.kh = b.height
            g.kadrNs = System.nanoTime()
            b.recycle()
            if (g.kadrNs - lastScreenNs > 50_000_000L) { zabrat(); schet(g.kadrNs); risovat(g.kadrNs, tolkoSloi = true) }
        }
    }

    /** Гостя нет (вышел): декодер уже остановлен — текстуры прочь. */
    fun gostUbrat(id: Int) = sync {
        if (released) return@sync
        val g = gosti.remove(id) ?: return@sync
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
        gostOsvobodit(g)
    }

    /** Видно ли видео гостя (кадр за последние 2 с) — иначе плашка рисует круг с буквой. */
    fun gostVidno(id: Int): Boolean {
        if (id in bezKamery) return false
        val g = gosti[id] ?: return false
        return g.kadrNs != 0L && System.nanoTime() - g.kadrNs < 2_000_000_000L
    }

    private fun gostOsvobodit(g: Gost) {
        g.st?.setOnFrameAvailableListener(null)
        g.surf?.release()
        g.st?.release()
        if (g.oes != 0) GLES20.glDeleteTextures(1, intArrayOf(g.oes), 0)
        if (g.tex2d != 0) GLES20.glDeleteTextures(1, intArrayOf(g.tex2d), 0)
        g.oes = 0; g.tex2d = 0; g.st = null; g.surf = null
    }

    private fun kadrGostya(g: Gost) {
        val t = g.st ?: return
        if (released) return
        EGL14.eglMakeCurrent(display, pbuf, pbuf, context)
        t.updateTexImage()
        t.getTransformMatrix(g.mtx)
        g.kadrNs = System.nanoTime()
        g.kw = 0                                                     // видео сильнее картинки
        if (g.kadrNs - lastScreenNs > 50_000_000L && g.kadrNs - kameraNs > 50_000_000L) {
            zabrat(); schet(g.kadrNs); risovat(g.kadrNs, tolkoSloi = true)
        }
    }

    /** Плитки гостей поверх кадра эфира: квадрат со скруглёнными углами, видео обрезано по центру. */
    private fun plitkiNa(o: Out) {
        val ps = plitki
        if (ps.isEmpty()) return
        val s = o.h / 1080f
        val now = System.nanoTime()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for ((id, p) in ps) {
            val g = gosti[id] ?: continue
            if (g.kadrNs == 0L || now - g.kadrNs > 2_000_000_000L || id in bezKamery) continue   // нет видео — круг с буквой (плашка)
            val st = (p.s * s).toInt()
            val x = (p.x * s).toInt()
            val y = o.h - ((p.y + p.s) * s).toInt()
            GLES20.glViewport(x, y, st, st)
            val video = g.st != null && g.kw == 0
            val sh = if (video) shOes else sh2d
            GLES20.glUseProgram(sh.p)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (video) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, g.oes)
                android.opengl.Matrix.multiplyMM(g.mtxKrop, 0, g.mtx, 0, krop(g.vw, g.vh), 0)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.tex2d)
                android.opengl.Matrix.multiplyMM(g.mtxKrop, 0, PEREVOROT, 0, krop(g.kw, g.kh), 0)
            }
            GLES20.glUniformMatrix4fv(sh.uMtx, 1, false, g.mtxKrop, 0)
            GLES20.glUniform2f(sh.uRazmer, st.toFloat(), st.toFloat())
            GLES20.glUniform1f(sh.uR, Raskladka.RADIUS * s)
            quad.position(0)
            GLES20.glVertexAttribPointer(sh.aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(sh.aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(sh.aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(sh.aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private class Sh(val p: Int, val aPos: Int, val aTex: Int, val uMtx: Int, val uRazmer: Int, val uR: Int)

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
        if (shKamera.p == 0) return kvadrat(ktex, kmtxPov)
        val umen = maxOf(r[2], r[3]) < maxOf(kw, kh)      // кадр телефона больше окна — уменьшаем
        kvadratKamery(kmtxPov, if (umen) 0.25f / r[2] else 0f, if (umen) 0.25f / r[3] else 0f)
    }

    /**
     * Окно камеры. Одна выборка на точку при уменьшении (1080p телефона в окно ~500 точек) теряет
     * мелочь и даёт «лесенку» и мерцание шума — 4 выборки по четвертям точки окна (2.5.5, Александр
     * 17.10: «картинка с камеры слабовата»). sx, sy — четверть точки окна в долях окна (0 — одна выборка).
     */
    private fun kvadratKamery(m: FloatArray, sx: Float, sy: Float) {
        val k = shKamera
        GLES20.glUseProgram(k.p)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ktex)
        GLES20.glUniformMatrix4fv(k.uMtx, 1, false, m, 0)
        GLES20.glUniform2f(uShag, sx, sy)
        quad.position(0)
        GLES20.glVertexAttribPointer(k.aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(k.aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(k.aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(k.aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        /** Плитка гостя: vUv — точка плитки 0…1 (для скруглённых углов). */
        private val VERTEX_PLITKA = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMtx;
            varying vec2 vTex;
            varying vec2 vUv;
            void main() { gl_Position = aPos; vTex = (uMtx * aTex).xy; vUv = aPos.xy * 0.5 + 0.5; }
        """.trimIndent()
        /** Скругление: расстояние до прямоугольника со скруглёнными углами; край — 1 точка сглаживания. */
        private const val MASKA = """
            uniform vec2 uRazmer;
            uniform float uR;
            float maska() {
                vec2 p = (vUv - 0.5) * uRazmer;
                vec2 q = abs(p) - (uRazmer * 0.5 - uR);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uR;
                return clamp(0.5 - d, 0.0, 1.0);
            }
        """
        private val FRAGMENT_PLITKA = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vUv;
            uniform samplerExternalOES sTex;
            $MASKA
            void main() { float a = maska() * ${Raskladka.VIDEO_A}; gl_FragColor = vec4(texture2D(sTex, vTex).rgb * a, a); }
        """.trimIndent()
        private val FRAGMENT_PLITKA_2D = """
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vUv;
            uniform sampler2D sTex;
            $MASKA
            void main() { float a = maska() * ${Raskladka.VIDEO_A}; gl_FragColor = vec4(texture2D(sTex, vTex).rgb * a, a); }
        """.trimIndent()

        /** Квадрат из середины кадра w×h: матрица текстурных координат (по столбцам), умножается справа. */
        fun krop(w: Int, h: Int): FloatArray {
            if (w <= 0 || h <= 0 || w == h) return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            return if (w > h) { val k = h.toFloat() / w; floatArrayOf(k, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, (1 - k) / 2, 0f, 0f, 1f) }
            else { val k = w.toFloat() / h; floatArrayOf(1f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f, 0f, 0f, (1 - k) / 2, 0f, 1f) }
        }

        /**
         * Окно камеры: шаг на четверть точки окна, переведённый в координаты текстуры той же матрицей
         * (с поворотом окна), — 4 выборки вокруг середины точки (каждая ещё и сглаженная, LINEAR).
         */
        private val VERTEX_KAMERA = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMtx;
            uniform vec2 uShag;
            varying vec2 vTex;
            varying vec2 vDx;
            varying vec2 vDy;
            void main() {
                gl_Position = aPos;
                vTex = (uMtx * aTex).xy;
                vDx = (uMtx * vec4(uShag.x, 0.0, 0.0, 0.0)).xy;
                vDy = (uMtx * vec4(0.0, uShag.y, 0.0, 0.0)).xy;
            }
        """.trimIndent()
        /** Координаты — highp: в mediump сдвиг на четверть точки 1080p-кадра тонет в округлении. */
        private val FRAGMENT_KAMERA = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTex;
            varying vec2 vDx;
            varying vec2 vDy;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = 0.25 * (texture2D(sTex, vTex - vDx - vDy) + texture2D(sTex, vTex + vDx - vDy)
                    + texture2D(sTex, vTex - vDx + vDy) + texture2D(sTex, vTex + vDx + vDy));
            }
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
