package com.taostudio.tapaccounting.ui.billdetail

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.activity.BillDetailActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 底部账单详情弹层的唯一入口。
 *
 * 负责：独立详情页开关、防重复打开、创建弹层、异步加载数据、调用 binder。
 * 入口只负责传 [BillDetailSheetRequest] + [BillDetailSheetActions]。
 */
class BillDetailSheetController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var activeSheet: BottomSheetDialog? = null
    private var lastBillId: Long = -1L
    private var lastShowAtMs: Long = 0L

    fun show(request: BillDetailSheetRequest, actions: BillDetailSheetActions) {
        val bill = request.bill
        if (Prefs.isIndependentDetailEnabled(context) && bill.id > 0L) {
            BillDetailActivity.start(context, bill.id)
            return
        }

        // 防重复打开：同一笔账单正在展示就直接复用；换了账单先关掉旧的。
        val now = SystemClock.elapsedRealtime()
        if (activeSheet?.isShowing == true) {
            if (lastBillId == bill.id) return
            activeSheet?.dismiss()
        }
        if (lastBillId == bill.id && now - lastShowAtMs < DEBOUNCE_MS) return
        lastBillId = bill.id
        lastShowAtMs = now

        val dialog = BottomSheetDialog(context)
        activeSheet = dialog
        val view = LayoutInflater.from(context)
            .inflate(R.layout.layout_bill_detail_bottom_sheet, null)
        actions.attach(dismiss = { dialog.dismiss() }, recyclerView = request.listRecyclerView)

        dialog.setContentView(view)
        configureSheet(dialog)
        dialog.setOnDismissListener {
            if (activeSheet === dialog) {
                activeSheet = null
            }
            actions.onDismiss()
        }
        dialog.show()

        val loader = BillDetailDataLoader(context)
        // 先同步渲染账单本身，再异步补来源徽标 / 退款记录 / 原账单。
        BillDetailSheetBinder.bind(context, view, loader.initialState(request), actions, scope)
        scope.launch {
            val model = loader.load(request)
            BillDetailSheetBinder.bind(context, view, model, actions, scope)
        }
    }

    private fun configureSheet(dialog: BottomSheetDialog) {
        dialog.dismissWithAnimation = true
        dialog.setOnShowListener { shown ->
            val sheetDialog = shown as? BottomSheetDialog ?: return@setOnShowListener
            val sheetView = sheetDialog
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(sheetView)
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            sheetView.post {
                val contentHeight = (sheetView as? ViewGroup)?.getChildAt(0)?.measuredHeight
                    ?: sheetView.measuredHeight
                val desiredHeight = minOf(contentHeight, maxHeight).coerceAtLeast(1)
                sheetView.layoutParams = sheetView.layoutParams.apply {
                    height = desiredHeight
                }
                sheetView.requestLayout()
                behavior.peekHeight = desiredHeight
            }
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
