package com.taostudio.tapaccounting.ui.common

import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 删除账单后的「已删除 + 撤销」提示条。
 *
 * 交互约定（用户明确要求）：点删除**不再二次确认**，直接删；底部提示条给出 6 秒撤销窗口，
 * 点「撤销」就把账单恢复回来；窗口超时（用户没管）再补一个 Toast 确认最终结果。
 *
 * 为什么有些场景要 [request] 而不是直接弹：删除常发生在「删完立刻 finish」的页面
 * （账单详情页），Snackbar 挂在即将销毁的 Activity 上会跟着一起没。所以这类场景先记一笔，
 * 等承载页 onResume 时由 [consumePending] 真正弹出。
 */
object BillUndoController {

    private const val UNDO_WINDOW_MS = 6000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private data class Pending(val message: String, val rows: List<DeletedBill>)

    @Volatile
    private var pending: Pending? = null

    /** 删完马上要 finish 的页面用这个：先记下来，回到承载页再弹。 */
    fun request(message: String, rows: List<DeletedBill>) {
        if (rows.isEmpty()) return
        pending = Pending(message, rows)
    }

    /** 承载页 onResume 调用：有待展示的撤销提示就弹出来并清掉。 */
    fun consumePending(anchor: View, onRestored: () -> Unit) {
        val item = pending ?: return
        pending = null
        showNow(anchor, item.message, item.rows, onRestored)
    }

    /** 删完仍停留在当前页面的场景，直接弹。anchor 为 null 时退化为 [request]。 */
    fun showNow(
        anchor: View?,
        message: String,
        rows: List<DeletedBill>,
        onRestored: () -> Unit
    ) {
        if (rows.isEmpty()) return
        val target = anchor
        if (target == null) {
            request(message, rows)
            return
        }

        val snackbar = Snackbar.make(target, message, UNDO_WINDOW_MS)
        snackbar.setAction(R.string.undo) {
            scope.launch {
                val db = AppDatabase.getDatabase(target.context)
                val restored = withContext(Dispatchers.IO) { BillDeleteUndo.undo(db, rows) }
                if (restored) onRestored()
            }
        }
        snackbar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // 只有「放着不管直到超时」才补 Toast；点了撤销或手动划掉都不再打扰。
                if (event == DISMISS_EVENT_TIMEOUT) {
                    Toast.makeText(target.context, message, Toast.LENGTH_SHORT).show()
                }
            }
        })
        snackbar.show()
    }
}
