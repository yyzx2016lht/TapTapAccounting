package com.taostudio.tapaccounting.capture.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 接收 App 保存账单后的 [CaptureContract.ACTION_BILL_CREATED]，做一次定向对账。
 *
 * 广播只加速；丢了也不影响正确性——PendingListActivity 的 onResume/刷新仍会全量对账。
 * 只处理带 captureId 的广播，且该 id 必须已存在于本地库（见 [CaptureReconciler]），
 * 因此外部伪造广播最多触发一次无副作用的查询。
 */
class BillCreatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CaptureContract.ACTION_BILL_CREATED) return
        val captureId = intent.getStringExtra(CaptureContract.EXTRA_CAPTURE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val app = context.applicationContext as? CaptureApp ?: return

        val pendingResult = goAsync()
        // goAsync 只争取到很短的进程存活时间；所有 IO 都在后台线程完成。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = app.repository
                val changed = CaptureReconciler.reconcile(
                    repository = repository,
                    client = CaptureBridgeClient(context),
                    captureIds = setOf(captureId),
                )
                if (changed > 0) {
                    CaptureNotifier.refreshAll(context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "BILL_CREATED reconcile failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TapCaptureBridge"
    }
}
