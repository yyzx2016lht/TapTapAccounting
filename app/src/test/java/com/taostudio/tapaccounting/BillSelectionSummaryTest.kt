package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillSelectionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSelectionSummaryTest {

    private fun bill(
        type: Int,
        amount: Double,
        currency: String = "CNY",
        subType: Int = Bill.SUBTYPE_NORMAL
    ): Bill = Bill(type = type, amount = amount, time = 0L, currency = currency, subType = subType)

    @Test
    fun emptySelectionIsAllZero() {
        val summary = BillSelectionSummary.summarize(emptyList())
        assertEquals(0.0, summary.expense.amount, 0.001)
        assertEquals(0.0, summary.income.amount, 0.001)
        assertEquals("CNY", summary.expense.currency)
        assertEquals(0, summary.otherCount)
    }

    @Test
    fun singleCurrencyExpensesAreSummed() {
        val summary = BillSelectionSummary.summarize(
            listOf(
                bill(Bill.TYPE_EXPENSE, 11.00),
                bill(Bill.TYPE_EXPENSE, 9.17),
                bill(Bill.TYPE_EXPENSE, 10.11)
            )
        )
        assertEquals(30.28, summary.expense.amount, 0.001)
        assertEquals(0.0, summary.income.amount, 0.001)
        assertEquals("CNY", summary.expense.currency)
        assertFalse(summary.expense.mixedCurrency)
    }

    @Test
    fun mixedExpenseAndIncomeAreGroupedByType() {
        val summary = BillSelectionSummary.summarize(
            listOf(
                bill(Bill.TYPE_EXPENSE, 20.0),
                bill(Bill.TYPE_INCOME, 5.5),
                bill(Bill.TYPE_EXPENSE, 2.0)
            )
        )
        assertEquals(22.0, summary.expense.amount, 0.001)
        assertEquals(5.5, summary.income.amount, 0.001)
    }

    @Test
    fun refundIncomeCountsAsIncome() {
        val summary = BillSelectionSummary.summarize(
            listOf(bill(Bill.TYPE_INCOME, 9.9, subType = Bill.SUBTYPE_REFUND))
        )
        assertEquals(9.9, summary.income.amount, 0.001)
    }

    @Test
    fun mixedCurrenciesAreFlaggedPerColumn() {
        val summary = BillSelectionSummary.summarize(
            listOf(
                bill(Bill.TYPE_EXPENSE, 10.0, currency = "CNY"),
                bill(Bill.TYPE_EXPENSE, 10.0, currency = "USD"),
                bill(Bill.TYPE_INCOME, 3.0, currency = "USD")
            )
        )
        assertTrue(summary.expense.mixedCurrency)
        assertFalse(summary.income.mixedCurrency)
        assertEquals("USD", summary.income.currency)
    }

    @Test
    fun transferAndRepaymentAreCountedNotSummed() {
        val summary = BillSelectionSummary.summarize(
            listOf(
                bill(Bill.TYPE_EXPENSE, 10.0),
                bill(Bill.TYPE_TRANSFER, 100.0),
                bill(Bill.TYPE_REPAYMENT, 50.0)
            )
        )
        assertEquals(10.0, summary.expense.amount, 0.001)
        assertEquals(0.0, summary.income.amount, 0.001)
        assertEquals(2, summary.otherCount)
    }

    @Test
    fun blankCurrencyFallsBackToCny() {
        val summary = BillSelectionSummary.summarize(
            listOf(bill(Bill.TYPE_EXPENSE, 10.0, currency = " "))
        )
        assertEquals("CNY", summary.expense.currency)
    }
}
