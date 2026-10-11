package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BookPeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Test

class BookPeriodTotalsTest {

    private fun bill(
        book: String,
        type: Int = Bill.TYPE_EXPENSE,
        amount: Double,
        currency: String = "CNY",
        exchangeRate: Double = 1.0,
        excludeFromStats: Boolean = false
    ): Bill = Bill(
        type = type,
        amount = amount,
        time = 0L,
        currency = currency,
        exchangeRate = exchangeRate,
        bookName = book,
        excludeFromStats = excludeFromStats
    )

    @Test
    fun onlyBillsInTargetBooksAreCounted() {
        val totals = BookPeriodTotals.of(
            listOf(
                bill("伙食账本", amount = 20.0),
                bill("伙食账本", type = Bill.TYPE_INCOME, amount = 5.0),
                bill("日用账本", amount = 99.0)
            ),
            setOf("伙食账本")
        )
        assertEquals(20.0, totals.expense, 0.001)
        assertEquals(5.0, totals.income, 0.001)
        assertEquals(2, totals.count)
    }

    @Test
    fun blankBookNameBelongsToDefaultBook() {
        val totals = BookPeriodTotals.of(
            listOf(bill("", amount = 8.0)),
            setOf(BookAccountManager.DEFAULT_BOOK)
        )
        assertEquals(8.0, totals.expense, 0.001)
        assertEquals(1, totals.count)
    }

    @Test
    fun foreignCurrencyIsConvertedWithExchangeRate() {
        val totals = BookPeriodTotals.of(
            listOf(bill("旅行", amount = 10.0, currency = "USD", exchangeRate = 7.0)),
            setOf("旅行")
        )
        assertEquals(70.0, totals.expense, 0.001)
    }

    @Test
    fun excludedBillsAreCountedButNotSummed() {
        val totals = BookPeriodTotals.of(
            listOf(bill("伙食账本", amount = 30.0, excludeFromStats = true)),
            setOf("伙食账本")
        )
        assertEquals(0.0, totals.expense, 0.001)
        assertEquals(1, totals.count)
    }

    @Test
    fun emptyTargetSetYieldsZero() {
        val totals = BookPeriodTotals.of(listOf(bill("伙食账本", amount = 1.0)), emptySet())
        assertEquals(BookPeriodTotals(), totals)
    }
}
