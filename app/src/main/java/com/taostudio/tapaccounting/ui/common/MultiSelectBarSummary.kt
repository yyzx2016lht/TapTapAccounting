package com.taostudio.tapaccounting.ui.common

import android.content.Context
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillSelectionSummary
import com.taostudio.tapaccounting.logic.CurrencyManager

/**
 * 多选模式底部操作栏的摘要文案（对齐资产详情页的做法：摘要行放在操作栏顶部，
 * 不依赖页面顶部的 banner，滚动位置不影响展示）。
 *
 * 格式：「已选 N 笔 · 支出 ¥X · 收入 ¥Y」，选中含转账/还款时追加「· 其他 N 笔」；
 * 某一栏跨多币种时该栏显示「混合币种」而不是直接相加。
 */
object MultiSelectBarSummary {

    fun build(context: Context, bills: List<Bill>): String {
        val summary = BillSelectionSummary.summarize(bills)
        val expense = formatTotal(context, summary.expense)
        val income = formatTotal(context, summary.income)
        val base = context.getString(R.string.multi_select_bar_summary_fmt, bills.size, expense, income)
        return if (summary.otherCount > 0) {
            base + context.getString(R.string.multi_select_bar_summary_other_suffix_fmt, summary.otherCount)
        } else {
            base
        }
    }

    private fun formatTotal(context: Context, total: BillSelectionSummary.Total): String =
        if (total.mixedCurrency) {
            context.getString(R.string.multi_select_summary_mixed_currency)
        } else {
            AmountFormatHelper.formatCurrency(CurrencyManager.getSymbol(total.currency), total.amount)
        }
}
