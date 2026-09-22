package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-1：跨币种转账目标端金额必须按目标币种舍入，且不能把错误币种金额写入目标账户。
 *
 * 转账 exchangeRate 语义：bill.currency → targetCurrency（源→目标）。
 * 支出/收入 exchangeRate 语义：bill.currency → CNY。
 */
class BillAssetImpactServiceTransferTest {

    @Test
    fun targetDelta_sameCurrency_roundsToTargetDecimals() {
        val bill = transferBill(amount = 10.005, currency = "USD", exchangeRate = 1.0)
        val delta = BillAssetImpactService.targetDeltaInCurrency(bill, "USD")
        assertEquals(10.01, delta, 0.0)
    }

    @Test
    fun targetDelta_jpyZeroDecimal_roundsHalfUp() {
        // JPY 0 位小数：1234.6 → 1235
        val bill = transferBill(amount = 100.0, currency = "CNY", exchangeRate = 12.346)
        val delta = BillAssetImpactService.targetDeltaInCurrency(bill, "JPY")
        assertEquals(1235.0, delta, 0.0)
    }

    @Test
    fun targetDelta_honorsConfirmedSourceToTargetRate() {
        // 用户确认 100 USD = 92 EUR（exchangeRate = 0.92）
        val bill = transferBill(amount = 100.0, currency = "USD", exchangeRate = 0.92)
        val delta = BillAssetImpactService.targetDeltaInCurrency(bill, "EUR")
        assertEquals(92.0, delta, 0.0)
    }

    @Test
    fun targetDelta_missingRate_convertsViaMarketAndRounds() {
        // exchangeRate = 0 时不得把源币金额直接记入目标账户，应走 convertAmountBetweenCurrencies
        val bill = transferBill(amount = 100.0, currency = "USD", exchangeRate = 0.0)
        val delta = BillAssetImpactService.targetDeltaInCurrency(bill, "CNY")
        // DEFAULT_RATES: USD=0.14 → 100/0.14 ≈ 714.2857 → 714.29
        assertEquals(714.29, delta, 0.0)
    }

    @Test
    fun transferConservation_sourceAndTarget_useCurrencyAwareAmounts() {
        // 100 USD → EUR，确认汇率 0.92，手续费 2 USD
        val bill = transferBill(amount = 100.0, currency = "USD", exchangeRate = 0.92, fee = 2.0)
        val sourceOut = sourceDelta(bill, "USD")
        val targetIn = BillAssetImpactService.targetDeltaInCurrency(bill, "EUR")

        // 源端：本金+手续费同币种（未换算时等于 amount+fee）
        assertEquals(102.0, sourceOut, 0.0)
        // 目标端：必须是 EUR 金额，不是 CNY 也不是 USD
        assertEquals(92.0, targetIn, 0.0)
        // 舍入后金额必须落在目标币种精度上
        assertEquals(targetIn, BillAssetImpactService.roundMoneyForCurrency(targetIn, "EUR"), 0.0)
    }

    @Test
    fun signedBalanceDelta_transferTarget_matchesTargetDelta() {
        val bill = transferBill(amount = 50.0, currency = "USD", exchangeRate = 2.0)
        val delta = AssetBillBalanceHistory.signedBalanceDelta(
            bill = bill,
            assetId = 2L,
            assetName = "EUR卡",
            assetCurrency = "EUR"
        )
        assertEquals(100.0, delta, 0.0)
    }

    @Test
    fun signedBalanceDelta_transferSource_includesFeeInSourceCurrency() {
        val bill = transferBill(amount = 50.0, currency = "USD", exchangeRate = 2.0, fee = 1.5)
        val delta = AssetBillBalanceHistory.signedBalanceDelta(
            bill = bill,
            assetId = 1L,
            assetName = "USD卡",
            assetCurrency = "USD"
        )
        assertEquals(-51.5, delta, 0.0)
    }

    private fun transferBill(
        amount: Double,
        currency: String,
        exchangeRate: Double,
        fee: Double = 0.0
    ): Bill {
        return Bill(
            id = 10L,
            type = Bill.TYPE_TRANSFER,
            amount = amount,
            currency = currency,
            exchangeRate = exchangeRate,
            fee = fee,
            accountId = 1L,
            toAccountId = 2L,
            accountName = "USD卡",
            toAccountName = "EUR卡",
            time = 1L
        )
    }

    /** 与 BillAssetImpactService.sourceDeltaInCurrency 对齐的测试侧副本（同币种时 amount+fee）。 */
    private fun sourceDelta(bill: Bill, sourceCurrency: String): Double {
        val principal = BillAssetImpactService.convertAmountBetweenCurrencies(
            bill.amount, bill.currency, sourceCurrency
        )
        val fee = if (bill.fee > 0.0) {
            BillAssetImpactService.convertAmountBetweenCurrencies(bill.fee, bill.currency, sourceCurrency)
        } else {
            0.0
        }
        return principal + fee
    }
}
