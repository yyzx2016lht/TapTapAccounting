package com.taostudio.tapaccounting.ui.main.stats

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.insight.InsightCardModel
import com.taostudio.tapaccounting.logic.insight.InsightEngine
import com.taostudio.tapaccounting.logic.BillStatsContribution
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TrendStat(val index: Int, val expense: Double, val income: Double)

data class CategoryStat(
    val categoryName: String,
    val amount: Double,
    val percentage: Float,
    val amountDiffFromLastPeriod: Double
)

data class MemberStat(val memberId: String, val expense: Double, val income: Double)

data class TimeReport(
    val dateString: String,
    val expense: Double,
    val income: Double,
    val balance: Double,
    val bills: List<Bill>
)

private data class DayAggregate(
    var expense: Double = 0.0,
    var income: Double = 0.0,
    val bills: MutableList<Bill> = mutableListOf()
)

data class StatsUiState(
    val year: Int = Calendar.getInstance().get(Calendar.YEAR),
    val month: Int = Calendar.getInstance().get(Calendar.MONTH),
    val isMonthMode: Boolean = true,
    val dateLabel: String = "",
    val forcedStartTime: Long? = null,
    val forcedEndTime: Long? = null,
    val forcedLabel: String? = null,
    val selectedCurrency: String? = null,
    val selectedBookName: String? = null,
    val viewScope: ResolvedLedgerViewScope? = null,
    val isLoading: Boolean = true,
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val balance: Double = 0.0,
    val dailyAvg: Double = 0.0,
    val totalTransfer: Double = 0.0,
    val totalRepayment: Double = 0.0,
    val totalRefund: Double = 0.0,
    val memberStats: List<MemberStat> = emptyList(),
    val trendStats: List<TrendStat> = emptyList(),
    val categoryStatsExpense: List<CategoryStat> = emptyList(),
    val categoryStatsIncome: List<CategoryStat> = emptyList(),
    val timeReports: List<TimeReport> = emptyList(),
    val bills: List<Bill> = emptyList()
)

class StatsViewModel(private val billDao: BillDao, private val localMemberIdForBook: suspend (String) -> String? = { null }) : ViewModel() {
    companion object {
        private const val TAG = "StatsViewModel"
        private const val MAX_STATS_CACHE_ENTRIES = 24
        private const val FLOW_SAMPLE_MS = 120L
        private val CATEGORY_SPLIT_REGEX = Regex("\\s*>\\s*|/::/| - |::|·")
        private val statsSnapshotCache = linkedMapOf<String, StatsUiState>()
    }

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState = _uiState.asStateFlow()

    /** 统计页洞察卡片（最多 4 张） */
    private val _insightCards = MutableStateFlow<List<InsightCardModel>>(emptyList())
    val insightCards: StateFlow<List<InsightCardModel>> = _insightCards.asStateFlow()

    private var loadJob: Job? = null
    private val dfMonthLabel = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val dfDateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun statsAmountOf(bill: Bill, selectedCurrency: String?): Double {
        // 转账 exchangeRate 是源→目标，不能当 →CNY；见 StatsAmountResolver
        return com.taostudio.tapaccounting.logic.StatsAmountResolver.resolve(bill, selectedCurrency)
    }

    init {
        // Wait for host sync (year/month/book) before first heavy query to avoid redundant cold-start loads.
    }

    fun setMode(isMonth: Boolean) {
        _uiState.update {
            it.copy(
                isMonthMode = isMonth,
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun setYearMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                year = year,
                month = month,
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun prevDate() {
        val s = _uiState.value
        if (s.forcedStartTime != null && s.forcedEndTime != null) {
            if (s.forcedStartTime == 0L && s.forcedEndTime == Long.MAX_VALUE) return
            val duration = (s.forcedEndTime - s.forcedStartTime + 1L).coerceAtLeast(1L)
            val newEnd = s.forcedStartTime - 1L
            val newStart = newEnd - duration + 1L
            applyDateRange(newStart.coerceAtLeast(0L), newEnd.coerceAtLeast(0L), null)
            return
        }

        val cal = Calendar.getInstance().apply { set(s.year, s.month, 1) }
        if (s.isMonthMode) cal.add(Calendar.MONTH, -1) else cal.add(Calendar.YEAR, -1)
        setYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    fun nextDate() {
        val s = _uiState.value
        if (s.forcedStartTime != null && s.forcedEndTime != null) {
            if (s.forcedStartTime == 0L && s.forcedEndTime == Long.MAX_VALUE) return
            val duration = (s.forcedEndTime - s.forcedStartTime + 1L).coerceAtLeast(1L)
            val newStart = s.forcedEndTime + 1L
            val newEnd = newStart + duration - 1L
            applyDateRange(newStart, newEnd, null)
            return
        }

        val cal = Calendar.getInstance().apply { set(s.year, s.month, 1) }
        if (s.isMonthMode) cal.add(Calendar.MONTH, 1) else cal.add(Calendar.YEAR, 1)
        setYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    fun applyThisMonthFilter() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, "本月")
    }

    fun applyLastMonthFilter() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, "上月")
    }

    fun applyThisYearFilter() {
        val targetYear = Calendar.getInstance().get(Calendar.YEAR)
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, String.format(Locale.getDefault(), "%04d", targetYear))
    }

    fun applyLastYearFilter() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -1)
        val targetYear = cal.get(Calendar.YEAR)
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, String.format(Locale.getDefault(), "%04d", targetYear))
    }

    fun applyTodayFilter() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        applyDateRange(start, cal.timeInMillis, "今天")
    }

    fun applyYesterdayFilter() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        applyDateRange(start, cal.timeInMillis, "昨天")
    }

    fun applyThisWeekFilter() {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        applyDateRange(start, cal.timeInMillis, "本周")
    }

    fun applyLastWeekFilter() {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            add(Calendar.WEEK_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        applyDateRange(start, cal.timeInMillis, "上周")
    }

    fun applyAllTimeFilter() {
        applyDateRange(0L, Long.MAX_VALUE, "全部")
    }

    fun applyCustomDateFilter(start: Long, end: Long) {
        val safeStart = minOf(start, end)
        val safeEnd = maxOf(start, end)
        val label = formatSmartRangeLabel(safeStart, safeEnd)
        applyDateRange(safeStart, safeEnd, label)
    }

    fun clearDateFilter() {
        _uiState.update {
            it.copy(
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun setCurrencyFilter(currencyCode: String?) {
        _uiState.update { it.copy(selectedCurrency = currencyCode?.takeIf { code -> code.isNotBlank() }) }
        loadData()
    }

    fun setBookFilter(bookName: String?) {
        _uiState.update { it.copy(selectedBookName = bookName?.takeIf { name -> name.isNotBlank() }) }
        loadData()
    }

    fun setViewScope(scope: ResolvedLedgerViewScope) {
        _uiState.update {
            it.copy(
                viewScope = scope,
                selectedBookName = scope.singleBookName
            )
        }
        loadData()
    }

    fun syncHostSelection(
        year: Int,
        month: Int,
        bookName: String?,
        viewScope: ResolvedLedgerViewScope? = null
    ) {
        val normalizedBook = bookName?.takeIf { it.isNotBlank() }
        val old = _uiState.value
        var changed = false
        var next = old
        if (old.year != year || old.month != month) {
            changed = true
            next = next.copy(
                year = year,
                month = month,
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        if (old.selectedBookName != normalizedBook || old.viewScope?.signature != viewScope?.signature) {
            changed = true
            next = next.copy(selectedBookName = normalizedBook, viewScope = viewScope)
        }
        if (changed) {
            _uiState.value = next
            loadData()
            return
        }
        if (old.bills.isEmpty() || old.isLoading) {
            loadData()
        }
    }

    fun getTransferBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.type == Bill.TYPE_TRANSFER && it.subType != Bill.SUBTYPE_REPAYMENT }
            .sortedByDescending { it.time }
    }

    fun getRepaymentBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.type == Bill.TYPE_TRANSFER && it.subType == Bill.SUBTYPE_REPAYMENT }
            .sortedByDescending { it.time }
    }

    fun getRefundBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.subType == Bill.SUBTYPE_REFUND }
            .sortedByDescending { it.time }
    }

    private fun applyDateRange(start: Long, end: Long, label: String?) {
        _uiState.update {
            it.copy(
                forcedStartTime = start,
                forcedEndTime = end,
                forcedLabel = label
            )
        }
        loadData()
    }

    @OptIn(FlowPreview::class)
    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val (start, end, label) = resolveRange(snapshot)
            val (prevStart, prevEnd) = resolvePreviousRange(start, end)
            val cacheKey = buildCacheKey(snapshot, start, end, prevStart, prevEnd)

            _uiState.update { it.copy(dateLabel = label, isLoading = true) }
            statsSnapshotCache[cacheKey]?.let { cached ->
                _uiState.value = cached.copy(
                    year = snapshot.year,
                    month = snapshot.month,
                    isMonthMode = snapshot.isMonthMode,
                    dateLabel = label,
                    forcedStartTime = snapshot.forcedStartTime,
                    forcedEndTime = snapshot.forcedEndTime,
                    forcedLabel = snapshot.forcedLabel,
                    selectedCurrency = snapshot.selectedCurrency,
                    selectedBookName = snapshot.selectedBookName,
                    viewScope = snapshot.viewScope,
                    isLoading = false
                )
            }

            val currentFlow = billDao.getBillsBetweenTimes(start, end)
            val prevFlow = if (prevStart <= prevEnd) {
                billDao.getBillsBetweenTimes(prevStart, prevEnd)
            } else {
                flowOf(emptyList())
            }

            combine(currentFlow, prevFlow) { currentBills, prevBills ->
                currentBills to prevBills
            }
                .sample(FLOW_SAMPLE_MS)
                .distinctUntilChangedBy { (currentBillsRaw, prevBillsRaw) ->
                    billsFingerprint(currentBillsRaw) to billsFingerprint(prevBillsRaw)
                }
                .collectLatest { (currentBillsRaw, prevBillsRaw) ->
                    val stateNow = _uiState.value
                    val calcStart = SystemClock.elapsedRealtime()
                    val (currentBills, prevBills, newState, filterCost, processCost) = withContext(Dispatchers.Default) {
                        val filterStart = SystemClock.elapsedRealtime()
                        val filteredCurrent = applyExtraFilters(currentBillsRaw, stateNow)
                        val filteredPrev = applyExtraFilters(prevBillsRaw, stateNow)
                        val filterDuration = SystemClock.elapsedRealtime() - filterStart
                        val processStart = SystemClock.elapsedRealtime()
                        val processed = processData(filteredCurrent, filteredPrev, stateNow, start, end)
                        val processDuration = SystemClock.elapsedRealtime() - processStart
                        CalcPayload(filteredCurrent, filteredPrev, processed, filterDuration, processDuration)
                    }
                    if (!hasMeaningfulStatsChange(stateNow, newState, currentBills)) {
                        Log.d(
                            TAG,
                            "loadData skip(no-change): currentRaw=${currentBillsRaw.size}, prevRaw=${prevBillsRaw.size}, " +
                                "filterMs=$filterCost, processMs=$processCost"
                        )
                        return@collectLatest
                    }
                    _uiState.value = newState
                    putStatsCache(cacheKey, newState)

                    // 计算洞察卡片（仅月模式）
                    if (newState.isMonthMode) {
                        _insightCards.value = InsightEngine.generateForStats(currentBills, prevBills)
                    } else {
                        _insightCards.value = emptyList()
                    }

                    val cost = SystemClock.elapsedRealtime() - calcStart
                    Log.d(
                        TAG,
                        "loadData apply: currentRaw=${currentBillsRaw.size}, prevRaw=${prevBillsRaw.size}, " +
                            "current=${currentBills.size}, prev=${prevBills.size}, filterMs=$filterCost, processMs=$processCost, totalMs=$cost"
                    )
                }
        }
    }

    private fun buildCacheKey(
        state: StatsUiState,
        start: Long,
        end: Long,
        prevStart: Long,
        prevEnd: Long
    ): String {
        return listOf(
            state.year,
            state.month,
            state.isMonthMode,
            start,
            end,
            prevStart,
            prevEnd,
            state.selectedCurrency.orEmpty(),
            state.selectedBookName.orEmpty(),
            state.viewScope?.signature.orEmpty()
        ).joinToString("|")
    }

    private fun putStatsCache(key: String, state: StatsUiState) {
        statsSnapshotCache.remove(key)
        statsSnapshotCache[key] = state
        while (statsSnapshotCache.size > MAX_STATS_CACHE_ENTRIES) {
            val eldest = statsSnapshotCache.entries.firstOrNull()?.key ?: break
            statsSnapshotCache.remove(eldest)
        }
    }

    private fun resolveRange(state: StatsUiState): Triple<Long, Long, String> {
        val forcedStart = state.forcedStartTime
        val forcedEnd = state.forcedEndTime
        if (forcedStart != null && forcedEnd != null) {
            val label = state.forcedLabel ?: if (forcedEnd == Long.MAX_VALUE) {
                "全部"
            } else {
                inferRangeLabel(forcedStart, forcedEnd)
            }
            return Triple(forcedStart, forcedEnd, label)
        }

        val (start, end) = getTimeRange(state.year, state.month, state.isMonthMode)
        val label = if (state.isMonthMode) {
            String.format(Locale.getDefault(), "%04d-%02d", state.year, state.month + 1)
        } else {
            String.format(Locale.getDefault(), "%04d", state.year)
        }
        return Triple(start, end, label)
    }

    private fun inferRangeLabel(start: Long, end: Long): String {
        val safeStart = minOf(start, end)
        val safeEnd = maxOf(start, end)
        val startCal = Calendar.getInstance().apply { timeInMillis = safeStart }
        val endCal = Calendar.getInstance().apply { timeInMillis = safeEnd }

        val sameYear = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)
        val sameMonth = sameYear && startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH)
        if (sameMonth) {
            val startIsMonthHead = startCal.get(Calendar.DAY_OF_MONTH) == 1
            val endIsMonthTail = endCal.get(Calendar.DAY_OF_MONTH) == endCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            if (startIsMonthHead && endIsMonthTail) {
                return String.format(
                    Locale.getDefault(),
                    "%04d-%02d",
                    startCal.get(Calendar.YEAR),
                    startCal.get(Calendar.MONTH) + 1
                )
            }
        }

        if (sameYear) {
            val startIsYearHead =
                startCal.get(Calendar.MONTH) == Calendar.JANUARY &&
                    startCal.get(Calendar.DAY_OF_MONTH) == 1
            val endIsYearTail =
                endCal.get(Calendar.MONTH) == Calendar.DECEMBER &&
                    endCal.get(Calendar.DAY_OF_MONTH) == 31
            if (startIsYearHead && endIsYearTail) {
                return String.format(Locale.getDefault(), "%04d", startCal.get(Calendar.YEAR))
            }
        }

        return formatSmartRangeLabel(safeStart, safeEnd)
    }

    private fun formatSmartRangeLabel(start: Long, end: Long): String {
        val safeStart = minOf(start, end)
        val safeEnd = maxOf(start, end)
        val startCal = Calendar.getInstance().apply { timeInMillis = safeStart }
        val endCal = Calendar.getInstance().apply { timeInMillis = safeEnd }
        val isSameDay =
            startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
                startCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)
        if (isSameDay) return formatCompactDate(safeStart)
        return "${formatCompactDate(safeStart)}~${formatCompactDate(safeEnd)}"
    }

    private fun formatCompactDate(timeMs: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = timeMs }
        val now = Calendar.getInstance()
        val isCurrentYear = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        return if (isCurrentYear) {
            String.format(
                Locale.getDefault(),
                "%02d-%02d",
                target.get(Calendar.MONTH) + 1,
                target.get(Calendar.DAY_OF_MONTH)
            )
        } else {
            dfDateLabel.format(Date(timeMs))
        }
    }

    private fun resolvePreviousRange(start: Long, end: Long): Pair<Long, Long> {
        if (end == Long.MAX_VALUE) return Pair(1L, 0L)
        val duration = (end - start + 1L).coerceAtLeast(1L)
        val prevEnd = (start - 1L).coerceAtLeast(0L)
        val prevStart = (prevEnd - duration + 1L).coerceAtLeast(0L)
        return Pair(prevStart, prevEnd)
    }

    private fun applyExtraFilters(bills: List<Bill>, state: StatsUiState): List<Bill> {
        if (state.selectedCurrency == null && state.selectedBookName == null && state.viewScope == null) return bills
        val selectedBookNormalized = state.selectedBookName?.let { BookAccountManager.normalizeBookName(it) }
        return bills.filter { bill ->
            val currencyMatched = state.selectedCurrency == null || bill.currency == state.selectedCurrency
            val bookMatched = selectedBookNormalized == null ||
                BookAccountManager.normalizeBookName(bill.bookName) == selectedBookNormalized
            val scopeMatched = state.viewScope?.includes(bill) ?: true
            currencyMatched && bookMatched && scopeMatched
        }
    }

    private fun getTimeRange(year: Int, month: Int, isMonth: Boolean): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(year, if (isMonth) month else 0, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        if (isMonth) cal.add(Calendar.MONTH, 1) else cal.add(Calendar.YEAR, 1)
        cal.add(Calendar.MILLISECOND, -1)
        return Pair(start, cal.timeInMillis)
    }

    private fun topLevelCategory(name: String): String {
        val normalized = name.removePrefix("退款：").removePrefix("退款·").trim()
        return normalized
            .split(CATEGORY_SPLIT_REGEX)
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { "未分类" }
    }

    private fun secondLevelCategory(name: String): String {
        val normalized = name.removePrefix("退款：").removePrefix("退款·").trim()
        val parts = normalized.split(CATEGORY_SPLIT_REGEX)
        return when {
            parts.size >= 2 -> parts[1].trim().ifEmpty { parts[0].trim().ifEmpty { "未分类" } }
            parts.isNotEmpty() -> parts[0].trim().ifEmpty { "未分类" }
            else -> "未分类"
        }
    }

    private fun processData(
        bills: List<Bill>,
        prevBills: List<Bill>,
        state: StatsUiState,
        rangeStart: Long,
        rangeEnd: Long
    ): StatsUiState {
        var totalExpense = 0.0
        var totalIncome = 0.0
        var totalTransfer = 0.0
        var totalRepayment = 0.0
        var totalRefund = 0.0

        val categoryExpenseMap = mutableMapOf<String, Double>()
        val categoryIncomeMap = mutableMapOf<String, Double>()
        val prevCategoryExpenseMap = mutableMapOf<String, Double>()
        val prevCategoryIncomeMap = mutableMapOf<String, Double>()
        val memberTotals = linkedMapOf<String, DoubleArray>()
        val topLevelCache = HashMap<String, String>(64)

        val dayMap = mutableMapOf<Int, DayAggregate>()
        val cal = Calendar.getInstance()

        fun topLevelCached(name: String): String =
            topLevelCache.getOrPut(name) { topLevelCategory(name) }

        bills.forEach { bill ->
            // 跳过不计入统计的账单
            if (bill.excludeFromStats) return@forEach

            val amount = statsAmountOf(bill, state.selectedCurrency)
            val isRefund = bill.subType == Bill.SUBTYPE_REFUND
            val isRepayment = bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT
            val contribution = BillStatsContribution.from(bill, amount)
            bill.memberId?.let { memberId ->
                val totals = memberTotals.getOrPut(memberId) { doubleArrayOf(0.0, 0.0) }
                totals[0] += contribution.expense
                totals[1] += contribution.income
            }

            if (isRefund) {
                totalRefund += contribution.refund
                totalExpense += contribution.expense
                if (contribution.expense != 0.0) {
                    val topLevel = topLevelCached(bill.categoryName)
                    categoryExpenseMap[topLevel] =
                        (categoryExpenseMap[topLevel] ?: 0.0) + contribution.expense
                }
            } else if (isRepayment) {
                totalRepayment += amount
            } else if (bill.type == Bill.TYPE_TRANSFER) {
                totalTransfer += amount
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                totalExpense += contribution.expense
                val topLevel = topLevelCached(bill.categoryName)
                categoryExpenseMap[topLevel] = (categoryExpenseMap[topLevel] ?: 0.0) + contribution.expense
            } else if (bill.type == Bill.TYPE_INCOME) {
                totalIncome += contribution.income
                val topLevel = topLevelCached(bill.categoryName)
                categoryIncomeMap[topLevel] = (categoryIncomeMap[topLevel] ?: 0.0) + contribution.income
            }

            cal.timeInMillis = bill.time
            val dayKey = cal.get(Calendar.YEAR) * 10_000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
            val dayAggregate = dayMap.getOrPut(dayKey) { DayAggregate() }
            dayAggregate.expense += contribution.expense
            dayAggregate.income += contribution.income
            dayAggregate.bills.add(bill)
        }

        prevBills.forEach { bill ->
            if (bill.excludeFromStats) return@forEach
            val amount = statsAmountOf(bill, state.selectedCurrency)
            val contribution = BillStatsContribution.from(bill, amount)
            val topLevel = topLevelCached(bill.categoryName)

            if (contribution.expense != 0.0) {
                prevCategoryExpenseMap[topLevel] = (prevCategoryExpenseMap[topLevel] ?: 0.0) + contribution.expense
            } else if (contribution.income > 0.0) {
                prevCategoryIncomeMap[topLevel] = (prevCategoryIncomeMap[topLevel] ?: 0.0) + contribution.income
            }
        }

        val expenseCategoryTotal = categoryExpenseMap.values.sum()
        val incomeCategoryTotal = categoryIncomeMap.values.sum()

        val categoryStatsExpense = categoryExpenseMap
            .map { (name, amount) ->
                CategoryStat(
                    categoryName = name,
                    amount = amount,
                    percentage = if (expenseCategoryTotal > 0) ((amount / expenseCategoryTotal) * 100f).toFloat() else 0f,
                    amountDiffFromLastPeriod = amount - (prevCategoryExpenseMap[name] ?: 0.0)
                )
            }
            .filter { it.amount > 0 } // 只显示净支出大于0的
            .sortedByDescending { it.amount }

        val categoryStatsIncome = categoryIncomeMap
            .map { (name, amount) ->
                CategoryStat(
                    categoryName = name,
                    amount = amount,
                    percentage = if (incomeCategoryTotal > 0) ((amount / incomeCategoryTotal) * 100f).toFloat() else 0f,
                    amountDiffFromLastPeriod = amount - (prevCategoryIncomeMap[name] ?: 0.0)
                )
            }
            .sortedByDescending { it.amount }

        val timeReports = dayMap
            .map { (day, dayAggregate) ->
                val year = day / 10_000
                val month = (day / 100) % 100
                val dayOfMonth = day % 100
                TimeReport(
                    dateString = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month, dayOfMonth),
                    expense = dayAggregate.expense,
                    income = dayAggregate.income,
                    balance = dayAggregate.income - dayAggregate.expense,
                    bills = dayAggregate.bills.sortedByDescending { it.time }
                )
            }
            .sortedByDescending { it.dateString }

        val now = Calendar.getInstance()
        val startCal = Calendar.getInstance().apply { timeInMillis = rangeStart }
        val endCal = Calendar.getInstance().apply { timeInMillis = rangeEnd }

        val activeDays = if (rangeEnd == Long.MAX_VALUE) {
            maxOf(1, dayMap.size)
        } else {
            val dayMs = 24L * 60L * 60L * 1000L
            val defaultDays = ((rangeEnd - rangeStart) / dayMs + 1L).coerceAtLeast(1L).toInt()
            val isCurrentMonthRange =
                startCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    startCal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                    endCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    endCal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                    startCal.get(Calendar.DAY_OF_MONTH) == 1 &&
                    endCal.get(Calendar.DAY_OF_MONTH) == endCal.getActualMaximum(Calendar.DAY_OF_MONTH)

            if (isCurrentMonthRange) {
                now.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
            } else {
                defaultDays
            }
        }

        // 年视图：月均支出 = 年支出 / 已过去的月数
        // 月视图：日均支出 = 月支出 / 天数
        val avgValue = if (state.isMonthMode) {
            totalExpense / activeDays
        } else {
            val isCurrentYear = startCal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
            val activeMonths = if (isCurrentYear) {
                (now.get(Calendar.MONTH) + 1).coerceAtLeast(1)
            } else {
                12
            }
            totalExpense / activeMonths
        }

        return state.copy(
            isLoading = false,
            totalExpense = totalExpense,
            totalIncome = totalIncome,
            totalTransfer = totalTransfer,
            totalRepayment = totalRepayment,
            totalRefund = totalRefund,
            memberStats = memberTotals.map { MemberStat(it.key, it.value[0], it.value[1]) },
            balance = totalIncome - totalExpense,
            dailyAvg = avgValue,
            categoryStatsExpense = categoryStatsExpense,
            categoryStatsIncome = categoryStatsIncome,
            timeReports = timeReports,
            bills = bills
        )
    }

    private fun hasMeaningfulStatsChange(
        oldState: StatsUiState,
        newState: StatsUiState,
        currentBills: List<Bill>
    ): Boolean {
        if (oldState.isLoading != newState.isLoading) return true
        if (oldState.dateLabel != newState.dateLabel) return true
        if (oldState.year != newState.year || oldState.month != newState.month || oldState.isMonthMode != newState.isMonthMode) {
            return true
        }
        if (
            oldState.selectedCurrency != newState.selectedCurrency ||
            oldState.selectedBookName != newState.selectedBookName ||
            oldState.viewScope?.signature != newState.viewScope?.signature
        ) {
            return true
        }
        if (oldState.totalExpense != newState.totalExpense ||
            oldState.totalIncome != newState.totalIncome ||
            oldState.totalTransfer != newState.totalTransfer ||
            oldState.totalRepayment != newState.totalRepayment ||
            oldState.totalRefund != newState.totalRefund ||
            oldState.balance != newState.balance ||
            oldState.dailyAvg != newState.dailyAvg
        ) {
            return true
        }
        if (oldState.memberStats != newState.memberStats) return true
        if (categoryStatsFingerprint(oldState.categoryStatsExpense) != categoryStatsFingerprint(newState.categoryStatsExpense)) {
            return true
        }
        if (categoryStatsFingerprint(oldState.categoryStatsIncome) != categoryStatsFingerprint(newState.categoryStatsIncome)) {
            return true
        }
        if (timeReportsFingerprint(oldState.timeReports) != timeReportsFingerprint(newState.timeReports)) {
            return true
        }
        return billsFingerprint(oldState.bills) != billsFingerprint(currentBills)
    }

    private fun billsFingerprint(bills: List<Bill>): Long {
        var acc = 1469598103934665603L
        bills.forEach { bill ->
            acc = (acc xor bill.id).times(1099511628211L)
            acc = (acc xor bill.time).times(1099511628211L)
            acc = (acc xor bill.type.toLong()).times(1099511628211L)
            acc = (acc xor bill.subType.toLong()).times(1099511628211L)
            acc = (acc xor bill.amount.toRawBits()).times(1099511628211L)
            acc = (acc xor bill.exchangeRate.toRawBits()).times(1099511628211L)
            acc = (acc xor bill.categoryName.hashCode().toLong()).times(1099511628211L)
            acc = (acc xor bill.currency.hashCode().toLong()).times(1099511628211L)
            acc = (acc xor bill.bookName.hashCode().toLong()).times(1099511628211L)
            acc = (acc xor bill.memberId.hashCode().toLong()).times(1099511628211L)
            acc = (acc xor bill.sharedRevision).times(1099511628211L)
            acc = (acc xor (if (bill.excludeFromStats) 1L else 0L)).times(1099511628211L)
        }
        return acc xor bills.size.toLong()
    }

    private fun categoryStatsFingerprint(items: List<CategoryStat>): Long {
        var acc = 1125899906842597L
        items.forEach { item ->
            acc = (acc * 31) + item.categoryName.hashCode().toLong()
            acc = (acc * 31) + item.amount.toRawBits()
            acc = (acc * 31) + item.percentage.toRawBits().toLong()
            acc = (acc * 31) + item.amountDiffFromLastPeriod.toRawBits()
        }
        return acc
    }

    private fun timeReportsFingerprint(items: List<TimeReport>): Long {
        var acc = 7809847782465536322L
        items.forEach { report ->
            acc = (acc xor report.dateString.hashCode().toLong()).times(1099511628211L)
            acc = (acc xor report.expense.toRawBits()).times(1099511628211L)
            acc = (acc xor report.income.toRawBits()).times(1099511628211L)
            acc = (acc xor report.balance.toRawBits()).times(1099511628211L)
            acc = (acc xor report.bills.size.toLong()).times(1099511628211L)
        }
        return acc
    }

    private data class CalcPayload(
        val currentBills: List<Bill>,
        val prevBills: List<Bill>,
        val state: StatsUiState,
        val filterCostMs: Long,
        val processCostMs: Long
    )

    fun getBillsForCategory(categoryName: String, isExpense: Boolean): List<Bill> {
        return _uiState.value.bills.filter { bill ->
            if (bill.excludeFromStats) return@filter false
            val isMatch = topLevelCategory(bill.categoryName) == categoryName
            if (isExpense) {
                (bill.type == Bill.TYPE_EXPENSE &&
                    bill.subType == Bill.SUBTYPE_NORMAL && isMatch) ||
                (bill.subType == Bill.SUBTYPE_REFUND && isMatch)
            } else {
                bill.type == Bill.TYPE_INCOME &&
                    bill.subType == Bill.SUBTYPE_NORMAL && isMatch
            }
        }
    }

    fun getSubCategoryStats(categoryName: String, isExpense: Boolean): Map<String, Double> {
        val bills = getBillsForCategory(categoryName, isExpense)
        val map = mutableMapOf<String, Double>()
        bills.forEach { bill ->
            val amount = statsAmountOf(bill, _uiState.value.selectedCurrency)
            val sub = secondLevelCategory(bill.categoryName)
            val contribution = BillStatsContribution.from(bill, amount)
            val categoryAmount = if (isExpense) contribution.expense else contribution.income
            if (categoryAmount != 0.0) map[sub] = (map[sub] ?: 0.0) + categoryAmount
        }
        return map.filterValues { it > 0 }
    }

    /**
     * 是否存在真正的子分类（而不是仅有顶级分类本身）。
     * 例如："餐饮" -> false；"餐饮>早餐" / "餐饮::早餐" / "餐饮·早餐" -> true
     */
    fun hasSubCategories(categoryName: String, isExpense: Boolean): Boolean {
        val bills = getBillsForCategory(categoryName, isExpense)
        return bills.any { bill ->
            val normalized = bill.categoryName.removePrefix("退款：").removePrefix("退款·").trim()
            val parts = normalized.split(Regex("\\s*>\\s*|/::/| - |::|·"))
            parts.size >= 2 && parts[1].trim().isNotEmpty()
        }
    }

    fun getBillsForSubCategory(categoryName: String, subCategory: String, isExpense: Boolean): List<Bill> {
        return getBillsForCategory(categoryName, isExpense).filter { bill ->
            secondLevelCategory(bill.categoryName) == subCategory
        }
    }
}

class StatsViewModelFactory(private val billDao: BillDao, private val localMemberIdForBook: suspend (String) -> String? = { null }) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatsViewModel(billDao, localMemberIdForBook) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

