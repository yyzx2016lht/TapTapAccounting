package com.taostudio.tapaccounting.capture.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * hook / relay → 主应用的入口之一（显式广播）。
 *
 * 与插件 provider 不同，这里使用 `FLAG_INCLUDE_STOPPED_PACKAGES` 也能把被系统停掉的主应用拉起；
 * 落库在 `goAsync()` 的后台协程里完成，不阻塞广播队列，也不启动任何 Activity。
 */
class CaptureIngestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != CaptureContract.APP_INGEST_ACTION) return
        val token = intent.getStringExtra(CaptureContract.EXTRA_TOKEN)
        val payload = intent.getStringExtra(CaptureContract.EXTRA_PAYLOAD) ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ack = CaptureIngestCoordinator.ingest(app, token, payload)
                val ok = runCatching { JSONObject(ack).optBoolean("ok", false) }.getOrDefault(false)
                if (ok) {
                    // 只有真实投递（插件 hook/relay）会走到这里；模拟数据直接调 Coordinator，不置位。
                    com.taostudio.tapaccounting.capture.data.CapturePrefs.setHookDelivered(app, true)
                }
                pendingResult.setResultCode(if (ok) 1 else 0)
                pendingResult.setResultData(ack)
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast ingest failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TapCaptureIngestApp"
    }
}
