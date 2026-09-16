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
        // 省电模式：启发式只当"唤醒触发器"。敲一下先震动提示，再切到 ML 精确检测这个时长，
        // 到期后无条件回到启发式待机——只看时间，不看用户是否还在动。
        private const val WAKE_WINDOW_MS = 60_000L
        private const val POWER_CHECK_INTERVAL_MS = 1_000L
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
         * 测试模式命中反馈的最小间隔。
         *
         * 主要去重靠的是 [lastHeResult] 的边沿判断，这里只是防连敲时 Toast 排队；
         * 必须明显小于 TapRT 的 maxTimeGapNs(500ms) 和双击间隔，否则"1 击 → 2 击"
         * 这个跳变会被误吞掉。
         */
        private const val HE_TEST_FEEDBACK_THROTTLE_MS = 150L

        /**
         * 跨检测器实例存活的"精确检测窗口"截止时刻（进程级，绝对 uptime）。
         *
         * 有几条路径会把正在跑的检测器停掉再拉起来：AI 面板/悬浮窗的
         * `ACTION_STOP_DOUBLE_TAP` → `ACTION_START_DOUBLE_TAP`、看门狗重建、转屏重建。
         * 这些都只是"暂停一下"，不该让用户已经敲醒的那一分钟作废。
         * 因为存的是绝对时刻，暂停期间它照常流逝，所以恢复时不会得到凭空续期。
         */
        @Volatile
        private var carriedWakeWindowUntilMs = 0L

        /** 用户真正关掉敲击功能时调用，避免把上一次的窗口带进下一次开启。 */
        fun clearCarriedWakeWindow() {
            carriedWakeWindowUntilMs = 0L
        }
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

    private var powerProfile = PowerProfile.Full

    /**
     * 精确检测窗口的截止时刻。
     * 同时兼作"本次窗口是否已经震动提示过"的判据——档位切换是 post 到传感器线程队尾执行的，
     * 这中间还可能进来几个传感器事件，必须靠这个时间戳去重，否则会连震好几次。
     */
    private var wakeWindowUntilUptimeMs = 0L

    private val powerProfileCheck = object : Runnable {
        override fun run() {
            if (!isRunning || forceFullMlMode) return
            if (powerProfile == PowerProfile.Full &&
                SystemClock.uptimeMillis() >= wakeWindowUntilUptimeMs
            ) {
                carriedWakeWindowUntilMs = 0L
                switchPowerProfile(PowerProfile.HeuristicStandby, "wake-window-expired")
            }
            sensorHandler?.postDelayed(this, POWER_CHECK_INTERVAL_MS)
        }
    }

    /** 供前台服务通知查询"现在是什么状态"。 */
    fun currentDetectionState(): TapDetectionState = when {
        !isRunning -> TapDetectionState.Off
        forceFullMlMode -> TapDetectionState.AlwaysMl
        heTestMode -> TapDetectionState.HeuristicTest
        powerProfile == PowerProfile.HeuristicStandby -> TapDetectionState.HeuristicStandby
        else -> TapDetectionState.PreciseWindow
    }

    /** 精确检测窗口剩余毫秒数；不在窗口内返回 0。用于通知里的倒计时。 */
    fun preciseWindowRemainingMs(): Long {
        if (!isRunning || forceFullMlMode || powerProfile != PowerProfile.Full) return 0L
        val remain = wakeWindowUntilUptimeMs - SystemClock.uptimeMillis()
        return if (remain > 0L) remain else 0L
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

            // 省电模式默认从启发式待机起步。但如果上一轮的精确窗口还没到期
            // （悬浮窗/AI 面板刚把检测器停掉又拉起来，或看门狗重建），就接着用剩下的时间，
            // 不能让用户已经"敲醒"的那一分钟白白丢掉。
            val carriedUntil = carriedWakeWindowUntilMs
            val resumePrecise = powerSaving && !heTestMode && carriedUntil > SystemClock.uptimeMillis()
            val startProfile = when {
                !powerSaving -> PowerProfile.Full
                heTestMode -> PowerProfile.HeuristicStandby
                resumePrecise -> PowerProfile.Full
                else -> PowerProfile.HeuristicStandby
            }
            if (!resumePrecise) carriedWakeWindowUntilMs = 0L

            // 灵敏度必须按起始档位取：启发式那条链路要用更钝的省电档灵敏度。
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
            wakeWindowUntilUptimeMs = if (resumePrecise) carriedUntil else 0L
            // 必须复位：否则上一轮的残留值会让本次会话的第一次敲击被边沿判断吞掉
            lastHeResult = 0
            registerSensors(powerProfile)

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()
            if (powerSaving) {
                sensorHandler?.postDelayed(powerProfileCheck, POWER_CHECK_INTERVAL_MS)
            }
            emitState()

            val tapModelName = TapModel.resolve(context).displayName
            Log.d(TAG, "TapDetector started: model=$tapModelName, " +
                    "sensitivity=$sensitivity, nnapi=$nnapiLowPower, " +
                    "powerSaving=$powerSaving, forceFullMlMode=$forceFullMlMode, " +
                    "gyroRegistered=${startProfile != PowerProfile.HeuristicStandby}, " +
                    "startProfile=$startProfile, " +
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
                SAMPLING_INTERVAL_NS,
                shouldUseHeuristicRuntime()
            )

            val result = currentTap.checkDoubleTapTiming(event.timestamp)

            // 省电模式：启发式命中只当"唤醒触发器"。
            // 不弹悬浮窗、不执行用户设置的双击动作，只震动 + 提示，然后切到 ML 精确检测一段时间。
            // 用户在这个窗口里再敲一次，才走正常的 ML 识别与动作。
            if (!forceFullMlMode && powerProfile == PowerProfile.HeuristicStandby) {
                // result 在敲击后会保持 500ms 不变，所以只在它跳变时才算"检出一次"。
                // 单击：0 → 1（报一次）；双击：0 → 1 → 2（报两次）。
                val isNewDetection = result >= 1 && result != lastHeResult
                lastHeResult = result
                if (isNewDetection) {
                    if (heTestMode) {
                        // 测试模式：给反馈但绝不切档，方便连续试灵敏度、不用等一分钟回落
                        notifyHeTestHit(result)
                    } else {
                        wakeToPreciseMode(result)
                    }
                }
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
            lastHeResult = 0
            val sensitivity = sensitivityFor(profile)
            tripleEnabled = Prefs.isTapTripleEnabled(context)
            generation++
            tap = createTapRuntime(
                useHeuristic = profile == PowerProfile.HeuristicStandby,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                classifier = activeClassifier
            )
            registerSensors(profile)
            emitState()
            Log.d(
                TAG,
                "Dynamic power profile changed: profile=$profile, reason=$reason, " +
                    "samplingPeriodUs=${profile.samplingPeriodUs}, batchingUs=$SENSOR_BATCHING_PERIOD_US, " +
                    "gyroRegistered=${profile != PowerProfile.HeuristicStandby}, heuristic=${profile == PowerProfile.HeuristicStandby}"
            )
        }
    }

    /**
     * 省电模式下的唤醒：震动 + 提示，然后切到 ML 精确检测 [WAKE_WINDOW_MS]。
     *
     * 这里刻意不看运动状态。旧实现用"静止 3 分钟"作为回落条件，只要用户一直拿着手机，
     * 每一次微小移动都会把计时重置，于是永远回不到省电档——现在只看时间。
     */
    private fun wakeToPreciseMode(tapCount: Int) {
        val now = SystemClock.uptimeMillis()
        // 窗口内不重复震动/提示：切换动作是 post 到队尾执行的，这中间还会进来几个传感器事件。
        if (now < wakeWindowUntilUptimeMs) return
        wakeWindowUntilUptimeMs = now + WAKE_WINDOW_MS
        carriedWakeWindowUntilMs = wakeWindowUntilUptimeMs
        notifyWake()
        switchPowerProfile(PowerProfile.Full, "heuristic-wake-$tapCount")
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

    /** 唤醒反馈：震动一下 + Toast 提示用户"可以再敲了"。 */
    private fun notifyWake() {        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Utils.vibrate(appContext, duration = 45L, reason = "tap-wake", amplitude = 210)
            try {
                Toast.makeText(appContext, R.string.tap_wake_hint, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "wake toast failed: ${e.message}")
            }
        }
    }

    /**
     * 取某个档位该用的峰值判定阈值。
     *
     * 启发式和 ML 用的是**两条独立链路**，同一个阈值表现完全不同：
     * 启发式没有分类器兜底，主灵敏度那个值会让它频繁误触，所以单独给一档更钝的值。
     */
    private fun sensitivityFor(profile: PowerProfile): Float {
        val level = if (profile == PowerProfile.HeuristicStandby) {
            Prefs.getTapHeSensitivityLevel(context)
        } else {
            Prefs.getTapSensitivityLevel(context)
        }
        return TAP_SENSITIVITY_VALUES.getOrElse(level) { 0.05f }
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
                true
            ).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(HE_NEGATIVE_PEAK_TOLERATE)
                getNegativePeakDetection().setWindowSize(64)
                reset(false)
            }
            useHeuristic -> TapRT(160000000L).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(HE_NEGATIVE_PEAK_TOLERATE)
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
                TapRT(160000000L).apply {
                    configureCommonFilters(sensitivity)
                    getNegativePeakDetection().setMinNoiseTolerate(HE_NEGATIVE_PEAK_TOLERATE)
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
