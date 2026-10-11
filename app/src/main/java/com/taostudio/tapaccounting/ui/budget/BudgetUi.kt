package com.taostudio.tapaccounting.ui.budget

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.BudgetService

/** 预算相关页面共用的配色与格式，阈值与统计页预算概览一致（80% 预警、100% 超支）。 */
internal object BudgetUi {

    fun money(amount: Double): String = "¥${AmountFormatHelper.formatAmount(amount)}"

    fun usageColor(context: Context, spent: Double, budget: Double): Int {
        val usedPercent = if (budget > 0.0) spent / budget else 0.0
        return context.getColor(
            when {
                usedPercent >= 1.0 -> R.color.expense_color
                usedPercent >= 0.8 -> R.color.warning_color
                else -> R.color.income_color
            }
        )
    }

    fun statusColor(context: Context, status: BudgetService.BudgetStatus): Int = context.getColor(
        when (status) {
            BudgetService.BudgetStatus.EXCEEDED -> R.color.expense_color
            BudgetService.BudgetStatus.WARNING -> R.color.warning_color
            BudgetService.BudgetStatus.NORMAL -> R.color.income_color
        }
    )

    fun neutralColor(context: Context): Int = context.getColor(R.color.stats_segmented_text_inactive)

    fun bindPill(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        view.setBackgroundResource(R.drawable.bg_budget_status_pill)
        view.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x1F))
    }

    fun bindProgress(bar: ProgressBar, spent: Double, budget: Double, color: Int) {
        bar.progress = if (budget > 0.0) ((spent / budget) * 100).toInt().coerceIn(0, 100) else 0
        bar.progressTintList = ColorStateList.valueOf(color)
    }
}
