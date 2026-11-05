package com.taostudio.tapaccounting.capture.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract

/**
 * 唤醒接收器：接收来自主应用或系统 Relay 的 ACTION_WAKE_INGEST 广播。
 *
 * 当插件进程被系统停掉（o-stop）或冻结时，通过 FLAG_INCLUDE_STOPPED_PACKAGES 显式广播
 * 拉起插件进程，启动保活服务与 TCP 服务端，恢复 ContentProvider 可用状态。
 */
class CaptureWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == CaptureContract.ACTION_WAKE_INGEST) {
            Log.i("TapCaptureWake", "received ACTION_WAKE_INGEST, waking up plugin")
            CaptureKeepAliveService.start(context)
            PluginDirectServer.ensureStarted(context)
        }
    }
}
