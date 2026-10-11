package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.dao.BudgetDao
import com.taostudio.tapaccounting.data.local.dao.CategoryDao
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max

/**
 * 预算计算服务。
 * 所有金额来自本地 Room/DAO 计算。
 */
class BudgetService(
    private val budgetDao: BudgetDao,
    private val billDao: BillDao,
    private val categoryDao: CategoryDao
) {
    sealed class BudgetScope {
        data class Book(val bookName: String) : BudgetScope()
        object AllBooks : BudgetScope()

        val daoBookName: String
            get() = when (this) {
                is Book -> bookName
                AllBooks -> ""
            }
    }

    /** 预算状态 */
    enum class BudgetStatus {
        NORMAL,   // 正常
        WARNING,  // 即将超支（>= alertThreshold）
        EXCEEDED  // 已超支
    }

    enum class BudgetPace {
        AHEAD, ON_TRACK, HAS_ROOM
    }

    enum class BudgetRiskReason {
        NONE,
        THRESHOLD_REACHED,
        SPENDING_FAST,
        EXCEEDED
    }

    /** 预算进度 */
    data class BudgetProgress(
        val budgetAmount: Double,
        val usedAmount: Double,
        val percent: Double,
        val remaining: Double,
        val status: BudgetStatus,
        val daysInMonth: Int,
        val elapsedDays: Int,
        val remainingDays: Int,
        val timeProgress: Double,
        val dailyRemainingAllowance: Double,
        val paceRatio: Double,
        val pace: BudgetPace,
        val riskReason: BudgetRiskReason,
        val riskScore: Double
    ) {
        /**
         * 已结束的月份（剩余天数为 0）。
         * 此时"每天可用"没有意义（dailyRemainingAllowance 恒为 0），
         * 各界面应改用"本月已结束"类文案，避免显示成"每天 ¥0"。
         */
        val isMonthClosed: Boolean get() = remainingDays <= 0
    }

    data class BudgetOverview(
        val budget: Budget,
        val progress: BudgetProgress
    )

    data class BudgetSuggestionPlan(
        val categoryId: Long?,
        val categoryName: String?,
        val conservativeAmount: Double,
        val normalAmount: Double,
        val looseAmount: Double,
        val historyAverage: Double,
        val activeMonths: Int,
        val reason: String
    )

    /**
     * 获取某分类在指定月份的支出金额。
     * 排除 excludeFromStats = true 的账单。
     */
    suspend fun getMonthSpend(
        bookName: String,
        categoryId: Long?,
        yearMonth: String
    ): Double {
        val (start, end) = parseYearMonthRange(yearMonth) ?: return 0.0
        return if (categoryId == null) {
            billDao.sumBudgetExpense(start, end, bookName)
        } else {
            billDao.sumBudgetExpenseByCategories(start, end, bookName, listOf(categoryId))
                .firstOrNull { it.categoryId == categoryId }
                ?.total
                ?: 0.0
        }
    }

    /**
     * 获取预算进度。
     */
    suspend fun getBudgetProgress(budget: Budget): BudgetProgress {
        val used = getMonthSpend(budget.bookName, budget.categoryId, budget.yearMonth)
        return buildProgress(budget, used)
    }

    /**
     * 根据过去 N 个月同分类月均支出，向上取整到 10 元。
     */
    suspend fun suggestBudgetFromHistory(
        bookName: String,
        categoryId: Long?,
        currentYearMonth: String,
        months: Int = 3
    ): Double? {
        return suggestBudgetPlanFromHistory(bookName, categoryId, null, currentYearMonth, months)
            ?.normalAmount
    }

    suspend fun suggestBudgetPlanFromHistory(
        bookName: String,
        categoryId: Long?,
        categoryName: String?,
        currentYearMonth: String,
        months: Int = 6
    ): BudgetSuggestionPlan? {
        val spends = collectHistorySpends(bookName, categoryId, currentYearMonth, months)
        if (spends.isEmpty()) return null
        val avg = spends.average()
        return buildSuggestionPlan(
            categoryId = categoryId,
            categoryName = categoryName,
            historyAverage = avg,
            activeMonths = spends.size,
            reason = "近 ${spends.size} 个有支出月份的月均支出"
        )
    }

    suspend fun suggestUnbudgetedCategoryPlans(
        bookName: String,
        currentYearMonth: String,
        months: Int = 6,
        limit: Int = 5
    ): List<BudgetSuggestionPlan> {
        val currentBudgets = budgetDao.getBudgetsByMonthAndBook(currentYearMonth, bookName)
        val budgetedCategoryIds = currentBudgets.mapNotNull { it.categoryId }.toSet()
        val categoryNames = CategoryRepository.displayNamesById(
            categoryDao.getCategoriesListByType(0)
        )
        val historyMonths = getPreviousMonths(currentYearMonth, months)
        val totals = linkedMapOf<Long, Pair<String?, MutableList<Double>>>()
        for (month in historyMonths) {
            val (start, end) = parseYearMonthRange(month) ?: continue
            billDao.sumBudgetExpenseByAllCategories(start, end, bookName).forEach { item ->
                val categoryId = item.categoryId ?: return@forEach
                if (categoryId in budgetedCategoryIds || item.total <= 0.0) return@forEach
                val entry = totals.getOrPut(categoryId) { item.categoryName to mutableListOf() }
                entry.second += item.total
            }
        }
        return totals.mapNotNull { (categoryId, pair) ->
            val spends = pair.second
            if (spends.isEmpty()) return@mapNotNull null
            buildSuggestionPlan(
                categoryId = categoryId,
                categoryName = categoryNames[categoryId] ?: pair.first,
                historyAverage = spends.average(),
                activeMonths = spends.size,
                reason = "未设置预算，但历史支出较高"
            )
        }.sortedByDescending { it.historyAverage }
            .take(limit)
    }

    suspend fun copyPreviousMonthBudgets(
        bookName: String,
        targetYearMonth: String
    ): Int {
        val previousMonth = getPreviousMonths(targetYearMonth, 1).firstOrNull() ?: return 0
        val source = budgetDao.getBudgetsByMonthAndBook(previousMonth, bookName)
        if (source.isEmpty()) return 0
        val existing = budgetDao.getBudgetsByMonthAndBook(targetYearMonth, bookName)
        val existingKeys = existing.map { it.categoryKey }.toSet()
        val now = System.currentTimeMillis()
        val missing = source.filterNot { it.categoryKey in existingKeys }
        missing
            .forEach { budget ->
                budgetDao.saveForSlot(
                    budget.copy(
                        id = 0,
                        yearMonth = targetYearMonth,
                        createdAt = now,
                        updatedAt = now,
                        sharedId = null,
                        revision = 0,
                        isShared = false,
                        sharedDeviceId = null
                    )
                )
            }
        return missing.size
    }

    /**
     * 获取当月所有预算及其进度。
     */
    suspend fun getMonthBudgetsWithProgress(
        bookName: String,
        yearMonth: String
    ): List<BudgetOverview> {
        return getMonthBudgetOverview(
            scope = if (bookName.isBlank()) BudgetScope.AllBooks else BudgetScope.Book(bookName),
            yearMonth = yearMonth
        )
    }

    suspend fun getMonthBudgetOverview(
        scope: BudgetScope,
        yearMonth: String
    ): List<BudgetOverview> {
        val bookName = scope.daoBookName
        val budgets = budgetDao.getBudgetsByMonthAndBook(yearMonth, bookName)
        return buildBudgetOverview(budgets, bookName, yearMonth)
    }

    private suspend fun buildBudgetOverview(
        budgets: List<Budget>,
        bookName: String,
        yearMonth: String
    ): List<BudgetOverview> {
        val (start, end) = parseYearMonthRange(yearMonth) ?: return emptyList()
        val totalUsed = billDao.sumBudgetExpense(start, end, bookName)
        val categoryIds = budgets.mapNotNull { it.categoryId }.distinct()
        val categoryUsage = if (categoryIds.isEmpty()) {
            emptyMap()
        } else {
            billDao.sumBudgetExpenseByCategories(start, end, bookName, categoryIds)
                .associate { it.categoryId to it.total }
        }

        val currentCategoryNames = CategoryRepository.displayNamesById(
            categoryDao.getCategoriesListByType(0)
        )
        return budgets.map { storedBudget ->
            val budget = storedBudget.categoryId?.let { categoryId ->
                currentCategoryNames[categoryId]?.let { currentName ->
                    storedBudget.copy(categoryName = currentName)
                }
            } ?: storedBudget
            val used = when {
                budget.categoryKey == Budget.TOTAL_CATEGORY_KEY -> totalUsed
                budget.categoryId != null -> categoryUsage[budget.categoryId] ?: 0.0
                !budget.categoryName.isNullOrBlank() -> billDao.sumBudgetExpenseByCategoryName(start, end, bookName, budget.categoryName)
                else -> 0.0
            }
            BudgetOverview(budget, buildProgress(budget, used))
        }.sortedWith(
            compareByDescending<BudgetOverview> { it.budget.categoryKey == Budget.TOTAL_CATEGORY_KEY }
                .thenByDescending { it.progress.riskScore }
                .thenByDescending { it.progress.percent }
                .thenBy { it.budget.categoryName.orEmpty() }
        )
    }

    private suspend fun collectHistorySpends(
        bookName: String,
        categoryId: Long?,
        currentYearMonth: String,
        months: Int
    ): List<Double> {
        return getPreviousMonths(currentYearMonth, months)
            .map { month -> getMonthSpend(bookName, categoryId, month) }
            .filter { it > 0.0 }
    }

    private fun buildSuggestionPlan(
        categoryId: Long?,
        categoryName: String?,
        historyAverage: Double,
        activeMonths: Int,
        reason: String
    ): BudgetSuggestionPlan {
        return BudgetSuggestionPlan(
            categoryId = categoryId,
            categoryName = categoryName,
            conservativeAmount = roundBudget(historyAverage * 0.9),
            normalAmount = roundBudget(historyAverage * 1.05),
            looseAmount = roundBudget(historyAverage * 1.2),
            historyAverage = historyAverage,
            activeMonths = activeMonths,
            reason = reason
        )
    }

    private fun roundBudget(value: Double): Double {
        return ceil(value / 10.0) * 10.0
    }

    fun buildProgress(
        budget: Budget,
        used: Double,
        nowMillis: Long = System.currentTimeMillis()
    ): BudgetProgress {
        return buildProgress(
            budgetAmount = budget.amount,
            usedAmount = used,
            alertThreshold = budget.alertThreshold,
            yearMonth = budget.yearMonth,
            nowMillis = nowMillis
        )
    }

    companion object {
        fun buildProgress(
            budgetAmount: Double,
            usedAmount: Double,
            alertThreshold: Double,
            yearMonth: String,
            nowMillis: Long
        ): BudgetProgress {
            val monthPosition = monthPosition(yearMonth, nowMillis)
            val percent = if (budgetAmount > 0) usedAmount / budgetAmount else 0.0
            val remaining = budgetAmount - usedAmount
            val dailyRemaining = if (remaining > 0 && monthPosition.remainingDays > 0) {
                remaining / monthPosition.remainingDays
            } else {
                0.0
            }
            val paceRatio = if (monthPosition.timeProgress > 0.0) {
                percent / monthPosition.timeProgress
            } else if (percent > 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                0.0
            }
            val pace = when {
                percent >= 1.0 || paceRatio >= 1.15 -> BudgetPace.AHEAD
                paceRatio <= 0.85 -> BudgetPace.HAS_ROOM
                else -> BudgetPace.ON_TRACK
            }
            val riskReason = when {
                percent >= 1.0 -> BudgetRiskReason.EXCEEDED
                percent >= alertThreshold -> BudgetRiskReason.THRESHOLD_REACHED
                paceRatio >= 1.15 && percent >= 0.2 -> BudgetRiskReason.SPENDING_FAST
                else -> BudgetRiskReason.NONE
            }
            val status = when (riskReason) {
                BudgetRiskReason.EXCEEDED -> BudgetStatus.EXCEEDED
                BudgetRiskReason.THRESHOLD_REACHED,
                BudgetRiskReason.SPENDING_FAST -> BudgetStatus.WARNING
                BudgetRiskReason.NONE -> BudgetStatus.NORMAL
            }
            val riskScore = when (status) {
                BudgetStatus.EXCEEDED -> 300.0 + percent
                BudgetStatus.WARNING -> 200.0 + max(percent, paceRatio)
                BudgetStatus.NORMAL -> percent
            }
            return BudgetProgress(
                budgetAmount = budgetAmount,
                usedAmount = usedAmount,
                percent = percent,
                remaining = remaining,
                status = status,
                daysInMonth = monthPosition.daysInMonth,
                elapsedDays = monthPosition.elapsedDays,
                remainingDays = monthPosition.remainingDays,
                timeProgress = monthPosition.timeProgress,
                dailyRemainingAllowance = dailyRemaining,
                paceRatio = paceRatio,
                pace = pace,
                riskReason = riskReason,
                riskScore = riskScore
            )
        }

        private data class MonthPosition(
            val daysInMonth: Int,
            val elapsedDays: Int,
            val remainingDays: Int,
            val timeProgress: Double
        )

        private fun monthPosition(yearMonth: String, nowMillis: Long): MonthPosition {
            val parts = yearMonth.split("-")
            val year = parts.getOrNull(0)?.toIntOrNull()
            val month = parts.getOrNull(1)?.toIntOrNull()
            if (year == null || month == null) {
                return MonthPosition(30, 0, 30, 0.0)
            }

            val monthCal = Calendar.getInstance().apply {
                set(year, month - 1, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val currentYear = nowCal.get(Calendar.YEAR)
            val currentMonth = nowCal.get(Calendar.MONTH) + 1
            val isPastMonth = currentYear > year || (currentYear == year && currentMonth > month)
            val isFutureMonth = currentYear < year || (currentYear == year && currentMonth < month)
            val elapsedDays = when {
                isPastMonth -> daysInMonth
                isFutureMonth -> 0
                else -> nowCal.get(Calendar.DAY_OF_MONTH).coerceIn(1, daysInMonth)
            }
            val remainingDays = when {
                isPastMonth -> 0
                isFutureMonth -> daysInMonth
                else -> daysInMonth - elapsedDays + 1
            }
            return MonthPosition(
                daysInMonth = daysInMonth,
                elapsedDays = elapsedDays,
                remainingDays = remainingDays,
                timeProgress = (elapsedDays.toDouble() / daysInMonth).coerceIn(0.0, 1.0)
            )
        }
    }

    /**
     * 解析年月字符串为时间范围。
     */
    private fun parseYearMonthRange(yearMonth: String): Pair<Long, Long>? {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null

        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        return start to end
    }

    /**
     * 获取前 N 个月的年月字符串列表。
     */
    private fun getPreviousMonths(currentYearMonth: String, months: Int): List<String> {
        val parts = currentYearMonth.split("-")
        val year = parts[0].toIntOrNull() ?: return emptyList()
        val month = parts[1].toIntOrNull() ?: return emptyList()

        val result = mutableListOf<String>()
        var y = year
        var m = month
        for (i in 1..months) {
            m--
            if (m < 1) {
                m = 12
                y--
            }
            result.add(String.format("%04d-%02d", y, m))
        }
        return result
    }
}
