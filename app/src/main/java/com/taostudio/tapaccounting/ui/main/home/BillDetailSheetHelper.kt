package com.taostudio.tapaccounting.ui.main.home

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetActions
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetController
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetRequest

/**
 * 聊天 / 账单搜索打开底部账单详情弹层。
 *
 * 具体绑定已统一到 [BillDetailSheetController] + `BillDetailSheetBinder`，
 * 这里只保留入口原有的回调参数，调用方不用改。
 */
object BillDetailSheetHelper {

    fun showBillDetailSheet(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bill: Bill,
        onRefund: ((Bill) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onBillChanged: (() -> Unit)? = null,
        /** 账单列表：删除/撤销时用它定位那一行播行动画；不传就没有动画。 */
        listRecyclerView: RecyclerView? = null
    ) {
        val request = BillDetailSheetRequest(
            bill = bill,
            listRecyclerView = listRecyclerView
        )
        val actions = object : BillDetailSheetActions(
            context = context,
            scope = lifecycleOwner.lifecycleScope,
            onDataChanged = { onBillChanged?.invoke() }
        ) {
            override fun onRefund(bill: Bill) {
                onRefund?.invoke(bill)
            }

            override fun onDismiss() {
                onDismiss?.invoke()
            }

            override fun onLinkedBillClick(bill: Bill) {
                showBillDetailSheet(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    bill = bill,
                    onRefund = onRefund,
                    onDismiss = onDismiss,
                    onBillChanged = onBillChanged,
                    listRecyclerView = listRecyclerView
                )
            }
        }

        BillDetailSheetController(context, lifecycleOwner.lifecycleScope)
            .show(request, actions)
    }
}
