package com.questclaude.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Живой компас шлема — для розы ветров в радаре эфира: куда Александр смотрит в настоящем мире.
 * Берём лучший датчик, что даёт шлем: ROTATION_VECTOR (с магнитометром — настоящий север),
 * GEOMAGNETIC_ROTATION_VECTOR, иначе GAME_ROTATION_VECTOR — без магнитометра, север
 * относительный (куда смотрел при старте). «Север здесь» (sever) — поправка: туда, куда смотришь
 * сейчас, — север. Куда смотрит шлем: экран обращён к глазам, значит взгляд — тыльная сторона
 * устройства (как камера телефона, поднятого перед собой): remapCoordinateSystem(X, Z).
 */
class Kompas(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private val r = FloatArray(9)
    private val r2 = FloatArray(9)
    private val o = FloatArray(3)
    /** "absolute" — с магнитометром, "relative" — без, "none" — датчика нет. */
    @Volatile var tip = "none"
        private set
    /** Лучший датчик поворота, что даёт шлем (выбран сразу — /nalet скажет, какой, ещё до эфира). */
    private val datchik: Sensor? = sm?.let {
        it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { tip = "absolute" }
            ?: it.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)?.also { tip = "absolute" }
            ?: it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.also { tip = "relative" }
    }
    @Volatile private var syroy: Float? = null          // азимут без поправки, сглаженный
    /** Поправка (градусы): курс = сырой − поправка. */
    @Volatile var popravka = 0f

    /** Курс 0–360 (0 — север), null — датчик ещё молчит. */
    val kurs: Float? get() = syroy?.let { Radar.norm(it - popravka) }

    /** Датчики шлема — для /radar (что вообще есть). */
    fun spisok(): String = try {
        sm?.getSensorList(Sensor.TYPE_ALL)?.joinToString(", ") { it.stringType.substringAfterLast('.') } ?: ""
    } catch (e: Exception) { "" }

    fun start() {
        if (thread != null || sm == null) return
        val s = datchik ?: return
        val t = HandlerThread("kompas").apply { start() }
        thread = t
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI, Handler(t.looper))
    }

    fun stop() {
        try { sm?.unregisterListener(this) } catch (e: Exception) { }
        thread?.quitSafely()
        thread = null
        syroy = null
    }

    /** «Север здесь»: куда смотришь сейчас — север. */
    fun sever() {
        syroy?.let { popravka = it }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
        SensorManager.getOrientation(r2, o)
        val a = Radar.norm(Math.toDegrees(o[0].toDouble()).toFloat())
        val b = syroy
        if (b == null) {
            syroy = a
            if (tip == "relative") popravka = a                  // без магнитометра: север — куда смотрел при старте
        } else syroy = Radar.norm(b + Radar.raznica(b, a) * 0.25f)    // сглаживание без скачка через 0/360
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
