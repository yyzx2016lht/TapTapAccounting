package com.taostudio.tapaccounting.logic.insight

import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillStatsContribution
import com.taostudio.tapaccounting.logic.StatsAmountResolver
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 统计页「消费洞察」的本地计算引擎，不依赖 LLM。
 *
 * 口径与统计页保持一致：金额由调用方的 [amountOf] 决定（含币种筛选），
 * 退款按 [BillStatsContribution] 冲减支出，分类由 [categoryOf] 归并为一级分类。
 *
 * 当月未过完时与「上月同期」（上月 1 日至今天对应日）比较，避免月初总是显示支出下降。
 */
object InsightEngine {

    private const val MAX_CARDS = 4
    private const val MIN_COMPARE_DAYS = 3
    private const val MIN_PROJECTION_DAYS = 5

    fun generate(
        currentBills: List<Bill>,
        previousBills: List<Bill>,
        monthStart: Long,
        now: Long = System.currentTimeMillis(),
        currencySymbol: String = "¥",
        amountOf: (Bill) -> Double = { StatsAmountResolver.resolve(it, null) },
        categoryOf: (Bill) -> String = { it.categoryName.ifBlank { "未分类" } },
        maxCards: Int = MAX_CARDS
    ): InsightReport {
        val period = Period.of(monthStart, now) ?: return InsightReport.EMPTY

        fun toExpenses(bills: List<Bill>, from: Long, untilExclusive: Long) = bills
            .filter { it.time >= from && it.time < untilExclusive }
            .mapNotNull { bill ->
                val expense = BillStatsContribution.from(bill, amountOf(bill)).expense
                if (expense == 0.0) null else Expense(bill, expense, categoryOf(bill))
            }

        val current = toExpenses(currentBills, period.start, period.endExclusive)
        if (current.sumOf { it.amount } <= 0.0) return InsightReport(emptyList(), period.label)
        val previous = toExpenses(previousBills, period.previousStart, period.previousCutoff)

        val ctx = Ctx(period, currencySymbol, now, current, previous)
        val headline = ctx.totalCard() ?: ctx.projectionCard()
        val others = listOfNotNull(
            ctx.categoryIncreaseCard(),
            ctx.categoryDecreaseCard(),
            ctx.largeExpenseCard(),
            ctx.concentrationCard(),
            ctx.weekendCard()
        ).sortedWith(compareBy<InsightCardModel> { severityRank(it.severity) }.thenByDescending { it.impact })

        return InsightReport(
            cards = (listOfNotNull(headline) + others).take(maxCards),
            comparisonLabel = period.label
        )
    }

    private fun severityRank(severity: InsightSeverity) = when (severity) {
        InsightSeverity.WARN -> 0
        InsightSeverity.POSITIVE -> 1
        InsightSeverity.INFO -> 2
    }

    private data class Expense(val bill: Bill, val amount: Double, val category: String)

    private class Period(
        val start: Long,
        val endExclusive: Long,
        val previousStart: Long,
        val previousCutoff: Long,
        val previousCompareDays: Int,
        val elapsedDays: Int,
        val totalDays: Int,
        val inProgress: Boolean
    ) {
        val previousName get() = if (inProgress) "上月同期" else "上月"
        val currentName get() = if (inProgress) "截至今天" else "本月"

        val label: String
            get() = when {
                !inProgress -> "对比上月"
                previousCompareDays <= 1 -> "对比上月 1 日"
                else -> "对比上月 1–$previousCompareDays 日"
            }

        companion object {
            fun of(monthStart: Long, now: Long): Period? {
                if (now < monthStart) return null
                val startCal = Calendar.getInstance().apply { timeInMillis = monthStart }
                val totalDays = startCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val endExclusive = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
                val inProgress = now < endExclusive
                val elapsedDays = if (inProgress) {
                    Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_MONTH)
                } else {
                    totalDays
                }
                val prevCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                val previousStart = prevCal.timeInMillis
                val previousCompareDays = if (inProgress) {
                    minOf(elapsedDays, prevCal.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                }
                val previousCutoff = (prevCal.clone() as Calendar)
                    .apply { add(Calendar.DAY_OF_MONTH, previousCompareDays) }
                    .timeInMillis
                return Period(
                    monthStart, endExclusive, previousStart, previousCutoff,
                    previousCompareDays, elapsedDays, totalDays, inProgress
                )
            }
        }
    }

    private class Ctx(
        val period: Period,
        val symbol: String,
        val now: Long,
        val current: List<Expense>,
        val previous: List<Expense>
    ) {
        val currentTotal = current.sumOf { it.amount }
        val previousTotal = previous.sumOf { it.amount }
        val currentByCategory = current.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
        val previousByCategory = previous.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
        val normalExpenses = current.filter { it.bill.type == Bill.TYPE_EXPENSE && it.amount > 0 }

        // ── 总支出 vs 上月（同期） ─────────────────────────────────────

        fun totalCard(): InsightCardModel? {
            if (previousTotal <= 0.0) return null
            if (period.inProgress && period.elapsedDays < MIN_COMPARE_DAYS) return null

            val diff = currentTotal - previousTotal
            val ratio = diff / previousTotal
            val flat = abs(ratio) < 0.05 || abs(diff) < 20.0
            val title = when {
                flat -> "支出与${period.previousName}基本持平"
                diff > 0 -> "比${period.previousName}多花 ${money(diff)}"
                else -> "比${period.previousName}少花 ${money(-diff)}"
            }
            val body = buildString {
                append("${period.currentName} ${money(currentTotal)}，${period.previousName} ${money(previousTotal)}")
                projection()?.let { append("；按此节奏月底约 ${money(it)}") }
            }
            val severity = when {
                flat -> InsightSeverity.INFO
                ratio >= 0.2 && diff >= 50.0 -> InsightSeverity.WARN
                diff < 0 -> InsightSeverity.POSITIVE
                else -> InsightSeverity.INFO
            }
            return InsightCardModel(
                id = "${InsightType.MONTH_TOTAL_DELTA}",
                type = InsightType.MONTH_TOTAL_DELTA,
                title = title,
                body = body,
                severity = severity,
                highlight = if (flat) null else percent(ratio),
                impact = abs(diff)
            )
        }

        /** 上月没有可比数据时，退而给出月底预估。 */
        fun projectionCard(): InsightCardModel? {
            val projected = projection() ?: return null
            return InsightCardModel(
                id = "${InsightType.MONTH_PROJECTION}",
                type = InsightType.MONTH_PROJECTION,
                title = "按此节奏，本月预计支出 ${money(projected)}",
                body = "截至今天已支出 ${money(currentTotal)}，上月暂无可对比的数据",
                severity = InsightSeverity.INFO,
                impact = projected
            )
        }

        /** 大额一次性支出（如房租）不按天外推，否则月初的预估会被放大数十倍。 */
        private fun projection(): Double? {
            if (!period.inProgress || period.elapsedDays < MIN_PROJECTION_DAYS) return null
            if (period.elapsedDays >= period.totalDays) return null
            val avgBill = normalExpenses.map { it.amount }.average().takeIf { !it.isNaN() } ?: return null
            val oneOff = normalExpenses.filter { it.amount >= 200.0 && it.amount >= avgBill * 3 }.sumOf { it.amount }
            val daily = (currentTotal - oneOff) / period.elapsedDays
            return (oneOff + daily * period.totalDays).coerceAtLeast(currentTotal)
        }

        // ── 分类变化 ────────────────────────────────────────────────

        private val minCategoryDiff get() = maxOf(50.0, currentTotal * 0.05)

        fun categoryIncreaseCard(): InsightCardModel? {
            if (previousTotal <= 0.0 || period.inProgress && period.elapsedDays < MIN_COMPARE_DAYS) return null
            val (category, cur, prev) = currentByCategory
                .map { (name, cur) -> Triple(name, cur, previousByCategory[name] ?: 0.0) }
                .filter { (_, cur, prev) -> cur - prev >= minCategoryDiff && (prev <= 0.0 || (cur - prev) / prev >= 0.3) }
                .maxByOrNull { (_, cur, prev) -> cur - prev } ?: return null
            val diff = cur - prev
            val isNew = prev <= 0.0
            return InsightCardModel(
                id = "${InsightType.MONTH_CATEGORY_DELTA}:$category:up",
                type = InsightType.MONTH_CATEGORY_DELTA,
                title = "${category}比${period.previousName}多花 ${money(diff)}",
                body = if (isNew) {
                    "${period.currentName} ${money(cur)}，${period.previousName}没有这类支出"
                } else {
                    "${period.currentName} ${money(cur)}，${period.previousName} ${money(prev)}"
                },
                severity = if (!isNew && diff / prev >= 0.5) InsightSeverity.WARN else InsightSeverity.INFO,
                highlight = if (isNew) "新增" else percent(diff / prev),
                action = InsightAction.OpenCategory(category),
                impact = diff
            )
        }

        fun categoryDecreaseCard(): InsightCardModel? {
            if (period.inProgress && period.elapsedDays < MIN_COMPARE_DAYS) return null
            val (category, cur, prev) = previousByCategory
                .map { (name, prev) -> Triple(name, (currentByCategory[name] ?: 0.0).coerceAtLeast(0.0), prev) }
                .filter { (_, cur, prev) -> prev > 0.0 && prev - cur >= minCategoryDiff && (prev - cur) / prev >= 0.3 }
                .maxByOrNull { (_, cur, prev) -> prev - cur } ?: return null
            val diff = prev - cur
            return InsightCardModel(
                id = "${InsightType.MONTH_CATEGORY_DELTA}:$category:down",
                type = InsightType.MONTH_CATEGORY_DELTA,
                title = "${category}比${period.previousName}少花 ${money(diff)}",
                body = if (cur <= 0.0) {
                    "${period.currentName}还没有这类支出，${period.previousName} ${money(prev)}"
                } else {
                    "${period.currentName} ${money(cur)}，${period.previousName} ${money(prev)}"
                },
                severity = InsightSeverity.POSITIVE,
                highlight = percent(-diff / prev),
                action = if (cur > 0.0) InsightAction.OpenCategory(category) else null,
                impact = diff
            )
        }

        // ── 单笔大额 ────────────────────────────────────────────────

        fun largeExpenseCard(): InsightCardModel? {
            if (normalExpenses.size < 5) return null
            val sorted = normalExpenses.sortedBy { it.amount }
            val median = sorted[sorted.size / 2].amount
            val top = sorted.last()
            if (top.amount < 100.0 || top.amount < median * 5 || top.amount < currentTotal * 0.15) return null

            val details = listOf(formatDay(top.bill.time), top.category, top.bill.remark.trim().take(12))
                .filter { it.isNotBlank() }
            return InsightCardModel(
                id = "${InsightType.LARGE_EXPENSE}:${top.bill.id}",
                type = InsightType.LARGE_EXPENSE,
                title = "最大一笔支出 ${money(top.amount)}",
                body = details.joinToString(" · "),
                severity = InsightSeverity.INFO,
                highlight = "占 ${(top.amount / currentTotal * 100).roundToInt()}%",
                action = InsightAction.OpenBill(top.bill.id),
                impact = top.amount
            )
        }

        // ── 分类集中度 ──────────────────────────────────────────────

        fun concentrationCard(): InsightCardModel? {
            if (current.size < 5 || currentTotal < 300.0) return null
            val positive = currentByCategory.filterValues { it > 0.0 }
            if (positive.size < 2) return null
            val (category, amount) = positive.maxByOrNull { it.value } ?: return null
            val ratio = amount / currentTotal
            if (ratio < 0.5) return null
            return InsightCardModel(
                id = "${InsightType.CATEGORY_CONCENTRATION}:$category",
                type = InsightType.CATEGORY_CONCENTRATION,
                title = "超过一半的钱花在了$category",
                body = "$category ${money(amount)}，本月共 ${money(currentTotal)}",
                severity = InsightSeverity.INFO,
                highlight = "${(ratio * 100).roundToInt()}%",
                action = InsightAction.OpenCategory(category),
                impact = amount
            )
        }

        // ── 周末 vs 工作日 ──────────────────────────────────────────

        fun weekendCard(): InsightCardModel? {
            val cal = Calendar.getInstance().apply { timeInMillis = period.start }
            var weekendDays = 0
            repeat(period.elapsedDays) {
                if (isWeekend(cal)) weekendDays++
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            val weekdayDays = period.elapsedDays - weekendDays
            if (weekendDays < 2 || weekdayDays < 3) return null

            var weekendSpend = 0.0
            var weekdaySpend = 0.0
            current.forEach {
                cal.timeInMillis = it.bill.time
                if (isWeekend(cal)) weekendSpend += it.amount else weekdaySpend += it.amount
            }
            val weekendAvg = weekendSpend / weekendDays
            val weekdayAvg = weekdaySpend / weekdayDays
            if (weekdayAvg <= 0.0 || weekendSpend < 100.0 || weekendAvg < weekdayAvg * 1.8) return null

            val times = String.format(Locale.getDefault(), "%.1f", weekendAvg / weekdayAvg)
            return InsightCardModel(
                id = "${InsightType.WEEKEND_SPEND}",
                type = InsightType.WEEKEND_SPEND,
                title = "周末日均花费是工作日的 $times 倍",
                body = "周末日均 ${money(weekendAvg)}，工作日日均 ${money(weekdayAvg)}",
                severity = InsightSeverity.INFO,
                impact = weekendSpend
            )
        }

        // ── helpers ────────────────────────────────────────────────

        private fun isWeekend(cal: Calendar): Boolean {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        }

        private fun money(amount: Double): String =
            symbol + AmountFormatHelper.formatCompact(abs(amount).roundToLong().toDouble())

        private fun percent(ratio: Double): String {
            val sign = if (ratio > 0) "+" else "-"
            return "$sign${abs(ratio * 100).roundToInt()}%"
        }

        private fun formatDay(time: Long): String {
            val target = Calendar.getInstance().apply { timeInMillis = time }
            val today = Calendar.getInstance().apply { timeInMillis = now }
            fun sameDay(a: Calendar, b: Calendar) =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (sameDay(target, today)) return "今天"
            today.add(Calendar.DAY_OF_YEAR, -1)
            if (sameDay(target, today)) return "昨天"
            return "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}
