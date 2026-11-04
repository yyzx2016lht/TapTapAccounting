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

/**
 * 微信/支付宝 hook 的主入口。
 *
 * 宿主 manifest 里没有本插件，Android 11+ 的包可见性会让
 * `ContentResolver.call()` 解析不到 [CaptureIngestProvider]；显式广播不受这个限制。
 * 广播本身还是 exported 的，所以用 token 校验，只接受带正确 token 的 payload。
 */
class CaptureIngestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CaptureContract.ACTION_INGEST) return
        val token = intent.getStringExtra(CaptureContract.EXTRA_TOKEN)
        val payloadJson = intent.getStringExtra(CaptureContract.EXTRA_PAYLOAD) ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ack = CaptureIngestHandler.ingest(context, token, payloadJson)
                Log.i(TAG, "broadcast ingest ack=$ack")
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast ingest failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TapCaptureIngest"
    }
}
