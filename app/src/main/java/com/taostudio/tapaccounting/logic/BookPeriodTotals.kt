package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.entity.Bill

/** 某个时间段内一组账本的收支与笔数，账本抽屉和账本总览共用同一套口径。 */
data class BookPeriodTotals(
    val expense: Double = 0.0,
    val income: Double = 0.0,
    val count: Int = 0
) {
    companion object {
        fun of(bills: List<Bill>, books: Set<String>): BookPeriodTotals {
            val normalizedBooks = books.mapTo(hashSetOf(), BookAccountManager::normalizeBookName)
            var expense = 0.0
            var income = 0.0
            var count = 0
            bills.forEach { bill ->
                if (BookAccountManager.normalizeBookName(bill.bookName) !in normalizedBooks) return@forEach
                val amount = if (bill.currency == "CNY") bill.amount else bill.amount * bill.exchangeRate
                val contribution = BillStatsContribution.from(bill, amount)
                expense += contribution.expense
                income += contribution.income
                count++
            }
            return BookPeriodTotals(expense, income, count)
        }
    }
}
