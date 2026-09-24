package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * Derives per-bill balances on an asset detail timeline by walking backward from the
 * asset's current balance through historical bills (newest first).
 */
object AssetBillBalanceHistory {

    fun computeBalanceAfterByBillId(
        bills: List<Bill>,
        assetId: Long,
        assetName: String,
        assetCurrency: String,
        currentBalance: Double
    ): Map<Long, Double> {
        var running = BillAssetImpactService.roundMoneyForCurrency(currentBalance, assetCurrency)
        val sorted = bills.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
        val result = LinkedHashMap<Long, Double>(sorted.size)
        for (bill in sorted) {
            val delta = signedBalanceDelta(bill, assetId, assetName, assetCurrency)
            if (delta == 0.0) continue
            if (bill.id <= 0L) continue
            result[bill.id] = running
            running = BillAssetImpactService.roundMoneyForCurrency(running - delta, assetCurrency)
        }
        return result
    }

    fun formatBalanceAfterLabel(balance: Double, currency: String): String {
        return "余额${CurrencyUtils.formatAmount(balance, currency)}"
    }

    fun signedBalanceDelta(
        bill: Bill,
        assetId: Long,
        assetName: String,
        assetCurrency: String
    ): Double {
        when {
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
                bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> return 0.0

            bill.subType == Bill.SUBTYPE_REFUND -> {
                if (!matchesSource(bill, assetId, assetName)) return 0.0
                return convert(bill.amount, bill, assetCurrency)
            }

            bill.type == Bill.TYPE_EXPENSE -> {
                if (!matchesSource(bill, assetId, assetName)) return 0.0
                return -convert(amountAtTransactionTime(bill), bill, assetCurrency)
            }

            bill.type == Bill.TYPE_INCOME -> {
                if (!matchesSource(bill, assetId, assetName)) return 0.0
                return convert(bill.amount, bill, assetCurrency)
            }

            bill.type == Bill.TYPE_TRANSFER -> {
                var delta = 0.0
                if (matchesSource(bill, assetId, assetName)) {
                    val principal = convert(bill.amount, bill, assetCurrency)
                    val fee = if (bill.fee > 0.0) {
                        convert(bill.fee, bill, assetCurrency)
                    } else {
                        0.0
                    }
                    delta -= principal + fee
                }
                if (matchesTarget(bill, assetId, assetName)) {
                    delta += BillAssetImpactService.targetDeltaInCurrency(bill, assetCurrency)
                }
                return delta
            }

            else -> return 0.0
        }
    }

    /**
     * Refunds reduce an expense bill's current [Bill.amount], but the asset timeline must
     * preserve the amount that actually left the account when the expense happened.
     */
    fun amountAtTransactionTime(bill: Bill): Double {
        return if (
            bill.type == Bill.TYPE_EXPENSE &&
            bill.subType != Bill.SUBTYPE_REFUND &&
            bill.originalAmount > 0.0
        ) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun convert(amount: Double, bill: Bill, toCurrency: String): Double {
        if (bill.currency.equals(toCurrency, ignoreCase = true)) return amount
        // P1-3: 支出/收入/退款优先记账时 exchangeRate（bill.currency→CNY），避免历史时间线随实时汇率漂移
        if (
            bill.type != Bill.TYPE_TRANSFER &&
            bill.exchangeRate > 0.0 &&
            toCurrency.equals("CNY", ignoreCase = true)
        ) {
            return BillAssetImpactService.roundMoneyForCurrency(amount * bill.exchangeRate, toCurrency)
        }
        return BillAssetImpactService.convertAmountBetweenCurrencies(amount, bill.currency, toCurrency)
    }

    fun matchesSource(bill: Bill, assetId: Long, assetName: String): Boolean {
        if (bill.accountId == assetId) return true
        return bill.accountId == null && assetName.isNotEmpty() && bill.accountName == assetName
    }

    fun matchesTarget(bill: Bill, assetId: Long, assetName: String): Boolean {
        if (bill.toAccountId == assetId) return true
        return bill.toAccountId == null && assetName.isNotEmpty() && bill.toAccountName == assetName
    }
}
