package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2：转账统计不能把「源→目标」汇率当「→CNY」。
 */
class StatsAmountResolverTest {

    @Test
    fun expense_usesFrozenToCnyRate() {
        val bill = expense(amount = 100.0, currency = "USD", exchangeRate = 7.14)
        val amount = StatsAmountResolver.resolve(bill, selectedCurrency = null)
        assertEquals(714.0, amount, 0.0001)
    }

    @Test
    fun transfer_doesNotTreatSourceToTargetRateAsToCny() {
        // 100 USD → EUR，确认汇率 0.92（源→目标）
        // 若误用 amount*exchangeRate 会得到 92「CNY」；正确应按 USD→CNY 市场价折算
        val bill = transfer(amount = 100.0, currency = "USD", exchangeRate = 0.92)
        val amount = StatsAmountResolver.resolve(
            bill,
            selectedCurrency = null,
            convertToCny = { amt, cur ->
                // 模拟 USD→CNY：1 USD = 7 CNY
                if (cur.equals("USD", true)) amt * 7.0 else amt
            }
        )
        assertEquals(700.0, amount, 0.0001)
    }

    @Test
    fun transfer_sameCurrencyCny_usesFaceAmount() {
        val bill = transfer(amount = 100.0, currency = "CNY", exchangeRate = 1.0)
        val amount = StatsAmountResolver.resolve(
            bill,
            selectedCurrency = null,
            convertToCny = { amt, _ -> amt }
        )
        assertEquals(100.0, amount, 0.0001)
    }

    @Test
    fun selectedCurrencyFilter_keepsOriginalAmount() {
        val bill = transfer(amount = 100.0, currency = "USD", exchangeRate = 0.92)
        val amount = StatsAmountResolver.resolve(bill, selectedCurrency = "USD")
        assertEquals(100.0, amount, 0.0001)
    }

    @Test
    fun transfer_missingRateFallsBackToFaceAmount() {
        val bill = transfer(amount = 50.0, currency = "XYZ", exchangeRate = 0.5)
        val amount = StatsAmountResolver.resolve(
            bill,
            selectedCurrency = null,
            convertToCny = { _, _ -> throw MissingCurrencyRateException(setOf("XYZ")) }
        )
        assertEquals(50.0, amount, 0.0001)
    }

    @Test
    fun repayment_alsoSeparatesFromToCnySemantics() {
        val bill = Bill(
            id = 3L,
            type = Bill.TYPE_TRANSFER,
            subType = Bill.SUBTYPE_REPAYMENT,
            amount = 100.0,
            currency = "USD",
            exchangeRate = 0.92,
            time = 1L
        )
        val amount = StatsAmountResolver.resolve(
            bill,
            selectedCurrency = null,
            convertToCny = { amt, cur -> if (cur.equals("USD", true)) amt * 7.0 else amt }
        )
        assertEquals(700.0, amount, 0.0001)
    }

    private fun expense(amount: Double, currency: String, exchangeRate: Double) = Bill(
        id = 1L,
        type = Bill.TYPE_EXPENSE,
        amount = amount,
        currency = currency,
        exchangeRate = exchangeRate,
        time = 1L
    )

    private fun transfer(amount: Double, currency: String, exchangeRate: Double) = Bill(
        id = 2L,
        type = Bill.TYPE_TRANSFER,
        amount = amount,
        currency = currency,
        exchangeRate = exchangeRate,
        time = 1L
    )
}
