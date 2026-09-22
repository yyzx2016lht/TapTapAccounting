package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill

object BillAssetImpactService {

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun logFull(tag: String, message: String) {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return
        Logger.d(ctx, tag, message)
    }

    private fun logAssetDelta(asset: Asset, delta: Double, action: String, billId: Long) {
        val before = asset.balance
        val after = before + delta
        logFull("ASSET_IMPACT", "AUDIT_ASSET|action=$action|billId=$billId|assetId=${asset.id}|asset=${asset.name}|delta=$delta|before=$before|after=$after")
    }

    suspend fun applyBillBalanceImpact(db: AppDatabase, bill: Bill): Int {
        return try {
            applyBillBalanceImpactInternal(db, bill)
        } catch (e: MissingCurrencyRateException) {
            Logger.d("BillAssetImpact", "汇率缺失，跳过余额更新: ${e.missingCurrencies}, billId=${bill.id}")
            0
        }
    }

    private suspend fun applyBillBalanceImpactInternal(db: AppDatabase, bill: Bill): Int {
        var impactedAssets = 0
        when {
            // 兼容旧平账记录：旧 subtype 不再生成，但历史数据删除/恢复时需防止重复影响余额
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> return 0
            bill.type == Bill.TYPE_EXPENSE -> {
                val asset = resolveSourceAsset(db, bill) ?: run {
                    logFull("BILL_GUARD", "（警告）apply_expense 未找到资产，billId=${bill.id}, asset=${bill.accountName}")
                    return 0
                }
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = expenseDeltaInAssetCurrency(baseOriginalAmount(bill), bill, asset)
                logAssetDelta(asset, -sourceDelta, "apply_expense", bill.id)
                db.assetDao().addBalanceDelta(asset.id, -sourceDelta)
                syncInvestmentPrincipalAfterExternalImpact(db, asset, bill)
                impactedAssets += 1
            }
            bill.type == Bill.TYPE_INCOME -> {
                val asset = resolveSourceAsset(db, bill) ?: run {
                    logFull("BILL_GUARD", "（警告）apply_income 未找到资产，billId=${bill.id}, asset=${bill.accountName}")
                    return 0
                }
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency)
                logAssetDelta(asset, sourceDelta, "apply_income", bill.id)
                db.assetDao().addBalanceDelta(asset.id, sourceDelta)
                syncInvestmentPrincipalAfterExternalImpact(db, asset, bill)
                impactedAssets += 1
            }
            bill.type == Bill.TYPE_TRANSFER -> {
                val sourceAsset = resolveSourceAsset(db, bill)
                val targetAsset = resolveTargetAsset(db, bill)
                ensureRatesForImpact(bill, sourceAsset = sourceAsset, targetAsset = targetAsset)

                if (sourceAsset != null) {
                    val sourceDelta = sourceDeltaInCurrency(bill, sourceAsset.currency)
                    logAssetDelta(sourceAsset, -sourceDelta, "apply_transfer_source", bill.id)
                    db.assetDao().addBalanceDelta(sourceAsset.id, -sourceDelta)
                    syncInvestmentPrincipalAfterExternalImpact(db, sourceAsset, bill)
                    impactedAssets += 1
                }

                if (targetAsset != null) {
                    val targetDelta = targetDeltaInCurrency(bill, targetAsset.currency)
                    logAssetDelta(targetAsset, targetDelta, "apply_transfer_target", bill.id)
                    db.assetDao().addBalanceDelta(targetAsset.id, targetDelta)
                    impactedAssets += 1
                }
                if (sourceAsset == null && targetAsset == null) {
                    logFull(
                        "BILL_GUARD",
                        "（警告）apply_transfer 未找到任一资产，billId=${bill.id}, from=${bill.accountName}, to=${bill.toAccountName}"
                    )
                }
            }
        }
        return impactedAssets
    }

    suspend fun revertBillBalanceImpact(db: AppDatabase, bill: Bill): Int {
        return try {
            revertBillBalanceImpactInternal(db, bill)
        } catch (e: MissingCurrencyRateException) {
            Logger.d("BillAssetImpact", "汇率缺失，跳过余额回滚: ${e.missingCurrencies}, billId=${bill.id}")
            0
        }
    }

    private suspend fun revertBillBalanceImpactInternal(db: AppDatabase, bill: Bill): Int {
        var impactedAssets = 0
        when {
            // 兼容旧平账记录：旧 subtype 的 revert 逻辑保持原样，防止错误回滚余额
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> {
                val asset = resolveSourceAsset(db, bill) ?: run {
                    logFull("BILL_GUARD", "（警告）revert_adjust 未找到资产，billId=${bill.id}, asset=${bill.accountName}")
                    return 0
                }
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val delta = convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency)
                if (bill.type == Bill.TYPE_INCOME) {
                    logAssetDelta(asset, -delta, "revert_adjust_income", bill.id)
                    db.assetDao().addBalanceDelta(asset.id, -delta)
                } else {
                    logAssetDelta(asset, delta, "revert_adjust_other", bill.id)
                    db.assetDao().addBalanceDelta(asset.id, delta)
                }
                impactedAssets += 1
            }
            bill.type == Bill.TYPE_EXPENSE -> {
                val asset = resolveSourceAsset(db, bill) ?: run {
                    logFull("BILL_GUARD", "（警告）revert_expense 未找到资产，billId=${bill.id}, asset=${bill.accountName}")
                    return 0
                }
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = expenseDeltaInAssetCurrency(baseOriginalAmount(bill), bill, asset)
                logAssetDelta(asset, sourceDelta, "revert_expense", bill.id)
                db.assetDao().addBalanceDelta(asset.id, sourceDelta)
                syncInvestmentPrincipalAfterExternalImpact(db, asset, bill)
                impactedAssets += 1
            }
            bill.type == Bill.TYPE_INCOME -> {
                val asset = resolveSourceAsset(db, bill) ?: run {
                    logFull("BILL_GUARD", "（警告）revert_income 未找到资产，billId=${bill.id}, asset=${bill.accountName}")
                    return 0
                }
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency)
                logAssetDelta(asset, -sourceDelta, "revert_income", bill.id)
                db.assetDao().addBalanceDelta(asset.id, -sourceDelta)
                syncInvestmentPrincipalAfterExternalImpact(db, asset, bill)
                impactedAssets += 1
            }
            bill.type == Bill.TYPE_TRANSFER -> {
                val sourceAsset = resolveSourceAsset(db, bill)
                val targetAsset = resolveTargetAsset(db, bill)
                ensureRatesForImpact(bill, sourceAsset = sourceAsset, targetAsset = targetAsset)

                if (sourceAsset != null) {
                    val sourceDelta = sourceDeltaInCurrency(bill, sourceAsset.currency)
                    logAssetDelta(sourceAsset, sourceDelta, "revert_transfer_source", bill.id)
                    db.assetDao().addBalanceDelta(sourceAsset.id, sourceDelta)
                    syncInvestmentPrincipalAfterExternalImpact(db, sourceAsset, bill)
                    impactedAssets += 1
                }

                if (targetAsset != null) {
                    val targetDelta = -targetDeltaInCurrency(bill, targetAsset.currency)
                    logAssetDelta(targetAsset, targetDelta, "revert_transfer_target", bill.id)
                    db.assetDao().addBalanceDelta(targetAsset.id, targetDelta)
                    impactedAssets += 1
                }
                if (sourceAsset == null && targetAsset == null) {
                    logFull(
                        "BILL_GUARD",
                        "（警告）revert_transfer 未找到任一资产，billId=${bill.id}, from=${bill.accountName}, to=${bill.toAccountName}"
                    )
                }
            }
        }
        return impactedAssets
    }

    fun convertAmountBetweenCurrencies(amount: Double, fromCurrency: String, toCurrency: String): Double {
        return MoneyConversionService.convertAmountBetweenCurrencies(
            amount = amount,
            fromCurrency = fromCurrency,
            toCurrency = toCurrency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun estimateExchangeRateToTarget(amount: Double, sourceCurrency: String, targetCurrency: String): Double {
        return MoneyConversionService.estimateExchangeRateToTarget(
            amount = amount,
            sourceCurrency = sourceCurrency,
            targetCurrency = targetCurrency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun estimateExchangeRateToCny(currency: String): Double {
        return MoneyConversionService.estimateExchangeRateToCny(
            currency = currency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun roundMoney(amount: Double): Double {
        return MoneyConversionService.roundMoney(amount)
    }

    fun roundMoneyForCurrency(amount: Double, currencyCode: String): Double {
        return MoneyConversionService.roundMoneyForCurrency(amount, currencyCode)
    }

    fun roundRate(rate: Double): Double {
        return MoneyConversionService.roundRate(rate)
    }

    suspend fun ensureRatesForImpact(
        bill: Bill,
        sourceAsset: Asset?,
        targetAsset: Asset?
    ) {
        val requiredCurrencies = linkedSetOf<String>()
        requiredCurrencies.add(bill.currency)
        sourceAsset?.currency?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        if (bill.type == Bill.TYPE_TRANSFER) {
            targetAsset?.currency?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        }
        MoneyConversionService.requireCurrenciesAvailable(
            currencies = requiredCurrencies,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    private suspend fun resolveSourceAsset(db: AppDatabase, bill: Bill): Asset? {
        return resolveAssetByReference(db, bill.accountId, bill.accountName)
    }

    private suspend fun resolveTargetAsset(db: AppDatabase, bill: Bill): Asset? {
        return resolveAssetByReference(db, bill.toAccountId, bill.toAccountName)
    }

    private suspend fun resolveAssetByReference(
        db: AppDatabase,
        assetId: Long?,
        assetName: String
    ): Asset? {
        assetId?.let { db.assetDao().getAssetById(it) }?.let { return it }
        if (assetName.isBlank()) return null

        db.assetDao().getAssetByName(assetName)?.let { return it }

        val target = normalizeAssetName(assetName)
        if (target.isBlank()) return null

        return db.assetDao().getAllAssetsList()
            .firstOrNull { asset ->
                val candidate = normalizeAssetName(asset.name)
                candidate.isNotBlank() && candidate == target
            }
    }

    private fun normalizeAssetName(name: String): String {
        return name
            .trim()
            .lowercase()
            .replace("银行卡", "")
            .replace("信用卡", "")
            .replace("银行", "")
            .replace("账户", "")
            .replace("账本", "")
            .replace("卡", "")
            .replace("\\s+".toRegex(), "")
    }

    private suspend fun syncInvestmentPrincipalAfterExternalImpact(
        db: AppDatabase,
        assetBeforeImpact: Asset,
        bill: Bill
    ) {
        if (assetBeforeImpact.assetCategory != Asset.CATEGORY_INVESTMENT) return
        if (bill.subType == Bill.SUBTYPE_INVESTMENT_ESTIMATE ||
            (bill.categoryName == InvestmentInterestService.CATEGORY_NAME && bill.remark.contains("自动结息"))
        ) return

        val latestAsset = db.assetDao().getAssetById(assetBeforeImpact.id) ?: return
        InvestmentInterestService.reconcileAssetLotsToBalance(
            db = db,
            asset = latestAsset,
            changedAt = bill.time
        )
    }

    /**
     * 支出扣减折算到资产币种。
     * 账户为 CNY 且账单币种不同时，优先用记账时写入的 exchangeRate（可能是用户确认汇率）；
     * 其余情况仍走市场汇率表。
     */
    private fun expenseDeltaInAssetCurrency(amount: Double, bill: Bill, asset: Asset): Double {
        if (bill.currency == asset.currency) return amount
        if (asset.currency.equals("CNY", ignoreCase = true) && bill.exchangeRate > 0.0) {
            return amount * bill.exchangeRate
        }
        return convertAmountBetweenCurrencies(amount, bill.currency, asset.currency)
    }

    /**
     * 转账目标端入账金额（目标资产币种）。
     *
     * 转账的 [Bill.exchangeRate] 语义是 `bill.currency → targetCurrency`（表单/AI 确认汇率），
     * 与支出/收入的「→CNY」语义不同。目标端必须按目标货币舍入，且不能忽略 [targetCurrency]。
     */
    fun targetDeltaInCurrency(bill: Bill, targetCurrency: String): Double {
        if (bill.currency.equals(targetCurrency, ignoreCase = true)) {
            return roundMoneyForCurrency(bill.amount, targetCurrency)
        }
        // 优先采用记账时确认的源→目标汇率，并按目标货币舍入。
        if (bill.exchangeRate > 0.0) {
            return roundMoneyForCurrency(bill.amount * bill.exchangeRate, targetCurrency)
        }
        // 汇率缺失时走市场汇率换算（与源端对称），禁止把源币金额直接记入目标账户。
        return convertAmountBetweenCurrencies(bill.amount, bill.currency, targetCurrency)
    }

    private fun sourceDeltaInCurrency(bill: Bill, sourceCurrency: String): Double {
        val principal = convertAmountBetweenCurrencies(bill.amount, bill.currency, sourceCurrency)
        val fee = if (bill.fee > 0.0) {
            convertAmountBetweenCurrencies(bill.fee, bill.currency, sourceCurrency)
        } else {
            0.0
        }
        return principal + fee
    }
}
