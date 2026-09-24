package com.taostudio.tapaccounting

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepAliveA11yService"

        @Volatile
        var instance: KeepAliveAccessibilityService? = null
            private set

        private const val ENSURE_SERVICE_INTERVAL_MS = 60_000L

        fun isServiceEnabled(): Boolean = instance != null

        fun takeScreenshotCompat(callback: (Bitmap?) -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val service = instance
                if (service == null) {
                    Log.e(TAG, "takeScreenshotCompat: service is null")
                    callback(null)
                    return
                }
                try {
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        Executor { it.run() },
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                try {
                                    val hardwareBuffer: HardwareBuffer = result.hardwareBuffer
                                    val colorSpace = result.colorSpace
                                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    Log.d(TAG, "takeScreenshot success: ${bitmap?.width}x${bitmap?.height}")
                                    callback(bitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "takeScreenshot decode failed: ${e.message}")
                                    callback(null)
                                } finally {
                                    result.hardwareBuffer.close()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                                callback(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "takeScreenshot exception: ${e.message}")
                    callback(null)
                }
            } else {
                Log.w(TAG, "takeScreenshot not supported below Android 11")
                callback(null)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            serviceInfo = serviceInfo.apply {
                // P2-10: 勿用 TYPES_ALL_MASK，只监听窗口状态变化即可保活
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            Log.d(TAG, "Accessibility service connected")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed: ${e.message}")
        }
        ensureOverlayService("a11y-connected")
    }

    private var lastEnsureAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnsureAtMs >= ENSURE_SERVICE_INTERVAL_MS) {
            ensureOverlayService("a11y-event")
        }
    }

    override fun onInterrupt() {
        // 保活用途，不处理
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun ensureOverlayService(reason: String) {
        lastEnsureAtMs = SystemClock.elapsedRealtime()
        if (!Prefs.isDoubleTapEnabled(this) || OverlayService.isServiceRunning) return
        try {
            OverlayService.startCompat(this, Intent(this, OverlayService::class.java))
            Log.d(TAG, "ensureOverlayService: requested start, reason=$reason")
        } catch (e: Exception) {
            Log.d(TAG, "ensureOverlayService failed: ${e.message}")
        }
    }
}

