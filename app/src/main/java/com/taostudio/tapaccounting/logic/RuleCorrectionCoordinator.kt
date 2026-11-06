package com.taostudio.tapaccounting.logic

import android.content.Context
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance

object RuleCorrectionCoordinator {
    data class EditContext(
        val oldBill: Bill,
        val newBill: Bill,
        val provenance: BillRuleProvenance?,
        val isOverlay: Boolean,
    )

    fun maybePrompt(
        ctx: Context,
        context: EditContext,
        onFinished: () -> Unit = {},
    ) {
        onFinished()
    }
}
