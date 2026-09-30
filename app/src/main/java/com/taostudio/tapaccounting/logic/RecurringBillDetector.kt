package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import java.util.Calendar
import kotlin.math.abs

/**
 * 周期账单检测器。
 * 从已有账单中识别"疑似周期支出"。
 */
object RecurringBillDetector {

    private const val MIN_CONFIDENCE = 0.72
    private const val WEEKLY_ANCHOR_TOLERANCE_DAYS = 1
    private const val MONTHLY_ANCHOR_TOLERANCE_DAYS = 3
    private const val YEARLY_ANCHOR_TOLERANCE_DAYS = 7

    data class RecurringCandidate(
        val merchantKey: String,
        val categoryId: Long?,
        val categoryName: String?,
        val accountName: String?,
        val bookName: String,
        val medianAmount: Double,
        val tolerance: Double,
        val frequency: RecurringFrequency,
        val dayOfMonthHint: Int?,
        val lastSeenAt: Long,
        val confidence: Double,
        val billCount: Int
    )

    /**
     * 从近 12 个月支出账单中检测周期模式。
     * @return 最多 5 个候选，按 confidence 降序
     */
    fun detect(
        bills: List<Bill>,
        amountTolerance: Double = 1.0
    ): List<RecurringCandidate> {
        val expenses = bills.filter {
            it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND
        }

        // 按归一化名称 + 分类 + 资产 + 账本分组，避免不同账户的相似扣费互相串扰。
        data class GroupKey(
            val normalizedRemark: String,
            val category: String,
            val account: String?,
            val bookName: String
        )

        val groups = expenses.filter { it.remark.isNotBlank() }
            .groupBy {
                GroupKey(
                    normalizedRemark = RecurringBillingService.normalizeMerchantKey(it.remark),
                    category = it.categoryName,
                    account = it.accountName,
                    bookName = it.bookName
                )
            }

        val candidates = mutableListOf<RecurringCandidate>()

        for ((key, groupBills) in groups) {
            if (groupBills.size < 3) continue

            // 金额检查：波动在用户设置的绝对金额范围内
            val amounts = groupBills.map { it.amount }.sorted()
            val median = amounts[amounts.size / 2]
            val tolerance = amountTolerance.coerceAtLeast(0.0)
            val toleranceOk = amounts.all { abs(it - median) <= tolerance }
            if (!toleranceOk) continue

            // 间隔检查：周期账单应当每一段都接近固定周期，而不是只让平均值碰巧接近。
            val times = groupBills.map { it.time }.sorted()
            val intervals = (1 until times.size).map {
                (times[it] - times[it - 1]) / (24.0 * 3600_000)
            }
            if (intervals.isEmpty()) continue

            val frequency = when {
                intervals.all { it in 6.0..8.0 } -> RecurringFrequency.WEEKLY
                intervals.all { it in 27.0..33.0 } -> RecurringFrequency.MONTHLY
                intervals.all { it in 358.0..372.0 } -> RecurringFrequency.YEARLY
                else -> continue
            }
            if (!hasStableDateAnchor(times, frequency)) continue

            // 计算置信度
            val countScore = (groupBills.size / 5.0).coerceAtMost(1.0)
            val avgInterval = intervals.average()
            val intervalVariance = intervals.map { abs(it - avgInterval) }.average()
            val intervalScore = (1.0 - intervalVariance / avgInterval).coerceIn(0.0, 1.0)
            val confidence = (countScore * 0.4 + intervalScore * 0.6)
            if (confidence < MIN_CONFIDENCE) continue

            // 提取扣款日提示
            val dayOfMonthHint = if (frequency == RecurringFrequency.MONTHLY) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = times.last()
                cal.get(Calendar.DAY_OF_MONTH)
            } else null

            candidates.add(
                RecurringCandidate(
                    merchantKey = key.normalizedRemark,
                    categoryId = groupBills.first().categoryId,
                    categoryName = key.category,
                    accountName = key.account,
                    bookName = key.bookName,
                    medianAmount = median,
                    tolerance = tolerance,
                    frequency = frequency,
                    dayOfMonthHint = dayOfMonthHint,
                    lastSeenAt = times.last(),
                    confidence = confidence,
                    billCount = groupBills.size
                )
            )
        }

        return candidates.sortedByDescending { it.confidence }.take(5)
    }

    /**
     * 将候选转换为 RecurringPattern 实体。
     */
    fun toPattern(candidate: RecurringCandidate): RecurringPattern {
        val now = System.currentTimeMillis()
        val nextExpected = RecurringBillingService.calculateNextExpected(candidate.lastSeenAt, candidate.frequency, candidate.dayOfMonthHint)

        return RecurringPattern(
            merchantKey = candidate.merchantKey,
            categoryId = candidate.categoryId,
            categoryName = candidate.categoryName,
            accountName = candidate.accountName,
            bookName = candidate.bookName,
            amountApprox = candidate.medianAmount,
            amountTolerance = candidate.tolerance,
            frequency = candidate.frequency,
            dayOfMonthHint = candidate.dayOfMonthHint,
            lastSeenAt = candidate.lastSeenAt,
            nextExpectedAt = nextExpected,
            status = RecurringStatus.SUGGESTED,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun hasStableDateAnchor(times: List<Long>, frequency: RecurringFrequency): Boolean {
        val anchors = times.map { time ->
            Calendar.getInstance().apply { timeInMillis = time }
        }
        return when (frequency) {
            RecurringFrequency.WEEKLY -> {
                val weekdays = anchors.map { it.get(Calendar.DAY_OF_WEEK) }
                circularSpread(weekdays, 7) <= WEEKLY_ANCHOR_TOLERANCE_DAYS
            }
            RecurringFrequency.MONTHLY -> {
                val days = anchors.map { it.get(Calendar.DAY_OF_MONTH) }
                circularSpread(days, 31) <= MONTHLY_ANCHOR_TOLERANCE_DAYS
            }
            RecurringFrequency.YEARLY -> {
                val days = anchors.map { it.get(Calendar.DAY_OF_YEAR) }
                circularSpread(days, 366) <= YEARLY_ANCHOR_TOLERANCE_DAYS
            }
        }
    }

    private fun circularSpread(values: List<Int>, cycleSize: Int): Int {
        if (values.size <= 1) return 0
        val sorted = values.sorted()
        val largestGap = sorted.zipWithNext { a, b -> b - a }
            .plus(sorted.first() + cycleSize - sorted.last())
            .maxOrNull() ?: 0
        return cycleSize - largestGap
    }

}
