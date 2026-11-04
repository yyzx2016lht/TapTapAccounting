package com.taostudio.tapaccounting.capture.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.CategorySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 处理通知栏快捷操作：
 * 1. RemoteInput 直接输入备注（免打开 App）；
 * 2. 一键标记最新待记为不计。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        val action = intent?.action ?: return
        val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)?.trim().orEmpty()
        if (captureId.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val repo = CaptureApp.from(ctx).repository
                when (action) {
                    ACTION_REPLY_NOTE -> {
                        val remoteBundle = RemoteInput.getResultsFromIntent(intent)
                        val note = remoteBundle?.getCharSequence(KEY_REMOTE_NOTE)?.toString()?.trim().orEmpty()
                        if (note.isNotEmpty()) {
                            val updated = withContext(Dispatchers.IO) {
                                repo.updateNoteAndReclassify(ctx, captureId, note)
                            }
                            val catHint = updated
                                ?.takeIf { it.categorySource == CategorySource.RULE }
                                ?.categoryName
                                ?.let { " → $it" }
                                .orEmpty()
                            Toast.makeText(ctx, "已补备注：$note$catHint", Toast.LENGTH_SHORT).show()
                        }
                    }

                    ACTION_IGNORE_CAPTURE -> {
                        withContext(Dispatchers.IO) {
                            repo.updateStatus(captureId, CaptureStatus.IGNORED)
                        }
                        Toast.makeText(ctx, "已标记该笔为不计", Toast.LENGTH_SHORT).show()
                    }
                }
                CaptureNotifier.refreshAll(ctx)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY_NOTE = "com.taostudio.tapaccounting.capture.action.REPLY_NOTE"
        const val ACTION_IGNORE_CAPTURE = "com.taostudio.tapaccounting.capture.action.IGNORE_CAPTURE"
        const val EXTRA_CAPTURE_ID = "capture_id"
        const val KEY_REMOTE_NOTE = "key_remote_note"
    }
}
