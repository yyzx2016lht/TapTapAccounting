package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3：汇率缺失必须失败中止，禁止静默跳过余额影响。
 */
class MissingCurrencyRateGuardTest {

    @Test
    fun missingCurrencies_treatsZeroRateAsMissing() {
        val missing = MoneyConversionService.missingCurrencies(
            currencies = listOf("CNY", "USD", "EUR"),
            rateProvider = { code ->
                when (code) {
                    "USD" -> 0.0 // 零汇率无效
                    "EUR" -> 0.13
                    else -> 1.0
                }
            }
        )
        assertEquals(setOf("USD"), missing)
    }

    @Test
    fun requireCurrenciesAvailable_throwsOnZeroRate() {
        val ex = runCatching {
            MoneyConversionService.requireCurrenciesAvailable(
                currencies = listOf("USD"),
                rateProvider = { 0.0 }
            )
        }.exceptionOrNull()
        assertTrue(ex is MissingCurrencyRateException)
    }

    @Test
    fun convertAmountBetweenCurrencies_throwsRatherThanSilentZero() {
        val ex = runCatching {
            MoneyConversionService.convertAmountBetweenCurrencies(
                amount = 100.0,
                fromCurrency = "USD",
                toCurrency = "CNY",
                rateProvider = { code -> if (code == "USD") 0.0 else null }
            )
        }.exceptionOrNull()
        assertTrue(ex is MissingCurrencyRateException)
    }

    @Test
    fun ensureRatesForImpact_throwsWhenTargetCurrencyRateMissing() {
        // 纯逻辑面：requireCurrenciesAvailable 覆盖转账目标币种
        val ex = runCatching {
            MoneyConversionService.requireCurrenciesAvailable(
                currencies = listOf("CNY", "USD", "XYZ"),
                rateProvider = { code -> if (code == "USD") 0.14 else if (code == "CNY") 1.0 else null }
            )
        }.exceptionOrNull()
        assertTrue(ex is MissingCurrencyRateException)
        assertEquals(setOf("XYZ"), (ex as MissingCurrencyRateException).missingCurrencies)
    }
}
