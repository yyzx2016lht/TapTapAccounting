package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * Unified income/expense contribution for budget-style statistics.
 *
 * Linked refunds have already reduced their source expense's current amount.
 * Unlinked legacy refunds remain negative expenses until they can be reconciled
 * to a source, so imported historical data still restores the budget.
 */
data class BillStatsContribution(
    val expense: Double = 0.0,
    val income: Double = 0.0,
    val refund: Double = 0.0
) {
    companion object {
        /**
         * 这笔账单是否参与收支统计。
         *
         * `excludeFromStats` 是用户可见的开关；旧数据里的
         * [Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED] 没有可靠的布尔标记，沿用同一排除语义。
         * 资产余额、资产流水等「钱有没有动」的计算不要用这个判断。
         */
        fun countsInStats(bill: Bill): Boolean =
            !bill.excludeFromStats && bill.subType != Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED

        fun from(bill: Bill, amount: Double): BillStatsContribution {
            if (bill.excludeFromStats || bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED) {
                return BillStatsContribution()
            }
            if (bill.subType == Bill.SUBTYPE_REFUND) {
                val hasSourceLink = bill.relatedBillId != null || !bill.relatedSharedId.isNullOrBlank()
                return BillStatsContribution(
                    expense = if (hasSourceLink) 0.0 else -amount,
                    refund = amount
                )
            }
            return when (bill.type) {
                Bill.TYPE_EXPENSE -> BillStatsContribution(expense = amount)
                Bill.TYPE_INCOME -> BillStatsContribution(income = amount)
                else -> BillStatsContribution()
            }
        }
    }
}
