package com.taostudio.tapaccounting.capture.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.taostudio.tapaccounting.capture.R
import com.taostudio.tapaccounting.capture.contract.CaptureContract

/**
 * 1px 透明无动画唤醒 Activity，完全对齐 AutoAccounting 的 FloatingWindowTriggerActivity。
 *
 * 为什么必须有它：
 * 在 ColorOS / Android 15+ 上，插件退到后台后会被 Hans 冻结（cgroup freezer），
 * 此时插件进程的 TCP accept 线程挂起、显式广播被放入 defer 队列、后台 startForegroundService
 * 被 mAllowStart_noBinding=PROC_STATE_TOP 拦截，直到用户手动打开插件才会解冻。
 *
 * 而常驻于 com.android.phone（UID 1001，具备后台启动 Activity 特权）的 RelayServer
 * 通过 startActivity 拉起本透明 Activity 时，ColorOS ActivityTaskManager 会在 3ms 内
 * 立即将本插件从 Hans 黑名单解冻（Hans update: false），且因本 Activity 为 1px 透明
 * FLAG_NOT_FOCUSABLE 并在 onCreate/onStart 内立即 finish()，当前前台应用（如阅读软件、微信）
 * 不会失去焦点或产生任何闪烁。
 */
class CaptureWakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.TransparentTriggerTheme)
        configureOnePxWindow()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleWakeIntent(intent)
    }

    private fun configureOnePxWindow() {
        try {
            window?.apply {
                setGravity(Gravity.START or Gravity.TOP)
                attributes = attributes.apply {
                    x = 0
                    y = 0
                    width = 1
                    height = 1
                    flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                setWindowAnimations(0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleWakeIntent(intent: Intent?) {
        try {
            val appCtx = applicationContext ?: this
            PluginDirectServer.ensureStarted(appCtx)
            CaptureKeepAliveService.start(appCtx)

            val token = intent?.getStringExtra(CaptureContract.EXTRA_TOKEN)
            val payload = intent?.getStringExtra(CaptureContract.EXTRA_PAYLOAD)
            val relayId = intent?.getStringExtra(CaptureContract.EXTRA_RELAY_ID)
            if (!token.isNullOrBlank() && !payload.isNullOrBlank()) {
                Log.i(TAG, "wake activity triggered id=${relayId ?: "-"}")
                PluginDirectServer.ingestAsync(appCtx, token, payload, relayId.orEmpty())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake handle failed: ${t.message}")
        } finally {
            exitSilently()
        }
    }

    private fun exitSilently() {
        try {
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            window?.setWindowAnimations(0)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "TapCaptureWake"
    }
}
