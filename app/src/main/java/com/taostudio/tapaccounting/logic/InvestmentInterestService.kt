package com.taostudio.tapaccounting.logic

import androidx.room.withTransaction
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object InvestmentInterestService {
    const val CATEGORY_NAME = "理财产品"
    const val CATEGORY_ICON = "http://res3.qianjiapp.com/cateic_licai.png"
    private const val BALANCE_EPSILON = 0.000001
    private const val MAX_OCCURRENCES = 50_000
    private val mathContext = MathContext.DECIMAL128
    private val daysInYear = BigDecimal("365")
    private val oneHundred = BigDecimal("100")

    data class InvestmentSchedule(
        val startEarningAt: Long,
        val firstPayoutAt: Long,
        val annualInterestRate: Double = 0.0,
        val settlementCycle: Int = InvestmentLot.CYCLE_DAILY
    )

    internal data class SettlementProjection(
        val remainingPrincipal: Double,
        val interestCarry: Double,
        val postedInterest: Double,
        val lastSettledAt: Long,
        val nextPayoutAt: Long?,
        val settledPeriods: Int
    )

    suspend fun ensureInvestmentCategories(db: AppDatabase) {
        ensureCategory(db, Bill.TYPE_EXPENSE)
        ensureCategory(db, Bill.TYPE_INCOME)
    }

    /** 在同一事务中保存转账及其本金批次，避免只保存成功一半。 */
    suspend fun insertTransferWithLot(
        db: AppDatabase,
        bill: Bill,
        targetAsset: Asset,
        schedule: InvestmentSchedule
    ): Bill = db.withTransaction {
        val savedBill = BillMutationService.insertBillWithinActiveTransaction(
            db = db,
            bill = bill,
            applyAssetImpact = true
        )
        createOrReplaceLotForTransfer(db, savedBill, targetAsset, schedule)
        savedBill
    }

    suspend fun createOrReplaceLotForTransfer(
        db: AppDatabase,
        bill: Bill,
        targetAsset: Asset,
        schedule: InvestmentSchedule
    ) {
        if (bill.type != Bill.TYPE_TRANSFER || bill.id <= 0L) return
        val principal = BillAssetImpactService.roundMoneyForCurrency(
            bill.amount * bill.exchangeRate,
            targetAsset.currency
        )
        if (principal <= 0.0) return
        val existing = db.investmentLotDao().getLotBySourceBillId(bill.id)
        val lot = buildLot(
            id = existing?.id ?: 0L,
            asset = targetAsset,
            sourceBillId = bill.id,
            principal = principal,
            schedule = schedule,
            createTime = existing?.createTime ?: System.currentTimeMillis()
        )
        db.investmentLotDao().insertLot(lot)
    }

    suspend fun createLotForAssetBalance(
        db: AppDatabase,
        asset: Asset,
        schedule: InvestmentSchedule
    ) {
        val principal = BillAssetImpactService.roundMoneyForCurrency(asset.balance, asset.currency)
        if (principal <= 0.0) return
        db.investmentLotDao().insertLot(
            buildLot(
                asset = asset,
                sourceBillId = null,
                principal = principal,
                schedule = schedule
            )
        )
    }

    private fun buildLot(
        id: Long = 0L,
        asset: Asset,
        sourceBillId: Long?,
        principal: Double,
        schedule: InvestmentSchedule,
        createTime: Long = System.currentTimeMillis()
    ): InvestmentLot {
        require(isValidAnnualRate(schedule.annualInterestRate)) { "Invalid annual interest rate" }
        val start = startOfDay(schedule.startEarningAt)
        val cycle = normalizeCycle(schedule.settlementCycle)
        val requestedPayout = startOfDay(schedule.firstPayoutAt)
        val firstPayout = requestedPayout.takeIf { it > start } ?: firstPayoutFor(start, cycle)
        return InvestmentLot(
            id = id,
            assetId = asset.id,
            sourceBillId = sourceBillId,
            principalAmount = principal,
            remainingPrincipal = principal,
            currency = asset.currency,
            annualInterestRate = schedule.annualInterestRate,
            startEarningAt = start,
            firstPayoutAt = firstPayout,
            lastSettledAt = start,
            settlementCycle = cycle,
            settlementInterval = 1,
            interestCarry = 0.0,
            status = InvestmentLot.STATUS_ACTIVE,
            createTime = createTime
        )
    }

    suspend fun settleDueInterest(db: AppDatabase, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val lots = db.investmentLotDao().getOpenLots()
            if (lots.isEmpty()) return@withTransaction
            val todayStart = startOfDay(now)
            lots.forEach { lot ->
                val asset = db.assetDao().getAssetById(lot.assetId) ?: return@forEach
                if (asset.assetCategory != Asset.CATEGORY_INVESTMENT || lot.annualInterestRate == 0.0) {
                    return@forEach
                }
                settleLotInterest(db, asset, lot, todayStart)
            }
        }
    }

    /**
     * 用户修改收益配置时，旧配置只累计到今天，新配置从今天开始生效。
     * 暂停期间不补算收益；再次启用时会从启用当天重新开始计息。
     */
    suspend fun updateLotSchedule(
        db: AppDatabase,
        lotId: Long,
        annualInterestRate: Double,
        settlementCycle: Int,
        active: Boolean,
        effectiveAt: Long = System.currentTimeMillis()
    ) {
        require(isValidAnnualRate(annualInterestRate)) { "Invalid annual interest rate" }
        db.withTransaction {
            var lot = db.investmentLotDao().getLotById(lotId) ?: return@withTransaction
            val today = startOfDay(effectiveAt)
            if (lot.status == InvestmentLot.STATUS_ACTIVE && lot.annualInterestRate != 0.0) {
                val asset = db.assetDao().getAssetById(lot.assetId) ?: return@withTransaction
                settleLotInterest(db, asset, lot, today)
                lot = db.investmentLotDao().getLotById(lotId) ?: return@withTransaction
            }

            val carryAtChange = if (lot.status == InvestmentLot.STATUS_ACTIVE) {
                accrueUnpostedCarryThrough(lot, today)
            } else {
                lot.interestCarry
            }
            val cycle = normalizeCycle(settlementCycle)
            db.investmentLotDao().updateLot(
                lot.copy(
                    annualInterestRate = annualInterestRate,
                    settlementCycle = cycle,
                    settlementInterval = lot.settlementInterval.coerceAtLeast(1),
                    startEarningAt = today,
                    firstPayoutAt = firstPayoutFor(today, cycle),
                    lastSettledAt = today,
                    interestCarry = if (annualInterestRate == 0.0) 0.0 else carryAtChange,
                    status = if (active) InvestmentLot.STATUS_ACTIVE else InvestmentLot.STATUS_PAUSED
                )
            )
        }
    }

    /** 账单被编辑后，按 sourceBillId 精确更新对应批次，而不是误减最早的一笔。 */
    suspend fun syncLotAfterBillReplacement(db: AppDatabase, oldBill: Bill, newBill: Bill) {
        val existing = oldBill.id.takeIf { it > 0L }
            ?.let { db.investmentLotDao().getLotBySourceBillId(it) }
        val target = if (newBill.type == Bill.TYPE_TRANSFER) {
            newBill.toAccountId?.let { db.assetDao().getAssetById(it) }
                ?: newBill.toAccountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        } else {
            null
        }

        if (target?.assetCategory != Asset.CATEGORY_INVESTMENT) {
            existing?.let { db.investmentLotDao().deleteBySourceBillId(oldBill.id) }
            return
        }

        val newPrincipal = BillAssetImpactService.roundMoneyForCurrency(
            newBill.amount * newBill.exchangeRate,
            target.currency
        )
        if (newPrincipal <= 0.0) {
            db.investmentLotDao().deleteBySourceBillId(oldBill.id)
            return
        }

        if (existing != null && existing.assetId == target.id && existing.currency == target.currency) {
            val principalDelta = newPrincipal - existing.principalAmount
            db.investmentLotDao().updateLot(
                existing.copy(
                    principalAmount = newPrincipal,
                    remainingPrincipal = BillAssetImpactService.roundMoneyForCurrency(
                        (existing.remainingPrincipal + principalDelta).coerceAtLeast(0.0),
                        target.currency
                    )
                )
            )
            return
        }

        val schedule = existing?.let {
            InvestmentSchedule(
                startEarningAt = it.startEarningAt,
                firstPayoutAt = it.firstPayoutAt,
                annualInterestRate = it.annualInterestRate,
                settlementCycle = it.settlementCycle
            )
        } ?: defaultScheduleForBalanceChange(newBill.time, target.annualInterestRate)
        db.investmentLotDao().deleteBySourceBillId(oldBill.id)
        createOrReplaceLotForTransfer(db, newBill, target, schedule)
    }

    /** 删除/恢复估算收益时同步修正它所属的本金批次，保持批次合计与资产余额一致。 */
    suspend fun applyEstimateBillLotImpact(db: AppDatabase, bill: Bill, restoring: Boolean) {
        if (bill.subType != Bill.SUBTYPE_INVESTMENT_ESTIMATE) return
        val lotId = Regex("批次 #(\\d+)").find(bill.remark)
            ?.groupValues?.getOrNull(1)
            ?.toLongOrNull()
            ?: return
        val lot = db.investmentLotDao().getLotById(lotId) ?: return
        val appliedSign = if (bill.type == Bill.TYPE_INCOME) 1.0 else -1.0
        val direction = if (restoring) 1.0 else -1.0
        val updatedPrincipal = BillAssetImpactService.roundMoneyForCurrency(
            (lot.remainingPrincipal + bill.amount * appliedSign * direction).coerceAtLeast(0.0),
            lot.currency
        )
        db.investmentLotDao().updateLot(
            lot.copy(
                remainingPrincipal = updatedPrincipal,
                status = if (updatedPrincipal <= BALANCE_EPSILON) {
                    InvestmentLot.STATUS_CLOSED
                } else if (lot.status == InvestmentLot.STATUS_CLOSED) {
                    InvestmentLot.STATUS_PAUSED
                } else {
                    lot.status
                }
            )
        )
    }

    suspend fun reconcileAssetLotsToBalance(
        db: AppDatabase,
        asset: Asset,
        changedAt: Long = System.currentTimeMillis()
    ) {
        if (asset.id <= 0L || asset.assetCategory != Asset.CATEGORY_INVESTMENT) return

        var openLots = db.investmentLotDao().getOpenLotsByAssetId(asset.id)
        openLots = openLots.map { lot ->
            if (lot.currency == asset.currency) return@map lot
            lot.copy(
                principalAmount = BillAssetImpactService.convertAmountBetweenCurrencies(
                    lot.principalAmount,
                    lot.currency,
                    asset.currency
                ),
                remainingPrincipal = BillAssetImpactService.convertAmountBetweenCurrencies(
                    lot.remainingPrincipal,
                    lot.currency,
                    asset.currency
                ),
                interestCarry = BillAssetImpactService.convertAmountBetweenCurrencies(
                    lot.interestCarry,
                    lot.currency,
                    asset.currency
                ),
                currency = asset.currency
            ).also { db.investmentLotDao().updateLot(it) }
        }

        val targetPrincipal = BillAssetImpactService.roundMoneyForCurrency(
            asset.balance.coerceAtLeast(0.0),
            asset.currency
        )
        val currentPrincipal = BillAssetImpactService.roundMoneyForCurrency(
            openLots.sumOf { it.remainingPrincipal },
            asset.currency
        )
        val delta = BillAssetImpactService.roundMoneyForCurrency(
            targetPrincipal - currentPrincipal,
            asset.currency
        )
        if (abs(delta) <= BALANCE_EPSILON) return

        if (delta > 0.0) {
            createLotForAssetBalance(
                db = db,
                asset = asset.copy(balance = delta),
                schedule = defaultScheduleForBalanceChange(changedAt, asset.annualInterestRate)
            )
            return
        }

        applyFifoPrincipalReduction(openLots, abs(delta), asset.currency).forEach { updatedLot ->
            db.investmentLotDao().updateLot(updatedLot)
        }
    }

    internal fun applyFifoPrincipalReduction(
        orderedLots: List<InvestmentLot>,
        reductionAmount: Double,
        currency: String = orderedLots.firstOrNull()?.currency ?: "CNY"
    ): List<InvestmentLot> {
        var reductionLeft = BillAssetImpactService.roundMoneyForCurrency(reductionAmount, currency)
        if (reductionLeft <= BALANCE_EPSILON) return emptyList()

        val updatedLots = mutableListOf<InvestmentLot>()
        orderedLots.forEach { lot ->
            if (reductionLeft <= BALANCE_EPSILON) return@forEach
            val reduced = minOf(lot.remainingPrincipal, reductionLeft)
            reductionLeft = BillAssetImpactService.roundMoneyForCurrency(reductionLeft - reduced, currency)
            val remaining = BillAssetImpactService.roundMoneyForCurrency(
                lot.remainingPrincipal - reduced,
                currency
            )
            updatedLots += lot.copy(
                remainingPrincipal = remaining,
                status = if (remaining <= BALANCE_EPSILON) InvestmentLot.STATUS_CLOSED else lot.status
            )
        }
        return updatedLots
    }

    /** 精确复利到最后统一舍入，避免每日把不足一分的收益永久丢掉。 */
    internal fun compoundDailyInterestTotal(
        initialPrincipal: Double,
        annualInterestRatePercent: Double,
        days: Int
    ): Double {
        if (days <= 0 || initialPrincipal <= 0.0) return 0.0
        val dailyRate = BigDecimal.valueOf(annualInterestRatePercent)
            .divide(oneHundred, mathContext)
            .divide(daysInYear, mathContext)
        var principal = BigDecimal.valueOf(initialPrincipal)
        val initial = principal
        repeat(days) {
            if (principal.signum() > 0) {
                val next = principal.add(principal.multiply(dailyRate, mathContext), mathContext)
                principal = if (next.signum() < 0) BigDecimal.ZERO else next
            }
        }
        return principal.subtract(initial).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    internal fun projectDueSettlement(lot: InvestmentLot, todayStart: Long): SettlementProjection {
        val today = startOfDay(todayStart)
        val start = startOfDay(lot.startEarningAt)
        var cursor = startOfDay(lot.lastSettledAt).coerceAtLeast(start)
        var principal = BigDecimal.valueOf(lot.remainingPrincipal)
        var carry = BigDecimal.valueOf(lot.interestCarry)
        var postedTotal = BigDecimal.ZERO
        val annualDailyRate = BigDecimal.valueOf(lot.annualInterestRate)
            .divide(oneHundred, mathContext)
            .divide(daysInYear, mathContext)
        val decimals = CurrencyUtils.decimalPlaces(lot.currency)
        val cycle = normalizeCycle(lot.settlementCycle)
        val interval = lot.settlementInterval.coerceAtLeast(1)
        val first = startOfDay(lot.firstPayoutAt).takeIf { it > start }
            ?: firstPayoutFor(start, cycle, interval)

        var occurrence = 0
        var due = payoutOccurrence(start, first, cycle, interval, occurrence)
        while (due <= cursor && occurrence < MAX_OCCURRENCES) {
            occurrence++
            due = payoutOccurrence(start, first, cycle, interval, occurrence)
        }

        var settledPeriods = 0
        while (due <= today && occurrence < MAX_OCCURRENCES && principal.signum() > 0) {
            // 防御：期次序列回跳时不回退 cursor，避免从历史日期重新逐日计息
            if (due > cursor) {
                val days = daysBetween(cursor, due)
                if (days > 0) {
                    val rawInterest = principal
                        .multiply(annualDailyRate, mathContext)
                        .multiply(BigDecimal.valueOf(days.toLong()), mathContext)
                        .add(carry, mathContext)
                    var posted = rawInterest.setScale(decimals, RoundingMode.HALF_UP)
                    if (principal.add(posted).signum() < 0) posted = principal.negate()
                    carry = rawInterest.subtract(posted, mathContext)
                    principal = principal.add(posted, mathContext)
                    postedTotal = postedTotal.add(posted, mathContext)
                }
                cursor = due
                settledPeriods++
            }
            occurrence++
            due = payoutOccurrence(start, first, cycle, interval, occurrence)
        }

        return SettlementProjection(
            remainingPrincipal = principal.setScale(decimals, RoundingMode.HALF_UP).toDouble(),
            interestCarry = carry.toDouble(),
            postedInterest = postedTotal.setScale(decimals, RoundingMode.HALF_UP).toDouble(),
            lastSettledAt = cursor,
            nextPayoutAt = due.takeIf { occurrence < MAX_OCCURRENCES },
            settledPeriods = settledPeriods
        )
    }

    fun nextPayoutAt(lot: InvestmentLot): Long? =
        projectDueSettlement(lot, startOfDay(lot.lastSettledAt)).nextPayoutAt

    fun firstPayoutFor(startEarningDay: Long, cycle: Int, interval: Int = 1): Long {
        val first = startOfDay(startEarningDay)
        return when (normalizeCycle(cycle)) {
            InvestmentLot.CYCLE_WEEKLY -> plusDays(first, 7 * interval.coerceAtLeast(1))
            InvestmentLot.CYCLE_MONTHLY -> plusMonths(first, interval.coerceAtLeast(1))
            InvestmentLot.CYCLE_QUARTERLY -> plusMonths(first, 3 * interval.coerceAtLeast(1))
            InvestmentLot.CYCLE_YEARLY -> plusYears(first, interval.coerceAtLeast(1))
            else -> plusDays(first, interval.coerceAtLeast(1))
        }
    }

    fun cycleLabel(cycle: Int): String = when (normalizeCycle(cycle)) {
        InvestmentLot.CYCLE_WEEKLY -> "每周"
        InvestmentLot.CYCLE_MONTHLY -> "每月"
        InvestmentLot.CYCLE_QUARTERLY -> "每季度"
        InvestmentLot.CYCLE_YEARLY -> "每年"
        else -> "每日"
    }

    fun cycleOptions(): List<Pair<String, Int>> = listOf(
        "每日" to InvestmentLot.CYCLE_DAILY,
        "每周" to InvestmentLot.CYCLE_WEEKLY,
        "每月" to InvestmentLot.CYCLE_MONTHLY,
        "每季度" to InvestmentLot.CYCLE_QUARTERLY,
        "每年" to InvestmentLot.CYCLE_YEARLY
    )

    fun isValidAnnualRate(rate: Double): Boolean = rate.isFinite() && rate > -100.0 && rate <= 10_000.0

    private fun normalizeCycle(cycle: Int): Int = cycle.takeIf {
        it in InvestmentLot.CYCLE_DAILY..InvestmentLot.CYCLE_YEARLY
    } ?: InvestmentLot.CYCLE_DAILY

    private fun defaultScheduleForBalanceChange(
        changedAt: Long,
        annualInterestRate: Double
    ): InvestmentSchedule {
        val start = plusDays(startOfDay(changedAt), 1)
        return InvestmentSchedule(
            startEarningAt = start,
            firstPayoutAt = firstPayoutFor(start, InvestmentLot.CYCLE_DAILY),
            annualInterestRate = annualInterestRate,
            settlementCycle = InvestmentLot.CYCLE_DAILY
        )
    }

    private suspend fun settleLotInterest(
        db: AppDatabase,
        asset: Asset,
        lot: InvestmentLot,
        todayStart: Long
    ) {
        val projection = projectDueSettlement(lot, todayStart)
        if (projection.settledPeriods <= 0) return

        val posted = projection.postedInterest
        if (posted != 0.0) {
            val category = ensureCategory(db, if (posted >= 0.0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE)
            val sourceBook = lot.sourceBillId
                ?.let { db.billDao().getBillById(it)?.bookName }
                ?.takeIf { it.isNotBlank() }
                ?: BookAccountManager.getDefaultBook(TapApplication.app())
            val bill = Bill(
                type = if (posted >= 0.0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE,
                subType = Bill.SUBTYPE_INVESTMENT_ESTIMATE,
                amount = abs(posted),
                originalAmount = abs(posted),
                currency = asset.currency,
                exchangeRate = runCatching {
                    BillAssetImpactService.estimateExchangeRateToCny(asset.currency)
                }.getOrDefault(1.0),
                categoryId = category.id,
                accountId = asset.id,
                accountName = asset.name,
                categoryName = CATEGORY_NAME,
                time = projection.lastSettledAt,
                remark = buildSettlementRemark(lot, projection),
                bookName = sourceBook,
                excludeFromStats = true
            )
            BillMutationService.insertLocalGeneratedBillWithinActiveTransaction(
                db = db,
                bill = bill,
                applyAssetImpact = true
            )
        }

        db.investmentLotDao().updateLot(
            lot.copy(
                remainingPrincipal = projection.remainingPrincipal,
                interestCarry = projection.interestCarry,
                lastSettledAt = projection.lastSettledAt,
                settlementInterval = lot.settlementInterval.coerceAtLeast(1)
            )
        )
        db.assetDao().updateInterestLastSettledAt(asset.id, projection.lastSettledAt)
    }

    private fun accrueUnpostedCarryThrough(lot: InvestmentLot, day: Long): Double {
        val from = startOfDay(lot.lastSettledAt).coerceAtLeast(startOfDay(lot.startEarningAt))
        val days = daysBetween(from, startOfDay(day))
        if (days <= 0 || lot.annualInterestRate == 0.0) return lot.interestCarry
        val rate = BigDecimal.valueOf(lot.annualInterestRate)
            .divide(oneHundred, mathContext)
            .divide(daysInYear, mathContext)
        return BigDecimal.valueOf(lot.interestCarry)
            .add(
                BigDecimal.valueOf(lot.remainingPrincipal)
                    .multiply(rate, mathContext)
                    .multiply(BigDecimal.valueOf(days.toLong()), mathContext),
                mathContext
            )
            .toDouble()
    }

    private fun buildSettlementRemark(lot: InvestmentLot, projection: SettlementProjection): String {
        val from = formatDate(lot.lastSettledAt)
        val to = formatDate(projection.lastSettledAt)
        return "批次 #${lot.id} · 固定年化 ${formatCompactDecimal(lot.annualInterestRate)}% ${cycleLabel(lot.settlementCycle)}自动结息" +
            "（$from 至 $to，共 ${projection.settledPeriods} 期）；本机估算值，以实际到账为准"
    }

    private suspend fun ensureCategory(db: AppDatabase, type: Int): Category {
        db.categoryDao().getCategoryByNameAndType(CATEGORY_NAME, type)?.let { return it }
        val maxOrder = db.categoryDao().getMaxSortOrder(type, null) ?: 0
        val category = Category(
            name = CATEGORY_NAME,
            type = type,
            iconId = CATEGORY_ICON,
            sortOrder = maxOrder + 10
        )
        return category.copy(id = db.categoryDao().insertCategory(category))
    }

    private fun payoutOccurrence(
        start: Long,
        first: Long,
        cycle: Int,
        interval: Int,
        occurrence: Int
    ): Long {
        if (occurrence <= 0) return startOfDay(first)
        // 从起息日锚定每一期，避免 1 月 31 日 -> 2 月 29 日后永久漂移到每月 29 日。
        return firstPayoutFor(
            startEarningDay = start,
            cycle = cycle,
            interval = interval.coerceAtLeast(1) * (occurrence + 1)
        )
    }

    fun startOfDay(timeMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun plusDays(dayStartMillis: Long, days: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = startOfDay(dayStartMillis)
            add(Calendar.DATE, days)
        }.timeInMillis
    }

    private fun plusMonths(dayStartMillis: Long, months: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = startOfDay(dayStartMillis)
            add(Calendar.MONTH, months)
        }.timeInMillis
    }

    private fun plusYears(dayStartMillis: Long, years: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = startOfDay(dayStartMillis)
            add(Calendar.YEAR, years)
        }.timeInMillis
    }

    fun daysBetween(startDayMillis: Long, endDayMillis: Long): Int {
        val start = startOfDay(startDayMillis)
        val end = startOfDay(endDayMillis)
        if (end <= start) return 0
        var count = 0
        var cursor = start
        while (cursor < end) {
            cursor = plusDays(cursor, 1)
            count++
        }
        return count
    }

    private fun formatCompactDecimal(value: Double): String {
        return String.format(Locale.getDefault(), "%.4f", value).trimEnd('0').trimEnd('.')
    }

    private fun formatDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))
    }
}
