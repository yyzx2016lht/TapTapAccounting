package com.taostudio.tapaccounting.ui.main.stats

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.BadTokenException
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.sync.SharedSyncScheduler
import com.taostudio.tapaccounting.logic.BudgetService
import com.taostudio.tapaccounting.logic.MemberBudgetAllocation
import com.taostudio.tapaccounting.ui.main.home.InsightCardAdapter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.crossfadeText
import com.taostudio.tapaccounting.ui.common.UiMotion.fadeIn
import com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet
import com.taostudio.tapaccounting.ui.dialog.LedgerViewScopeDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import com.taostudio.tapaccounting.ui.main.home.HomeViewModel
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScopeStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class StatsFragment : Fragment() {
    companion object {
        private const val TAG = "StatsFragment"
        private const val ENABLE_JANK_MONITOR = false
        private const val CHART_ANIMATE_MAX_ITEMS = 6
        private const val CHART_ANIMATE_MAX_BILLS = 120
        private const val CHART_HEAVY_BILLS_THRESHOLD = 180
        private const val CHART_HEAVY_CATEGORIES_THRESHOLD = 8
        private const val CHART_HEAVY_RENDER_DELAY_MS = 96L
        private const val MODE_SWITCH_ANIM_MIN_INTERVAL_MS = 360L
        private const val ENTER_ANIM_DURATION_MS = 120L
    }

    /** 与首页共享的时间状态（Activity 作用域） */
    private val homeViewModel: HomeViewModel by activityViewModels()

    private val viewModel: StatsViewModel by viewModels {
        // P2-9: use application context
        val db = AppDatabase.getDatabase(requireNotNull(activity).application)
        StatsViewModelFactory(db.billDao()) { bookName -> db.sharedLedgerDao().getByBookName(bookName)?.localMemberId }
    }

    private lateinit var pieChart: PieChart
    private lateinit var rvInsightCards: RecyclerView
    private lateinit var tvInsightEmpty: TextView
    private lateinit var insightCardAdapter: InsightCardAdapter
    private lateinit var rvCategoryList: RecyclerView
    private lateinit var categoryAdapter: CategoryStatsAdapter
    private var isCategoryExpense = true


    private lateinit var tvTotalExpense: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvDailyAvg: TextView
    private lateinit var tvTotalExpenseLabel: TextView
    private lateinit var tvTotalIncomeLabel: TextView
    private lateinit var tvTotalTransfer: TextView
    private lateinit var tvTotalRepayment: TextView
    private lateinit var tvTotalRefund: TextView
    private lateinit var tvBudgetStatus: TextView
    private lateinit var tvRecurringStatus: TextView
    private lateinit var tvDateSelector: TextView
    private lateinit var tvDailyAvgLabel: TextView
    private lateinit var btnModeMonth: TextView
    private lateinit var btnModeYear: TextView
    private lateinit var btnCategoryExpense: View
    private lateinit var btnCategoryIncome: View
    private lateinit var indicatorModeMonth: View
    private lateinit var indicatorModeYear: View

    private lateinit var rowTransfer: View
    private lateinit var rowRepayment: View
    private lateinit var rowRefund: View
    private lateinit var layoutOverviewExtra: View
    private lateinit var layoutCategoryTypeSwitcher: View
    private lateinit var tvCategoryReportTitle: TextView
    private lateinit var rowRecurringEntry: View
    private lateinit var layoutMemberStatsSection: View
    private lateinit var layoutMemberStats: LinearLayout
    private lateinit var ivOverviewExpand: View
    private lateinit var btnPrevDate: ImageView
    private lateinit var btnNextDate: ImageView

    private lateinit var emptyStateContainer: View
    private lateinit var statsContentContainer: View
    private lateinit var topPanel: View
    private var topPanelBaseMarginTop: Int = 0
    private lateinit var nsvStats: NestedScrollView
    private lateinit var layoutModeSwitcher: View
    private var modeSwitcherRevealProgress: Float = 1f

    private var isOverviewExpanded = false
    private var hasAppliedBudgetModeDefaultExpansion = false
    private var hasSharedMembers = false
    private var lastModeIsMonth: Boolean? = null
    private var lastDateLabel: String? = null
    private var lastHostSyncSignature: String? = null
    private var lastScreenRenderKey: Long? = null
    private var lastCategoryListRenderKey: Long? = null
    private var lastChartRenderKey: Long? = null
    private var chartRenderJob: Job? = null
    private var featureEntryStatusJob: Job? = null
    private var memberStatsJob: Job? = null
    private var budgetOverviewJob: Job? = null
    private var hostSyncJob: Job? = null
    private var lastFeatureEntryStatusKey: String? = null
    private var hasPlayedEnterAnimation = false
    private var lastModeSwitchAnimAt = 0L
    private var frameCallbackPosted = false
    private var lastFrameNs = 0L
    private var frameSampleUntilMs = 0L
    private var perfStage = "idle"
    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        if (!frameCallbackPosted) return@FrameCallback
        if (lastFrameNs != 0L) {
            val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
            if (deltaMs >= 24.0) {
                Log.w(TAG, "jank frame: ${"%.1f".format(Locale.US, deltaMs)}ms stage=$perfStage")
            }
        }
        lastFrameNs = frameTimeNanos
        if (SystemClock.elapsedRealtime() <= frameSampleUntilMs) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            frameCallbackPosted = false
            lastFrameNs = 0L
            perfStage = "idle"
            Log.d(TAG, "jank monitor stop: timeout")
        }
    }

    private val chartColors = listOf(
        "#FF9800", "#FF5722", "#00C853", "#8BC34A", "#2196F3", "#03A9F4", "#9C27B0", "#E91E63"
    ).map { Color.parseColor(it) }

    private val dfDateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_stats, container, false)
        initViews(root)
        setupListeners(root)
        observeViewModel()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        syncHostSelectionIfNeeded("onViewCreated")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.getDatabase(requireContext().applicationContext)
                    .budgetDao()
                    .observeAll()
                    .collectLatest { updateUI(viewModel.uiState.value) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncHostSelectionIfNeeded("onResume")
        refreshFeatureEntryStatus()
        if (!isHidden) SharedSyncScheduler.enqueueFullNow(requireContext())
        val state = viewModel.uiState.value
        updateUI(state)
        playEnterAnimationIfNeeded()
        startJankMonitor("onResume")
    }

    override fun onPause() {
        stopJankMonitor("onPause")
        super.onPause()
    }

    /**
     * MainActivity 使用 hide/show 管理 Tab，切换 Tab 时不会触发 onResume，
     * 需要在 onHiddenChanged 中同步日期。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Fragment 从隐藏变为可见（即切换到统计 Tab）
            syncHostSelectionIfNeeded("onHiddenChanged:show")
            refreshFeatureEntryStatus()
            SharedSyncScheduler.enqueueFullNow(requireContext())
            startJankMonitor("onHiddenChanged:show")
        } else {
            stopJankMonitor("onHiddenChanged:hidden")
        }
    }

    /**
     * 将统计页日期对齐到首页账单页正在查看的年月：
     * - 月模式时：显示同年同月（如 2025-03）
     * - 年模式时：显示同年（如 2025）
     */
    private fun syncDateFromHomeIfNeeded() {
        val homeState = homeViewModel.uiState.value
        val targetYear = homeState.selectedYear
        val targetMonth = (homeState.selectedMonth - 1).coerceIn(0, 11)
        val statsState = viewModel.uiState.value

        if (statsState.year != targetYear || statsState.month != targetMonth) {
            viewModel.setYearMonth(targetYear, targetMonth)
        }
    }

    private fun syncHostSelectionIfNeeded(reason: String) {
        val homeState = homeViewModel.uiState.value
        val targetYear = homeState.selectedYear
        val targetMonth = (homeState.selectedMonth - 1).coerceIn(0, 11)
        val globalBook = BookAccountManager.normalizeBookName(BookAccountManager.getSelectedBook(requireContext()))
        hostSyncJob?.cancel()
        hostSyncJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val scope = withContext(Dispatchers.IO) {
                LedgerViewScopeStore.resolve(context, AppDatabase.getDatabase(context), globalBook)
            }
            val targetBookFilter = scope.singleBookName
            val signature = "$targetYear|$targetMonth|${scope.signature}"
            val current = viewModel.uiState.value
            if (
                signature != lastHostSyncSignature ||
                current.year != targetYear ||
                current.month != targetMonth ||
                current.viewScope?.signature != scope.signature
            ) {
                lastHostSyncSignature = signature
                Log.d(TAG, "syncHostSelectionIfNeeded: reason=$reason, signature=$signature")
                viewModel.syncHostSelection(targetYear, targetMonth, targetBookFilter, scope)
            }
        }
    }

    private fun syncHomeDateFromStatsIfNeeded(state: StatsUiState) {
        if (!isAdded || isHidden || !isVisible || !isResumed) return
        if (!state.isMonthMode) return
        if (state.forcedStartTime != null || state.forcedEndTime != null) return

        val targetYear = state.year
        val targetMonth = state.month + 1
        val homeState = homeViewModel.uiState.value
        if (homeState.selectedYear != targetYear || homeState.selectedMonth != targetMonth) {
            homeViewModel.setMonth(targetYear, targetMonth)
        }
    }

    private fun initViews(root: View) {
        topPanel = root.findViewById(R.id.stats_top_panel)
        topPanelBaseMarginTop = (topPanel.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        nsvStats = root.findViewById(R.id.nsv_stats)
        layoutModeSwitcher = root.findViewById(R.id.layout_mode_switcher)
        layoutModeSwitcher.bringToFront()
        layoutModeSwitcher.post { layoutModeSwitcher.bringToFront() }
        applyModeSwitcherProgress()

        pieChart = root.findViewById(R.id.pie_chart)

        rvInsightCards = root.findViewById(R.id.rvInsightCards)
        tvInsightEmpty = root.findViewById(R.id.tv_insight_empty)
        rvInsightCards.layoutManager = LinearLayoutManager(context)
        rvInsightCards.isNestedScrollingEnabled = false
        rvInsightCards.overScrollMode = View.OVER_SCROLL_NEVER
        rvInsightCards.itemAnimator = null
        insightCardAdapter = InsightCardAdapter()
        rvInsightCards.adapter = insightCardAdapter

        tvTotalExpense = root.findViewById(R.id.tv_total_expense)
        tvTotalIncome = root.findViewById(R.id.tv_total_income)
        tvBalance = root.findViewById(R.id.tv_balance)
        tvDailyAvg = root.findViewById(R.id.tv_daily_avg)
        tvTotalExpenseLabel = root.findViewById(R.id.tv_total_expense_label)
        tvTotalIncomeLabel = root.findViewById(R.id.tv_total_income_label)
        tvTotalTransfer = root.findViewById(R.id.tv_total_transfer)
        tvTotalRepayment = root.findViewById(R.id.tv_total_repayment)
        tvTotalRefund = root.findViewById(R.id.tv_total_refund)
        tvBudgetStatus = root.findViewById(R.id.tv_budget_status)
        tvRecurringStatus = root.findViewById(R.id.tv_recurring_status)
        tvDateSelector = root.findViewById(R.id.tv_date_selector)
        tvDailyAvgLabel = root.findViewById(R.id.tv_daily_avg_label)
        btnModeMonth = root.findViewById(R.id.btn_mode_month)
        btnModeYear = root.findViewById(R.id.btn_mode_year)
        btnCategoryExpense = root.findViewById(R.id.btn_category_expense)
        btnCategoryIncome = root.findViewById(R.id.btn_category_income)
        indicatorModeMonth = root.findViewById(R.id.indicator_mode_month)
        indicatorModeYear = root.findViewById(R.id.indicator_mode_year)

        rowTransfer = root.findViewById(R.id.row_total_transfer)
        rowRepayment = root.findViewById(R.id.row_total_repayment)
        rowRefund = root.findViewById(R.id.row_total_refund)
        layoutOverviewExtra = root.findViewById(R.id.layout_overview_extra)
        layoutCategoryTypeSwitcher = root.findViewById(R.id.layout_category_type_switcher)
        tvCategoryReportTitle = root.findViewById(R.id.tv_category_report_title)
        rowRecurringEntry = root.findViewById(R.id.row_recurring_entry)
        layoutMemberStatsSection = root.findViewById(R.id.layout_member_stats_section)
        layoutMemberStats = root.findViewById(R.id.layout_member_stats)
        ivOverviewExpand = root.findViewById(R.id.iv_overview_expand)
        btnPrevDate = root.findViewById(R.id.btn_prev_date)
        btnNextDate = root.findViewById(R.id.btn_next_date)

        emptyStateContainer = root.findViewById(R.id.empty_state_container)
        statsContentContainer = root.findViewById(R.id.stats_content_container)

        rvCategoryList = root.findViewById(R.id.rv_category_list)
        rvCategoryList.layoutManager = LinearLayoutManager(context)
        rvCategoryList.isNestedScrollingEnabled = false
        rvCategoryList.overScrollMode = View.OVER_SCROLL_NEVER
        rvCategoryList.itemAnimator = null
        categoryAdapter = CategoryStatsAdapter(
            chartColors = chartColors,
            items = emptyList(),
            isExpense = isCategoryExpense,
            currencySymbol = "¥"
        ) { categoryName ->
            showSubCategoryDetails(categoryName)
        }
        rvCategoryList.adapter = categoryAdapter

        setupPieChart()
        updateOverviewExpandState()
        applyStatusBarInset(root)
    }

    private fun applyStatusBarInset(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Top area uses a fixed 56dp status spacer in XML, so no runtime offset is needed.
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupListeners(root: View) {
        root.findViewById<View>(R.id.btn_book_switch).setOnClickListener {
            showBookFilterDialog()
        }

        root.findViewById<View>(R.id.btn_filter).setOnClickListener {
            showCustomFilterSheet()
        }

        btnCategoryExpense.setOnClickListener {
            if (!isCategoryExpense) {
                isCategoryExpense = true
                (statsContentContainer as? ViewGroup)?.let { androidx.transition.TransitionManager.beginDelayedTransition(it) }
                updateUI(viewModel.uiState.value)
            }
        }

        btnCategoryIncome.setOnClickListener {
            if (isCategoryExpense) {
                isCategoryExpense = false
                (statsContentContainer as? ViewGroup)?.let { androidx.transition.TransitionManager.beginDelayedTransition(it) }
                updateUI(viewModel.uiState.value)
            }
        }

        btnModeMonth.setOnClickListener {
            viewModel.setMode(true)
        }

        btnModeYear.setOnClickListener {
            viewModel.setMode(false)
        }

        btnPrevDate.setOnClickListener {
            viewModel.prevDate()
        }

        btnNextDate.setOnClickListener {
            viewModel.nextDate()
        }

        root.findViewById<View>(R.id.layout_date_selector).setOnClickListener {
            showUnifiedMonthYearPicker()
        }
        nsvStats.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            updateModeSwitcherByScroll(scrollY - oldScrollY, scrollY)
        }
        rvCategoryList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateModeSwitcherByScroll(dy, nsvStats.scrollY)
            }
        })

        root.findViewById<View>(R.id.btn_overview_expand_area).setOnClickListener {
            isOverviewExpanded = !isOverviewExpanded
            updateOverviewExpandState()
        }

        rowTransfer.setOnClickListener {
            showOverviewBillList(
                title = "转账账单",
                bills = viewModel.getTransferBills(),
                emptyMessage = "暂无转账账单"
            )
        }
        rowRepayment.setOnClickListener {
            showOverviewBillList(
                title = "还款账单",
                bills = viewModel.getRepaymentBills(),
                emptyMessage = "暂无还款账单"
            )
        }
        rowRefund.setOnClickListener {
            showOverviewBillList(
                title = "退款账单",
                bills = viewModel.getRefundBills(),
                emptyMessage = "暂无退款账单"
            )
        }

        // P0-1: 预算管理入口
        root.findViewById<View>(R.id.row_budget_entry)?.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.taostudio.tapaccounting.ui.budget.BudgetManageActivity::class.java))
        }

        // P0-4: 周期账单入口
        root.findViewById<View>(R.id.row_recurring_entry)?.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.taostudio.tapaccounting.ui.recurring.RecurringBillsActivity::class.java))
        }
    }

    private fun updateModeTabStyles(isMonthMode: Boolean) {
        btnModeMonth.setTextColor(Color.parseColor(if (isMonthMode) "#111827" else "#6B7280"))
        btnModeYear.setTextColor(Color.parseColor(if (isMonthMode) "#6B7280" else "#111827"))
        indicatorModeMonth.setBackgroundColor(Color.parseColor(if (isMonthMode) "#111827" else "#00000000"))
        indicatorModeYear.setBackgroundColor(Color.parseColor(if (isMonthMode) "#00000000" else "#111827"))
    }

    private fun updateCategoryTabStyles(isExpenseTab: Boolean) {
        val expTv = btnCategoryExpense.findViewByType(TextView::class.java)
        val incTv = btnCategoryIncome.findViewByType(TextView::class.java)
        expTv?.setTextColor(Color.parseColor(if (isExpenseTab) "#111827" else "#6B7280"))
        incTv?.setTextColor(Color.parseColor(if (isExpenseTab) "#6B7280" else "#111827"))
        btnCategoryExpense.setBackgroundResource(if (isExpenseTab) R.drawable.bg_stats_segmented_selected else R.drawable.bg_stats_segmented_unselected)
        btnCategoryIncome.setBackgroundResource(if (isExpenseTab) R.drawable.bg_stats_segmented_unselected else R.drawable.bg_stats_segmented_selected)
        btnCategoryExpense.elevation = if (isExpenseTab) 2f else 0f
        btnCategoryIncome.elevation = if (isExpenseTab) 0f else 2f
    }

    private fun <T : View> View.findViewByType(clazz: Class<T>): T? {
        if (clazz.isInstance(this)) return this as T
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                val child = getChildAt(i).findViewByType(clazz)
                if (child != null) return child
            }
        }
        return null
    }

    private fun animateContentTransition() {
        val container = statsContentContainer ?: return
        container.animate().cancel()
        container.alpha = 0.6f
        container.translationY = 12f
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(UiMotion.NORMAL)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .start()
        val overview = view?.findViewById<View>(R.id.tv_balance)?.parent as? View
        overview?.let {
            it.animate().cancel()
            it.alpha = 0.7f
            it.animate()
                .alpha(1f)
                .setDuration(UiMotion.FAST)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
        }
    }

    private fun updateOverviewExpandState() {
        val budgetMode = isStatsBudgetModeEnabled(viewModel.uiState.value)
        layoutOverviewExtra.visibility =
            if (isOverviewExpanded && !budgetMode) View.VISIBLE else View.GONE
        layoutMemberStatsSection.visibility =
            if (isOverviewExpanded && hasSharedMembers) View.VISIBLE else View.GONE
        if (ivOverviewExpand is android.widget.ImageView) {
            (ivOverviewExpand as android.widget.ImageView).animate().rotation(if (isOverviewExpanded) 180f else 0f).setDuration(180).start()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 收集洞察卡片
                launch {
                    viewModel.insightCards.collect { cards: List<com.taostudio.tapaccounting.logic.insight.InsightCardModel> ->
                        val enabled = Prefs.isInsightCardsEnabled(requireContext())
                        if (enabled && cards.isNotEmpty()) {
                            insightCardAdapter.submitList(cards)
                            rvInsightCards.visibility = View.VISIBLE
                            tvInsightEmpty.visibility = View.GONE
                        } else if (enabled) {
                            insightCardAdapter.submitList(emptyList())
                            rvInsightCards.visibility = View.GONE
                            tvInsightEmpty.visibility = View.VISIBLE
                        } else {
                            insightCardAdapter.submitList(emptyList())
                            rvInsightCards.visibility = View.GONE
                            tvInsightEmpty.visibility = View.GONE
                        }
                    }
                }
                viewModel.uiState.collect { state ->
                    if (state.isLoading && state.bills.isEmpty() && lastScreenRenderKey == null) {
                        return@collect
                    }
                    val screenRenderKey = buildScreenRenderKey(state)
                    if (screenRenderKey != lastScreenRenderKey) {
                        updateUI(state)
                        lastScreenRenderKey = screenRenderKey
                    }
                    syncHomeDateFromStatsIfNeeded(state)
                }
            }
        }
    }

    private fun updateUI(state: StatsUiState) {
        perfStage = "updateUI:bindSummary"
        val updateStart = SystemClock.elapsedRealtime()
        val modeChanged = lastModeIsMonth != null && lastModeIsMonth != state.isMonthMode
        val symbol = state.selectedCurrency?.let { CurrencyManager.getSymbol(it) } ?: "¥"

        tvDateSelector.text = if (state.dateLabel.isNotBlank()) {
            state.dateLabel
        } else if (state.isMonthMode) {
            String.format(Locale.getDefault(), "%04d-%02d", state.year, state.month + 1)
        } else {
            String.format(Locale.getDefault(), "%04d", state.year)
        }

        val budgetMode = renderStatsOverview(state, symbol)
        tvTotalTransfer.text = "$symbol${AmountFormatHelper.formatAmount(state.totalTransfer)}"
        tvTotalRepayment.text = "$symbol${AmountFormatHelper.formatAmount(state.totalRepayment)}"
        tvTotalRefund.text = "$symbol${AmountFormatHelper.formatAmount(state.totalRefund)}"
        renderMemberStats(state, symbol)
        btnPrevDate.setImageResource(if (state.isMonthMode) R.drawable.ic_chevron_left else R.drawable.ic_chevrons_left)
        btnNextDate.setImageResource(if (state.isMonthMode) R.drawable.ic_chevron_right else R.drawable.ic_chevrons_right)
        updateFeatureEntryStatus(state)
        btnPrevDate.contentDescription = if (state.isMonthMode) "上个月" else "上一年"
        btnNextDate.contentDescription = if (state.isMonthMode) "下个月" else "下一年"
        updateModeTabStyles(state.isMonthMode)
        updateCategoryTabStyles(isCategoryExpense)
        if (modeChanged) {
            playModeSwitchAnimation(state)
        } else if (lastDateLabel != null && lastDateLabel != tvDateSelector.text.toString()) {
            animateContentTransition()
        }
        lastModeIsMonth = state.isMonthMode
        lastDateLabel = tvDateSelector.text.toString()

        val hasAnyCategoryData = state.categoryStatsExpense.isNotEmpty() ||
            (!budgetMode && state.categoryStatsIncome.isNotEmpty())
        
        emptyStateContainer.visibility = if (!hasAnyCategoryData && !state.isLoading) View.VISIBLE else View.GONE
        statsContentContainer.visibility = if (hasAnyCategoryData) View.VISIBLE else View.GONE

        val list = if (isCategoryExpense) state.categoryStatsExpense else state.categoryStatsIncome
        
        // 处理当前 Tab 的空状态展示
        val layoutTabEmptyState = view?.findViewById<View>(R.id.layout_tab_empty_state)
        val tvTabEmptyText = view?.findViewById<TextView>(R.id.tv_tab_empty_text)
        val layoutCategoryTip = view?.findViewById<View>(R.id.layout_category_tip)
        
        if (list.isNotEmpty()) {
            pieChart.visibility = View.VISIBLE
            layoutTabEmptyState?.visibility = View.GONE
            layoutCategoryTip?.visibility = View.VISIBLE
        } else {
            pieChart.visibility = View.INVISIBLE
            layoutTabEmptyState?.visibility = View.VISIBLE
            layoutCategoryTip?.visibility = View.INVISIBLE
            tvTabEmptyText?.text = if (isCategoryExpense) "本期暂无支出数据" else "本期暂无收入数据"
        }

        val listRenderKey = buildCategoryListRenderKey(list, isCategoryExpense, symbol)
        val shouldSkipTransientEmptyList = state.isLoading && list.isEmpty() && lastCategoryListRenderKey == null
        if (!shouldSkipTransientEmptyList && listRenderKey != lastCategoryListRenderKey) {
            perfStage = "updateUI:submitList"
            categoryAdapter.submitList(list, isCategoryExpense, symbol)
            lastCategoryListRenderKey = listRenderKey
        }

        perfStage = "updateUI:scheduleChart"
        if (!shouldSkipTransientEmptyList) {
            scheduleCategoryChartUpdate(state, list.size)
        }

        val cost = SystemClock.elapsedRealtime() - updateStart
        Log.d(TAG, "updateUI done: bills=${state.bills.size}, list=${list.size}, costMs=$cost")
        perfStage = "idle"
    }

    private fun renderStatsOverview(state: StatsUiState, symbol: String): Boolean {
        val budgetMode = isStatsBudgetModeEnabled(state)
        if (budgetMode && !hasAppliedBudgetModeDefaultExpansion) {
            isOverviewExpanded = true
            hasAppliedBudgetModeDefaultExpansion = true
        } else if (!budgetMode) {
            hasAppliedBudgetModeDefaultExpansion = false
        }
        tvBalance.text = getString(
            if (budgetMode) R.string.budget_overview_title else R.string.income_expense_overview
        )
        layoutCategoryTypeSwitcher.visibility = if (budgetMode) View.GONE else View.VISIBLE
        tvCategoryReportTitle.text = getString(
            if (budgetMode) R.string.expense_category_analysis else R.string.proportion_analysis
        )
        rowRecurringEntry.visibility = if (budgetMode) View.GONE else View.VISIBLE
        if (budgetMode) {
            isCategoryExpense = true
            tvTotalExpenseLabel.setText(R.string.budget_amount_short)
            tvTotalIncomeLabel.setText(R.string.budget_spent_short)
            tvDailyAvgLabel.setText(R.string.budget_remaining_short)
            tvTotalIncome.text = "$symbol${AmountFormatHelper.formatAmount(state.totalExpense)}"
            tvTotalExpense.setTextColor(requireContext().getColor(R.color.stats_segmented_text_active))
            tvTotalIncome.setTextColor(requireContext().getColor(R.color.expense_color))
            renderBudgetModeAmounts(state, symbol)
        } else {
            budgetOverviewJob?.cancel()
            tvTotalExpenseLabel.setText(R.string.total_expense)
            tvTotalIncomeLabel.setText(R.string.total_income)
            tvDailyAvgLabel.text = if (state.isMonthMode) "日均支出" else "月均支出"
            tvTotalExpense.text = "$symbol${AmountFormatHelper.formatAmount(state.totalExpense)}"
            tvTotalIncome.text = "$symbol${AmountFormatHelper.formatAmount(state.totalIncome)}"
            tvDailyAvg.text = "$symbol${AmountFormatHelper.formatAmount(state.dailyAvg)}"
            tvTotalExpense.setTextColor(requireContext().getColor(R.color.expense_color))
            tvTotalIncome.setTextColor(requireContext().getColor(R.color.income_color))
            tvDailyAvg.setTextColor(requireContext().getColor(R.color.stats_segmented_text_active))
        }
        updateCategoryTabStyles(isCategoryExpense)
        updateOverviewExpandState()
        return budgetMode
    }

    private fun renderBudgetModeAmounts(state: StatsUiState, symbol: String) {
        budgetOverviewJob?.cancel()
        tvTotalExpense.text = "—"
        tvDailyAvg.text = "—"
        tvDailyAvg.setTextColor(requireContext().getColor(R.color.stats_segmented_text_active))
        val range = budgetStatsYearMonthRange(state) ?: return
        budgetOverviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val (totalBudget, spentAmount) = withContext(Dispatchers.IO) {
                val budgetBookName = state.viewScope?.singleBookName
                    ?: state.selectedBookName
                    ?: ""
                val db = AppDatabase.getDatabase(requireContext())
                val budgets = db.budgetDao().getTotalBudgetsBetween(
                        startYearMonth = range.first,
                        endYearMonth = range.second,
                        bookName = budgetBookName
                    )
                    .filter { state.selectedCurrency == null || state.selectedCurrency == it.currency }
                val budgetTotal = budgets.takeIf { it.isNotEmpty() }?.sumOf { it.amount }
                val spent = if (state.selectedCurrency == null) {
                    val budgetService = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
                    yearMonthsBetween(range).sumOf { yearMonth ->
                        budgetService.getMonthSpend(budgetBookName, categoryId = null, yearMonth)
                    }
                } else {
                    state.totalExpense
                }
                budgetTotal to spent
            }
            if (!isStatsBudgetModeEnabled(state)) return@launch
            tvTotalIncome.text = "$symbol${AmountFormatHelper.formatAmount(spentAmount)}"
            if (totalBudget == null) {
                tvTotalExpense.setText(R.string.budget_not_set_short)
                tvDailyAvg.text = "—"
                return@launch
            }
            val remaining = totalBudget - spentAmount
            tvTotalExpense.text = "$symbol${AmountFormatHelper.formatAmount(totalBudget)}"
            tvDailyAvg.text = "$symbol${AmountFormatHelper.formatAmount(remaining)}"
            tvDailyAvg.setTextColor(requireContext().getColor(
                if (remaining < 0.0) R.color.expense_color else R.color.income_color
            ))
        }
    }

    private fun yearMonthsBetween(range: Pair<String, String>): List<String> {
        val startParts = range.first.split('-')
        val endParts = range.second.split('-')
        if (startParts.size != 2 || endParts.size != 2) return emptyList()
        var year = startParts[0].toIntOrNull() ?: return emptyList()
        var month = startParts[1].toIntOrNull() ?: return emptyList()
        val endYear = endParts[0].toIntOrNull() ?: return emptyList()
        val endMonth = endParts[1].toIntOrNull() ?: return emptyList()
        if (month !in 1..12 || endMonth !in 1..12 || year > endYear ||
            (year == endYear && month > endMonth)
        ) return emptyList()
        return buildList {
            while (year < endYear || year == endYear && month <= endMonth) {
                add(String.format(Locale.US, "%04d-%02d", year, month))
                month += 1
                if (month > 12) {
                    month = 1
                    year += 1
                }
            }
        }
    }

    private fun isStatsBudgetModeEnabled(state: StatsUiState = viewModel.uiState.value): Boolean {
        if (state.viewScope?.supportsBudgetSummary == false) return false
        return Prefs.isStatsBudgetModeEnabled(
            requireContext(),
            state.viewScope?.legacyBookName ?: BookAccountManager.getSelectedBook(requireContext())
        )
    }

    private fun budgetStatsYearMonthRange(state: StatsUiState): Pair<String, String>? {
        if (state.forcedStartTime == null && state.forcedEndTime == null) {
            return if (state.isMonthMode) {
                val month = String.format(Locale.US, "%04d-%02d", state.year, state.month + 1)
                month to month
            } else {
                String.format(Locale.US, "%04d-01", state.year) to
                    String.format(Locale.US, "%04d-12", state.year)
            }
        }
        val start = state.forcedStartTime ?: return null
        val end = state.forcedEndTime ?: return null
        if (end < start || end == Long.MAX_VALUE) return null
        val startCalendar = Calendar.getInstance().apply { timeInMillis = start }
        val endCalendar = Calendar.getInstance().apply { timeInMillis = end }
        val startsAtMonthBoundary = startCalendar.get(Calendar.DAY_OF_MONTH) == 1 &&
            startCalendar.get(Calendar.HOUR_OF_DAY) == 0 &&
            startCalendar.get(Calendar.MINUTE) == 0 &&
            startCalendar.get(Calendar.SECOND) == 0 &&
            startCalendar.get(Calendar.MILLISECOND) == 0
        val expectedEnd = (endCalendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        if (!startsAtMonthBoundary || end != expectedEnd) return null
        fun Calendar.yearMonth(): String = String.format(
            Locale.US,
            "%04d-%02d",
            get(Calendar.YEAR),
            get(Calendar.MONTH) + 1
        )
        return startCalendar.yearMonth() to endCalendar.yearMonth()
    }

    private fun renderMemberStats(state: StatsUiState, symbol: String) {
        memberStatsJob?.cancel()
        val bookName = state.selectedBookName ?: run {
            layoutMemberStats.removeAllViews()
            hasSharedMembers = false
            updateMemberStatsVisibility()
            return
        }
        memberStatsJob = viewLifecycleOwner.lifecycleScope.launch {
            val budgetYearMonth = memberBudgetYearMonth(state)
            val supportsMonthlyBudget = budgetYearMonth != null
            val (members, totalBudget) = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext())
                val ledger = db.sharedLedgerDao().getByBookName(bookName)
                    ?: return@withContext emptyList<SharedMember>() to null
                val allMembers = db.sharedMemberDao().getByLedgerId(ledger.id).sortedBy { it.joinOrder }
                val members = if (state.viewScope?.scope?.members == LedgerMemberScope.MINE) {
                    allMembers.filter { it.isLocal }
                } else {
                    allMembers
                }
                val budget = if (supportsMonthlyBudget) {
                    db.budgetDao().getTotalBudget(requireNotNull(budgetYearMonth), bookName)?.takeIf {
                        state.selectedCurrency == null || state.selectedCurrency == it.currency
                    }
                } else {
                    null
                }
                members to budget
            }
            layoutMemberStats.removeAllViews()
            hasSharedMembers = members.isNotEmpty()
            updateMemberStatsVisibility()
            members.forEach { member ->
                val name = member.resolvedName()
                val stat = state.memberStats.firstOrNull { it.memberId == member.memberId }
                    ?: MemberStat(member.memberId, 0.0, 0.0)
                val card = layoutInflater.inflate(R.layout.item_stats_member_summary, layoutMemberStats, false)
                card.findViewById<TextView>(R.id.tv_member_avatar).apply {
                    text = name.firstOrNull()?.toString().orEmpty()
                    setBackgroundResource(
                        if (member.isLocal) R.drawable.bg_stats_member_avatar_local
                        else R.drawable.bg_stats_member_avatar_remote
                    )
                    contentDescription = "$name 的头像"
                }
                card.findViewById<TextView>(R.id.tv_member_name).text = name
                card.findViewById<TextView>(R.id.tv_member_role).text =
                    if (member.isLocal) "我 · 本机成员" else "另一位成员"
                card.findViewById<TextView>(R.id.tv_member_expense).text =
                    "$symbol${AmountFormatHelper.formatAmount(stat.expense)}"
                card.findViewById<TextView>(R.id.tv_member_budget_remaining).apply {
                    val memberLimit = totalBudget?.let {
                        MemberBudgetAllocation.amountFor(it.amount, it.memberBudgetAllocations, member.memberId)
                    }
                    val remaining = memberLimit?.minus(stat.expense)
                    text = when {
                        !supportsMonthlyBudget -> "—"
                        remaining == null -> getString(R.string.budget_not_set_short)
                        else -> "$symbol${AmountFormatHelper.formatAmount(remaining)}"
                    }
                    setTextColor(requireContext().getColor(
                        if (remaining != null && remaining < 0.0) R.color.expense_color
                        else R.color.stats_segmented_text_active
                    ))
                }
                layoutMemberStats.addView(card)
            }
        }
    }

    private fun memberBudgetYearMonth(state: StatsUiState): String? {
        if (state.forcedStartTime == null && state.forcedEndTime == null) {
            return if (state.isMonthMode) {
                String.format(Locale.US, "%04d-%02d", state.year, state.month + 1)
            } else {
                null
            }
        }
        val start = state.forcedStartTime ?: return null
        val end = state.forcedEndTime ?: return null
        val startCalendar = Calendar.getInstance().apply { timeInMillis = start }
        val isMonthStart = startCalendar.get(Calendar.DAY_OF_MONTH) == 1 &&
            startCalendar.get(Calendar.HOUR_OF_DAY) == 0 &&
            startCalendar.get(Calendar.MINUTE) == 0 &&
            startCalendar.get(Calendar.SECOND) == 0 &&
            startCalendar.get(Calendar.MILLISECOND) == 0
        if (!isMonthStart) return null
        val expectedEnd = (startCalendar.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }.timeInMillis
        return if (end == expectedEnd) {
            String.format(
                Locale.US,
                "%04d-%02d",
                startCalendar.get(Calendar.YEAR),
                startCalendar.get(Calendar.MONTH) + 1
            )
        } else {
            null
        }
    }

    private fun updateMemberStatsVisibility() {
        layoutMemberStatsSection.visibility =
            if (isOverviewExpanded && hasSharedMembers) View.VISIBLE else View.GONE
    }

    private fun updateFeatureEntryStatus(state: StatsUiState) {
        val yearMonth = String.format(Locale.getDefault(), "%04d-%02d", state.year, state.month + 1)
        val normalizedBook = state.selectedBookName
            ?.let { BookAccountManager.normalizeBookName(it) }
            .orEmpty()
        val daoBookName = if (normalizedBook == BookAccountManager.ALL_BOOK) "" else normalizedBook
        val supportsBudgetSummary = state.viewScope?.supportsBudgetSummary != false
        val key = "$yearMonth|$daoBookName|${state.viewScope?.signature.orEmpty()}"
        if (key == lastFeatureEntryStatusKey) return
        lastFeatureEntryStatusKey = key
        featureEntryStatusJob?.cancel()
        featureEntryStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                // P2-9: use application context
        val db = AppDatabase.getDatabase(requireNotNull(activity).application)
                val budgetService = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
                val budgets = if (supportsBudgetSummary) {
                    budgetService.getMonthBudgetsWithProgress(daoBookName, yearMonth)
                } else {
                    emptyList()
                }
                val budgetProgress = budgets
                    .filter { it.budget.categoryId != null && it.progress.status == BudgetService.BudgetStatus.EXCEEDED }
                    .maxByOrNull { -it.progress.remaining }
                    ?: budgets.firstOrNull { it.budget.categoryId == null }
                    ?: budgets.maxByOrNull { it.progress.percent }

                fun isBookInView(bookName: String): Boolean {
                    val scope = state.viewScope
                    return if (scope != null) {
                        BookAccountManager.normalizeBookName(bookName) in scope.selectedBookNames
                    } else {
                        daoBookName.isBlank() || BookAccountManager.normalizeBookName(bookName) == daoBookName
                    }
                }

                val pendingCount = db.recurringPatternDao()
                    .getByStatus(RecurringStatus.SUGGESTED)
                    .count { isBookInView(it.bookName) }
                val confirmed = db.recurringPatternDao()
                    .getByStatus(RecurringStatus.CONFIRMED)
                    .filter { isBookInView(it.bookName) }
                val now = System.currentTimeMillis()
                val dueSoonAt = now + 3L * 24L * 3600_000L
                val dueSoonCount = confirmed.count { pattern ->
                    val expectedAt = pattern.nextExpectedAt ?: return@count false
                    expectedAt in now..dueSoonAt
                }

                val budgetText = if (!supportsBudgetSummary) {
                    "当前查看范围不汇总预算"
                } else budgetProgress?.let {
                    val progress = it.progress
                    when {
                        progress.status == BudgetService.BudgetStatus.EXCEEDED && it.budget.categoryId != null ->
                            getString(
                                R.string.budget_entry_over_category_fmt,
                                it.budget.categoryName ?: getString(R.string.budget_monthly_total),
                                -progress.remaining
                            )
                        progress.status == BudgetService.BudgetStatus.EXCEEDED ->
                            getString(R.string.budget_status_exceeded)
                        progress.remaining >= 0 ->
                            getString(
                                R.string.budget_entry_remaining_daily_fmt,
                                progress.remaining,
                                progress.dailyRemainingAllowance
                            )
                        else -> getString(
                            R.string.budget_entry_status_fmt,
                            progress.percent * 100
                        )
                    }
                } ?: getString(R.string.budget_entry_status_empty)

                val recurringText = when {
                    pendingCount > 0 -> getString(R.string.recurring_entry_status_pending, pendingCount)
                    dueSoonCount > 0 -> getString(R.string.recurring_entry_status_due, dueSoonCount)
                    confirmed.isNotEmpty() -> getString(R.string.recurring_entry_status_confirmed, confirmed.size)
                    else -> getString(R.string.recurring_entry_status_empty)
                }

                budgetText to recurringText
            }
            tvBudgetStatus.text = status.first
            tvRecurringStatus.text = status.second
        }
    }

    private fun refreshFeatureEntryStatus() {
        lastFeatureEntryStatusKey = null
        if (::tvBudgetStatus.isInitialized && ::tvRecurringStatus.isInitialized) {
            updateFeatureEntryStatus(viewModel.uiState.value)
        }
    }

    private fun scheduleCategoryChartUpdate(state: StatsUiState, listSize: Int) {
        val isHeavyRender = state.bills.size > CHART_ANIMATE_MAX_BILLS || listSize > CHART_ANIMATE_MAX_ITEMS
        val delayMs = if (isHeavyRender) CHART_HEAVY_RENDER_DELAY_MS else 0L
        chartRenderJob?.cancel()
        chartRenderJob = viewLifecycleOwner.lifecycleScope.launch {
            val effectiveDelay = if (delayMs <= 0L) 18L else delayMs
            perfStage = if (effectiveDelay == 18L) "chart:nextFrame" else "chart:delayed(${effectiveDelay}ms)"
            delay(effectiveDelay)
            if (!isAdded) return@launch
            perfStage = "chart:delayedRender"
            updateCategoryChart(viewModel.uiState.value)
        }
    }

    private fun playEnterAnimationIfNeeded() {
        if (hasPlayedEnterAnimation) return
        hasPlayedEnterAnimation = true
        val targets = listOf(topPanel, statsContentContainer)
        targets.forEach { view ->
            view.animate().cancel()
            view.alpha = 0.94f
            view.animate()
                .alpha(1f)
                .setDuration(ENTER_ANIM_DURATION_MS)
                .start()
        }
    }

    private fun playModeSwitchAnimation(state: StatsUiState) {
        if (state.bills.size >= CHART_ANIMATE_MAX_BILLS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastModeSwitchAnimAt < MODE_SWITCH_ANIM_MIN_INTERVAL_MS) return
        lastModeSwitchAnimAt = now
        tvDateSelector.animate().cancel()
        tvDateSelector.alpha = 0.86f
        tvDateSelector.animate()
            .alpha(1f)
            .setDuration(UiMotion.FAST)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .start()
        animateContentTransition()
    }

    private fun setupPieChart() {
        pieChart.description.isEnabled = false
        pieChart.legend.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setUsePercentValues(true)
        pieChart.setTransparentCircleAlpha(0)
        pieChart.holeRadius = 58f
        pieChart.rotationAngle = 270f
        pieChart.isRotationEnabled = true
        pieChart.setEntryLabelColor(Color.TRANSPARENT)
        pieChart.setExtraOffsets(22f, 18f, 22f, 18f)
        pieChart.setNoDataText("暂无图表数据")
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
        pieChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val label = (e as? PieEntry)?.label ?: return
                val index = categoryAdapter.findPositionByCategory(label)
                if (index >= 0) {
                    categoryAdapter.pinCategory(label)
                    (rvCategoryList.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                    rvCategoryList.post { rvCategoryList.smoothScrollToPosition(0) }
                }
            }

            override fun onNothingSelected() {
                categoryAdapter.clearPinCategory()
            }
        })
    }

    private fun updateCategoryChart(state: StatsUiState) {
        val targetStats = if (isCategoryExpense) state.categoryStatsExpense else state.categoryStatsIncome
        val total = targetStats.sumOf { it.amount }

        if (targetStats.isNotEmpty() && total > 0) {
            // 按原始顺序（大→小，与下方列表一致）建立「分类名→颜色」映射
            val colorByName = targetStats.mapIndexed { i, stat ->
                stat.categoryName to chartColors[i % chartColors.size]
            }.toMap()

            // 不做 TopN 裁剪：尽量全部显示，仅隐藏占比 < 2% 的分类
            val filteredStats = targetStats.filter { it.percentage >= 2f }.sortedBy { it.amount }
            if (filteredStats.isEmpty()) {
                pieChart.clear()
                pieChart.setNoDataText("暂无占比超过 2% 的分类")
                pieChart.invalidate()
                categoryAdapter.setColorMap(colorByName)
                return
            }
            val pieEntries = filteredStats.map { PieEntry(it.amount.toFloat(), it.categoryName) }
            val sliceColors = filteredStats.map { colorByName[it.categoryName] ?: chartColors[0] }
            val labelSize = when {
                filteredStats.size >= 12 -> 7.5f
                filteredStats.size >= 9 -> 8.0f
                else -> 9.0f
            }

            val pieDataSet = PieDataSet(pieEntries, "").apply {
                colors = sliceColors
                xValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
                yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
                valueLinePart1OffsetPercentage = 100f
                valueLinePart1Length = if (filteredStats.size >= 10) 0.22f else 0.30f
                valueLinePart2Length = if (filteredStats.size >= 10) 0.55f else 0.78f
                selectionShift = 4f
                setValueLineVariableLength(true)
                setUsingSliceColorAsValueLineColor(true)
                valueTextSize = labelSize
                setValueTextColors(sliceColors)
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = ""
                    override fun getPieLabel(value: Float, pieEntry: PieEntry): String {
                        val pct = if (total > 0) (pieEntry.value / total * 100f) else 0f
                        return "${pieEntry.label} ${String.format(java.util.Locale.getDefault(), "%.1f%%", pct)}"
                    }
                }
            }

            pieChart.data = PieData(pieDataSet)
            val visibleTotal = filteredStats.sumOf { it.amount }
            val centerTitle = if (isCategoryExpense) "支出占比" else "收入占比"
            val symbol = state.selectedCurrency?.let { CurrencyManager.getSymbol(it) } ?: "¥"
            pieChart.centerText = "$centerTitle\n$symbol${AmountFormatHelper.formatAmount(visibleTotal)}"
            pieChart.rotationAngle = findBestInitialRotation(pieEntries.map { it.value })
            pieChart.setCenterTextSize(12f)
            pieChart.setCenterTextColor(Color.parseColor("#374151"))
            pieChart.setDrawEntryLabels(false)
            pieChart.animateY(260)

            // 把颜色映射同步给列表 Adapter，使图标背景色/进度条颜色与饼图一致
            categoryAdapter.setColorMap(colorByName)
        } else {
            pieChart.clear()
        }

        pieChart.legend.isEnabled = false
        pieChart.invalidate()
    }

    private fun buildScreenRenderKey(state: StatsUiState): Long {
        var acc = 1469598103934665603L
        acc = (acc xor state.year.toLong()).times(1099511628211L)
        acc = (acc xor state.month.toLong()).times(1099511628211L)
        acc = (acc xor if (state.isMonthMode) 1L else 0L).times(1099511628211L)
        acc = (acc xor state.dateLabel.hashCode().toLong()).times(1099511628211L)
        acc = (acc xor state.totalExpense.toRawBits()).times(1099511628211L)
        acc = (acc xor state.totalIncome.toRawBits()).times(1099511628211L)
        acc = (acc xor state.balance.toRawBits()).times(1099511628211L)
        acc = (acc xor state.dailyAvg.toRawBits()).times(1099511628211L)
        acc = (acc xor state.totalTransfer.toRawBits()).times(1099511628211L)
        acc = (acc xor state.totalRepayment.toRawBits()).times(1099511628211L)
        acc = (acc xor state.totalRefund.toRawBits()).times(1099511628211L)
        acc = (acc xor state.viewScope?.signature.hashCode().toLong()).times(1099511628211L)
        acc = (acc xor categoryStatsFingerprint(state.categoryStatsExpense)).times(1099511628211L)
        acc = (acc xor categoryStatsFingerprint(state.categoryStatsIncome)).times(1099511628211L)
        return acc xor state.bills.size.toLong()
    }

    private fun buildCategoryListRenderKey(
        list: List<CategoryStat>,
        isExpense: Boolean,
        symbol: String
    ): Long {
        var acc = if (isExpense) 1L else 2L
        acc = (acc * 31) + symbol.hashCode().toLong()
        list.forEach {
            acc = (acc * 31) + it.categoryName.hashCode().toLong()
            acc = (acc * 31) + it.amount.toRawBits()
            acc = (acc * 31) + it.percentage.toRawBits().toLong()
            acc = (acc * 31) + it.amountDiffFromLastPeriod.toRawBits()
        }
        return acc
    }

    private fun buildChartRenderKey(
        state: StatsUiState,
        list: List<CategoryStat>,
        visibleTotal: Double
    ): Long {
        var acc = if (isCategoryExpense) 17L else 29L
        acc = (acc * 31) + (state.selectedCurrency?.hashCode()?.toLong() ?: 0L)
        acc = (acc * 31) + state.bills.size.toLong()
        acc = (acc * 31) + visibleTotal.toRawBits()
        list.forEach {
            acc = (acc * 31) + it.categoryName.hashCode().toLong()
            acc = (acc * 31) + it.amount.toRawBits()
            acc = (acc * 31) + it.percentage.toRawBits().toLong()
        }
        return acc
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

    /**
     * 在首次展示前自动选择一个标签更不容易重叠的角度。
     * 原理：遍历候选角度，按左右两侧标签中心 y 值估算相邻拥挤程度，取最小值。
     */
    private fun findBestInitialRotation(values: List<Float>): Float {
        if (values.size <= 2) return 270f
        val total = values.sum().takeIf { it > 0f } ?: return 270f
        val sweeps = values.map { it / total * 360f }

        var bestAngle = 270f
        var bestScore = Float.MAX_VALUE
        for (candidate in 0 until 360 step 6) {
            val score = computeOverlapScore(sweeps, candidate.toFloat(), minGap = 0.15f)
            if (score < bestScore) {
                bestScore = score
                bestAngle = candidate.toFloat()
            }
        }
        return bestAngle
    }

    private fun computeOverlapScore(sweeps: List<Float>, rotationAngle: Float, minGap: Float): Float {
        var start = rotationAngle
        val leftY = mutableListOf<Float>()
        val rightY = mutableListOf<Float>()

        sweeps.forEach { sweep ->
            val center = start + sweep / 2f
            val rad = Math.toRadians(center.toDouble())
            val y = sin(rad).toFloat()
            val x = cos(rad).toFloat()
            if (x >= 0f) rightY.add(y) else leftY.add(y)
            start += sweep
        }

        fun sideScore(points: List<Float>): Float {
            if (points.size <= 1) return 0f
            val sorted = points.sorted()
            var score = 0f
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                if (gap < minGap) score += (minGap - gap)
            }
            return score
        }

        return sideScore(leftY) + sideScore(rightY)
    }

    private fun showMonthYearPicker() {
        val state = viewModel.uiState.value

        if (state.isMonthMode) {
            val pickerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 20)
            }

            val npYear = NumberPicker(requireContext()).apply {
                minValue = 2000
                maxValue = 2100
                value = state.year
            }

            val npMonth = NumberPicker(requireContext()).apply {
                minValue = 1
                maxValue = 12
                value = state.month + 1
                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
            }

            pickerLayout.addView(npYear)
            pickerLayout.addView(npMonth)

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.select_month))
                .setView(pickerLayout)
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    viewModel.setYearMonth(npYear.value, npMonth.value - 1)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            OverlayDialogs.showPageCenterDialog(dialog, requireContext())
        } else {
            val npYear = NumberPicker(requireContext()).apply {
                minValue = 2000
                maxValue = 2100
                value = state.year
            }

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.select_year))
                .setView(npYear)
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    viewModel.setYearMonth(npYear.value, state.month)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            OverlayDialogs.showPageCenterDialog(dialog, requireContext())
        }
    }

    private fun updateModeSwitcherByScroll(dy: Int, scrollY: Int) {
        if (!::layoutModeSwitcher.isInitialized) return
        val revealZonePx = resources.displayMetrics.density * 56f
        if (scrollY <= 0) modeSwitcherRevealProgress = 1f

        if (dy > 0) {
            // Behave like Home FAB: hide on upward swipe.
            modeSwitcherRevealProgress -= (dy / 120f).coerceAtMost(0.34f)
        } else if (dy < 0) {
            // Gentle downward swipe should bring it back.
            modeSwitcherRevealProgress += ((-dy) / 185f).coerceAtMost(0.22f)
        }

        if (scrollY in 1..revealZonePx.toInt()) {
            val nearTopProgress = 1f - (scrollY / revealZonePx).coerceIn(0f, 1f)
            modeSwitcherRevealProgress = maxOf(modeSwitcherRevealProgress, nearTopProgress)
        }

        modeSwitcherRevealProgress = modeSwitcherRevealProgress.coerceIn(0f, 1f)
        applyModeSwitcherProgress()
    }

    private fun applyModeSwitcherProgress() {
        val raw = modeSwitcherRevealProgress
        val eased = raw * raw * (3f - 2f * raw)
        layoutModeSwitcher.alpha = eased
        layoutModeSwitcher.translationY = -16f * (1f - eased)
        layoutModeSwitcher.scaleX = 0.95f + 0.05f * eased
        layoutModeSwitcher.scaleY = 0.95f + 0.05f * eased
        layoutModeSwitcher.isClickable = eased > 0.08f
    }

    private fun syncBookFromGlobalIfNeeded() {
        syncHostSelectionIfNeeded("globalBookChanged")
    }

    private fun showUnifiedMonthYearPicker() {
        val state = viewModel.uiState.value
        if (state.isMonthMode) {
            YearMonthPickerDialog.show(
                context = requireContext(),
                title = "\u9009\u62E9\u6708\u4EFD",
                initialYear = state.year,
                initialMonth = state.month + 1
            ) { year, month ->
                viewModel.setYearMonth(year, month - 1)
            }
        } else {
            YearMonthPickerDialog.show(
                context = requireContext(),
                title = "\u9009\u62E9\u5E74\u4EFD",
                initialYear = state.year,
                initialMonth = state.month + 1,
                yearOnly = true
            ) { year, _ ->
                viewModel.setYearMonth(year, state.month)
            }
        }
    }

    private fun showBookFilterDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)
            val current = withContext(Dispatchers.IO) {
                LedgerViewScopeStore.resolve(context, db, BookAccountManager.getSelectedBook(context))
            }
            LedgerViewScopeDialog.show(requireContext(), current) { scope ->
                LedgerViewScopeStore.save(context, scope)
                viewLifecycleOwner.lifecycleScope.launch {
                    val resolved = withContext(Dispatchers.IO) {
                        LedgerViewScopeStore.resolve(context, db, current.legacyBookName)
                    }
                    BookAccountManager.setSelectedBook(requireContext(), resolved.legacyBookName)
                    lastHostSyncSignature = null
                    viewModel.setViewScope(resolved)
                    homeViewModel.switchBook(resolved.legacyBookName)
                }
            }
        }
    }

    private fun startJankMonitor(reason: String) {
        if (!ENABLE_JANK_MONITOR) return
        frameSampleUntilMs = SystemClock.elapsedRealtime() + 5000L
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        lastFrameNs = 0L
        perfStage = "starting"
        Log.d(TAG, "jank monitor start: $reason")
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopJankMonitor(reason: String) {
        if (!ENABLE_JANK_MONITOR) return
        if (!frameCallbackPosted) return
        frameCallbackPosted = false
        lastFrameNs = 0L
        perfStage = "idle"
        Log.d(TAG, "jank monitor stop: $reason")
    }

    private fun showCustomFilterSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_stats_filter_sheet, null)

        val chipToday = view.findViewById<MaterialButton>(R.id.chip_filter_today)
        val chipYesterday = view.findViewById<MaterialButton>(R.id.chip_filter_yesterday)
        val chipDayBeforeYesterday = view.findViewById<MaterialButton>(R.id.chip_filter_day_before_yesterday)
        val chipThisWeek = view.findViewById<MaterialButton>(R.id.chip_filter_this_week)
        val chipLastWeek = view.findViewById<MaterialButton>(R.id.chip_filter_last_week)
        val chipThisMonth = view.findViewById<MaterialButton>(R.id.chip_filter_this_month)
        val chipLastMonth = view.findViewById<MaterialButton>(R.id.chip_filter_last_month)
        val chipThisYear = view.findViewById<MaterialButton>(R.id.chip_filter_this_year)
        val chipLastYear = view.findViewById<MaterialButton>(R.id.chip_filter_last_year)
        val chipAll = view.findViewById<MaterialButton>(R.id.chip_filter_all)

        val cardStart = view.findViewById<View>(R.id.tv_filter_start_date)
        val cardEnd = view.findViewById<View>(R.id.tv_filter_end_date)
        val tvStart = view.findViewById<TextView>(R.id.tv_filter_start_date_text)
        val tvEnd = view.findViewById<TextView>(R.id.tv_filter_end_date_text)
        val currencySection = view.findViewById<View>(R.id.layout_filter_currency_section)
        val tvCurrency = view.findViewById<TextView>(R.id.tv_filter_currency_selector)
        val currencySelectorContainer = view.findViewById<View>(R.id.layout_filter_currency_selector)
        val currencyExpandIcon = view.findViewById<ImageView>(R.id.iv_filter_currency_expand)

        val btnClose = view.findViewById<View>(R.id.btn_close_filter_sheet)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_filter_sheet)
        val btnReset = view.findViewById<View>(R.id.btn_reset_filter_sheet)

        val state = viewModel.uiState.value
        var customStart: Long? = null
        var customEnd: Long? = null
        var selectedCurrency: String? = state.selectedCurrency
        var availableCurrencies: List<String> = emptyList()
        var suppressQuickSync = false
        var currencyPopup: PopupWindow? = null
        var selectedQuickLabel: String? = null
        var timeFilterTouched = false
        val quickChipLabelPairs = listOf(
            chipToday to "\u4eca\u5929",
            chipYesterday to "\u6628\u5929",
            chipDayBeforeYesterday to "\u524d\u5929",
            chipThisWeek to "\u672c\u5468",
            chipLastWeek to "\u4e0a\u5468",
            chipThisMonth to "\u672c\u6708",
            chipLastMonth to "\u4e0a\u6708",
            chipThisYear to "\u4eca\u5e74",
            chipLastYear to "\u53bb\u5e74",
            chipAll to "\u5168\u90e8"
        )
        val quickChips = quickChipLabelPairs.map { it.first }

        fun clearQuickChips() {
            suppressQuickSync = true
            quickChips.forEach { it.isChecked = false }
            suppressQuickSync = false
            selectedQuickLabel = null
        }

        fun updateDateField(view: TextView, value: Long?, placeholder: String) {
            if (value == null) {
                view.text = placeholder
                view.setTextColor(Color.parseColor("#8A97A8"))
                view.setTypeface(null, Typeface.BOLD)
            } else {
                view.text = dfDateLabel.format(Date(value))
                view.setTextColor(Color.parseColor("#22324A"))
                view.setTypeface(null, Typeface.BOLD)
            }
        }

        fun resetDateLabels() {
            updateDateField(tvStart, customStart, "开始")
            updateDateField(tvEnd, customEnd, "结束")
        }

        fun setDayStart(cal: Calendar) {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }

        fun setDayEnd(cal: Calendar) {
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
        }

        fun isQuickLabel(label: String?): Boolean {
            return label == "\u4eca\u5929" ||
                label == "\u6628\u5929" ||
                label == "\u524d\u5929" ||
                label == "\u672c\u5468" ||
                label == "\u4e0a\u5468" ||
                label == "\u672c\u6708" ||
                label == "\u4e0a\u6708" ||
                label == "\u4eca\u5e74" ||
                label == "\u53bb\u5e74" ||
                label == "\u5168\u90e8"
        }

        fun updateCustomRangeByQuick(label: String) {
            when (label) {
                "\u4eca\u5929" -> {
                    val cal = Calendar.getInstance()
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u6628\u5929" -> {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u524d\u5929" -> {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u672c\u5468" -> {
                    val cal = Calendar.getInstance().apply {
                        firstDayOfWeek = Calendar.MONDAY
                        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.add(Calendar.DAY_OF_YEAR, 6)
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u4e0a\u5468" -> {
                    val cal = Calendar.getInstance().apply {
                        firstDayOfWeek = Calendar.MONDAY
                        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                        add(Calendar.WEEK_OF_YEAR, -1)
                    }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.add(Calendar.DAY_OF_YEAR, 6)
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u672c\u6708" -> {
                    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u4e0a\u6708" -> {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.MONTH, -1)
                        set(Calendar.DAY_OF_MONTH, 1)
                    }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u4eca\u5e74" -> {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.MONTH, Calendar.JANUARY)
                        set(Calendar.DAY_OF_MONTH, 1)
                    }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.set(Calendar.MONTH, Calendar.DECEMBER)
                    cal.set(Calendar.DAY_OF_MONTH, 31)
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u53bb\u5e74" -> {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.YEAR, -1)
                        set(Calendar.MONTH, Calendar.JANUARY)
                        set(Calendar.DAY_OF_MONTH, 1)
                    }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.set(Calendar.MONTH, Calendar.DECEMBER)
                    cal.set(Calendar.DAY_OF_MONTH, 31)
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "\u5168\u90e8" -> {
                    customStart = null
                    customEnd = null
                }
            }
            resetDateLabels()
        }

        fun selectQuickChip(target: MaterialButton, label: String) {
            suppressQuickSync = true
            quickChips.forEach { it.isChecked = it == target }
            suppressQuickSync = false
            selectedQuickLabel = label
            updateCustomRangeByQuick(label)
        }

        tvCurrency.text = selectedCurrency ?: "全部币种"
        resetDateLabels()

        when (state.forcedLabel) {
            "\u4eca\u5929" -> selectQuickChip(chipToday, "\u4eca\u5929")
            "\u6628\u5929" -> selectQuickChip(chipYesterday, "\u6628\u5929")
            "\u524d\u5929" -> selectQuickChip(chipDayBeforeYesterday, "\u524d\u5929")
            "\u672c\u5468" -> selectQuickChip(chipThisWeek, "\u672c\u5468")
            "\u4e0a\u5468" -> selectQuickChip(chipLastWeek, "\u4e0a\u5468")
            "\u672c\u6708" -> selectQuickChip(chipThisMonth, "\u672c\u6708")
            "\u4e0a\u6708" -> selectQuickChip(chipLastMonth, "\u4e0a\u6708")
            "\u4eca\u5e74" -> selectQuickChip(chipThisYear, "\u4eca\u5e74")
            "\u53bb\u5e74" -> selectQuickChip(chipLastYear, "\u53bb\u5e74")
            "\u5168\u90e8" -> selectQuickChip(chipAll, "\u5168\u90e8")
        }

        if (state.forcedStartTime != null && state.forcedEndTime != null && state.forcedEndTime != Long.MAX_VALUE) {
            customStart = state.forcedStartTime
            customEnd = state.forcedEndTime
            if (!isQuickLabel(state.forcedLabel)) {
                clearQuickChips()
            }
            resetDateLabels()
        }

        quickChipLabelPairs.forEach { (chip, label) ->
            chip.setOnClickListener {
                if (suppressQuickSync) return@setOnClickListener
                timeFilterTouched = true
                selectQuickChip(chip, label)
            }
        }

        cardStart.setOnClickListener {
            showDatePicker { ts ->
                timeFilterTouched = true
                customStart = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        cardEnd.setOnClickListener {
            showDatePicker { ts ->
                timeFilterTouched = true
                customEnd = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val currencies = AppDatabase.getDatabase(context).assetDao().getAllAssetsList()
                .map { it.currency }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            withContext(Dispatchers.Main) {
                availableCurrencies = currencies
                val hasMultipleCurrencies = availableCurrencies.size > 1
                currencySection.visibility = if (hasMultipleCurrencies) View.VISIBLE else View.GONE
                if (selectedCurrency != null && !availableCurrencies.contains(selectedCurrency)) {
                    selectedCurrency = null
                    tvCurrency.text = "全部币种"
                }
                if (!hasMultipleCurrencies) {
                    selectedCurrency = null
                    currencyPopup?.dismiss()
                    currencyPopup = null
                    currencyExpandIcon.rotation = 0f
                    tvCurrency.text = "全部币种"
                }
            }
        }

        (currencySelectorContainer ?: tvCurrency).setOnClickListener {
            if (currencySection.visibility != View.VISIBLE) return@setOnClickListener
            if (currencyPopup?.isShowing == true) {
                currencyPopup?.dismiss()
                return@setOnClickListener
            }
            currencyPopup = showCurrencyAnchorPopup(
                anchor = currencySelectorContainer ?: tvCurrency,
                options = mutableListOf("全部币种").apply { addAll(availableCurrencies) },
                current = selectedCurrency,
                onSelected = { selected ->
                    selectedCurrency = selected
                    tvCurrency.text = selectedCurrency ?: "全部币种"
                },
                onDismiss = {
                    currencyExpandIcon.animate().rotation(0f).setDuration(140L).start()
                    currencyPopup = null
                }
            )
            currencyExpandIcon.animate().rotation(180f).setDuration(140L).start()
        }

        btnClose.setOnClickListener {
            currencyPopup?.dismiss()
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            timeFilterTouched = true
            clearQuickChips()
            customStart = null
            customEnd = null
            selectQuickChip(chipThisMonth, "本月")
            selectedCurrency = null
            resetDateLabels()
            tvCurrency.text = "全部币种"
            currencyPopup?.dismiss()
            currencyPopup = null
            currencyExpandIcon.rotation = 0f
        }
        btnConfirm.setOnClickListener {
            if (timeFilterTouched) {
                when {
                    selectedQuickLabel == "今天" -> viewModel.applyTodayFilter()
                    selectedQuickLabel == "昨天" -> viewModel.applyYesterdayFilter()
                    selectedQuickLabel == "前天" -> customStart?.let { start -> customEnd?.let { end -> viewModel.applyCustomDateFilter(start, end) } }
                    selectedQuickLabel == "本周" -> viewModel.applyThisWeekFilter()
                    selectedQuickLabel == "上周" -> viewModel.applyLastWeekFilter()
                    selectedQuickLabel == "本月" -> {
                        val now = Calendar.getInstance()
                        viewModel.setMode(true)
                        viewModel.setYearMonth(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
                    }
                    selectedQuickLabel == "上月" -> {
                        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
                        viewModel.setMode(true)
                        viewModel.setYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
                    }
                    selectedQuickLabel == "今年" -> {
                        viewModel.setMode(false)
                        viewModel.applyThisYearFilter()
                    }
                    selectedQuickLabel == "去年" -> {
                        viewModel.setMode(false)
                        viewModel.applyLastYearFilter()
                    }
                    selectedQuickLabel == "全部" -> viewModel.applyAllTimeFilter()
                    customStart != null && customEnd != null -> viewModel.applyCustomDateFilter(customStart!!, customEnd!!)
                    customStart != null || customEnd != null -> {
                        Toast.makeText(requireContext(), "\u8bf7\u5148\u9009\u62e9\u5b8c\u6574\u7684\u5f00\u59cb\u548c\u7ed3\u675f\u65e5\u671f", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    else -> viewModel.applyThisMonthFilter()
                }
            }

            viewModel.setCurrencyFilter(selectedCurrency)
            currencyPopup?.dismiss()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheetId = resources.getIdentifier(
                "design_bottom_sheet",
                "id",
                "com.google.android.material"
            )
            if (bottomSheetId == 0) return@setOnShowListener
            val bottomSheet = dialog.findViewById<View>(bottomSheetId) ?: return@setOnShowListener
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                isFitToContents = true
                this.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        if (!isAdded || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            dialog.show()
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun showDatePicker(onDateSelected: (Long) -> Unit) {
        ElegantDatePickerSheet.show(
            context = requireContext(),
            onDateSelected = onDateSelected
        )
    }

    private fun showCurrencyAnchorPopup(
        anchor: View,
        options: List<String>,
        current: String?,
        onSelected: (String?) -> Unit,
        onDismiss: () -> Unit
    ): PopupWindow {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val rowHeight = dp(38)
        val rowGap = dp(4)
        val panelPadding = dp(8)
        val visibleRows = options.size.coerceIn(2, 5)
        val desiredListHeight = (visibleRows * rowHeight) + ((visibleRows - 1) * rowGap)
        val desiredPopupHeight = desiredListHeight + panelPadding * 2

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            setBackgroundResource(R.drawable.bg_currency_popup_panel)
        }
        val scroll = NestedScrollView(requireContext()).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            rows,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        listContainer.addView(scroll)

        val selectedValue = current ?: "全部币种"
        var selectedRow: View? = null
        var popupRef: PopupWindow? = null
        options.forEachIndexed { index, option ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply { if (index > 0) topMargin = rowGap }
                setPadding(dp(10), 0, dp(10), 0)
                if (option == selectedValue) {
                    setBackgroundResource(R.drawable.bg_currency_popup_option_selected)
                } else {
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }
            val label = TextView(requireContext()).apply {
                text = option
                textSize = 13f
                setTextColor(Color.parseColor(if (option == selectedValue) "#2C74FF" else "#22324A"))
                if (option == selectedValue) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val check = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                setImageResource(R.drawable.ic_check_circle)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C74FF"))
                visibility = if (option == selectedValue) View.VISIBLE else View.INVISIBLE
            }
            row.addView(label)
            row.addView(check)
            row.setOnClickListener {
                onSelected(if (option == "全部币种") null else option)
                popupRef?.dismiss()
            }
            if (option == selectedValue) selectedRow = row
            rows.addView(row)
        }

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val availableAbove = (anchorLoc[1] - dp(12)).coerceAtLeast(dp(92))
        val popupHeight = desiredPopupHeight.coerceAtMost(availableAbove)
        val popup = PopupWindow(
            listContainer,
            anchor.width,
            popupHeight,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = true
        popup.setOnDismissListener(onDismiss)
        popupRef = popup
        try {
            if (anchor.isAttachedToWindow && anchor.windowToken != null) {
                popup.showAsDropDown(anchor, 0, -(anchor.height + popupHeight + dp(8)))
            } else {
                onDismiss()
            }
        } catch (_: BadTokenException) {
            onDismiss()
        } catch (_: IllegalStateException) {
            onDismiss()
        }

        selectedRow?.let { row ->
            scroll.post {
                val target = (row.top - rowGap).coerceAtLeast(0)
                scroll.scrollTo(0, target)
            }
        }
        return popup
    }

    private fun showOverviewBillList(title: String, bills: List<Bill>, emptyMessage: String) {
        if (bills.isEmpty()) {
            Toast.makeText(requireContext(), emptyMessage, Toast.LENGTH_SHORT).show()
            return
        }
        BillListBottomSheet(title, bills).show(childFragmentManager, "overview_bills")
    }

    private fun showSubCategoryDetails(categoryName: String) {
        val hasSubCategories = viewModel.hasSubCategories(categoryName, isCategoryExpense)
        if (!hasSubCategories) {
            val bills = viewModel.getBillsForCategory(categoryName, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "暂无该分类账单", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet(categoryName, bills).show(childFragmentManager, "bills")
            }
            return
        }

        val subStats = viewModel.getSubCategoryStats(categoryName, isCategoryExpense)
        if (subStats.isEmpty()) {
            val bills = viewModel.getBillsForCategory(categoryName, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "暂无该分类账单", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet(categoryName, bills).show(childFragmentManager, "bills")
            }
            return
        }
        val total = subStats.values.sum()

        val sheet = SubCategoryBottomSheet(
            title = categoryName,
            isExpense = isCategoryExpense,
            subStats = subStats,
            totalAmount = total,
            colors = chartColors,
            currencySymbol = viewModel.uiState.value.selectedCurrency
                ?.let { CurrencyManager.getSymbol(it) } ?: "¥"
        ) { subCategory ->
            val bills = viewModel.getBillsForSubCategory(categoryName, subCategory, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "\u6682\u65E0\u8BE5\u5206\u7C7B\u8D26\u5355", Toast.LENGTH_SHORT).show()
            } else {
                val billSheet = BillListBottomSheet(subCategory, bills)
                billSheet.show(childFragmentManager, "bills")
            }
        }
        sheet.show(childFragmentManager, "sub_categories")
    }
}


