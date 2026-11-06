package com.taostudio.tapaccounting.ui.billdetail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 各入口对底部账单详情弹层里四个按钮的反应。
 *
 * 默认实现覆盖「打开编辑页」「直接删除 + 列表动画 + 撤销」这类所有入口都一样的行为；
 * 入口只覆盖自己特殊的部分（退款弹层、页面刷新、聊天回调等）。
 */
open class BillDetailSheetActions(
    protected val context: Context,
    protected val scope: CoroutineScope,
    /** 删除/撤销后刷新数据。 */
    private val onDataChanged: () -> Unit = {},
    /** 撤销提示条挂在哪里；默认挂当前 Activity 的内容视图。 */
    private val undoAnchor: () -> View? = {
        (context as? Activity)?.findViewById(android.R.id.content)
    },
) {
    internal var listRecyclerView: RecyclerView? = null
    private var dismissHandler: (() -> Unit)? = null

    /** 新建退款：绝大多数入口打开自己的退款弹层。 */
    open fun onRefund(bill: Bill) = Unit

    /** 编辑普通账单：默认打开编辑页。 */
    open fun onEdit(bill: Bill) {
        openEditor(bill)
    }

    /** 编辑退款单：默认改成编辑对应的原账单（和旧行为一致）。 */
    open fun onEditRefund(originalBill: Bill, refundBill: Bill) {
        onRefund(originalBill)
    }

    /** 删除账单：默认直接删，列表行动画 + 撤销提示。 */
    open fun onDelete(bill: Bill) {
        BillRowAnimator.deleteThen(
            recyclerView = listRecyclerView,
            billIds = listOf(bill.id),
            startDelayMs = BillRowAnimator.SHEET_DISMISS_DELAY_MS
        ) {
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                val rows = BillDeleteUndo.delete(db, listOf(bill)) ?: return@launch
                withContext(Dispatchers.Main) {
                    onDataChanged()
                    BillUndoController.showNow(
                        undoAnchor(),
                        context.getString(R.string.bill_deleted_undo),
                        rows
                    ) {
                        onDataChanged()
                        BillRowAnimator.expandAfterRefresh(listRecyclerView, rows.map { it.originalBillId })
                    }
                }
            }
        }
    }

    /** 「不计入统计」保存成功后的回调（入口按需刷新）。 */
    open fun onToggleExcludeStats(excluded: Boolean) = Unit

    open fun onDismiss() = Unit

    /** 点击退款记录 / 原账单那一行：默认什么都不做（统计页只读）。 */
    open fun onLinkedBillClick(bill: Bill) = Unit

    protected fun openEditor(bill: Bill) {
        val intent = Intent(context, EditBillActivity::class.java)
        intent.putExtra("BILL_ID", bill.id)
        context.startActivity(intent)
    }

    internal fun attach(dismiss: () -> Unit, recyclerView: RecyclerView?) {
        dismissHandler = dismiss
        listRecyclerView = recyclerView
    }

    internal fun dismissSheet() {
        dismissHandler?.invoke()
    }
}
