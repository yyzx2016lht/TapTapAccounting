package com.taostudio.tapaccounting.logic.insight

import com.taostudio.tapaccounting.data.local.entity.Bill
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 本地洞察计算引擎。
 * 输入账单列表，输出排序后的洞察卡片。
 * 所有数字来自本地计算，不依赖 LLM。
 */
object InsightEngine {

    private const val MAX_HOME_CARDS = 2
    private const val MAX_STATS_CARDS = 4

    /**
     * 生成洞察卡片列表。
     * @param currentBills 当前月账单（含 excludeFromStats）
     * @param previousBills 上月账单
     * @param maxCards 最大卡片数
     * @return 排序后的洞察卡片
     */
    fun generate(
        currentBills: List<Bill>,
        previousBills: List<Bill>,
        maxCards: Int = MAX_STATS_CARDS
    ): List<InsightCardModel> {
        if (currentBills.isEmpty()) return emptyList()

        val currentExpenses = currentBills.filter {
            it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND && !it.excludeFromStats
        }
        val previousExpenses = previousBills.filter {
            it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND && !it.excludeFromStats
        }

        if (currentExpenses.isEmpty()) return emptyList()

        val candidates = mutableListOf<InsightCardModel>()

        // 1. 本月总支出环比
        buildTotalDeltaInsight(currentExpenses, previousExpenses)?.let { candidates.add(it) }

        // 2. 分类环比
        candidates.addAll(buildCategoryDeltaInsights(currentExpenses, previousExpenses))

        // 3. 大额消费
        candidates.addAll(buildLargeExpenseInsights(currentExpenses))

        // 4. 周末支出占比
        buildWeekendSpendInsight(currentExpenses)?.let { candidates.add(it) }

        // 5. 周期扣费提示
        buildRecurringHintInsight(currentExpenses)?.let { candidates.add(it) }

        // 6. 支出集中度
        buildCategoryConcentrationInsight(currentExpenses)?.let { candidates.add(it) }

        // 排序：WARN > POSITIVE > INFO，同级按影响金额降序
        val severityOrder = mapOf(
            InsightSeverity.WARN to 0,
            InsightSeverity.POSITIVE to 1,
            InsightSeverity.INFO to 2
        )
        return candidates
            .sortedWith(compareBy<InsightCardModel> { severityOrder[it.severity] ?: 9 }
                .thenByDescending { it.payload["amount"]?.toDoubleOrNull() ?: 0.0 })
            .take(maxCards)
    }

    /**
     * 生成首页洞察（最多 2 张）
     */
    fun generateForHome(
        currentBills: List<Bill>,
        previousBills: List<Bill>
    ): List<InsightCardModel> = generate(currentBills, previousBills, MAX_HOME_CARDS)

    /**
     * 生成统计页洞察（最多 4 张）
     */
    fun generateForStats(
        currentBills: List<Bill>,
        previousBills: List<Bill>
    ): List<InsightCardModel> = generate(currentBills, previousBills, MAX_STATS_CARDS)

    // ── MONTH_TOTAL_DELTA ────────────────────────────────────────────────

    private fun buildTotalDeltaInsight(
        current: List<Bill>,
        previous: List<Bill>
    ): InsightCardModel? {
        if (current.size < 3 || previous.size < 3) return null

        val currentAmount = current.sumOf { amountInCny(it) }
        val previousAmount = previous.sumOf { amountInCny(it) }
        if (previousAmount <= 0) return null

        val delta = (currentAmount - previousAmount) / previousAmount
        val deltaAmount = currentAmount - previousAmount
        if (abs(delta) < 0.15 || abs(deltaAmount) < 50.0) return null

        val percent = abs(delta * 100).roundToInt()
        val isIncrease = delta > 0
        val title = if (isIncrease) "本月支出上升" else "本月支出下降"
        val body = if (isIncrease) {
            "比上月多 ${percent}%（¥${formatAmount(currentAmount)} vs ¥${formatAmount(previousAmount)}）"
        } else {
            "比上月少 ${percent}%，少花 ¥${formatAmount(-deltaAmount)}"
        }
        return InsightCardModel(
            id = stableId(InsightType.MONTH_TOTAL_DELTA, if (isIncrease) "up" else "down"),
            type = InsightType.MONTH_TOTAL_DELTA,
            title = title,
            body = body,
            severity = if (isIncrease && delta >= 0.35) InsightSeverity.WARN else if (!isIncrease) InsightSeverity.POSITIVE else InsightSeverity.INFO,
            payload = mapOf(
                "amount" to formatAmount(abs(deltaAmount)),
                "currentAmount" to formatAmount(currentAmount),
                "previousAmount" to formatAmount(previousAmount),
                "percent" to "${percent}%"
            ),
            action = InsightAction(type = InsightActionType.OPEN_STATS)
        )
    }

    // ── MONTH_CATEGORY_DELTA ──────────────────────────────────────────────

    private fun buildCategoryDeltaInsights(
        current: List<Bill>,
        previous: List<Bill>
    ): List<InsightCardModel> {
        val results = mutableListOf<InsightCardModel>()

        val currentByCategory = current.groupBy { it.categoryName }
            .mapValues { (_, bills) -> bills.sumOf { amountInCny(it) } }
        val previousByCategory = previous.groupBy { it.categoryName }
            .mapValues { (_, bills) -> bills.sumOf { amountInCny(it) } }

        for ((category, currentAmount) in currentByCategory) {
            if (category.isBlank()) continue
            val previousAmount = previousByCategory[category] ?: continue
            if (previousAmount <= 0) continue

            val delta = (currentAmount - previousAmount) / previousAmount
            val deltaAmount = currentAmount - previousAmount
            if (abs(delta) > 0.25 && abs(deltaAmount) >= 30.0) {
                val isIncrease = delta > 0
                val percentStr = "${abs(delta * 100).roundToInt()}%"
                val title = if (isIncrease) "${category}支出增长" else "${category}支出减少"
                val body = if (isIncrease) {
                    "本月比上月高 $percentStr（¥${formatAmount(currentAmount)} vs ¥${formatAmount(previousAmount)}）"
                } else {
                    "本月比上月低 $percentStr，少花 ¥${formatAmount(-deltaAmount)}"
                }
                results.add(
                    InsightCardModel(
                        id = stableId(InsightType.MONTH_CATEGORY_DELTA, category, if (isIncrease) "up" else "down"),
                        type = InsightType.MONTH_CATEGORY_DELTA,
                        title = title,
                        body = body,
                        severity = if (isIncrease && delta > 0.5) InsightSeverity.WARN else if (!isIncrease) InsightSeverity.POSITIVE else InsightSeverity.INFO,
                        payload = mapOf(
                            "category" to category,
                            "amount" to formatAmount(abs(deltaAmount)),
                            "percent" to percentStr
                        ),
                        action = InsightAction(
                            type = InsightActionType.OPEN_CATEGORY,
                            payload = mapOf("categoryName" to category)
                        )
                    )
                )
            }
        }

        return results
    }

    // ── LARGE_EXPENSE ─────────────────────────────────────────────────────

    private fun buildLargeExpenseInsights(expenses: List<Bill>): List<InsightCardModel> {
        if (expenses.size < 3) return emptyList()

        val results = mutableListOf<InsightCardModel>()
        val amounts = expenses.map { amountInCny(it) }.sorted()
        val p90Index = (amounts.size * 0.9).toInt().coerceIn(0, amounts.size - 1)
        val p90 = amounts[p90Index]
        val avg = amounts.average()
        val threshold = maxOf(p90, avg * 2.5, 100.0)

        // 找当前数据范围末尾 7 天内的大额消费，支持查看历史月份。
        val anchorTime = expenses.maxOf { it.time }
        val sevenDaysAgo = anchorTime - 7L * 24 * 60 * 60 * 1000
        val recentLarge = expenses
            .filter { amountInCny(it) >= threshold && it.time >= sevenDaysAgo }
            .sortedByDescending { it.time }

        for (bill in recentLarge.take(1)) { // 最多 1 条
            val calendar = Calendar.getInstance().apply { timeInMillis = bill.time }
            val dayStr = when {
                isToday(calendar) -> "今天"
                isYesterday(calendar) -> "昨天"
                else -> "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
            }
            results.add(
                InsightCardModel(
                    id = stableId(InsightType.LARGE_EXPENSE, bill.id.toString()),
                    type = InsightType.LARGE_EXPENSE,
                    title = "大额消费提醒",
                    body = "${dayStr}有一笔 ¥${formatAmount(bill.amount)} 的${bill.categoryName.ifBlank { "消费" }}支出",
                    severity = InsightSeverity.WARN,
                    payload = mapOf(
                        "amount" to formatAmount(bill.amount),
                        "category" to bill.categoryName,
                        "billId" to bill.id.toString()
                    )
                )
            )
        }

        return results
    }

    // ── WEEKEND_SPEND ─────────────────────────────────────────────────────

    private fun buildWeekendSpendInsight(expenses: List<Bill>): InsightCardModel? {
        if (expenses.size < 8) return null

        val calendar = Calendar.getInstance()
        var weekendAmount = 0.0
        var totalAmount = 0.0

        for (bill in expenses) {
            val cny = amountInCny(bill)
            totalAmount += cny
            calendar.timeInMillis = bill.time
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                weekendAmount += cny
            }
        }

        if (totalAmount <= 0) return null
        val ratio = weekendAmount / totalAmount

        return if (ratio > 0.6 && weekendAmount >= 100.0) {
            InsightCardModel(
                id = stableId(InsightType.WEEKEND_SPEND),
                type = InsightType.WEEKEND_SPEND,
                title = "周末消费较高",
                body = "周末支出占本月 ${(ratio * 100).roundToInt()}%（¥${formatAmount(weekendAmount)}）",
                severity = InsightSeverity.INFO,
                payload = mapOf(
                    "ratio" to "${(ratio * 100).roundToInt()}%",
                    "amount" to formatAmount(weekendAmount)
                )
            )
        } else null
    }

    // ── RECURRING_HINT ────────────────────────────────────────────────────

    private fun buildRecurringHintInsight(expenses: List<Bill>): InsightCardModel? {
        if (expenses.size < 3) return null

        // 按 remark + category 归一化分组
        data class GroupKey(val normalizedRemark: String, val category: String)

        fun normalize(text: String): String =
            text.trim().lowercase().replace("\\s+".toRegex(), "")

        val groups = expenses.filter { it.remark.isNotBlank() }
            .groupBy { GroupKey(normalize(it.remark), it.categoryName) }

        var bestCandidate: InsightCardModel? = null

        for ((key, bills) in groups) {
            if (bills.size < 3) continue

            // 金额波动 ±15%
            val amounts = bills.map { amountInCny(it) }
            val median = amounts.sorted()[amounts.size / 2]
            val toleranceOk = amounts.all { abs(it - median) / median <= 0.15 }
            if (!toleranceOk) continue

            // 间隔检查
            val times = bills.map { it.time }.sorted()
            val intervals = (1 until times.size).map { (times[it] - times[it - 1]) / (24.0 * 3600_000) }
            if (intervals.isEmpty()) continue
            val avgInterval = intervals.average()

            val frequency = when {
                avgInterval in 6.0..8.0 -> "每周"
                avgInterval in 27.0..33.0 -> "每月"
                avgInterval in 358.0..372.0 -> "每年"
                else -> continue
            }

            bestCandidate = InsightCardModel(
                id = stableId(InsightType.RECURRING_HINT, key.normalizedRemark),
                type = InsightType.RECURRING_HINT,
                title = "疑似周期扣费",
                body = "检测到 ${bills.size} 笔相近金额的${frequency}扣费（${key.normalizedRemark}）",
                severity = InsightSeverity.INFO,
                payload = mapOf(
                    "count" to bills.size.toString(),
                    "amount" to formatAmount(median),
                    "frequency" to frequency
                )
            )
            break // 只取第一个
        }

        return bestCandidate
    }

    // ── CATEGORY_CONCENTRATION ───────────────────────────────────────────

    private fun buildCategoryConcentrationInsight(expenses: List<Bill>): InsightCardModel? {
        if (expenses.size < 5) return null
        val total = expenses.sumOf { amountInCny(it) }
        if (total < 300.0) return null

        val top = expenses
            .groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, bills) -> bills.sumOf { amountInCny(it) } }
            .maxByOrNull { it.value } ?: return null
        val ratio = top.value / total
        if (ratio < 0.45 || top.value < 200.0) return null

        return InsightCardModel(
            id = stableId(InsightType.CATEGORY_CONCENTRATION, top.key),
            type = InsightType.CATEGORY_CONCENTRATION,
            title = "${top.key}占比较高",
            body = "本月${top.key}占总支出 ${(ratio * 100).roundToInt()}%（¥${formatAmount(top.value)} / ¥${formatAmount(total)}）",
            severity = if (ratio >= 0.65) InsightSeverity.WARN else InsightSeverity.INFO,
            payload = mapOf(
                "category" to top.key,
                "amount" to formatAmount(top.value),
                "ratio" to "${(ratio * 100).roundToInt()}%"
            ),
            action = InsightAction(
                type = InsightActionType.OPEN_CATEGORY,
                payload = mapOf("categoryName" to top.key)
            )
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun stableId(type: InsightType, vararg parts: String): String =
        (listOf(type.name) + parts).joinToString(":")

    fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
        }
    }

    /** P1-6: 多币种账单折算到 CNY 再汇总，优先记账时汇率。 */
    private fun amountInCny(bill: Bill): Double {
        if (bill.currency.isBlank() || bill.currency.equals("CNY", ignoreCase = true)) {
            return bill.amount
        }
        if (bill.exchangeRate > 0.0) {
            return bill.amount * bill.exchangeRate
        }
        return com.taostudio.tapaccounting.logic.BillAssetImpactService
            .convertAmountBetweenCurrencies(bill.amount, bill.currency, "CNY")
    }

    private fun isToday(cal: Calendar): Boolean {
        val now = Calendar.getInstance()
        return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(cal: Calendar): Boolean {
        val now = Calendar.getInstance()
        now.add(Calendar.DAY_OF_YEAR, -1)
        return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }
}
