package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 统计页金额换算。
 *
 * 语义分离：
 * - 支出/收入的 [Bill.exchangeRate] 是 `bill.currency → CNY`（记账时冻结），可直接 `amount * rate`。
 * - 转账/还款的 [Bill.exchangeRate] 是 `bill.currency → targetCurrency`（源→目标），
 *   **不能**当作 →CNY；无币种筛选时必须单独折算 CNY。
 */
object StatsAmountResolver {

    fun resolve(
        bill: Bill,
        selectedCurrency: String?,
        convertToCny: (amount: Double, currency: String) -> Double = { amount, currency ->
            BillAssetImpactService.convertAmountBetweenCurrencies(amount, currency, "CNY")
        }
    ): Double {
        if (selectedCurrency != null) return bill.amount
        if (bill.type == Bill.TYPE_TRANSFER) {
            return runCatching { convertToCny(bill.amount, bill.currency) }
                .getOrElse { bill.amount }
        }
        return bill.amount * bill.exchangeRate
    }
}
