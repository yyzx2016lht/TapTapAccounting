package com.taostudio.tapaccounting.capture.rule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 插件前台时主动拉取迁移数据。
 *
 * ColorOS 会拦截「主应用后台启动插件 Provider」；但用户打开插件状态页时插件进程在前台，
 * 这时主应用回拉 rule_bundle / capture.db 一定成功。插件状态页会在 onCreate 和按钮点击时
 * 发这两个广播。
 */
class CaptureSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        when (intent?.action) {
            ACTION_PULL_RULES -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        CaptureRuleBundleBootstrap.ensureImported(app, force = true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "pull rules failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_PULL_LEGACY -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        CaptureLegacyImporter.importIfNeeded(app, force = true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "pull legacy failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PULL_RULES =
            "com.taostudio.tapaccounting.action.CAPTURE_PULL_RULES"
        const val ACTION_PULL_LEGACY =
            "com.taostudio.tapaccounting.action.CAPTURE_PULL_LEGACY"
        private const val TAG = "TapCaptureSync"
    }
}
