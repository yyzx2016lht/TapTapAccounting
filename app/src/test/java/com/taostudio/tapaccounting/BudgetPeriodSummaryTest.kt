package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.logic.BudgetPeriodSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPeriodSummaryTest {

    private fun total(yearMonth: String, amount: Double, currency: String = "CNY"): Budget = Budget(
        bookName = "",
        categoryId = null,
        categoryName = null,
        yearMonth = yearMonth,
        amount = amount,
        currency = currency,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun onlyBudgetedMonthsAreCompared() {
        val summary = BudgetPeriodSummary.summarize(
            totalBudgets = listOf(total("2026-09", 3000.0), total("2026-10", 3000.0)),
            monthSpend = mapOf(
                "2026-01" to 10000.0,
                "2026-09" to 2500.0,
                "2026-10" to 3500.0
            ),
            currentYearMonth = "2026-10"
        )
        assertEquals(2, summary.budgetedMonths)
        assertEquals(6000.0, summary.budget, 0.001)
        assertEquals(6000.0, summary.spent, 0.001)
        assertEquals(0.0, summary.remaining, 0.001)
        assertEquals(1, summary.overMonths)
    }

    @Test
    fun futureMonthsAreExcluded() {
        val summary = BudgetPeriodSummary.summarize(
            totalBudgets = listOf(total("2026-10", 3000.0), total("2026-11", 3000.0), total("2026-12", 3000.0)),
            monthSpend = mapOf("2026-10" to 1000.0),
            currentYearMonth = "2026-10"
        )
        assertEquals(1, summary.budgetedMonths)
        assertEquals(3000.0, summary.budget, 0.001)
        assertEquals(2000.0, summary.remaining, 0.001)
    }

    @Test
    fun categoryBudgetsAreIgnored() {
        val summary = BudgetPeriodSummary.summarize(
            totalBudgets = listOf(
                total("2026-05", 3000.0),
                total("2026-05", 500.0).copy(categoryId = 7L, categoryName = "餐饮")
            ),
            monthSpend = mapOf("2026-05" to 100.0),
            currentYearMonth = "2026-12"
        )
        assertEquals(1, summary.budgetedMonths)
        assertEquals(3000.0, summary.budget, 0.001)
    }

    @Test
    fun noStartedBudgetsGiveEmptySummary() {
        val summary = BudgetPeriodSummary.summarize(
            totalBudgets = listOf(total("2027-01", 3000.0)),
            monthSpend = emptyMap(),
            currentYearMonth = "2026-10"
        )
        assertEquals(0, summary.budgetedMonths)
        assertEquals(0.0, summary.budget, 0.001)
    }

    @Test
    fun monthsCoverWholeYearWithTiming() {
        val months = BudgetPeriodSummary.months(
            year = 2026,
            totalBudgets = listOf(total("2026-03", 1000.0), total("2026-10", 1000.0), total("2026-11", 1000.0)),
            monthSpend = mapOf("2026-03" to 1200.0, "2026-05" to 80.0, "2026-10" to 400.0),
            currentYearMonth = "2026-10"
        )
        assertEquals(12, months.size)
        assertEquals(BudgetPeriodSummary.Timing.PAST, months[2].timing)
        assertTrue(months[2].isOver)
        assertEquals(null, months[4].budget)
        assertEquals(80.0, months[4].spent, 0.001)
        assertEquals(BudgetPeriodSummary.Timing.CURRENT, months[9].timing)
        assertFalse(months[9].isOver)
        assertEquals(BudgetPeriodSummary.Timing.FUTURE, months[10].timing)
        assertEquals(1000.0, months[10].budget!!, 0.001)
    }

    @Test
    fun mixedCurrenciesAreFlagged() {
        val single = BudgetPeriodSummary.summarize(
            listOf(total("2026-01", 1.0), total("2026-02", 1.0, currency = " ")),
            emptyMap(),
            "2026-12"
        )
        assertFalse(single.mixedCurrency)
        val mixed = BudgetPeriodSummary.summarize(
            listOf(total("2026-01", 1.0), total("2026-02", 1.0, currency = "USD")),
            emptyMap(),
            "2026-12"
        )
        assertTrue(mixed.mixedCurrency)
    }
}
