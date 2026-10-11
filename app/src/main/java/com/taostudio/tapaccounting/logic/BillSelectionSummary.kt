package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 多选状态面板的数据（见 COMFORT-REVIEW 第 14 条）。
 *
 * 面板结构固定为「支出 / 收入」两栏 + 可选的「转账/还款 N 笔」栏，任何选择都给出同一形状的结果，
 * 界面不会因为选中集合的构成变化而换掉整行文案。某一栏跨多个币种时标记 [Total.mixedCurrency]，
 * 金额不可直接相加。
 */
object BillSelectionSummary {

    data class Total(
        val amount: Double,
        val currency: String,
        val mixedCurrency: Boolean
    )

    data class Summary(
        val expense: Total,
        val income: Total,
        val otherCount: Int
    )

    fun summarize(bills: List<Bill>): Summary {
        val fallbackCurrency = bills.firstOrNull()?.let(::currencyOf) ?: DEFAULT_CURRENCY
        return Summary(
            expense = total(bills.filter { it.type == Bill.TYPE_EXPENSE }, fallbackCurrency),
            income = total(bills.filter { it.type == Bill.TYPE_INCOME }, fallbackCurrency),
            otherCount = bills.count { it.type != Bill.TYPE_EXPENSE && it.type != Bill.TYPE_INCOME }
        )
    }

    private fun total(bills: List<Bill>, fallbackCurrency: String): Total {
        val currencies = bills.map(::currencyOf).distinct()
        return Total(
            amount = bills.sumOf { it.amount },
            currency = currencies.firstOrNull() ?: fallbackCurrency,
            mixedCurrency = currencies.size > 1
        )
    }

    private fun currencyOf(bill: Bill): String = bill.currency.trim().ifBlank { DEFAULT_CURRENCY }

    private const val DEFAULT_CURRENCY = "CNY"
}
