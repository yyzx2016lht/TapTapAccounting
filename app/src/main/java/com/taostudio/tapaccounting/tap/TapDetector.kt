package com.taostudio.tapaccounting.tap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.taostudio.tapaccounting.Prefs
import kotlin.math.abs
import kotlin.math.sqrt

class TapDetector(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onTapAction: (tapCount: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "TapDetector"
        private const val SAMPLING_INTERVAL_NS = 2500000L
        // Columbus/TapTap 原始实现：0 = 最快可用率，Resample3C 插值到 2.5ms 固定间隔
        private const val SENSOR_SAMPLING_PERIOD_US = 0
        private const val SENSOR_BATCHING_PERIOD_US = 0
        private const val FULL_POWER_AFTER_START_MS = 3 * 60_000L
        private const val STILLNESS_TO_LOW_POWER_MS = 3 * 60_000L
        private const val POWER_CHECK_INTERVAL_MS = 30_000L
        private const val SIGNIFICANT_ACCEL_DELTA = 1.15f
        private const val SIGNIFICANT_GYRO_ABS = 0.65f
        private const val TAP_THROTTLE_MS = 500L
        val TAP_SENSITIVITY_VALUES = floatArrayOf(
            0.75f, 0.53f, 0.40f, 0.25f, 0.1f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
        )

        private enum class PowerProfile(val samplingPeriodUs: Int) {
            Full(SENSOR_SAMPLING_PERIOD_US),
            HeuristicStandby(SENSOR_SAMPLING_PERIOD_US)
        }
    }

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var tap: TapRT? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    /**
     * 整个检测器会话共用的 TFLite 分类器。
     *
     * 原生解释器只在首次推理时创建（= 传感器回调线程），会话期间绝不重建、绝不从别的线程关闭，
     * 这是避免 `libtensorflowlite_jni.so` SIGSEGV 的关键。
     */
    private var activeClassifier: TapTfClassifier? = null

    /**
     * 运行时世代号。
     *
     * 每次重建检测器运行时递增；传感器事件携带自己的世代号，世代号对不上就直接不做推理。
     * 这样废弃运行时不会被继续使用，同时也保证 `tap` 永远不会被置空导致空指针。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var isRunning = false

    @Volatile
    var lastSensorEventTimeMillis: Long = 0L
        private set

    private var lastTapActionUptimeMs = 0L

    @Volatile
    private var tripleEnabled = false

    private var forceFullMlMode = true
    private var powerProfile = PowerProfile.Full
    private var fullPowerUntilUptimeMs = 0L
    private var lastSignificantMotionUptimeMs = 0L
    private var lastAccelMagnitude: Float? = null

    private val powerProfileCheck = object : Runnable {
        override fun run() {
            if (!isRunning || forceFullMlMode) return
            maybeEnterDynamicLowPower()
            sensorHandler?.postDelayed(this, POWER_CHECK_INTERVAL_MS)
        }
    }

    fun start(): Boolean {
        if (isRunning) return true

        val powerSaving = Prefs.isTapPowerSavingEnabled(context)
        forceFullMlMode = !powerSaving

        if (accelerometer == null) {
            Log.e(TAG, "Missing accelerometer")
            return false
        }
        if (gyroscope == null) {
            Log.e(TAG, "Missing gyroscope (required for ML tap detection)")
            return false
        }

        try {
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            val nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            tripleEnabled = Prefs.isTapTripleEnabled(context)

            // 会话内复用同一个分类器：模型和 NNAPI 开关在一次会话里不会变，
            // 重建解释器只会凭空增加"关闭与推理并发"的窗口。
            val classifier = activeClassifier ?: TapTfClassifier(
                context.assets,
                TapModel.resolve(context).path,
                nnapiLowPower
            ).also { activeClassifier = it }

            generation++
            tap = createTapRuntime(
                useHeuristic = false,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                classifier = classifier
            )

            sensorThread = HandlerThread("TapSensorThread", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
                sensorHandler = Handler(looper)
            }

            powerProfile = PowerProfile.Full
            fullPowerUntilUptimeMs = SystemClock.uptimeMillis() + FULL_POWER_AFTER_START_MS
            lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
            lastAccelMagnitude = null
            registerSensors(powerProfile)

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()
            if (powerSaving) {
                sensorHandler?.postDelayed(powerProfileCheck, POWER_CHECK_INTERVAL_MS)
            }

            val tapModelName = TapModel.resolve(context).displayName
            Log.d(TAG, "TapDetector started: model=$tapModelName, " +
                    "sensitivity=$sensitivity, nnapi=$nnapiLowPower, " +
                    "powerSaving=$powerSaving, forceFullMlMode=$forceFullMlMode, " +
                    "gyroRegistered=true, classifierLoaded=true, " +
                    "tripleEnabled=$tripleEnabled, " +
                    "samplingPeriodUs=${powerProfile.samplingPeriodUs}, " +
                    "samplingIntervalNs=$SAMPLING_INTERVAL_NS, " +
                    "batchingUs=$SENSOR_BATCHING_PERIOD_US, " +
                    "dynamicPower=$powerSaving, standby=heuristic")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TapDetector", e)
            stop()
            return false
        }
    }

    fun stop() {
        // ① 先关掉"入口"，让后续传感器回调立刻返回，不再启动新的推理
        isRunning = false
        generation++
        sensorHandler?.removeCallbacks(powerProfileCheck)

        // ② 注销监听。注意 unregisterListener 只保证"不再派发新事件"，
        //    已经排在 Handler 队列里的回调仍会执行，所以不能就地关闭解释器。
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        val runtime = tap
        tap = null

        // ③ 把原生释放排到传感器线程队尾：所有已排队的回调先跑完，最后才关解释器。
        //    先 post 再 quitSafely —— quitSafely 会等队列里已排队的消息执行完才结束 Looper，
        //    所以释放动作一定能在解释器的创建线程上完成。
        val cleanerHandler = sensorHandler
        if (cleanerHandler != null) {
            runtime?.releaseClassifier(cleanerHandler, cleanerHandler)
        } else {
            // 传感器线程从未建立，直接从当前线程收取（此时不可能有并发推理）
            runtime?.releaseClassifier(null, null)
        }
        activeClassifier = null

        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        Log.d(TAG, "TapDetector stopped (classifier cleanup queued on sensor thread)")
    }

    fun restart() {
        stop()
        start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return
        if (event.values.size < 3) return

        val currentTap = tap ?: return
        lastSensorEventTimeMillis = System.currentTimeMillis()

        try {
            trackMotionForDynamicPower(event)
            val isTripleEnabled = tripleEnabled
            currentTap.updateData(
                event.sensor.type,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                SAMPLING_INTERVAL_NS,
                shouldUseHeuristicRuntime()
            )

            val result = currentTap.checkDoubleTapTiming(event.timestamp)
            if (powerProfile == PowerProfile.HeuristicStandby && result >= 1) {
                extendFullPower("heuristic-candidate-$result")
            }
            if (result >= 2) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapActionUptimeMs < TAP_THROTTLE_MS) return
                lastTapActionUptimeMs = now
            }
            when {
                result == 2 -> onTapAction(2)
                result == 3 && isTripleEnabled -> onTapAction(3)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tap sensor event failed; resetting detector state", e)
            currentTap.reset(false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun registerSensors(profile: PowerProfile) {
        val handler = sensorHandler ?: return
        sensorManager.registerListener(
            this,
            accelerometer,
            profile.samplingPeriodUs,
            SENSOR_BATCHING_PERIOD_US,
            handler
        )
        if (!shouldUseHeuristicRuntime()) {
            sensorManager.registerListener(
                this,
                gyroscope,
                profile.samplingPeriodUs,
                SENSOR_BATCHING_PERIOD_US,
                handler
            )
        }
    }

    private fun switchPowerProfile(profile: PowerProfile, reason: String) {
        if (forceFullMlMode || powerProfile == profile) return
        val handler = sensorHandler ?: return
        // 重建运行时的全过程都排到传感器线程上，与传感器回调串行化，
        // 这样就不存在"注销监听后、重新注册前"事件打在中间状态的窗口。
        val scheduledGeneration = generation
        handler.post {
            if (!isRunning || forceFullMlMode || powerProfile == profile) return@post
            if (generation != scheduledGeneration) return@post
            try {
                sensorManager.unregisterListener(this, accelerometer)
                sensorManager.unregisterListener(this, gyroscope)
            } catch (_: Exception) {
            }
            // 关键：这里不再 close 分类器。分类器只跟会话绑定，与功耗档位无关；
            // 在会话中途关闭原生解释器正是 SIGSEGV 的来源。
            powerProfile = profile
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            tripleEnabled = Prefs.isTapTripleEnabled(context)
            generation++
            tap = createTapRuntime(
                useHeuristic = profile == PowerProfile.HeuristicStandby,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                classifier = activeClassifier
            )
            lastAccelMagnitude = null
            registerSensors(profile)
            Log.d(
                TAG,
                "Dynamic power profile changed: profile=$profile, reason=$reason, " +
                    "samplingPeriodUs=${profile.samplingPeriodUs}, batchingUs=$SENSOR_BATCHING_PERIOD_US, " +
                    "gyroRegistered=${profile != PowerProfile.HeuristicStandby}, heuristic=${profile == PowerProfile.HeuristicStandby}"
            )
        }
    }

    private fun extendFullPower(reason: String) {
        if (forceFullMlMode) return
        fullPowerUntilUptimeMs = SystemClock.uptimeMillis() + FULL_POWER_AFTER_START_MS
        if (powerProfile != PowerProfile.Full) {
            switchPowerProfile(PowerProfile.Full, reason)
        }
    }

    private fun maybeEnterDynamicLowPower() {
        if (powerProfile == PowerProfile.HeuristicStandby) return
        val now = SystemClock.uptimeMillis()
        if (now < fullPowerUntilUptimeMs) return
        if (now - lastSignificantMotionUptimeMs < STILLNESS_TO_LOW_POWER_MS) return
        switchPowerProfile(PowerProfile.HeuristicStandby, "still-${now - lastSignificantMotionUptimeMs}ms")
    }

    private fun trackMotionForDynamicPower(event: SensorEvent) {
        if (forceFullMlMode) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val previous = lastAccelMagnitude
                lastAccelMagnitude = magnitude
                if (previous != null && abs(magnitude - previous) >= SIGNIFICANT_ACCEL_DELTA) {
                    lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                    extendFullPower("accel-motion")
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroAbs = maxOf(abs(event.values[0]), abs(event.values[1]), abs(event.values[2]))
                if (gyroAbs >= SIGNIFICANT_GYRO_ABS) {
                    lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                    extendFullPower("gyro-motion")
                }
            }
        }
    }

    private fun shouldUseHeuristicRuntime(): Boolean =
        !forceFullMlMode && powerProfile == PowerProfile.HeuristicStandby

    private fun createTapRuntime(
        useHeuristic: Boolean,
        tripleEnabled: Boolean,
        sensitivity: Float,
        classifier: TapTfClassifier?
    ): TapRT {
        return when {
            useHeuristic && tripleEnabled -> HeuristicTapTapTapRT(
                160000000L,
                true,
                TapRT.HEURISTIC_MIN_TIME_GAP_NS
            ).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                getNegativePeakDetection().setWindowSize(64)
                reset(false)
            }
            useHeuristic -> TapRT(160000000L, TapRT.HEURISTIC_MIN_TIME_GAP_NS).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                getNegativePeakDetection().setWindowSize(64)
                reset(false)
            }
            tripleEnabled && classifier != null -> {
                TapTapTapRT(160000000L, true, sensitivity, classifier).apply {
                    configureCommonFilters(sensitivity)
                    reset(false)
                }
            }
            classifier != null -> {
                TapRT(160000000L).apply {
                    setClassifier(classifier)
                    configureCommonFilters(sensitivity)
                    reset(false)
                }
            }
            else -> {
                // 分类器不可用（模型缺失等）：退化为不使用 ML 的运行时，避免空指针
                Log.w(TAG, "classifier unavailable, falling back to heuristic runtime")
                TapRT(160000000L, TapRT.HEURISTIC_MIN_TIME_GAP_NS).apply {
                    configureCommonFilters(sensitivity)
                    getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                    getNegativePeakDetection().setWindowSize(64)
                    reset(false)
                }
            }
        }
    }

    private fun TapRT.configureCommonFilters(sensitivity: Float) {
        getLowpassKey().setPara(0.2f)
        getHighpassKey().setPara(0.2f)
        getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
        getPositivePeakDetector().setWindowSize(64)
    }
}
