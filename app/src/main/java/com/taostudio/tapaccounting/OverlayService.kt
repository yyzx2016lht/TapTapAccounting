package com.taostudio.tapaccounting

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.taostudio.tapaccounting.tap.TapDetectionState
import com.taostudio.tapaccounting.tap.TapDetector

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_foreground_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SHOW_OVERLAY = "com.taostudio.tapaccounting.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.taostudio.tapaccounting.HIDE_OVERLAY"
        const val ACTION_SHOW_AI_INPUT = "com.taostudio.tapaccounting.SHOW_AI_INPUT"
        const val ACTION_SCREEN_CAPTURE = "com.taostudio.tapaccounting.SCREEN_CAPTURE"
        const val ACTION_START_FLIP = "ACTION_START_FLIP"
        const val ACTION_STOP_FLIP = "ACTION_STOP_FLIP"
        const val ACTION_START_DOUBLE_TAP = "ACTION_START_DOUBLE_TAP"
        const val ACTION_STOP_DOUBLE_TAP = "ACTION_STOP_DOUBLE_TAP"
        const val ACTION_RESTART_DOUBLE_TAP = "ACTION_RESTART_DOUBLE_TAP"

        @Volatile
        var isServiceRunning = false
            private set

        fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        // 看门狗：既做传感器健康检查，也充当存活心跳源。
        // 间隔取 30s，配合 ProcessExitLogger 内部 60s 的写盘限流，
        // 进程若被强杀，心跳流水里能定位到 1 分钟以内。
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val TAP_DEAD_EVENT_TIMEOUT_MS = 45_000L
        private const val TAP_DEAD_CONSECUTIVE_LIMIT = 1
        private const val MAX_CONSECUTIVE_WATCHDOG_RESTARTS = 5
        private const val WATCHDOG_COOLDOWN_MS = 90_000L
        private const val TAP_FEEDBACK_THROTTLE_MS = 650L
        private const val SETTINGS_RESTART_DEBOUNCE_MS = 800L
        private const val DETECTOR_RECHECK_DELAY_MS = 4_000L
        private const val DETECTOR_SECOND_RECHECK_DELAY_MS = 10_000L
    }

    private lateinit var overlayManager: OverlayManager
    private var flipDetector: FlipDetector? = null
    private var tapDetector: TapDetector? = null

    private var isFlipEnabled = false
    private var isDoubleTapEnabled = false
    /** onDestroy 之后不再尝试刷新前台通知，避免在正在销毁的服务上 startForeground。 */
    private var isDestroying = false
    private val keepAliveManager = KeepAliveManager()
    private var lastTapFeedbackAtMs: Long = 0L

    // ════════════════════════════════════════════════════════
    //  保活与健康检测
    // ════════════════════════════════════════════════════════
    private inner class KeepAliveManager {
        private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var watchdogJob: Job? = null
        private var restartDetectorJob: Job? = null
        private var startDetectorJob: Job? = null
        private var wakeLock: PowerManager.WakeLock? = null

        private var consecutiveDeadChecks: Int = 0
        private var consecutiveWatchdogRestarts = 0
        private var watchdogCooldownUntilMs = 0L

        // ── 亮/灭屏监听 ──────────────────────────────────────
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen OFF: stopping detectors to save power")
                        startDetectorJob?.cancel()
                        restartDetectorJob?.cancel()
                        if (isFlipEnabled) stopFlipDetection()
                        if (isDoubleTapEnabled) stopTapDetection()
                        stopWatchdog()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (canRunTapDetectorNow()) {
                            Logger.d(this@OverlayService, "OverlayService", "Screen ON: detector allowed, scheduling detector start")
                            scheduleStartAfterUnlock("screen-on-unlocked")
                        } else {
                            Logger.d(this@OverlayService, "OverlayService", "Screen ON: detector not allowed, waiting")
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Logger.d(this@OverlayService, "OverlayService", "User present: scheduling detector start")
                        if (!isFlipEnabled && !isDoubleTapEnabled) return
                        watchdogCooldownUntilMs = 0L
                        consecutiveDeadChecks = 0
                        consecutiveWatchdogRestarts = 0
                        scheduleStartAfterUnlock("user-present")
                    }
                }
            }
        }

        // ── attach / detach ───────────────────────────────────
        fun attach() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // P2-11: 优先 NOT_EXPORTED；旧 API 无 flag 时回退
                    try {
                        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } catch (_: Throwable) {
                        registerReceiver(screenReceiver, filter)
                    }
                } else {
                    registerReceiver(screenReceiver, filter)
                }
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "registerReceiver failed: ${e.message}")
                try { registerReceiver(screenReceiver, filter) } catch (_: Exception) {}
            }

            if (Prefs.isShizukuPersistenceEnabled(this@OverlayService)) {
                Logger.d(this@OverlayService, "OverlayService", "Applying Shizuku deep persistence")
                ShizukuShell.applyAggressivePersistence(packageName)
                ShizukuRecoveryService.ensureStarted(this@OverlayService)
            } else {
                Logger.d(this@OverlayService, "OverlayService", "Shizuku Persistence is disabled by user.")
            }
        }

        fun detach() {
            startDetectorJob?.cancel()
            restartDetectorJob?.cancel()
            stopWatchdog()
            releaseAllWakeLocks()
            serviceScope.cancel()
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }

        // ── WakeLock ──────────────────────────────────────────
        fun acquireWakeLockBriefly(durationMs: Long = 4_000L) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapAccount::BriefWL")
                    wakeLock?.setReferenceCounted(false)
                }
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock?.acquire(durationMs)
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "acquireWakeLockBriefly failed: ${e.message}")
            }
        }

        private fun releaseAllWakeLocks() {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        }

        // ── Watchdog ──────────────────────────────────────────
        fun startWatchdog() {
            if (isWatchdogCoolingDown(SystemClock.elapsedRealtime())) return
            if (watchdogJob?.isActive == true) return
            watchdogJob = serviceScope.launch {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms)")
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    if (!isDoubleTapEnabled) break
                    ProcessExitLogger.recordHeartbeat(applicationContext as Application)
                    checkSensorHealth()
                }
            }
        }

        fun stopWatchdog() {
            if (watchdogJob == null) return
            watchdogJob?.cancel()
            watchdogJob = null
            Logger.d(this@OverlayService, "OverlayService", "Watchdog stopped")
        }

        fun syncWatchdogState() {
            if (isDoubleTapEnabled && canRunTapDetectorNow()) startWatchdog() else stopWatchdog()
        }

        fun reconcileDetectorState(reason: String) {
            if (!isFlipEnabled && !isDoubleTapEnabled) {
                stopWatchdog()
                return
            }
            if (!canRunTapDetectorNow()) {
                Logger.d(this@OverlayService, "OverlayService", "Reconcile skipped: detector not allowed. reason=$reason")
                return
            }

            var started = false
            if (isFlipEnabled && flipDetector == null) {
                Logger.d(this@OverlayService, "OverlayService", "Reconcile: starting missing flip detector. reason=$reason")
                startFlipDetection()
                started = true
            }
            if (isDoubleTapEnabled && tapDetector == null) {
                Logger.d(this@OverlayService, "OverlayService", "Reconcile: starting missing tap detector. reason=$reason")
                startTapDetection()
                started = true
            }

            if (started) {
                consecutiveDeadChecks = 0
                consecutiveWatchdogRestarts = 0
            }
            startWatchdog()
        }

        // ── 传感器健康检查 ────────────────────────────────────
        private fun checkSensorHealth() {
            if (!isDoubleTapEnabled) return

            if (!canRunTapDetectorNow()) {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog skipped: detector not allowed")
                consecutiveDeadChecks = 0
                stopWatchdog()
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (isWatchdogCoolingDown(now)) {
                stopWatchdog()
                return
            }

            val det = tapDetector
            if (det == null) {
                consecutiveWatchdogRestarts++
                if (consecutiveWatchdogRestarts > MAX_CONSECUTIVE_WATCHDOG_RESTARTS) {
                    enterWatchdogCooldown(now)
                    return
                }
                Logger.d(this@OverlayService, "OverlayService", "Watchdog: tap detector is null, rebuilding... (restart=${consecutiveWatchdogRestarts})")
                acquireWakeLockBriefly()
                restartDetector("watchdog-null-tap")
                return
            }
            val timeSinceLastEvent = now - det.lastSensorEventTimeMillis
            val staleThresholdMs = if (isAppInBackground()) 20_000L else TAP_DEAD_EVENT_TIMEOUT_MS
            if (timeSinceLastEvent <= staleThresholdMs) {
                consecutiveDeadChecks = 0
                consecutiveWatchdogRestarts = 0
                return
            }

            consecutiveDeadChecks++
            if (consecutiveDeadChecks < TAP_DEAD_CONSECUTIVE_LIMIT) {
                Logger.d(
                    this@OverlayService,
                    "OverlayService",
                    "Watchdog: tap no sensor event for ${timeSinceLastEvent}ms, waiting (${consecutiveDeadChecks}/$TAP_DEAD_CONSECUTIVE_LIMIT)"
                )
                return
            }

            consecutiveDeadChecks = 0
            consecutiveWatchdogRestarts++

            if (consecutiveWatchdogRestarts > MAX_CONSECUTIVE_WATCHDOG_RESTARTS) {
                enterWatchdogCooldown(now)
                return
            }

            acquireWakeLockBriefly()
            Logger.d(
                this@OverlayService,
                "OverlayService",
                "Watchdog: tap sensor dead for ${timeSinceLastEvent}ms (restart=${consecutiveWatchdogRestarts}), restarting..."
            )
            restartDetector("watchdog-dead-tap")
        }

        // ── Detector 重建 ─────────────────────────────────────
        fun restartDetector(reason: String) {
            restartDetectorJob?.cancel()
            restartDetectorJob = serviceScope.launch(Dispatchers.Main) {
                if (reason == "settings-restart") {
                    delay(SETTINGS_RESTART_DEBOUNCE_MS)
                }

                if (!canRunTapDetectorNow()) {
                    Logger.d(this@OverlayService, "OverlayService", "Restart skipped: detector not allowed. reason=$reason")
                    stopTapDetection()
                    stopWatchdog()
                    return@launch
                }

                Logger.d(this@OverlayService, "OverlayService", "Restarting detector: reason=$reason")
                if (reason != "settings-restart") {
                    stopFlipDetection()
                }
                stopTapDetection()
                delay(500L)
                if (isFlipEnabled && canRunTapDetectorNow() && reason != "settings-restart") {
                    startFlipDetection()
                }
                if (isDoubleTapEnabled && canRunTapDetectorNow()) {
                    startTapDetection()
                }
            }
        }

        // ── 延迟启动 ──────────────────────────────────────────
        fun scheduleStartAfterUnlock(reason: String) {
            startDetectorJob?.cancel()
            startDetectorJob = serviceScope.launch(Dispatchers.Main) {
                delay(1000L)
                reconcileDetectorState("$reason-1s")
                delay(DETECTOR_RECHECK_DELAY_MS)
                reconcileDetectorState("$reason-5s")
                delay(DETECTOR_SECOND_RECHECK_DELAY_MS)
                reconcileDetectorState("$reason-15s")
            }
        }

        fun onOrientationMaybeChanged(reason: String) {
            if (!isFlipEnabled && !isDoubleTapEnabled) return
            if (!canRunTapDetectorNow()) {
                Logger.d(this@OverlayService, "OverlayService", "Detector paused: not allowed after $reason")
                startDetectorJob?.cancel()
                restartDetectorJob?.cancel()
                stopFlipDetection()
                stopTapDetection()
                stopWatchdog()
            } else {
                Logger.d(this@OverlayService, "OverlayService", "Detector allowed after $reason, scheduling start")
                scheduleStartAfterUnlock(reason)
            }
        }

        // ── Watchdog 冷却 ─────────────────────────────────────
        private fun isWatchdogCoolingDown(now: Long): Boolean {
            return watchdogCooldownUntilMs > now
        }

        private fun enterWatchdogCooldown(now: Long) {
            watchdogCooldownUntilMs = now + WATCHDOG_COOLDOWN_MS
            consecutiveDeadChecks = 0
            consecutiveWatchdogRestarts = 0
            Logger.d(this@OverlayService, "OverlayService", "Watchdog cooldown entered; scheduling detector restart")
            acquireWakeLockBriefly()
            restartDetector("watchdog-cooldown")
        }

        private fun isAppInBackground(): Boolean {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val proc = am.runningAppProcesses?.find { it.pid == Process.myPid() } ?: return true
            return proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }
    }

    // ════════════════════════════════════════════════════════
    //  Service 生命周期
    // ════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Logger.d(this, "OverlayService", "Service Created")
        KeepAliveDiagnostics.logSnapshot(this, "overlay-onCreate")
        try {
            overlayManager = OverlayManager(this)
            isFlipEnabled = Prefs.isFlipEnabled(this)
            isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
            promoteToForeground(currentNotificationText())
            keepAliveManager.attach()

            if (isFlipEnabled) {
                startFlipDetectionIfAllowed("service-create")
            }
            if (isDoubleTapEnabled) {
                startTapDetectionIfAllowed("service-create")
                scheduleKeepAliveWork()
            }

            keepAliveManager.reconcileDetectorState("service-create")
            keepAliveManager.syncWatchdogState()
            Logger.d(this, "OverlayService", "Service onCreate completed.")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 Fatal Error in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.d(this, "OverlayService", "onStartCommand: $action")

        when (action) {
            null -> {
                // START_STICKY 重建，从 Prefs 恢复状态
                isFlipEnabled = Prefs.isFlipEnabled(this)
                isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
                if (isFlipEnabled) {
                    startFlipDetectionIfAllowed("sticky-restore")
                }
                if (isDoubleTapEnabled) {
                    cancelRestart()
                    startTapDetectionIfAllowed("sticky-restore")
                    scheduleKeepAliveWork()
                }
            }
            ACTION_START_FLIP -> {
                isFlipEnabled = true
                cancelRestart()
                promoteToForeground(getString(R.string.notif_quick_gesture_running))
                startFlipDetectionIfAllowed("user-start-flip")
            }
            ACTION_STOP_FLIP -> {
                isFlipEnabled = false
                stopFlipDetection()
                stopSelfIfIdle("flip-disabled")
            }
            ACTION_START_DOUBLE_TAP -> {
                isDoubleTapEnabled = true
                cancelRestart()
                promoteToForeground(currentNotificationText())
                startTapDetectionIfAllowed("user-start")
                scheduleKeepAliveWork()
            }
            ACTION_STOP_DOUBLE_TAP -> {
                val userDisabledTap = !Prefs.isDoubleTapEnabled(this)
                isDoubleTapEnabled = false
                stopTapDetection()
                if (userDisabledTap) {
                    OverlayWatchdogWorker.cancel(this)
                    stopSelfIfIdle("double-tap-disabled")
                } else {
                    Logger.d(this, "OverlayService", "Tap detection paused temporarily; service kept alive")
                }
            }
            ACTION_RESTART_DOUBLE_TAP -> {
                isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
                if (isDoubleTapEnabled) {
                    cancelRestart()
                    promoteToForeground(currentNotificationText())
                    keepAliveManager.restartDetector("settings-restart")
                    scheduleKeepAliveWork()
                } else {
                    stopTapDetection()
                }
            }
            ACTION_SHOW_OVERLAY -> overlayManager.showOverlay()
            ACTION_SHOW_AI_INPUT -> overlayManager.showAiInputPanel()
            ACTION_SCREEN_CAPTURE -> overlayManager.startScreenCaptureFromTap()
            ACTION_HIDE_OVERLAY -> {
                overlayManager.removeOverlay(isSaved = false)
                stopSelfIfIdle("overlay-hidden")
            }
            // 开机/看门狗重拉路径（BootReceiver 或直接 startService）
        }

        when (action) {
            ACTION_STOP_FLIP,
            ACTION_STOP_DOUBLE_TAP,
            ACTION_HIDE_OVERLAY,
            ACTION_SHOW_OVERLAY,
            ACTION_SHOW_AI_INPUT,
            ACTION_SCREEN_CAPTURE -> Unit
            ACTION_RESTART_DOUBLE_TAP -> {
                if (!isDoubleTapEnabled) keepAliveManager.reconcileDetectorState("onStartCommand-$action")
            }
            else -> keepAliveManager.reconcileDetectorState("onStartCommand-${action ?: "sticky"}")
        }
        keepAliveManager.syncWatchdogState()
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.d(this, "OverlayService", "Service onDestroy")
        isDestroying = true
        keepAliveManager.detach()
        stopFlipDetection()
        stopTapDetection()
        overlayManager.removeOverlay(isSaved = false)
        isServiceRunning = false
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.d(this, "OverlayService", "onTrimMemory: level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.d(this, "OverlayService", "onLowMemory")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }
        Logger.d(this, "OverlayService", "onConfigurationChanged: orientation=$orientation")
        keepAliveManager.onOrientationMaybeChanged("configuration-$orientation")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.d(this, "OverlayService", "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    private fun startFlipDetection() {
        if (flipDetector != null) return
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        flipDetector = FlipDetector(this, sensorManager) {
            Handler(Looper.getMainLooper()).post {
                handleFlipAction()
            }
        }
        if (flipDetector?.start() != true) {
            flipDetector = null
            Logger.d(this, "OverlayService", "FlipDetector start failed")
        } else {
            Logger.d(this, "OverlayService", "FlipDetector started")
        }
    }

    private fun startFlipDetectionIfAllowed(reason: String) {
        if (!canRunTapDetectorNow()) {
            Logger.d(this, "OverlayService", "FlipDetector not started: detector not allowed ($reason)")
            stopFlipDetection()
            return
        }
        startFlipDetection()
    }

    private fun stopFlipDetection() {
        flipDetector?.stop()
        flipDetector = null
    }

    private fun startTapDetection() {
        if (tapDetector != null) return
        ProcessExitLogger.recordHeartbeat(applicationContext as Application)
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        tapDetector = TapDetector(
            this,
            sensorManager,
            onStateChanged = { state ->
                Logger.d(this, "OverlayService", "Tap detection state -> $state")
                Handler(Looper.getMainLooper()).post { refreshDetectionNotification() }
            }
        ) { tapCount ->
            Handler(Looper.getMainLooper()).post {
                handleTapAction(tapCount)
            }
        }
        if (tapDetector?.start() != true) {
            tapDetector = null
            Logger.d(this, "OverlayService", "TapDetector start failed")
        } else {
            Logger.d(this, "OverlayService", "TapDetector started")
        }
    }

    private fun startTapDetectionIfAllowed(reason: String) {
        if (!canRunTapDetectorNow()) {
            Logger.d(this, "OverlayService", "TapDetector not started: detector not allowed ($reason)")
            stopTapDetection()
            return
        }
        startTapDetection()
    }

    private fun isUserUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) pm.isInteractive else pm.isScreenOn
        return interactive && !km.isKeyguardLocked
    }

    private fun canRunTapDetectorNow(): Boolean {
        return isUserUnlockedAndInteractive() && !isLandscapeDetectionBlocked()
    }

    private fun isLandscapeDetectionBlocked(): Boolean {
        return Prefs.isDisableLandscape(this) &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun handleTapAction(tapCount: Int) {
        if (Prefs.isDisableLandscape(this)) {
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Logger.d(this, "OverlayService", "Tap trigger ignored in landscape")
                return
            }
        }

        val actionId = when (tapCount) {
            2 -> Prefs.getTapActionDouble(this)
            3 -> Prefs.getTapActionTriple(this)
            else -> ""
        }
        if (actionId.isEmpty()) {
            triggerTapFeedback("tap-$tapCount-detected-no-action")
            Logger.d(this, "OverlayService", "Tap $tapCount detected but no action configured")
            Utils.toast(this, getString(R.string.toast_tap_no_action))
            return
        }
        val action = com.taostudio.tapaccounting.tap.TapActionRegistry.findById(actionId)
        if (action != null) {
            triggerTapFeedback("tap-$tapCount-detected")
            Logger.d(this, "OverlayService", "Tap $tapCount detected, executing: ${action.displayName}")
            keepAliveManager.acquireWakeLockBriefly(3_000L)
            action.execute(this)
        } else {
            triggerTapFeedback("tap-$tapCount-detected-unknown-action")
            Logger.d(this, "OverlayService", "Tap $tapCount detected but action id is unknown: $actionId")
            Utils.toast(this, getString(R.string.toast_tap_action_expired))
        }
    }

    private fun handleFlipAction() {
        if (Prefs.isDisableLandscape(this)) {
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Logger.d(this, "OverlayService", "Flip trigger ignored in landscape")
                return
            }
        }
        triggerTapFeedback("flip-detected")
        val actionId = Prefs.getFlipAction(this)
        if (actionId.isEmpty()) {
            Logger.d(this, "OverlayService", "Flip detected but no action configured")
            Utils.toast(this, getString(R.string.toast_flip_no_action))
            return
        }
        val action = com.taostudio.tapaccounting.tap.TapActionRegistry.findById(actionId)
        if (action == null) {
            Logger.d(this, "OverlayService", "Flip detected but action id is unknown: $actionId")
            Utils.toast(this, getString(R.string.toast_flip_action_expired))
            return
        }
        Logger.d(this, "OverlayService", "Flip detected, executing: ${action.displayName}")
        keepAliveManager.acquireWakeLockBriefly(3_000L)
        action.execute(this)
    }

    private fun stopTapDetection() {
        tapDetector?.stop()
        tapDetector = null
        refreshDetectionNotification()
    }

    /**
     * 挂上/续期看门狗。
     *
     * 用 KEEP 策略入队，所以服务每次重建都调用它是安全的：
     * 已存在的排期不会被重置，只保证"只要手势开着，看门狗就一定在"。
     */
    private fun scheduleKeepAliveWork() {
        OverlayWatchdogWorker.schedule(this)
    }

    private fun cancelRestart() {
        // 旧的 KeepAliveWorker 一次性重拉已废弃，改由 OverlayWatchdogWorker 周期自愈
    }

    // ════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════

    /**
     * 通知正文由当前检测状态推导，而不是写死一句"双击检测运行中"。
     *
     * 省电模式下档位会自己来回切（启发式待机 ⇄ 精确窗口），
     * 用户看通知就能知道现在敲下去会不会记账。
     */
    private fun currentNotificationText(): String {
        val detector = tapDetector
        if (isDoubleTapEnabled && detector != null) {
            return when (detector.currentDetectionState()) {
                TapDetectionState.HeuristicStandby -> getString(R.string.notif_tap_standby)
                TapDetectionState.HeuristicTest -> getString(R.string.notif_tap_he_test)
                TapDetectionState.AlwaysMl -> getString(R.string.notif_tap_always_ml)
                TapDetectionState.Off -> getString(R.string.notif_tap_paused)
            }
        }
        if (isDoubleTapEnabled) {
            // 开关开着但检测器没跑（息屏/锁屏/横屏屏蔽）
            return getString(R.string.notif_tap_paused)
        }
        if (Prefs.isDoubleTapEnabled(this)) {
            // 开关仍然开着，只是被 AI 面板/悬浮窗临时暂停了——这时候说"运行中"是假的
            return getString(R.string.notif_tap_paused_temp)
        }
        return if (isFlipEnabled) {
            getString(R.string.notif_quick_gesture_running)
        } else {
            getString(R.string.notif_double_tap_running)
        }
    }

    /** 按当前状态重刷前台通知。 */
    private fun refreshDetectionNotification() {
        if (isDestroying) return
        try {
            promoteToForeground(currentNotificationText())
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "refreshDetectionNotification failed: ${e.message}")
        }
    }

    private fun promoteToForeground(content: String) {
        val notification = OverlayServiceNotifications.build(this, CHANNEL_ID, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 对照实验：health 是 Android 14 引入的传感器监测类型，
            // 语义上比 specialUse 更贴合"加速度计/陀螺仪持续检测"。
            // 但个别 ROM 会要求 PROPERTY_HEALTH_FGS_SUBTYPE 等附加声明而抛异常，
            // 一旦抛异常服务就会掉出前台（比原来更糟），所以必须逐级回退。
            val candidates = intArrayOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            for (type in candidates) {
                try {
                    startForeground(NOTIF_ID, notification, type)
                    Logger.d(this, "OverlayService", "Foreground service active: $content (type=${fgTypeName(type)})")
                    return
                } catch (e: Exception) {
                    Logger.d(
                        this,
                        "OverlayService",
                        "startForeground with ${fgTypeName(type)} failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            Logger.d(this, "OverlayService", "🚨 all foreground types rejected; service stays non-foreground")
        } else {
            try {
                startForeground(NOTIF_ID, notification)
                Logger.d(this, "OverlayService", "Foreground service active: $content (legacy)")
            } catch (e: Exception) {
                Logger.d(this, "OverlayService", "startForeground failed: ${e.message}")
            }
        }
    }

    private fun fgTypeName(type: Int): String = when (type) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH -> "HEALTH"
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> "SPECIAL_USE"
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE -> "MICROPHONE"
        else -> "0x${Integer.toHexString(type)}"
    }

    fun enterMicrophoneMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        // 麦克风类型要求 RECORD_AUDIO 处于授权态，否则 startForeground 抛异常会让服务掉出前台。
        // 这里先自检，不满足就直接保持 SPECIAL_USE，宁可录音失败也不能丢掉前台身份。
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            Logger.d(this, "OverlayService", "enterMicrophoneMode skipped: RECORD_AUDIO not granted")
            return false
        }

        return try {
            startForeground(
                NOTIF_ID,
                OverlayServiceNotifications.build(this, CHANNEL_ID, getString(R.string.notif_recording)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Logger.d(this, "OverlayService", "enterMicrophoneMode: switched to MICROPHONE|SPECIAL_USE")
            true
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "enterMicrophoneMode failed: ${e.message}")
            // 回退：至少保证服务仍在前台
            runCatching { promoteToForeground(currentNotificationText()) }
            false
        }
    }

    fun exitMicrophoneMode() {
        try {
            if (isDoubleTapEnabled) {
                promoteToForeground(currentNotificationText())
                Logger.d(this, "OverlayService", "exitMicrophoneMode: restored SPECIAL_USE foreground")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                Logger.d(this, "OverlayService", "exitMicrophoneMode: foreground notification removed")
            }
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "exitMicrophoneMode failed: ${e.message}")
        }
    }

    private fun stopSelfIfIdle(reason: String) {
        if (isFlipEnabled || isDoubleTapEnabled || overlayManager.isShowing()) return
        Logger.d(this, "OverlayService", "Service idle ($reason), stopping")
        cancelRestart()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun triggerTapFeedback(reason: String) {
        if (!Prefs.isVibrateFeedbackEnabled(this)) {
            Logger.d(this, "OverlayService", "Tap feedback skipped: disabled by user. reason=$reason")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapFeedbackAtMs < TAP_FEEDBACK_THROTTLE_MS) {
            Logger.d(this, "OverlayService", "Tap feedback throttled. reason=$reason")
            return
        }
        lastTapFeedbackAtMs = now
        val vibrated = Utils.vibrate(this, duration = 45L, reason = reason, amplitude = 210)
        if (!vibrated) {
            Logger.d(this, "OverlayService", "Tap feedback requested but vibrator did not run. reason=$reason")
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null
}

