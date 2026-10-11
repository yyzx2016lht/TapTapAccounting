package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Budget
import java.util.Locale

/**
 * 跨月（年视图、多月筛选）的预算同期对比。
 *
 * 预算按月存储且需手动复制，一年里往往只有部分月份设了总预算。若拿「有预算月份之和」去比
 * 「全年支出」，剩余会被没设预算的月份拉成大额负数。这里只统计「设了总预算且已开始」的月份，
 * 预算与支出取同一批月份；尚未开始的月份没有支出，计入会虚高结余，因此排除。
 */
object BudgetPeriodSummary {

    data class Summary(
        val budgetedMonths: Int,
        val overMonths: Int,
        val budget: Double,
        val spent: Double,
        val mixedCurrency: Boolean
    ) {
        val remaining: Double get() = budget - spent
    }

    enum class Timing { PAST, CURRENT, FUTURE }

    data class Month(
        val yearMonth: String,
        val month: Int,
        val budget: Double?,
        val spent: Double,
        val timing: Timing
    ) {
        val isOver: Boolean get() = budget != null && timing != Timing.FUTURE && spent > budget
    }

    /** 某年 1~12 月逐月的总预算与支出，未设预算的月份 [Month.budget] 为 null。 */
    fun months(
        year: Int,
        totalBudgets: List<Budget>,
        monthSpend: Map<String, Double>,
        currentYearMonth: String
    ): List<Month> {
        val budgetByMonth = totalBudgets
            .filter { it.categoryId == null }
            .associateBy { it.yearMonth }
        return (1..12).map { month ->
            val yearMonth = String.format(Locale.US, "%04d-%02d", year, month)
            Month(
                yearMonth = yearMonth,
                month = month,
                budget = budgetByMonth[yearMonth]?.amount,
                spent = monthSpend[yearMonth] ?: 0.0,
                timing = when {
                    yearMonth < currentYearMonth -> Timing.PAST
                    yearMonth == currentYearMonth -> Timing.CURRENT
                    else -> Timing.FUTURE
                }
            )
        }
    }

    /**
     * @param totalBudgets 区间内各月的总预算（categoryId == null），调用方已按币种筛选
     * @param monthSpend yearMonth → 该月支出
     * @param currentYearMonth "yyyy-MM"，晚于它的月份视为未开始
     */
    fun summarize(
        totalBudgets: List<Budget>,
        monthSpend: Map<String, Double>,
        currentYearMonth: String
    ): Summary {
        val started = totalBudgets
            .filter { it.categoryId == null && it.yearMonth <= currentYearMonth }
            .distinctBy { it.yearMonth }
        val currencies = started.map { it.currency.trim().ifBlank { DEFAULT_CURRENCY } }.distinct()
        return Summary(
            budgetedMonths = started.size,
            overMonths = started.count { (monthSpend[it.yearMonth] ?: 0.0) > it.amount },
            budget = started.sumOf { it.amount },
            spent = started.sumOf { monthSpend[it.yearMonth] ?: 0.0 },
            mixedCurrency = currencies.size > 1
        )
    }

    private const val DEFAULT_CURRENCY = "CNY"
}
