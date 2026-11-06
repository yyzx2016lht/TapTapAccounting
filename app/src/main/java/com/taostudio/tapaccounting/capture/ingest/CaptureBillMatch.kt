package com.taostudio.tapaccounting.capture.ingest

import com.taostudio.tapaccounting.data.local.entity.Bill
import kotlin.math.roundToLong

/**
 * “最近是不是已经记过这笔钱”的纯逻辑。
 *
 * 除了单笔金额相等，还要识别拆分记账：
 * 37.77 被拆成 8 笔后，没有任何单笔是 37.77，但同一 paymentGroupId 的合计仍是 37.77。
 */
internal object CaptureBillMatch {

    fun matches(bills: List<Bill>, amountCents: Long): Boolean {
        if (amountCents <= 0L) return false
        val expenses = bills.filter {
            it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND
        }
        if (expenses.any { (it.amount * 100.0).roundToLong() == amountCents }) return true

        return expenses
            .filter { !it.paymentGroupId.isNullOrBlank() }
            .groupBy { it.paymentGroupId }
            .values
            .any { group ->
                group.sumOf { (it.amount * 100.0).roundToLong() } == amountCents
            }
    }
}
