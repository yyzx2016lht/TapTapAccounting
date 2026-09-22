package com.taostudio.tapaccounting

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

class FlipDetector(
    private val ctx: android.content.Context,
    private val manager: SensorManager,
    private val debounceMs: Long = 500L,
    private val onFlipChange: () -> Unit
) : SensorEventListener {

    enum class SamplingMode { ECO, ACTIVE, BOOST }

    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private enum class Face { UP, DOWN, UNKNOWN }

    private var lastFace = Face.UNKNOWN
    private var pendingFace = Face.UNKNOWN
    private var pendingFaceSince = 0L
    private var faceDownTime = 0L
    private var lastTriggerTime = 0L
    private val faceStableMs = 60L

    @Volatile
    var lastSensorEventTimeMillis: Long = System.currentTimeMillis()
    @Volatile
    private var samplingMode: SamplingMode = SamplingMode.ACTIVE

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    fun start(): Boolean {
        if (sensorThread == null) {
            sensorThread = HandlerThread("FlipSensorThread", Process.THREAD_PRIORITY_DEFAULT)
            sensorThread?.start()
            sensorHandler = Handler(sensorThread!!.looper)
        }
        return registerSensors()
    }

    fun stop() {
        manager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    fun setSamplingMode(mode: SamplingMode): Boolean {
        if (samplingMode == mode) return true
        samplingMode = mode
        if (sensorThread == null || sensorHandler == null) return false
        manager.unregisterListener(this)
        return registerSensors()
    }

    private fun registerSensors(): Boolean {
        val sensorDelay = when (samplingMode) {
            SamplingMode.ECO -> SensorManager.SENSOR_DELAY_NORMAL
            SamplingMode.ACTIVE -> SensorManager.SENSOR_DELAY_UI
            SamplingMode.BOOST -> SensorManager.SENSOR_DELAY_GAME
        }
        return sensor?.let {
            manager.registerListener(this, it, sensorDelay, sensorHandler)
        } ?: false
    }

    override fun onSensorChanged(event: SensorEvent) {
        lastSensorEventTimeMillis = System.currentTimeMillis()

        val z = event.values[2]
        val now = SystemClock.uptimeMillis()
        // progress：0=最稳定 100=最灵敏（与 SeekBar 方向一致：越右越灵敏）
        val progress = Prefs.getFlipSensitivity(ctx)
        // 阈值越低越容易判定朝上/朝下 → 灵敏度越高阈值应越低
        val baseThreshold = 9.0f - (progress / 100f) * 3.5f

        val currentFace = when {
            z > baseThreshold -> Face.UP
            z < -baseThreshold -> Face.DOWN
            else -> Face.UNKNOWN
        }

        if (abs(z) <= baseThreshold * 0.6f && currentFace == Face.UNKNOWN) return

        if (currentFace == Face.UNKNOWN) {
            pendingFace = Face.UNKNOWN
            return
        }

        if (currentFace != pendingFace) {
            pendingFace = currentFace
            pendingFaceSince = now
            return
        }

        if (now - pendingFaceSince < faceStableMs) return
        if (currentFace == lastFace) return

        if (currentFace == Face.DOWN) {
            faceDownTime = now
        } else if (currentFace == Face.UP && faceDownTime > 0L) {
            // 时长窗口越宽越容易接受较慢的翻转 → 灵敏度越高窗口应越宽
            val maxDuration = 300L + (progress * 5L)
            val flipDuration = now - faceDownTime
            if (flipDuration in 70L until maxDuration && now - lastTriggerTime > debounceMs) {
                Logger.d(
                    ctx,
                    "FlipDetector",
                    "Flip action triggered: duration=${flipDuration}ms threshold=${"%.2f".format(baseThreshold)}"
                )
                onFlipChange()
                lastTriggerTime = now
                faceDownTime = 0L
            }
        }

        lastFace = currentFace
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
