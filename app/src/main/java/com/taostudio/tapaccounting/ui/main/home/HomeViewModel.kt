package com.taostudio.tapaccounting.ui.main.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.main.SharedYearMonthSession
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerViewScopeStore
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.util.Calendar

private const val TAG = "HomePerf"
private fun now() = System.currentTimeMillis()
private fun normalizeMonth(month: Int): Int = month.coerceIn(1, 12)
private fun normalizeYear(year: Int): Int = year.coerceIn(2000, 2100)

/**
 * Activity 作用域的 ViewModel，跨越 HomeFragment 的重建（每次切换 Tab 都 new Fragment）而存活。
 * 将账单数据缓存在 StateFlow 中，Fragment 重建后立刻能收到上次的数据，消除空白闪现。
 *
 * 同时持有 HomeAdapter 实例——adapter 保存了上次渲染的 items 列表，
 * Fragment 重建后直接复用，DiffUtil 比对无变化时完全跳过 bind/layout，彻底消除首帧卡顿。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    /** 跨 Fragment 重建持久保存的 Adapter 实例 */
    val adapter: HomeAdapter = HomeAdapter()

    data class HomeUiState(
        val monthlyBills: List<Bill> = emptyList(),
        val filteredByBook: List<Bill> = emptyList(),
        val chartStart: Long = 0L,
        val chartEnd: Long = Long.MAX_VALUE,
        val selectedBookName: String = BookAccountManager.DEFAULT_BOOK,
        val selectedYear: Int = SharedYearMonthSession.getYearMonth().first,
        val selectedMonth: Int = SharedYearMonthSession.getYearMonth().second,
        val currentTimeRange: Int = 0,
        val currentType: Int = 0,
        val displayMode: YearMonthPickerDialog.DisplayMode = YearMonthPickerDialog.DisplayMode.MONTH,
        val isChartHidden: Boolean = false,
        val viewScope: ResolvedLedgerViewScope? = null,
        val viewScopeRevision: Long = -1L,
        /** true 表示 Room 查询正在进行中（首次加载），false 表示已有数据 */
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(
        HomeUiState(selectedBookName = BookAccountManager.getDefaultBook(application))
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var requestGeneration: Long = 0L
    /** 最近一次账单表 Flow 推送的时间；用来跳过「Flow 已经推过」之后的重复兜底重载。 */
    @Volatile
    private var lastBillEmissionAtMs: Long = 0L
    private val db by lazy { AppDatabase.getDatabase(application) }

    /** Fragment 重建后调用，同步 UI 设置到 ViewModel（如需要则重新触发加载）。
     *  只有当账本名、月份或图表范围发生变化时才重新触发 Flow 收集。*/
    fun syncAndLoad(
        bookName: String,
        year: Int,
        month: Int,
        timeRange: Int,
        type: Int,
        isChartHidden: Boolean
    ) {
        val normalizedYear = normalizeYear(year)
        val normalizedMonth = normalizeMonth(month)
        if (normalizedYear != year || normalizedMonth != month) {
            Log.w(TAG, "syncAndLoad: normalized invalid date input year=$year month=$month => year=$normalizedYear month=$normalizedMonth")
        }

        SharedYearMonthSession.setYearMonth(normalizedYear, normalizedMonth)

        val cur = _uiState.value
        val scopeRevision = LedgerViewScopeStore.revision(getApplication())
        val changed = cur.selectedBookName != bookName
            || cur.selectedYear != normalizedYear
            || cur.selectedMonth != normalizedMonth
            || cur.currentTimeRange != timeRange
            || cur.currentType != type
            || cur.viewScopeRevision != scopeRevision

        val cacheHit = fetchJob != null && fetchJob!!.isActive && !changed
        Log.d(TAG, "syncAndLoad: cacheHit=$cacheHit  changed=$changed  jobActive=${fetchJob?.isActive}  billsInCache=${cur.monthlyBills.size}")

        // 更新 UI 参数（即使不重新查询也需要刷新图表隐藏状态等）
        _uiState.value = cur.copy(
            selectedBookName = bookName,
            selectedYear = normalizedYear,
            selectedMonth = normalizedMonth,
            currentTimeRange = timeRange,
            currentType = type,
            isChartHidden = isChartHidden,
            viewScopeRevision = scopeRevision,
            isLoading = if (changed) true else cur.isLoading
        )

        // 如果 Flow 还没启动（首次）或参数变化，重新启动 Flow
        if (fetchJob == null || !fetchJob!!.isActive || changed) {
            startFlow()
        }
    }

    /** 强制重新加载（下拉刷新、切换账本等） */
    fun reload() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        startFlow()
    }

    /**
     * 最近一次账单表 Flow 推送距今多久（毫秒）。
     * 删除/编辑账单本身就会触发 Flow 重推，UI 不需要再来一次强制重载；
     * 兜底刷新据此判断「这次改动是不是已经被 Flow 覆盖了」。
     */
    fun billEmissionAgeMs(): Long =
        if (lastBillEmissionAtMs == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - lastBillEmissionAtMs

    /** 强制按传入参数刷新（用于下拉刷新/快速切页，避免使用旧快照） */
    fun forceReload(
        bookName: String,
        year: Int,
        month: Int,
        timeRange: Int,
        type: Int,
        isChartHidden: Boolean
    ) {
        val normalizedYear = normalizeYear(year)
        val normalizedMonth = normalizeMonth(month)
        SharedYearMonthSession.setYearMonth(normalizedYear, normalizedMonth)
        _uiState.value = _uiState.value.copy(
            selectedBookName = bookName,
            selectedYear = normalizedYear,
            selectedMonth = normalizedMonth,
            currentTimeRange = timeRange,
            currentType = type,
            isChartHidden = isChartHidden,
            viewScopeRevision = LedgerViewScopeStore.revision(getApplication()),
            isLoading = true
        )
        startFlow()
    }

    /** 账本切换：不清空旧数据，直接触发新查询，DiffUtil 会复用现有 ViewHolder 完成替换，避免 inflate */
    fun switchBook(bookName: String) {
        _uiState.value = _uiState.value.copy(
            selectedBookName = bookName,
            isLoading = true   // 标记加载中，防止短暂显示"暂无账单"
        )
        startFlow()
    }

    /** 更新月份并重新加载 */
    fun setMonth(year: Int, month: Int) {
        _uiState.value = _uiState.value.copy(
            selectedYear = year,
            selectedMonth = month,
            displayMode = YearMonthPickerDialog.DisplayMode.MONTH,
            isLoading = true
        )
        SharedYearMonthSession.setYearMonth(year, month)
        startFlow()
    }

    fun setYearMode(year: Int) {
        _uiState.value = _uiState.value.copy(
            selectedYear = year,
            displayMode = YearMonthPickerDialog.DisplayMode.YEAR,
            isLoading = true
        )
        startFlow()
    }

    fun setAllBillsMode() {
        _uiState.value = _uiState.value.copy(
            displayMode = YearMonthPickerDialog.DisplayMode.ALL,
            isLoading = true
        )
        startFlow()
    }

    /** 更新图表设置并重新加载 */
    fun setChartSettings(timeRange: Int, type: Int, hidden: Boolean) {
        _uiState.value = _uiState.value.copy(
            currentTimeRange = timeRange,
            currentType = type,
            isChartHidden = hidden,
            isLoading = true
        )
        startFlow()
    }

    private fun startFlow() {
        val generation = ++requestGeneration
        val s = _uiState.value
        Log.d(TAG, "startFlow: launching new DB flow (gen=$generation, book=${s.selectedBookName}, ym=${s.selectedYear}-${s.selectedMonth})")
        val t0 = now()
        fetchJob?.cancel()
        // 启动时锁定本次 flow 所用的参数快照，collectLatest 内部始终使用此快照，
        // 避免 DB 多次 emission 期间外部修改 _uiState 导致用了错误的 year/month 筛出错误结果
        val flowSnapshot = _uiState.value
        val (periodStart, periodEnd) = when (flowSnapshot.displayMode) {
            YearMonthPickerDialog.DisplayMode.MONTH ->
                getMonthRange(flowSnapshot.selectedYear, flowSnapshot.selectedMonth)
            YearMonthPickerDialog.DisplayMode.YEAR ->
                getYearRange(flowSnapshot.selectedYear)
            YearMonthPickerDialog.DisplayMode.ALL ->
                0L to Long.MAX_VALUE
        }
        val chartStart = getStartTimeFromRange(flowSnapshot.currentTimeRange)
        val chartEnd = getEndTimeFromRange(flowSnapshot.currentTimeRange)
        val queryStart = if (flowSnapshot.displayMode == YearMonthPickerDialog.DisplayMode.ALL) {
            0L
        } else {
            minOf(periodStart, chartStart)
        }
        val queryEnd = if (flowSnapshot.displayMode == YearMonthPickerDialog.DisplayMode.ALL) {
            Long.MAX_VALUE
        } else {
            maxOf(periodEnd, chartEnd)
        }
        fetchJob = viewModelScope.launch {
            val resolvedScope = withContext(Dispatchers.IO) {
                LedgerViewScopeStore.resolve(
                    context = getApplication(),
                    db = db,
                    legacyBookName = flowSnapshot.selectedBookName
                )
            }
            val selectedAliases = resolvedScope.selectedBooks
                .flatMap { BookAccountManager.rawAliases(it.name) }
                .distinct()
            val billsFlow = if (resolvedScope.scope.books is LedgerBookSelection.All) {
                db.billDao().getBillsBetweenTimes(queryStart, queryEnd)
            } else {
                db.billDao().getBillsByBookNamesBetweenTimes(selectedAliases, queryStart, queryEnd)
            }
            billsFlow.collectLatest { allTransactions ->
                if (generation != requestGeneration) {
                    Log.d(TAG, "collect dropped: stale generation=$generation latest=$requestGeneration")
                    return@collectLatest
                }
                val t1 = now()
                lastBillEmissionAtMs = System.currentTimeMillis()
                Log.d(TAG, "DB emission received: ${allTransactions.size} bills  [+${t1 - t0}ms since startFlow]")
                val isChartHiddenNow = Prefs.isShowHomeTrendCard(getApplication<Application>()).not()

                val (monthly, filtered) =
                    withContext(Dispatchers.Default) {
                        val filtered = allTransactions
                            .asSequence()
                            .filter(resolvedScope::includes)
                            .sortedByDescending { it.time }
                            .toList()
                        val monthly = when (flowSnapshot.displayMode) {
                            YearMonthPickerDialog.DisplayMode.MONTH ->
                                filtered.filter { isBillInSelectedMonth(it, flowSnapshot.selectedYear, flowSnapshot.selectedMonth) }
                            YearMonthPickerDialog.DisplayMode.YEAR ->
                                filtered.filter { isBillInSelectedYear(it, flowSnapshot.selectedYear) }
                            YearMonthPickerDialog.DisplayMode.ALL ->
                                filtered
                        }
                        monthly to filtered
                    }

                val t2 = now()
                Log.d(TAG, "filter/sort done: monthly=${monthly.size}  filtered=${filtered.size}  [+${t2 - t1}ms]")

                if (generation != requestGeneration) {
                    Log.d(TAG, "state update dropped: stale generation=$generation latest=$requestGeneration")
                    return@collectLatest
                }

                // 用 flowSnapshot 参数更新 StateFlow，保证 year/month/book 等参数与计算结果严格对应
                _uiState.value = _uiState.value.copy(
                    selectedBookName = resolvedScope.legacyBookName,
                    selectedYear = flowSnapshot.selectedYear,
                    selectedMonth = flowSnapshot.selectedMonth,
                    currentTimeRange = flowSnapshot.currentTimeRange,
                    currentType = flowSnapshot.currentType,
                    displayMode = flowSnapshot.displayMode,
                    monthlyBills = monthly,
                    filteredByBook = filtered,
                    chartStart = chartStart,
                    chartEnd = chartEnd,
                    isChartHidden = isChartHiddenNow,
                    viewScope = resolvedScope,
                    viewScopeRevision = LedgerViewScopeStore.revision(getApplication()),
                    isLoading = false
                )
                Log.d(TAG, "StateFlow updated  [+${now() - t2}ms to emit]")

            }
        }
    }

    private fun isBillInSelectedMonth(bill: Bill, year: Int, month: Int): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = bill.time
        return cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month
    }

    private fun isBillInSelectedYear(bill: Bill, year: Int): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = bill.time
        return cal.get(Calendar.YEAR) == year
    }

    private fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return start to cal.timeInMillis
    }

    private fun getYearRange(year: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(year, Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(year, Calendar.DECEMBER, 31, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return start to cal.timeInMillis
    }

    private fun getStartTimeFromRange(rangeOpt: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        when (rangeOpt) {
            0 -> cal.add(Calendar.DAY_OF_YEAR, -6)
            1 -> cal.add(Calendar.DAY_OF_YEAR, -14)
            2 -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
        }
        return cal.timeInMillis
    }

    private fun getEndTimeFromRange(rangeOpt: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        when (rangeOpt) {
            2 -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            }
        }
        return cal.timeInMillis
    }
}

