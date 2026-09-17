package com.taostudio.tapaccounting.tap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.tap.aosp.AospTapRT

class TapDetector(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onStateChanged: ((TapDetectionState) -> Unit)? = null,
    private val onTapAction: (tapCount: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "TapDetector"
        private const val SAMPLING_INTERVAL_NS = 2500000L
        // Columbus/TapTap 原始实现：0 = 最快可用率，Resample3C 插值到 2.5ms 固定间隔
        private const val SENSOR_SAMPLING_PERIOD_US = 0
        private const val SENSOR_BATCHING_PERIOD_US = 0
        private const val TAP_THROTTLE_MS = 500L
        /**
         * 启发式档"负面峰值检测器"的噪声阈值。
         *
         * 上游 AOSP 哥伦布（`APSensor.startListening`）把它**硬编码为 0.015f**，
         * 用户灵敏度只作用于正面检测器（`positivePeakDetector.minNoiseTolerate = sensitivity`）。
         * 我们此前错误地把负面检测器也接到了用户灵敏度上——HE 档读到 0.40，差 26 倍，
         * 而 `recognizeTapHeuristic()` 的 Back/Others 判定完全依赖这个负面峰值。
         *
         * 注意：上游那条链的高通 para 是 0.05f，我们这条 Key 链是 0.2f，
         * 所以绝对阈值不能保证原样搬运，需要在设备上用测试模式实测微调。
         */
        private const val HE_NEGATIVE_PEAK_TOLERATE = 0.015f

        /**
         * 省电档（HE）走 crDroid/AOSP 那套内核，所以它的三个时间常量用 AOSP 的值，
         * 不能沿用 ML 那套（160ms / 2.5ms）。
         *   APSensor: `tap = TapRT(context, 153600000L)`、`samplingIntervalNs = 2400000L`
         * 两者配套（AOSP 内核里 slope 的参考值硬编码 2400000f，正好等于采样间隔 → 增益 1.0）。
         */
        private const val AOSP_HE_SIZE_WINDOW_NS = 153_600_000L
        private const val AOSP_HE_SAMPLING_INTERVAL_NS = 2_400_000L
        /**
         * 测试模式命中反馈的最小间隔。
         *
         * 主要去重靠的是 [lastHeResult] 的边沿判断，这里只是防连敲时 Toast 排队；
         * 必须明显小于 TapRT 的 maxTimeGapNs(500ms) 和双击间隔，否则"1 击 → 2 击"
         * 这个跳变会被误吞掉。
         */
        private const val HE_TEST_FEEDBACK_THROTTLE_MS = 150L

        val TAP_SENSITIVITY_VALUES = floatArrayOf(
            0.75f, 0.53f, 0.40f, 0.25f, 0.1f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
        )

        /** 会话模式：二选一，会话期间不再切换。 */
        private enum class PowerProfile(val samplingPeriodUs: Int) {
            /** 默认：全程 ML（加速度计 + 陀螺仪 + 神经网络）。 */
            Full(SENSOR_SAMPLING_PERIOD_US),

            /** 省电敲击：全程启发式，只注册加速度计，不做推理。 */
            HeuristicStandby(SENSOR_SAMPLING_PERIOD_US)
        }
    }

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var tap: BaseTapRT? = null

    /**
     * 当前运行时是不是 crDroid/AOSP 那套内核（HE 档专用）。
     * 它的重采样间隔是 2.4ms 而不是 ML 的 2.5ms，必须配套传参，否则 slope 增益会差 4%。
     */
    private var runtimeIsAosp = false
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

    /**
     * 省电档测试模式：锁定启发式待机，敲中只给震动+提示。
     * 只在省电模式开启时才有意义——它挂在省电分组下面。
     */
    private var heTestMode = false
    private var lastHeTestFeedbackMs = 0L

    /**
     * 上一次的启发式判定结果，用于边沿检测。
     *
     * `checkDoubleTapTiming()` 返回的是**状态**不是事件：敲一下之后，队列里的时间戳
     * 要过 500ms 才老化，这期间每一次传感器事件（400Hz，约 200 次）都会返回 1。
     * 所以"检出一次"必须靠 result 的跳变来识别，不能靠时间限流。
     */
    private var lastHeResult = 0

    /** 会话模式，在 [start] 里根据"省电敲击"开关确定，会话期间不再改变。 */
    private var powerProfile = PowerProfile.Full

    /** 供前台服务通知查询"现在是什么状态"。 */
    fun currentDetectionState(): TapDetectionState = when {
        !isRunning -> TapDetectionState.Off
        forceFullMlMode -> TapDetectionState.AlwaysMl
        heTestMode -> TapDetectionState.HeuristicTest
        else -> TapDetectionState.HeuristicStandby
    }

    private fun emitState() {
        val callback = onStateChanged ?: return
        val state = currentDetectionState()
        try {
            callback(state)
        } catch (e: Exception) {
            Log.w(TAG, "state callback failed: ${e.message}")
        }
    }

    fun start(): Boolean {
        if (isRunning) return true

        val powerSaving = Prefs.isTapPowerSavingEnabled(context)
        forceFullMlMode = !powerSaving
        // 测试模式挂在省电分组下，所以省电关掉时它一并失效
        heTestMode = powerSaving && Prefs.isTapHeTestModeEnabled(context)

        if (accelerometer == null) {
            Log.e(TAG, "Missing accelerometer")
            return false
        }
        if (gyroscope == null) {
            Log.e(TAG, "Missing gyroscope (required for ML tap detection)")
            return false
        }

        try {
            val nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            tripleEnabled = Prefs.isTapTripleEnabled(context)

            // 会话内复用同一个分类器：模型和 NNAPI 开关在一次会话里不会变，
            // 重建解释器只会凭空增加"关闭与推理并发"的窗口。
            val classifier = activeClassifier ?: TapTfClassifier(
                context.assets,
                TapModel.resolve(context).path,
                nnapiLowPower
            ).also { activeClassifier = it }

            // 模式二选一，会话期间不再切换：
            //   省电敲击开 → 全程启发式（只加速度计、不做推理）
            //   省电敲击关 → 全程 ML（默认）
            val startProfile = if (powerSaving) {
                PowerProfile.HeuristicStandby
            } else {
                PowerProfile.Full
            }

            val sensitivity = sensitivityFor(startProfile)

            generation++
            tap = createTapRuntime(
                useHeuristic = startProfile == PowerProfile.HeuristicStandby,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                classifier = classifier
            )

            sensorThread = HandlerThread("TapSensorThread", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
                sensorHandler = Handler(looper)
            }

            powerProfile = startProfile
            // 必须复位：否则上一轮的残留值会让本次会话的第一次敲击被边沿判断吞掉
            lastHeResult = 0
            registerSensors(powerProfile)

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()
            emitState()

            val tapModelName = TapModel.resolve(context).displayName
            Log.d(TAG, "TapDetector started: model=$tapModelName, " +
                    "sensitivity=$sensitivity, nnapi=$nnapiLowPower, " +
                    "powerSaving=$powerSaving, " +
                    "gyroRegistered=${startProfile != PowerProfile.HeuristicStandby}, " +
                    "mode=$startProfile, " +
                    "tripleEnabled=$tripleEnabled, " +
                    "samplingPeriodUs=${powerProfile.samplingPeriodUs}, " +
                    "samplingIntervalNs=$SAMPLING_INTERVAL_NS, " +
                    "batchingUs=$SENSOR_BATCHING_PERIOD_US")
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
        emitState()
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
            val isTripleEnabled = tripleEnabled
            currentTap.updateData(
                event.sensor.type,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                if (runtimeIsAosp) AOSP_HE_SAMPLING_INTERVAL_NS else SAMPLING_INTERVAL_NS,
                shouldUseHeuristicRuntime()
            )

            val result = currentTap.checkDoubleTapTiming(event.timestamp)

            // 省电敲击（启发式）：不再有"先唤醒、再进精确档"那套门控——
            // 敲中就按和 ML 完全相同的方式直接执行用户配置的动作，只是检测算法不同。
            if (heTestMode) {
                // result 是**状态**不是事件：敲一下后队列里的时间戳要过 500ms 才老化，
                // 这期间每次传感器事件都会返回 1。所以"检出一次"必须靠跳变识别。
                // 只报 2 击（完整双击）——那正是正式模式下会触发动作的条件，所见即所得。
                val isNewDetection = result >= 1 && result != lastHeResult
                lastHeResult = result
                if (isNewDetection && result >= 2) notifyHeTestHit(result)
                return
            }
            lastHeResult = result

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
        // 省电敲击档不注册陀螺仪：AOSP 的启发式算法本身第一行就
        // `if (sensorType == Sensor.TYPE_GYROSCOPE) return`，用不到它；
        // 而且现在没有"切到 ML"这个动作了，也就不需要为切档预留陀螺仪历史。
        // 少一路 400Hz 传感器就是少一半回调，这正是省电敲击的意义。
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

    /**
     * 测试模式的命中反馈：震动 + 显示检出几击。
     *
     * 不做节流的话，灵敏度调太高时 Toast 会排队堆成一片，反而看不清哪一下被检出了；
     * 所以按 [HE_TEST_FEEDBACK_THROTTLE_MS] 限流。
     */
    private fun notifyHeTestHit(tapCount: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeTestFeedbackMs < HE_TEST_FEEDBACK_THROTTLE_MS) return
        lastHeTestFeedbackMs = now
        val appContext = context.applicationContext
        val message = appContext.getString(R.string.tap_he_test_hit_fmt, tapCount)
        Handler(Looper.getMainLooper()).post {
            Utils.vibrate(appContext, duration = 40L, reason = "tap-he-test", amplitude = 210)
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "he test toast failed: ${e.message}")
            }
        }
    }

    /**
     * 取当前会话该用的峰值判定阈值（供 ML 内核使用）。
     *
     * 两个模式**共用顶上那一个灵敏度滑块**，但映射方式不同：
     * ML 用 TapTap 血统的 [TAP_SENSITIVITY_VALUES]，启发式用 AOSP 的公式
     * （见 [aospHeSensitivity]）。滑块方向两边一致，都是"数字越大越灵敏"。
     */
    private fun sensitivityFor(profile: PowerProfile): Float {
        val level = Prefs.getTapSensitivityLevel(context)
        return TAP_SENSITIVITY_VALUES.getOrElse(level) { 0.05f }
    }

    private fun shouldUseHeuristicRuntime(): Boolean =
        !forceFullMlMode && powerProfile == PowerProfile.HeuristicStandby

    private fun createTapRuntime(
        useHeuristic: Boolean,
        tripleEnabled: Boolean,
        sensitivity: Float,
        classifier: TapTfClassifier?
    ): BaseTapRT {
        // 省电档一律走 crDroid/AOSP 内核：那是 crDroid 默认启用的主线路径
        // （config.xml 里 default_apsensor_heuristic_mode = true）。
        if (useHeuristic) {
            return createAospHeRuntime()
        }
        runtimeIsAosp = false
        return when {
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
                // 分类器不可用（模型缺失等）：退化为 crDroid/AOSP 启发式运行时，避免空指针
                Log.w(TAG, "classifier unavailable, falling back to heuristic runtime")
                createAospHeRuntime()
            }
        }
    }

    /**
     * 构建 crDroid/AOSP 内核的启发式运行时，并按上游 `APSensor.startListening()` 原样配置：
     *
     * ```
     * callback.setListening(true, SensorManager.SENSOR_DELAY_FASTEST)
     * lowpassAcc.para = 1f;  lowpassGyro.para = 1f
     * highpassAcc.para = 0.05f; highpassGyro.para = 0.05f
     * positivePeakDetector.minNoiseTolerate = sensitivity; windowSize = 64
     * negativePeakDetector.minNoiseTolerate = 0.015f;     windowSize = 64
     * reset(heuristicMode)
     * ```
     *
     * 上游把 `negativePeakDetector` 的阈值**硬编码**，用户灵敏度只作用于正面检测器
     * （`APSensor.updateSensitivity()` 也只改正面那个）。
     */
    private fun createAospHeRuntime(): BaseTapRT {
        runtimeIsAosp = true
        // 与 ML 共用顶上那一个灵敏度滑块，只是映射到 AOSP 的阈值公式
        val level = Prefs.getTapSensitivityLevel(context)
        return AospTapRT(AOSP_HE_SIZE_WINDOW_NS).apply {
            lowpassAcc.para = 1f
            lowpassGyro.para = 1f
            highpassAcc.para = 0.05f
            highpassGyro.para = 0.05f
            positivePeakDetector.minNoiseTolerate = aospHeSensitivity(level)
            positivePeakDetector.windowSize = 64
            negativePeakDetector.minNoiseTolerate = HE_NEGATIVE_PEAK_TOLERATE
            negativePeakDetector.windowSize = 64
            // 上游是 reset(heuristicMode)，HE 时即 true
            reset(true)
        }
    }

    /**
     * 省电档灵敏度档位(0..10) → AOSP 内核的正面峰值阈值。
     *
     * 数值沿用 crDroid `ColumbusService.updateSensitivity()` 的公式：
     * `value <= 5 → value/100f`，否则 `(value-5)*0.15f`。
     *
     * **但档位方向反转**（用 `10 - level` 进公式）：crDroid 的界面是 0=light(灵敏) …
     * 10=heavy(钝)，而本 App（含 ML 档）的约定是"数字越大越灵敏"。
     * HE 滑块必须跟全 App 一致，否则又会造出"标签与语义相反"那种坑。
     *
     * 对照：level 10 → 0.00(最灵敏) / 5 → 0.05 / 2 → 0.45 / 0 → 0.75(最钝)。
     */
    private fun aospHeSensitivity(level: Int): Float {
        val v = (10 - level).coerceIn(0, 10)
        return if (v <= 5) v.toFloat() / 100f else (v - 5).toFloat() * 0.15f
    }

    private fun TapRT.configureCommonFilters(sensitivity: Float) {
        getLowpassKey().setPara(0.2f)
        getHighpassKey().setPara(0.2f)
        getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
        getPositivePeakDetector().setWindowSize(64)
    }
}
