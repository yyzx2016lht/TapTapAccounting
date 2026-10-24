package com.taostudio.tapaccounting.ui.dialog

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BatchBillInserter
import com.taostudio.tapaccounting.logic.BillDisplayFormatter

/**
 * 一键全部入账之后追问「要不要把这几笔记成同一次扣款」。
 *
 * 用自定义卡片而不是系统 AlertDialog：把条数和合计金额放大突出，
 * 用户不用读正文就能确认「是不是这一堆」。
 */
object MergePaymentDialog {

    fun show(ctx: Context, savedBills: List<Bill>, onFinished: () -> Unit) {
        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.dialog_merge_payment_confirm, null)

        val currency = savedBills.firstOrNull()?.currency?.takeIf { it.isNotBlank() } ?: "CNY"
        val total = savedBills.sumOf { it.amount }

        view.findViewById<TextView>(R.id.tv_merge_payment_count).text =
            ctx.getString(R.string.merge_payment_count, savedBills.size)
        view.findViewById<TextView>(R.id.tv_merge_payment_total).text =
            BillDisplayFormatter.formatMoney(total, currency)
        view.findViewById<TextView>(R.id.tv_merge_payment_message).text =
            ctx.getString(R.string.merge_payment_message)
        val btnOk = view.findViewById<TextView>(R.id.btn_merge_payment_ok)
        val btnCancel = view.findViewById<TextView>(R.id.btn_merge_payment_cancel)
        btnOk.text = ctx.getString(R.string.merge_bills_positive)
        btnCancel.text = ctx.getString(R.string.merge_bills_negative)

        val dialog = AlertDialog.Builder(themeCtx).setView(view).create()
        btnOk.setOnClickListener {
            dialog.dismiss()
            BatchBillInserter.mergeAsOnePayment(ctx, savedBills, onFinished)
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            onFinished()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onFinished() }

        if (ctx is android.app.Activity) {
            OverlayDialogs.showPageCenterDialog(dialog, ctx, useSolidPanelBackground = false)
        } else {
            OverlayDialogs.showOverlayCenterDialog(dialog, ctx, useSolidPanelBackground = false)
        }
    }
}
