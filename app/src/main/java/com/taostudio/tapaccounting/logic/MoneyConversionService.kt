package com.taostudio.tapaccounting.logic

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object MoneyConversionService {

    private fun normalizeCurrency(code: String): String = code.trim().uppercase(Locale.ROOT)

    fun missingCurrencies(
        currencies: Collection<String>,
        rateProvider: (String) -> Double?
    ): Set<String> {
        return currencies
            .map(::normalizeCurrency)
            .filter { it.isNotBlank() && it != "CNY" }
            .filter { code ->
                val rate = rateProvider(code)
                rate == null || rate == 0.0
            }
            .toSet()
    }

    fun requireCurrenciesAvailable(
        currencies: Collection<String>,
        rateProvider: (String) -> Double?
    ) {
        val missing = missingCurrencies(currencies, rateProvider)
        if (missing.isNotEmpty()) {
            throw MissingCurrencyRateException(missing)
        }
    }

    fun convertAmountBetweenCurrencies(
        amount: Double,
        fromCurrency: String,
        toCurrency: String,
        rateProvider: (String) -> Double?
    ): Double {
        val from = normalizeCurrency(fromCurrency)
        val to = normalizeCurrency(toCurrency)
        if (from == to) return amount

        requireCurrenciesAvailable(listOf(from, to), rateProvider)

        val amountCny = if (from == "CNY") {
            amount
        } else {
            val fromRate = rateProvider(from) ?: error("Rate unexpectedly missing for $from")
            if (fromRate == 0.0) throw MissingCurrencyRateException(setOf(from))
            amount / fromRate
        }

        val target = if (to == "CNY") {
            amountCny
        } else {
            val toRate = rateProvider(to) ?: error("Rate unexpectedly missing for $to")
            amountCny * toRate
        }
        return roundMoneyForCurrency(target, to)
    }

    fun estimateExchangeRateToTarget(
        amount: Double,
        sourceCurrency: String,
        targetCurrency: String,
        rateProvider: (String) -> Double?
    ): Double {
        if (amount == 0.0) return 1.0
        val converted = convertAmountBetweenCurrencies(amount, sourceCurrency, targetCurrency, rateProvider)
        return roundRate(converted / amount)
    }

    fun estimateExchangeRateToCny(
        currency: String,
        rateProvider: (String) -> Double?
    ): Double {
        val normalized = normalizeCurrency(currency)
        if (normalized == "CNY") return 1.0
        requireCurrenciesAvailable(listOf(normalized), rateProvider)
        val rateToCurrency = rateProvider(normalized) ?: return 1.0
        if (rateToCurrency == 0.0) throw MissingCurrencyRateException(setOf(normalized))
        return roundRate(1.0 / rateToCurrency)
    }

    fun roundMoney(amount: Double): Double {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    fun roundMoneyForCurrency(amount: Double, currencyCode: String): Double {
        val decimals = CurrencyUtils.decimalPlaces(currencyCode)
        return BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }

    fun roundRate(rate: Double): Double {
        return BigDecimal.valueOf(rate).setScale(6, RoundingMode.HALF_UP).toDouble()
    }
}

class MissingCurrencyRateException(val missingCurrencies: Set<String>) : IllegalStateException(
    "Missing currency rates: ${missingCurrencies.joinToString(",")}" )

