package com.taostudio.tapaccounting.ui.main.assets

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointD
import com.github.mikephil.charting.utils.MPPointF
import com.github.mikephil.charting.animation.Easing
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.main.stats.BillListBottomSheet
import com.taostudio.tapaccounting.ui.main.stats.CategoryStat
import com.taostudio.tapaccounting.ui.main.stats.CategoryStatsAdapter
import com.taostudio.tapaccounting.ui.main.stats.SubCategoryBottomSheet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class AssetStatsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ASSET_ID = "ASSET_ID"

        private const val TAG_BAR = "AssetStatsBar"
        private const val INITIAL_BILL_LIST_SIZE = 50
        private const val BILL_LIST_STEP_SIZE = 200
        private const val PIE_LOADING_MIN_MS = 140L
        private const val PIE_ANIM_DURATION_MS = 560

        private data class AssetStatsCache(
            val asset: Asset,
            val bills: List<Bill>,
            val updatedAtMs: Long
        )

        private val assetStatsCacheByAssetId = mutableMapOf<Long, AssetStatsCache>()
    }

    private enum class PeriodMode { YEAR, MONTH }
    private enum class ChartMode { EXPENSE, INCOME }
    private enum class DateChipType { YEAR, MONTH }
    private enum class FilterBillType { EXPENSE, INCOME, TRANSFER, REPAYMENT, REFUND, ANY }

    private data class DateChipItem(
        val type: DateChipType,
        val year: Int,
        val month: Int? = null
    )

    private data class SectionHeaderRow(
        val title: String,
        val income: Double,
        val expense: Double
    )

    private data class BillRow(val bill: Bill)

    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvPeriodLabel: TextView
    private lateinit var rvDateStrip: RecyclerView
    private lateinit var rvBillList: RecyclerView
    private lateinit var rvCategoryList: RecyclerView
    private lateinit var barChart: BarChart
    private lateinit var pieChart: PieChart

    private lateinit var tvTotalExpense: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvTotalTransfer: TextView
    private lateinit var tvTotalRefund: TextView
    private lateinit var tvTotalCount: TextView

    private lateinit var btnBarExpense: TextView
    private lateinit var btnBarIncome: TextView
    private lateinit var btnPieExpense: TextView
    private lateinit var btnPieIncome: TextView
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: TextView
    private lateinit var btnMsSelectAll: TextView
    private lateinit var btnMsMove: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var nsvContent: NestedScrollView
    private lateinit var appBarStats: View

    private lateinit var dateStripAdapter: DateStripAdapter
    private lateinit var billAdapter: AssetStatsBillAdapter
    private lateinit var categoryAdapter: CategoryStatsAdapter

    private var assetId: Long = -1L
    private var currentAsset: Asset? = null
    private var allAssetBills: List<Bill> = emptyList()
    private var dateChips: List<DateChipItem> = emptyList()

    private var periodMode: PeriodMode = PeriodMode.YEAR
    private var barMode: ChartMode = ChartMode.EXPENSE
    private var pieMode: ChartMode = ChartMode.EXPENSE
    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var forcedStartTime: Long? = null
    private var forcedEndTime: Long? = null
    private var forcedLabel: String? = null
    private var forcedBillType: FilterBillType = FilterBillType.ANY
    private var currentBarLabels: List<String> = emptyList()
    private var currentBarValues: List<Float> = emptyList()
    private var selectedBarIndex: Int? = null
    private var loadSequence: Int = 0
    private var fullBillsLoadJob: Job? = null
    private var billListProgressJob: Job? = null
    private var pieRenderJob: Job? = null
    private var billRenderToken: Long = 0L
    private var pieRenderToken: Long = 0L
    private var hasResumedOnce = false
    private var pieChartHasRendered = false
    private var pagedBillRows: List<Any> = emptyList()
    private var pagedBillChunkEnds: List<Int> = emptyList()
    private var pagedNextChunkIndex: Int = 0
    private var pagedFilterKey: String = ""
    private var pagedAppendInFlight: Boolean = false
    private val filteredBillsCache = mutableMapOf<String, List<Bill>>()
    private val billRowsCache = mutableMapOf<String, List<Any>>()
    private val categoryBreakdownCache = mutableMapOf<String, CategoryBreakdownModel>()
    private lateinit var barMarker: AssetBarMarkerView
    private lateinit var topDoubleTapDetector: GestureDetector

    private val dfMonthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val dfMonthTitle = SimpleDateFormat("MM月", Locale.getDefault())
    private val dfDayTitle = SimpleDateFormat("MM.dd", Locale.getDefault())
    private val dfWeekday = SimpleDateFormat("E", Locale.CHINESE)
    private val dfBillDate = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val dfDateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val billDetailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        AssetBillDetailSheetController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            getCurrentAssetCurrency = { currentAsset?.currency },
            getDefaultAssetId = { assetId },
            amountForAssetRow = ::amountForAssetRow,
            detailOwnerAssetId = ::detailOwnerAssetId,
            refundedAmountInBillCurrency = ::refundedAmountInBillCurrency,
            baseOriginalAmount = ::baseOriginalAmount,
            buildAssetDetailFormula = ::buildAssetDetailFormula,
            onDataChanged = {
                filteredBillsCache.clear()
                billRowsCache.clear()
                categoryBreakdownCache.clear()
                loadAssetAndBills()
            }
        )
    }
    private val piePalette = listOf(
        "#26C6DA", "#66BB6A", "#42A5F5", "#FFB74D", "#FF7043", "#7E57C2",
        "#29B6F6", "#9CCC65", "#5C6BC0", "#EC407A", "#AB47BC", "#FFA726"
    ).map { Color.parseColor(it) }

    private data class PieRenderModel(
        val entries: List<PieEntry>,
        val sliceColors: List<Int>,
        val labelSize: Float,
        val labelByCategory: Map<String, String>,
        val rotation: Float,
        val useOutsideLabel: Boolean
    )

    private data class CategoryBreakdownModel(
        val categoryStats: List<CategoryStat>,
        val colorByCategory: Map<String, Int>,
        val pieModel: PieRenderModel?
    )

    private data class BillRenderPlan(
        val initialBillCount: Int,
        val stepBillCount: Int,
        val chunkDelayMs: Long,
        val firstRenderDelayMs: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_stats)

        assetId = intent.getLongExtra(EXTRA_ASSET_ID, -1L)
        if (assetId <= 0L) {
            finish()
            return
        }
        initViews()
        initListeners()
        initCharts()
        setupBackPressForMultiSelect()
        loadAssetAndBills()
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce) {
            // 独立账单详情页返回后，刷新账单列表和统计缓存。
            filteredBillsCache.clear()
            billRowsCache.clear()
            categoryBreakdownCache.clear()
            loadAssetAndBills()
        } else {
            hasResumedOnce = true
        }
    }

    override fun onDestroy() {
        fullBillsLoadJob?.cancel()
        billListProgressJob?.cancel()
        pieRenderJob?.cancel()
        super.onDestroy()
    }

    private fun initViews() {
        tvToolbarTitle = findViewById(R.id.tv_toolbar_title)
        tvPeriodLabel = findViewById(R.id.tv_period_label)
        rvDateStrip = findViewById(R.id.rv_date_strip)
        rvBillList = findViewById(R.id.rv_bill_list)
        rvCategoryList = findViewById(R.id.rv_category_list)
        barChart = findViewById(R.id.bar_chart)
        pieChart = findViewById(R.id.pie_chart)

        tvTotalExpense = findViewById(R.id.tv_total_expense)
        tvTotalIncome = findViewById(R.id.tv_total_income)
        tvBalance = findViewById(R.id.tv_balance)
        tvTotalTransfer = findViewById(R.id.tv_total_transfer)
        tvTotalRefund = findViewById(R.id.tv_total_refund)
        tvTotalCount = findViewById(R.id.tv_total_count)

        btnBarExpense = findViewById(R.id.btn_bar_expense)
        btnBarIncome = findViewById(R.id.btn_bar_income)
        btnPieExpense = findViewById(R.id.btn_pie_expense)
        btnPieIncome = findViewById(R.id.btn_pie_income)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = findViewById(R.id.btn_ms_select_all)
        btnMsMove = findViewById(R.id.btn_ms_move)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        nsvContent = findViewById(R.id.nsv_content)
        appBarStats = findViewById(R.id.appbar_stats)

        dateStripAdapter = DateStripAdapter { item ->
            when (item.type) {
                DateChipType.YEAR -> {
                    periodMode = PeriodMode.YEAR
                    selectedYear = item.year
                }
                DateChipType.MONTH -> {
                    periodMode = PeriodMode.MONTH
                    selectedYear = item.year
                    selectedMonth = item.month ?: 1
                }
            }
            renderAll()
        }
        rvDateStrip.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvDateStrip.adapter = dateStripAdapter

        categoryAdapter = CategoryStatsAdapter(
            chartColors = piePalette,
            items = emptyList(),
            isExpense = pieMode == ChartMode.EXPENSE,
            currencySymbol = "¥"
        ) { categoryName ->
            showCategoryDetails(categoryName)
        }
        rvCategoryList.layoutManager = LinearLayoutManager(this)
        rvCategoryList.isNestedScrollingEnabled = false
        rvCategoryList.overScrollMode = View.OVER_SCROLL_NEVER
        rvCategoryList.itemAnimator = null
        rvCategoryList.adapter = categoryAdapter

        billAdapter = AssetStatsBillAdapter(
            currencyProvider = { currentAsset?.currency ?: "CNY" },
            amountProvider = { bill -> amountForAssetRow(bill, assetId) }
        ).apply {
            onBillItemClick = { bill -> billDetailSheetController.showBillDetailSheet(bill, detailOwnerAssetId(bill)) }
            onSelectionChanged = { count -> updateStatsMultiSelectUi(count) }
        }
        rvBillList.layoutManager = LinearLayoutManager(this)
        rvBillList.itemAnimator = null
        rvBillList.adapter = billAdapter
        setupMultiSelectActions()
    }

    private fun initListeners() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.layout_asset_switch).setOnClickListener { switchAsset() }
        findViewById<View>(R.id.btn_filter).setOnClickListener { showCustomFilterSheet() }
        setupTopBarDoubleTapToTop()

        btnBarExpense.setOnClickListener {
            barMode = ChartMode.EXPENSE
            updateBarToggleState()
            renderBarChart(filteredBills())
        }
        btnBarIncome.setOnClickListener {
            barMode = ChartMode.INCOME
            updateBarToggleState()
            renderBarChart(filteredBills())
        }
        btnPieExpense.setOnClickListener {
            pieMode = ChartMode.EXPENSE
            updatePieToggleState()
            renderPieChartAsync(currentFilterCacheKey(), filteredBills())
        }
        btnPieIncome.setOnClickListener {
            pieMode = ChartMode.INCOME
            updatePieToggleState()
            renderPieChartAsync(currentFilterCacheKey(), filteredBills())
        }
        nsvContent.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY <= oldScrollY) return@setOnScrollChangeListener
            tryAppendNextBillChunkByScroll()
        }
    }

    private fun setupTopBarDoubleTapToTop() {
        topDoubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                nsvContent.post { nsvContent.smoothScrollTo(0, 0) }
                rvBillList.post { rvBillList.scrollToPosition(0) }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::appBarStats.isInitialized && isEventInsideView(ev, appBarStats)) {
            topDoubleTapDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isEventInsideView(ev: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun setupBackPressForMultiSelect() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (billAdapter.isMultiSelectMode) {
                    billAdapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener { billAdapter.clearSelection() }
        btnMsSelectAll.setOnClickListener {
            val selectableCount = billAdapter.getSelectableBills().size
            if (selectableCount > 0 && billAdapter.selectedBills.size >= selectableCount) {
                billAdapter.deselectAll()
            } else {
                billAdapter.selectAll()
            }
        }
        btnMsDelete.setOnClickListener {
            val targets = billAdapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                BillDeleteHelper.deleteBillsAndRevertBalance(db, targets)
                withContext(Dispatchers.Main) {
                    billAdapter.clearSelection()
                    filteredBillsCache.clear()
                    billRowsCache.clear()
                    categoryBreakdownCache.clear()
                    loadAssetAndBills()
                    Toast.makeText(
                        this@AssetStatsActivity,
                        getString(R.string.deleted_bills_fmt, targets.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        btnMsMove.setOnClickListener {
            val sourceAsset = currentAsset ?: return@setOnClickListener
            val targets = billAdapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            OverlayDialogs.showGridAssetPicker(
                this,
                sourceAsset.name,
                getString(R.string.select_target_asset),
                allowClear = false
            ) { selectedName ->
                if (selectedName == sourceAsset.name) {
                    Toast.makeText(this, getString(R.string.already_in_asset), Toast.LENGTH_SHORT).show()
                    return@showGridAssetPicker
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val targetAsset = db.assetDao().getAssetByName(selectedName) ?: return@withContext 0
                        var moved = 0
                        targets.forEach { bill ->
                            val movedBill = moveBillToTargetAsset(bill, sourceAsset, targetAsset)
                            if (movedBill != null) {
                                db.billDao().updateBill(movedBill)
                                moved++
                            }
                        }
                        moved
                    }
                    billAdapter.clearSelection()
                    if (result > 0) {
                        filteredBillsCache.clear()
                        billRowsCache.clear()
                        categoryBreakdownCache.clear()
                        loadAssetAndBills()
                    }
                    Toast.makeText(this@AssetStatsActivity, getString(R.string.moved_bills_fmt, result), Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateStatsMultiSelectUi(0)
    }

    private fun updateStatsMultiSelectUi(selectedCount: Int) {
        val active = billAdapter.isMultiSelectMode
        layoutMultiSelectActions.visibility = if (active) View.VISIBLE else View.GONE
        val hasSelection = selectedCount > 0
        btnMsCancel.text = getString(R.string.exit_multi_select)
        btnMsDelete.text = if (selectedCount > 0) getString(R.string.delete_fmt, selectedCount) else getString(R.string.delete_label)
        btnMsDelete.isEnabled = hasSelection
        btnMsMove.isEnabled = hasSelection
        btnMsDelete.alpha = if (hasSelection) 1f else 0.45f
        btnMsMove.alpha = if (hasSelection) 1f else 0.45f
    }

    private fun moveBillToTargetAsset(
        bill: Bill,
        sourceAsset: Asset,
        targetAsset: Asset
    ): Bill? {
        val matchSourceAccount = bill.accountId == sourceAsset.id ||
            (bill.accountId == null && bill.accountName == sourceAsset.name)
        val matchSourceToAccount = bill.toAccountId == sourceAsset.id ||
            (bill.toAccountId == null && bill.toAccountName == sourceAsset.name)

        val updated = if (bill.type == Bill.TYPE_TRANSFER) {
            when {
                matchSourceAccount && matchSourceToAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name,
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        } else {
            when {
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        }
        return if (updated != null && updated != bill) updated else null
    }

    private fun initCharts() {
        barChart.description.isEnabled = false
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = false
        barChart.setNoDataText(getString(R.string.no_chart_data))
        barChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
        barChart.setDrawGridBackground(false)
        barChart.setTouchEnabled(true)
        barChart.isDragEnabled = false
        barChart.setScaleEnabled(false)
        barChart.setPinchZoom(false)
        barChart.isDoubleTapToZoomEnabled = false
        // We resolve nearest column manually in onChartSingleTapped; disable default pixel-hit highlight
        // to avoid it clearing our highlight immediately when tap doesn't hit bar pixels.
        barChart.isHighlightPerTapEnabled = false
        barChart.setDrawMarkers(true)
        barChart.setMinOffset(0f)
        barChart.axisLeft.axisMinimum = 0f
        barChart.axisLeft.setDrawLabels(false)
        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisLeft.setDrawAxisLine(false)

        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.gridColor = Color.TRANSPARENT
        barChart.xAxis.textColor = Color.parseColor("#757D88")
        barChart.xAxis.textSize = 10f
        barChart.xAxis.setGranularityEnabled(true)
        barChart.xAxis.granularity = 1f
        barChart.xAxis.setDrawAxisLine(true)
        barChart.xAxis.axisLineColor = Color.parseColor("#B7BEC7")
        barChart.xAxis.axisLineWidth = 0.8f
        barChart.xAxis.setCenterAxisLabels(false)
        barChart.xAxis.setAvoidFirstLastClipping(false)
        barChart.xAxis.setDrawGridLines(false)
        barChart.setExtraOffsets(0f, 0f, 0f, 0f)

        barMarker = AssetBarMarkerView(this) { index ->
            buildBarMarkerText(index)
        }
        barChart.marker = barMarker
        barMarker.chartView = barChart
        barChart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: com.github.mikephil.charting.data.Entry?, h: Highlight?) {
                Log.d(TAG_BAR, "onValueSelected: entryX=${e?.x}, entryY=${e?.y}, highlightX=${h?.x}, ds=${h?.dataSetIndex}")
                barChart.invalidate()
            }
            override fun onNothingSelected() {
                Log.d(TAG_BAR, "onNothingSelected")
            }
        })
        barChart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartSingleTapped(me: android.view.MotionEvent?) {
                if (me == null || currentBarValues.isEmpty()) return
                Log.d(TAG_BAR, "onChartSingleTapped: touchX=${me.x}, touchY=${me.y}, valueCount=${currentBarValues.size}")
                highlightNearestBarByTouch(me.x, me.y)
            }
            override fun onChartGestureStart(me: android.view.MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) = Unit
            override fun onChartGestureEnd(me: android.view.MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) = Unit
            override fun onChartLongPressed(me: android.view.MotionEvent?) = Unit
            override fun onChartDoubleTapped(me: android.view.MotionEvent?) = Unit
            override fun onChartFling(me1: android.view.MotionEvent?, me2: android.view.MotionEvent?, velocityX: Float, velocityY: Float) = Unit
            override fun onChartScale(me: android.view.MotionEvent?, scaleX: Float, scaleY: Float) = Unit
            override fun onChartTranslate(me: android.view.MotionEvent?, dX: Float, dY: Float) = Unit
        }

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
        pieChart.setExtraOffsets(20f, 14f, 20f, 16f)
        pieChart.setNoDataText(getString(R.string.no_chart_data))
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
        pieChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val label = (e as? PieEntry)?.label ?: return
                if (categoryAdapter.findPositionByCategory(label) < 0) return
                categoryAdapter.pinCategory(label)
                (rvCategoryList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                rvCategoryList.post { rvCategoryList.smoothScrollToPosition(0) }
            }

            override fun onNothingSelected() {
                categoryAdapter.clearPinCategory()
            }
        })

        updateBarToggleState()
        updatePieToggleState()
    }

    private fun loadAssetAndBills() {
        val requestSeq = ++loadSequence
        fullBillsLoadJob?.cancel()
        billListProgressJob?.cancel()

        val cached = assetStatsCacheByAssetId[assetId]
        if (cached != null) {
            currentAsset = cached.asset
            allAssetBills = cached.bills
            filteredBillsCache.clear()
            initDateChips(resetSelection = true)
            renderAll()
        }

        lifecycleScope.launch {
            val previewResult = withContext(Dispatchers.IO) {
                db.billDao().backfillAssetLinksByName()
                val asset = db.assetDao().getAssetById(assetId)
                if (asset == null) {
                    null
                } else {
                    val bills = db.billDao().getBillsByAssetIdOrNameListLimited(
                        asset.id,
                        asset.name,
                        INITIAL_BILL_LIST_SIZE
                    )
                    asset to bills
                }
            }
            if (requestSeq != loadSequence) return@launch
            if (previewResult == null) {
                finish()
                return@launch
            }

            val previewAsset = previewResult.first
            val previewBills = previewResult.second
                .sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
            val shouldRenderPreview = cached == null || cached.asset.id != previewAsset.id
            if (shouldRenderPreview) {
                currentAsset = previewAsset
                allAssetBills = previewBills
                filteredBillsCache.clear()
                billRowsCache.clear()
                categoryBreakdownCache.clear()
                initDateChips(resetSelection = true)
                renderAll()
            }

            fullBillsLoadJob = lifecycleScope.launch {
                val fullBills = withContext(Dispatchers.IO) {
                    db.billDao().getBillsByAssetIdOrNameList(previewAsset.id, previewAsset.name)
                }.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                if (requestSeq != loadSequence) return@launch

                val changed = fullBills.size != allAssetBills.size ||
                    fullBills.firstOrNull()?.id != allAssetBills.firstOrNull()?.id

                currentAsset = previewAsset
                allAssetBills = fullBills
                filteredBillsCache.clear()
                billRowsCache.clear()
                categoryBreakdownCache.clear()
                assetStatsCacheByAssetId[assetId] = AssetStatsCache(
                    asset = previewAsset,
                    bills = fullBills,
                    updatedAtMs = System.currentTimeMillis()
                )
                initDateChips(resetSelection = false)
                if (changed || shouldRenderPreview) {
                    renderAll()
                }
            }
        }
    }

    private fun initDateChips(resetSelection: Boolean) {
        val now = Calendar.getInstance()
        val nowYear = now.get(Calendar.YEAR)
        val nowMonth = now.get(Calendar.MONTH) + 1
        val cal = Calendar.getInstance()
        var minBillYear = Int.MAX_VALUE
        allAssetBills.forEach { bill ->
            cal.timeInMillis = bill.time
            val y = cal.get(Calendar.YEAR)
            if (y < minBillYear) minBillYear = y
        }
        if (minBillYear == Int.MAX_VALUE) minBillYear = nowYear - 1
        val minYear = minOf(minBillYear, nowYear)
        val maxYear = nowYear
        val chips = mutableListOf<DateChipItem>()
        for (year in maxYear downTo minYear) {
            chips.add(DateChipItem(type = DateChipType.YEAR, year = year))
            val monthUpperBound = if (year == nowYear) nowMonth else 12
            for (month in monthUpperBound downTo 1) {
                chips.add(DateChipItem(type = DateChipType.MONTH, year = year, month = month))
            }
        }
        dateChips = chips
        if (resetSelection || selectedYear == 0) {
            selectedYear = nowYear
            selectedMonth = nowMonth
            periodMode = PeriodMode.YEAR
        } else {
            val yearExists = chips.any { it.type == DateChipType.YEAR && it.year == selectedYear }
            if (!yearExists) {
                selectedYear = nowYear
                selectedMonth = nowMonth
                periodMode = PeriodMode.YEAR
            }
        }
    }

    private fun switchAsset() {
        OverlayDialogs.showGridAssetPicker(
            this,
            currentAsset?.name.orEmpty(),
            getString(R.string.select_asset),
            allowClear = false
        ) { selectedName ->
            lifecycleScope.launch {
                val selected = withContext(Dispatchers.IO) {
                    db.assetDao().getAssetByName(selectedName)
                }
                if (selected == null) {
                    Toast.makeText(this@AssetStatsActivity, getString(R.string.asset_not_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                assetId = selected.id
                loadAssetAndBills()
            }
        }
    }

    private fun showCustomFilterSheet() {
        val dialog = BottomSheetDialog(this)
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
        val tvCurrency = view.findViewById<View>(R.id.tv_filter_currency_selector)

        val btnClose = view.findViewById<View>(R.id.btn_close_filter_sheet)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_filter_sheet)
        val btnReset = view.findViewById<View>(R.id.btn_reset_filter_sheet)

        currencySection.visibility = View.GONE
        tvCurrency.visibility = View.GONE

        var customStart = forcedStartTime
        var customEnd = forcedEndTime
        var selectedQuickLabel = forcedLabel?.takeIf { isQuickLabel(it) }
        var suppressQuickSync = false

        fun clearQuickChips() {
            suppressQuickSync = true
            listOf(
                chipToday,
                chipYesterday,
                chipDayBeforeYesterday,
                chipThisWeek,
                chipLastWeek,
                chipThisMonth,
                chipLastMonth,
                chipThisYear,
                chipLastYear,
                chipAll
            ).forEach { it.isChecked = false }
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

        fun updateCustomRangeByQuick(label: String) {
            selectedQuickLabel = label
            when (label) {
                "今天" -> {
                    val cal = Calendar.getInstance()
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "昨天" -> {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "前天" -> {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "本周" -> {
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
                "上周" -> {
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
                "本月" -> {
                    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                    setDayStart(cal)
                    customStart = cal.timeInMillis
                    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    setDayEnd(cal)
                    customEnd = cal.timeInMillis
                }
                "上月" -> {
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
                "今年" -> {
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
                "去年" -> {
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
                "全部" -> {
                    customStart = null
                    customEnd = null
                }
            }
            resetDateLabels()
        }

        fun selectQuickChip(target: MaterialButton, label: String) {
            suppressQuickSync = true
            listOf(
                chipToday,
                chipYesterday,
                chipDayBeforeYesterday,
                chipThisWeek,
                chipLastWeek,
                chipThisMonth,
                chipLastMonth,
                chipThisYear,
                chipLastYear,
                chipAll
            ).forEach { it.isChecked = it == target }
            suppressQuickSync = false
            updateCustomRangeByQuick(label)
        }

        resetDateLabels()
        when (selectedQuickLabel) {
            "今天" -> chipToday.isChecked = true
            "昨天" -> chipYesterday.isChecked = true
            "前天" -> chipDayBeforeYesterday.isChecked = true
            "本周" -> chipThisWeek.isChecked = true
            "上周" -> chipLastWeek.isChecked = true
            "本月" -> chipThisMonth.isChecked = true
            "上月" -> chipLastMonth.isChecked = true
            "今年" -> chipThisYear.isChecked = true
            "去年" -> chipLastYear.isChecked = true
            "全部" -> chipAll.isChecked = true
        }

        listOf(
            chipToday to "今天",
            chipYesterday to "昨天",
            chipDayBeforeYesterday to "前天",
            chipThisWeek to "本周",
            chipLastWeek to "上周",
            chipThisMonth to "本月",
            chipLastMonth to "上月",
            chipThisYear to "今年",
            chipLastYear to "去年",
            chipAll to "全部"
        ).forEach { (chip, label) ->
            chip.setOnClickListener {
                if (suppressQuickSync) return@setOnClickListener
                selectQuickChip(chip, label)
            }
        }

        cardStart.setOnClickListener {
            showDatePicker { ts ->
                customStart = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        cardEnd.setOnClickListener {
            showDatePicker { ts ->
                customEnd = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnReset.setOnClickListener {
            clearQuickChips()
            customStart = null
            customEnd = null
            resetDateLabels()
        }

        btnConfirm.setOnClickListener {
            when {
                selectedQuickLabel == "全部" -> {
                    forcedLabel = "全部"
                    forcedStartTime = null
                    forcedEndTime = null
                }
                selectedQuickLabel != null && customStart != null && customEnd != null -> {
                    forcedLabel = selectedQuickLabel
                    forcedStartTime = customStart
                    forcedEndTime = customEnd
                }
                customStart != null && customEnd != null -> {
                    forcedLabel = getString(R.string.filter_custom)
                    forcedStartTime = customStart
                    forcedEndTime = customEnd
                }
                customStart != null || customEnd != null -> {
                    Toast.makeText(this, getString(R.string.select_date_range), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                else -> {
                    forcedLabel = null
                    forcedStartTime = null
                    forcedEndTime = null
                }
            }
            filteredBillsCache.clear()
            billRowsCache.clear()
            categoryBreakdownCache.clear()
            renderAll()
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
        dialog.show()
    }

    private fun showDatePicker(onDateSelected: (Long) -> Unit) {
        ElegantDatePickerSheet.show(
            context = this,
            onDateSelected = onDateSelected
        )
    }

    private fun renderAll() {
        val asset = currentAsset ?: return
        if (billAdapter.isMultiSelectMode) {
            billAdapter.clearSelection()
        }
        tvToolbarTitle.text = getString(R.string.asset_stats_title_fmt, asset.name)
        val periodText = if (periodMode == PeriodMode.YEAR) {
            String.format(Locale.getDefault(), "%04d年", selectedYear)
        } else {
            String.format(Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth)
        }
        val filterText = buildFilterLabel()
        tvPeriodLabel.text = if (filterText.isNotBlank()) {
            "$periodText｜$filterText${getString(R.string.period_switch_hint)}"
        } else {
            "$periodText${getString(R.string.period_switch_hint)}"
        }

        dateStripAdapter.submit(dateChips, periodMode, selectedYear, selectedMonth)
        scrollDateStripToSelection()

        val filterKey = currentFilterCacheKey()
        val bills = filteredBills(filterKey)
        renderSummary(bills)
        renderBarChart(bills)
        renderPieChartAsync(filterKey, bills)
        renderBillSectionsProgressively(filterKey, bills)
    }

    private fun currentFilterCacheKey(): String {
        return "${periodMode.name}_${selectedYear}_${selectedMonth}_${forcedLabel.orEmpty()}_${forcedStartTime ?: -1L}_${forcedEndTime ?: -1L}_${forcedBillType.name}_${allAssetBills.size}_${allAssetBills.firstOrNull()?.id ?: -1L}"
    }

    private fun filteredBills(key: String = currentFilterCacheKey()): List<Bill> {
        filteredBillsCache[key]?.let { return it }
        val cal = Calendar.getInstance()
        val result = allAssetBills.filter { bill ->
            val timeMatched = if (forcedLabel == "全部") {
                true
            } else if (forcedStartTime != null && forcedEndTime != null) {
                bill.time in forcedStartTime!!..forcedEndTime!!
            } else {
                cal.timeInMillis = bill.time
                val year = cal.get(Calendar.YEAR)
                if (periodMode == PeriodMode.YEAR) {
                    year == selectedYear
                } else {
                    year == selectedYear && (cal.get(Calendar.MONTH) + 1) == selectedMonth
                }
            }
            val typeMatched = when (forcedBillType) {
                FilterBillType.EXPENSE -> bill.type == Bill.TYPE_EXPENSE && bill.subType != Bill.SUBTYPE_REFUND
                FilterBillType.INCOME -> bill.type == Bill.TYPE_INCOME
                FilterBillType.TRANSFER -> bill.type == Bill.TYPE_TRANSFER && bill.subType != Bill.SUBTYPE_REPAYMENT
                FilterBillType.REPAYMENT -> bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT
                FilterBillType.REFUND -> bill.subType == Bill.SUBTYPE_REFUND
                FilterBillType.ANY -> true
            }
            timeMatched && typeMatched
        }
        filteredBillsCache[key] = result
        return result
    }

    private fun isQuickLabel(label: String?): Boolean {
        return label == "今天" ||
            label == "昨天" ||
            label == "前天" ||
            label == "本周" ||
            label == "上周" ||
            label == "本月" ||
            label == "上月" ||
            label == "今年" ||
            label == "去年" ||
            label == "全部"
    }

    private fun buildFilterLabel(): String {
        val parts = mutableListOf<String>()
        val label = forcedLabel
        if (!label.isNullOrBlank()) {
            val timePart = when {
                label == "全部" -> getString(R.string.filter_all_time)
                label == getString(R.string.filter_custom) && forcedStartTime != null && forcedEndTime != null ->
                    formatDateRangeLabel(forcedStartTime!!, forcedEndTime!!)
                else -> label
            }
            parts += timePart
        } else if (forcedStartTime != null && forcedEndTime != null) {
            parts += formatDateRangeLabel(forcedStartTime!!, forcedEndTime!!)
        }
        val typePart = when (forcedBillType) {
            FilterBillType.EXPENSE -> getString(R.string.expense_label)
            FilterBillType.INCOME -> getString(R.string.income_label)
            FilterBillType.TRANSFER -> getString(R.string.transfer_label)
            FilterBillType.REPAYMENT -> getString(R.string.repayment_label)
            FilterBillType.REFUND -> getString(R.string.refund_label)
            FilterBillType.ANY -> ""
        }
        if (typePart.isNotBlank()) parts += typePart
        return parts.joinToString("｜")
    }

    private fun formatDateRangeLabel(start: Long, end: Long): String {
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
            String.format(
                Locale.getDefault(),
                "%04d-%02d-%02d",
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH) + 1,
                target.get(Calendar.DAY_OF_MONTH)
            )
        }
    }

    private fun renderBillSectionsProgressively(filterKey: String, bills: List<Bill>) {
        billListProgressJob?.cancel()
        resetBillPaginationState(filterKey)
        if (bills.isEmpty()) {
            billAdapter.replaceRows(emptyList())
            return
        }
        val token = ++billRenderToken
        billListProgressJob = lifecycleScope.launch {
            val plan = when {
                bills.size >= 600 -> BillRenderPlan(
                    initialBillCount = 12,
                    stepBillCount = 40,
                    chunkDelayMs = 92L,
                    firstRenderDelayMs = PIE_ANIM_DURATION_MS.toLong() + 180L
                )
                bills.size >= 300 -> BillRenderPlan(
                    initialBillCount = 18,
                    stepBillCount = 70,
                    chunkDelayMs = 76L,
                    firstRenderDelayMs = PIE_ANIM_DURATION_MS.toLong() + 120L
                )
                bills.size >= 150 -> BillRenderPlan(
                    initialBillCount = 16,
                    stepBillCount = 48,
                    chunkDelayMs = 78L,
                    firstRenderDelayMs = PIE_ANIM_DURATION_MS.toLong() + 100L
                )
                else -> BillRenderPlan(
                    initialBillCount = INITIAL_BILL_LIST_SIZE,
                    stepBillCount = BILL_LIST_STEP_SIZE,
                    chunkDelayMs = 48L,
                    firstRenderDelayMs = 40L
                )
            }
            val allRows = billRowsCache[filterKey] ?: withContext(Dispatchers.Default) {
                buildBillRows(bills)
            }.also { built ->
                billRowsCache[filterKey] = built
            }
            if (!isActive || token != billRenderToken) return@launch
            if (allRows.isEmpty()) {
                billAdapter.replaceRows(emptyList())
                return@launch
            }
            val chunkEnds = calculateRowChunkEnds(
                rows = allRows,
                initialBillCount = plan.initialBillCount,
                stepBillCount = plan.stepBillCount
            )
            if (chunkEnds.isEmpty()) {
                billAdapter.replaceRows(allRows)
                return@launch
            }

            delay(plan.firstRenderDelayMs)
            if (!isActive || token != billRenderToken) return@launch
            val firstEnd = chunkEnds.first()
            billAdapter.replaceRows(allRows.subList(0, firstEnd).toList())
            pagedBillRows = allRows
            pagedBillChunkEnds = chunkEnds
            pagedNextChunkIndex = 1
            pagedFilterKey = filterKey
            pagedAppendInFlight = false
            // If first page does not fill one screen, eagerly append once to avoid dead-end.
            nsvContent.post { ensureBillListCanScrollOrFullyLoaded() }
        }
    }

    private fun resetBillPaginationState(filterKey: String) {
        ++billRenderToken
        pagedBillRows = emptyList()
        pagedBillChunkEnds = emptyList()
        pagedNextChunkIndex = 0
        pagedFilterKey = filterKey
        pagedAppendInFlight = false
    }

    private fun tryAppendNextBillChunkByScroll() {
        if (pagedBillChunkEnds.isEmpty()) return
        if (pagedNextChunkIndex >= pagedBillChunkEnds.size) return
        if (pagedAppendInFlight) return
        if (!nsvContent.canScrollVertically(1)) return
        val child = nsvContent.getChildAt(0) ?: return
        val preloadPx = (80f * resources.displayMetrics.density).roundToInt()
        val reachedBottom =
            (nsvContent.scrollY + nsvContent.height) >= (child.height - preloadPx)
        if (!reachedBottom) return
        appendNextBillChunk()
    }

    private fun appendNextBillChunk() {
        if (pagedAppendInFlight) return
        if (pagedNextChunkIndex >= pagedBillChunkEnds.size) return
        val end = pagedBillChunkEnds[pagedNextChunkIndex]
        val start = pagedBillChunkEnds[pagedNextChunkIndex - 1]
        if (start >= end || end > pagedBillRows.size) {
            pagedNextChunkIndex = pagedBillChunkEnds.size
            return
        }
        pagedAppendInFlight = true
        billAdapter.appendRows(pagedBillRows.subList(start, end))
        pagedNextChunkIndex += 1
        pagedAppendInFlight = false
        nsvContent.post { ensureBillListCanScrollOrFullyLoaded() }
    }

    private fun ensureBillListCanScrollOrFullyLoaded() {
        if (pagedAppendInFlight) return
        if (pagedNextChunkIndex >= pagedBillChunkEnds.size) return
        if (nsvContent.canScrollVertically(1)) return
        appendNextBillChunk()
    }

    private fun renderSummary(bills: List<Bill>) {
        var totalExpense = 0.0
        var totalIncome = 0.0
        var totalTransfer = 0.0
        var totalRefund = 0.0

        bills.forEach { bill ->
            when {
                bill.subType == Bill.SUBTYPE_REFUND -> {
                    totalRefund += amountInAssetCurrency(bill, assetId, true)
                }
                bill.type == Bill.TYPE_EXPENSE -> {
                    totalExpense += amountInAssetCurrency(bill, assetId, false)
                }
                bill.type == Bill.TYPE_INCOME -> {
                    totalIncome += amountInAssetCurrency(bill, assetId, true)
                }
                bill.type == Bill.TYPE_TRANSFER -> {
                    totalTransfer += amountForAssetRow(bill, assetId)
                }
            }
        }

        val symbol = CurrencyManager.getSymbol(currentAsset?.currency ?: "CNY")
        tvTotalExpense.text = String.format(Locale.getDefault(), "%s%,.2f", symbol, totalExpense)
        tvTotalIncome.text = String.format(Locale.getDefault(), "%s%,.2f", symbol, totalIncome)
        tvTotalTransfer.text = String.format(Locale.getDefault(), "%s%,.2f", symbol, totalTransfer)
        tvTotalRefund.text = String.format(Locale.getDefault(), "%s%,.2f", symbol, totalRefund)
        tvTotalCount.text = bills.size.toString()

        val balance = totalIncome - totalExpense
        tvBalance.text = String.format(Locale.getDefault(), "%s%,.2f", symbol, balance)
        tvBalance.setTextColor(
            when {
                balance > 0 -> Color.parseColor("#159C5B")
                balance < 0 -> Color.parseColor("#D64545")
                else -> Color.parseColor("#111827")
            }
        )
    }

    private fun renderBarChart(bills: List<Bill>) {
        val labels: List<String>
        val values: List<Float>
        if (periodMode == PeriodMode.YEAR) {
            val monthly = DoubleArray(12) { 0.0 }
            val cal = Calendar.getInstance()
            bills.forEach { bill ->
                cal.timeInMillis = bill.time
                val month = cal.get(Calendar.MONTH)
                monthly[month] += amountForBar(bill)
            }
            labels = (1..12).map { "${it}月" }
            values = monthly.map { it.toFloat() }
        } else {
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val dayCount = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val daily = DoubleArray(dayCount) { 0.0 }
            val billCal = Calendar.getInstance()
            bills.forEach { bill ->
                billCal.timeInMillis = bill.time
                val day = billCal.get(Calendar.DAY_OF_MONTH) - 1
                if (day in daily.indices) {
                    daily[day] += amountForBar(bill)
                }
            }
            labels = (1..dayCount).map { it.toString() }
            values = daily.map { it.toFloat() }
        }

        val entries = values.mapIndexed { index, amount -> BarEntry(index.toFloat(), amount) }
        val dataSet = BarDataSet(entries, "")
        dataSet.color = if (barMode == ChartMode.EXPENSE) {
            Color.parseColor("#E85A67")
        } else {
            Color.parseColor("#2DB875")
        }
        dataSet.setDrawValues(periodMode == PeriodMode.YEAR)
        dataSet.valueTextSize = 7.5f
        dataSet.valueTextColor = if (barMode == ChartMode.EXPENSE) {
            Color.parseColor("#B95D68")
        } else {
            Color.parseColor("#2D8F62")
        }
        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getBarLabel(barEntry: BarEntry?): String {
                if (periodMode != PeriodMode.YEAR || barEntry == null || barEntry.y <= 0f) return ""
                return shortenAmount(barEntry.y.toDouble())
            }
        }
        dataSet.highLightColor = Color.parseColor("#7F2D3A")
        dataSet.highLightAlpha = 255

        val barData = BarData(dataSet).apply { barWidth = if (periodMode == PeriodMode.YEAR) 0.86f else 0.82f }
        barChart.data = barData
        currentBarLabels = labels
        currentBarValues = values
        selectedBarIndex = null
        barChart.highlightValue(null)

        barChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val nearest = value.roundToInt()
                if (kotlin.math.abs(value - nearest) > 0.001f) return ""
                val index = nearest
                if (index !in labels.indices) return ""
                return if (periodMode == PeriodMode.MONTH) {
                    val day = labels[index].toIntOrNull() ?: return ""
                    if (day % 2 == 0) labels[index] else ""
                } else {
                    labels[index]
                }
            }
        }
        val axisPadding = if (periodMode == PeriodMode.YEAR) 0.4f else 0.5f
        barChart.xAxis.axisMinimum = -axisPadding
        barChart.xAxis.axisMaximum = labels.size - 1 + axisPadding
        if (periodMode == PeriodMode.MONTH) {
            barChart.xAxis.setLabelCount(labels.size, false)
        } else {
            barChart.xAxis.setLabelCount(12, false)
        }
        barChart.fitScreen()
        barChart.moveViewToX(-axisPadding)
        barChart.setExtraTopOffset(if (periodMode == PeriodMode.MONTH) 8f else 4f)
        barChart.setExtraBottomOffset(2f)
        barChart.invalidate()
    }

    private fun highlightNearestBarByTouch(touchX: Float, touchY: Float) {
        val values = currentBarValues
        if (values.isEmpty()) return
        val dataSet = barChart.data?.getDataSetByIndex(0) ?: return
        val contentRect = barChart.viewPortHandler.contentRect
        Log.d(
            TAG_BAR,
            "highlightNearestBarByTouch: touchX=$touchX, touchY=$touchY, content=[${contentRect.left},${contentRect.top},${contentRect.right},${contentRect.bottom}]"
        )
        if (touchX < contentRect.left || touchX > contentRect.right) {
            Log.d(TAG_BAR, "touch ignored: x out of chart content")
            selectedBarIndex = null
            barChart.highlightValue(null)
            barChart.invalidate()
            return
        }
        val point = barChart.getTransformer(dataSet.axisDependency)
            .getValuesByTouchPoint(touchX, contentRect.centerY())
        val nearestIndex = point.x.roundToInt().coerceIn(0, values.lastIndex)
        Log.d(TAG_BAR, "mapped index=$nearestIndex, mappedX=${point.x}, mappedY=${point.y}, value=${values[nearestIndex]}")
        MPPointD.recycleInstance(point)
        val targetValue = values[nearestIndex]
        if (targetValue <= 0f) {
            Log.d(TAG_BAR, "skip marker: index=$nearestIndex has no expense/income")
            selectedBarIndex = null
            barChart.highlightValue(null)
            barChart.invalidate()
            return
        }
        if (selectedBarIndex == nearestIndex) {
            Log.d(TAG_BAR, "toggle off marker: index=$nearestIndex")
            selectedBarIndex = null
            barChart.highlightValue(null)
            barChart.invalidate()
            return
        }
        selectedBarIndex = nearestIndex
        val highlight = Highlight(nearestIndex.toFloat(), targetValue, 0)
        Log.d(TAG_BAR, "highlight -> x=${highlight.x}, y=${highlight.y}, ds=${highlight.dataSetIndex}")
        barChart.highlightValue(highlight, true)
        barChart.invalidate()
    }

    private fun buildBarMarkerText(index: Int): String {
        if (index !in currentBarValues.indices || index !in currentBarLabels.indices) return ""
        val value = currentBarValues[index].toDouble()
        val symbol = CurrencyManager.getSymbol(currentAsset?.currency ?: "CNY")
        val label = currentBarLabels[index]
        val firstLine = if (periodMode == PeriodMode.YEAR) {
            label
        } else {
            val day = label.toIntOrNull() ?: return ""
            String.format(
                Locale.getDefault(),
                "%02d-%02d",
                selectedMonth,
                day
            )
        }
        val modeText = if (barMode == ChartMode.EXPENSE) getString(R.string.expense_label) else getString(R.string.income_label)
        return "$firstLine\n$modeText: $symbol${String.format(Locale.getDefault(), "%,.2f", value)}"
    }

    private fun shortenAmount(amount: Double): String {
        val raw = when {
            amount >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", amount / 1_000_000)
            amount >= 1_000 -> String.format(Locale.getDefault(), "%.2fK", amount / 1_000)
            amount == 0.0 -> ""
            else -> String.format(Locale.getDefault(), "%.2f", amount)
        }
        return raw
            .replace(Regex("(?<=\\d)0+K$"), "K")
            .replace(Regex("\\.0+K$"), "K")
            .replace(Regex("(?<=\\d)0+$"), "")
            .replace(Regex("\\.$"), "")
    }

    private class AssetBarMarkerView(
        context: android.content.Context,
        private val textProvider: (Int) -> String
    ) : MarkerView(context, R.layout.view_asset_bar_marker) {
        private val tvText: TextView = findViewById(R.id.tv_marker_text)

        override fun refreshContent(e: com.github.mikephil.charting.data.Entry?, highlight: Highlight?) {
            val index = highlight?.x?.roundToInt() ?: e?.x?.roundToInt() ?: return
            tvText.text = textProvider(index)
            Log.d(TAG_BAR, "marker refresh: index=$index, text=${tvText.text}")
            super.refreshContent(e, highlight)
        }

        override fun getOffset(): MPPointF {
            return MPPointF(-(width / 2f), -height.toFloat() - 10f)
        }

        override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
            val base = getOffset()
            val chart = chartView ?: return base
            val offset = MPPointF(base.x, base.y)

            if (posX + offset.x < 0f) {
                offset.x = -posX
            } else if (posX + width + offset.x > chart.width) {
                offset.x = chart.width - posX - width.toFloat()
            }

            if (posY + offset.y < 0f) {
                offset.y = 0f
            }
            return offset
        }
    }

    private fun amountForBar(bill: Bill): Double {
        return when (barMode) {
            ChartMode.EXPENSE -> {
                if (bill.type == Bill.TYPE_EXPENSE && bill.subType != Bill.SUBTYPE_REFUND) {
                    amountInAssetCurrency(bill, assetId, false)
                } else {
                    0.0
                }
            }
            ChartMode.INCOME -> {
                if (bill.type == Bill.TYPE_INCOME && bill.subType != Bill.SUBTYPE_REFUND) {
                    amountInAssetCurrency(bill, assetId, true)
                } else {
                    0.0
                }
            }
        }
    }

    private fun renderPieChartAsync(filterKey: String, bills: List<Bill>) {
        pieRenderJob?.cancel()
        val token = ++pieRenderToken
        val modeSnapshot = pieMode
        val cacheKey = "${filterKey}_${modeSnapshot.name}"
        val loadingStartedAtMs = SystemClock.elapsedRealtime()
        showPieLoadingState()
        pieRenderJob = lifecycleScope.launch {
            // 至少给 UI 一个帧周期，确保“加载中 -> 动画”过渡可见。
            delay(18L)
            if (!isActive || modeSnapshot != pieMode || token != pieRenderToken) return@launch
            val model = categoryBreakdownCache[cacheKey] ?: withContext(Dispatchers.Default) {
                buildCategoryBreakdown(bills, modeSnapshot)
            }
            if (!isActive || modeSnapshot != pieMode || token != pieRenderToken) return@launch
            if (!categoryBreakdownCache.containsKey(cacheKey)) {
                categoryBreakdownCache[cacheKey] = model
            }
            val elapsed = SystemClock.elapsedRealtime() - loadingStartedAtMs
            if (elapsed < PIE_LOADING_MIN_MS) {
                delay(PIE_LOADING_MIN_MS - elapsed)
            }
            if (!isActive || modeSnapshot != pieMode || token != pieRenderToken) return@launch
            applyPieChartModel(model.pieModel)
            renderCategoryStats(model, modeSnapshot)
        }
    }

    private fun buildCategoryBreakdown(bills: List<Bill>, mode: ChartMode): CategoryBreakdownModel {
        val categoryMap = linkedMapOf<String, Double>()
        var total = 0.0
        bills.forEach { bill ->
            val amount = amountForPieByMode(bill, mode)
            if (amount <= 0.0) return@forEach
            val category = topLevelCategory(bill.categoryName)
            categoryMap[category] = (categoryMap[category] ?: 0.0) + amount
            total += amount
        }
        if (total <= 0.0) return CategoryBreakdownModel(emptyList(), emptyMap(), null)

        val sortedStats = categoryMap.toList().sortedByDescending { it.second }
        val colorByName = sortedStats.mapIndexed { index, pair ->
            pair.first to piePalette[index % piePalette.size]
        }.toMap()
        val categoryStats = sortedStats.map { (name, amount) ->
            CategoryStat(
                categoryName = name,
                amount = amount,
                percentage = (amount / total * 100.0).toFloat(),
                amountDiffFromLastPeriod = 0.0
            )
        }

        return CategoryBreakdownModel(
            categoryStats = categoryStats,
            colorByCategory = colorByName,
            pieModel = buildPieRenderModel(sortedStats, total, colorByName)
        )
    }

    private fun buildPieRenderModel(
        sortedStats: List<Pair<String, Double>>,
        total: Double,
        colorByName: Map<String, Int>
    ): PieRenderModel? {
        val minPercent = 2.0
        val filteredStats = sortedStats
            .filter { (_, amount) -> (amount / total * 100.0) >= minPercent }
            .sortedBy { it.second }
        if (filteredStats.isEmpty()) return null

        val entries = filteredStats.map { PieEntry(it.second.toFloat(), it.first) }
        val sliceColors = filteredStats.map { (name, _) -> colorByName[name] ?: piePalette[0] }
        val labelByCategory = filteredStats.associate { (name, amount) ->
            val pct = (amount / total * 100.0)
            name to "${name} ${String.format(Locale.getDefault(), "%.1f%%", pct)}"
        }
        val useOutsideLabel = true
        val labelSize = when {
            filteredStats.size >= 12 -> 7.5f
            filteredStats.size >= 9 -> 8.0f
            else -> 9.0f
        }
        val rotation = findBestInitialRotation(entries.map { it.value })

        return PieRenderModel(
            entries = entries,
            sliceColors = sliceColors,
            labelSize = labelSize,
            labelByCategory = labelByCategory,
            rotation = rotation,
            useOutsideLabel = useOutsideLabel
        )
    }

    private fun applyPieChartModel(model: PieRenderModel?) {
        if (model == null) {
            showPieEmptyState()
            pieChartHasRendered = false
            return
        }
        val dataSet = PieDataSet(model.entries, "").apply {
            colors = model.sliceColors
            xValuePosition = if (model.useOutsideLabel) {
                PieDataSet.ValuePosition.OUTSIDE_SLICE
            } else {
                PieDataSet.ValuePosition.INSIDE_SLICE
            }
            yValuePosition = if (model.useOutsideLabel) {
                PieDataSet.ValuePosition.OUTSIDE_SLICE
            } else {
                PieDataSet.ValuePosition.INSIDE_SLICE
            }
            valueLinePart1OffsetPercentage = 100f
            valueLinePart1Length = if (model.entries.size >= 10) 0.22f else 0.30f
            valueLinePart2Length = if (model.entries.size >= 10) 0.55f else 0.78f
            selectionShift = 4f
            setValueLineVariableLength(model.useOutsideLabel)
            setUsingSliceColorAsValueLineColor(model.useOutsideLabel)
            valueTextSize = model.labelSize
            setValueTextColors(model.sliceColors)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = ""
                override fun getPieLabel(value: Float, pieEntry: PieEntry): String {
                    return model.labelByCategory[pieEntry.label] ?: pieEntry.label
                }
            }
        }

        val pieData = PieData(dataSet).apply { setDrawValues(false) }
        pieChart.data = pieData
        pieChart.data?.notifyDataChanged()
        pieChart.notifyDataSetChanged()
        pieChart.highlightValues(null)
        pieChart.centerText = ""
        pieChart.setDrawEntryLabels(false)
        pieChart.rotationAngle = model.rotation
        pieChart.clearAnimation()
        pieChart.animate().cancel()
        pieChart.isRotationEnabled = true
        pieChartHasRendered = true
        pieChart.alpha = 0f
        pieChart.animate()
            .alpha(1f)
            .setDuration(280L)
            .start()
        pieChart.post {
            pieChart.animateY(PIE_ANIM_DURATION_MS, Easing.EaseInOutCubic)
            pieChart.postDelayed({
                pieChart.data?.setDrawValues(true)
                pieChart.data?.notifyDataChanged()
                pieChart.notifyDataSetChanged()
                pieChart.invalidate()
            }, PIE_ANIM_DURATION_MS.toLong() + 16L)
        }
    }

    private fun renderCategoryStats(model: CategoryBreakdownModel, mode: ChartMode) {
        categoryAdapter.clearPinCategory()
        categoryAdapter.setColorMap(model.colorByCategory)
        categoryAdapter.submitList(
            model.categoryStats,
            mode == ChartMode.EXPENSE,
            currentAsset?.currency?.let { CurrencyManager.getSymbol(it) } ?: "¥"
        )
    }

    private fun showPieLoadingState() {
        pieChart.clearAnimation()
        pieChart.animate().cancel()
        pieChart.setNoDataText(getString(R.string.data_loading))
        pieChart.clear()
        pieChart.invalidate()
    }

    private fun showPieEmptyState() {
        pieChart.clearAnimation()
        pieChart.animate().cancel()
        pieChart.setNoDataText(getString(R.string.no_chart_data))
        pieChart.clear()
        pieChart.invalidate()
    }

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

    private fun amountForPie(bill: Bill): Double = amountForPieByMode(bill, pieMode)

    private fun amountForPieByMode(bill: Bill, mode: ChartMode): Double {
        return when (mode) {
            ChartMode.EXPENSE -> {
                if (bill.type == Bill.TYPE_EXPENSE && bill.subType != Bill.SUBTYPE_REFUND) {
                    amountInAssetCurrency(bill, assetId, false)
                } else {
                    0.0
                }
            }
            ChartMode.INCOME -> {
                if (bill.type == Bill.TYPE_INCOME && bill.subType != Bill.SUBTYPE_REFUND) {
                    amountInAssetCurrency(bill, assetId, true)
                } else {
                    0.0
                }
            }
        }
    }

    private fun buildBillRows(bills: List<Bill>): List<Any> {
        val rows = mutableListOf<Any>()
        if (periodMode == PeriodMode.YEAR) {
            val groups = linkedMapOf<String, MutableList<Bill>>()
            bills.forEach { bill ->
                val key = dfMonthKey.format(Date(bill.time))
                groups.getOrPut(key) { mutableListOf() }.add(bill)
            }
            groups.forEach { (_, monthBills) ->
                val title = dfMonthTitle.format(Date(monthBills.first().time))
                val summary = calcSectionIncomeExpense(monthBills)
                rows += SectionHeaderRow(title, summary.first, summary.second)
                monthBills.forEach { rows += BillRow(it) }
            }
        } else {
            val groups = linkedMapOf<Int, MutableList<Bill>>()
            val cal = Calendar.getInstance()
            bills.forEach { bill ->
                cal.timeInMillis = bill.time
                val day = cal.get(Calendar.DAY_OF_MONTH)
                groups.getOrPut(day) { mutableListOf() }.add(bill)
            }
            groups.forEach { (_, dayBills) ->
                val firstDate = Date(dayBills.first().time)
                val title = "${dfDayTitle.format(firstDate)} ${dfWeekday.format(firstDate)}"
                val summary = calcSectionIncomeExpense(dayBills)
                rows += SectionHeaderRow(title, summary.first, summary.second)
                dayBills.forEach { rows += BillRow(it) }
            }
        }
        return rows
    }

    private fun calculateRowChunkEnds(
        rows: List<Any>,
        initialBillCount: Int,
        stepBillCount: Int
    ): List<Int> {
        if (rows.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var nextTarget = initialBillCount.coerceAtLeast(1)
        var billCount = 0
        rows.forEachIndexed { index, row ->
            if (row is BillRow) billCount++
            if (billCount >= nextTarget) {
                result += (index + 1)
                nextTarget += stepBillCount.coerceAtLeast(1)
            }
        }
        if (result.isEmpty() || result.last() != rows.size) {
            result += rows.size
        }
        return result
    }

    private fun calcSectionIncomeExpense(bills: List<Bill>): Pair<Double, Double> {
        var income = 0.0
        var expense = 0.0
        bills.forEach { bill ->
            when {
                bill.subType == Bill.SUBTYPE_REFUND -> income += amountInAssetCurrency(bill, assetId, true)
                bill.type == Bill.TYPE_INCOME -> income += amountInAssetCurrency(bill, assetId, true)
                bill.type == Bill.TYPE_EXPENSE -> expense += amountInAssetCurrency(bill, assetId, false)
            }
        }
        return income to expense
    }

    private fun updateBarToggleState() {
        if (barMode == ChartMode.EXPENSE) {
            btnBarExpense.background = getDrawable(R.drawable.bg_stats_toggle_selected)
            btnBarIncome.background = ColorDrawable(Color.TRANSPARENT)
            btnBarExpense.setTextColor(Color.parseColor("#111827"))
            btnBarIncome.setTextColor(Color.parseColor("#8B93A1"))
        } else {
            btnBarIncome.background = getDrawable(R.drawable.bg_stats_toggle_selected)
            btnBarExpense.background = ColorDrawable(Color.TRANSPARENT)
            btnBarIncome.setTextColor(Color.parseColor("#111827"))
            btnBarExpense.setTextColor(Color.parseColor("#8B93A1"))
        }
    }

    private fun updatePieToggleState() {
        if (pieMode == ChartMode.EXPENSE) {
            btnPieExpense.background = getDrawable(R.drawable.bg_stats_toggle_selected)
            btnPieIncome.background = ColorDrawable(Color.TRANSPARENT)
            btnPieExpense.setTextColor(Color.parseColor("#111827"))
            btnPieIncome.setTextColor(Color.parseColor("#8B93A1"))
        } else {
            btnPieIncome.background = getDrawable(R.drawable.bg_stats_toggle_selected)
            btnPieExpense.background = ColorDrawable(Color.TRANSPARENT)
            btnPieIncome.setTextColor(Color.parseColor("#111827"))
            btnPieExpense.setTextColor(Color.parseColor("#8B93A1"))
        }
    }

    private fun scrollDateStripToSelection() {
        val targetIndex = dateChips.indexOfFirst {
            when (periodMode) {
                PeriodMode.YEAR -> it.type == DateChipType.YEAR && it.year == selectedYear
                PeriodMode.MONTH -> it.type == DateChipType.MONTH && it.year == selectedYear && it.month == selectedMonth
            }
        }
        if (targetIndex >= 0) {
            rvDateStrip.post { rvDateStrip.smoothScrollToPosition(targetIndex) }
        }
    }

    private fun topLevelCategory(name: String): String {
        val normalized = BillDisplayFormatter.stripRefundPrefix(name).trim()
        val level = normalized.split(Regex("\\s*>\\s*|/::/| - |::|·")).firstOrNull().orEmpty()
        return if (level.isBlank()) "未分类" else level
    }

    private fun secondLevelCategory(name: String): String {
        val normalized = BillDisplayFormatter.stripRefundPrefix(name).trim()
        val parts = normalized.split(Regex("\\s*>\\s*|/::/| - |::|·"))
        val secondLevel = parts.getOrNull(1)?.trim().orEmpty()
        return if (secondLevel.isBlank()) topLevelCategory(name) else secondLevel
    }

    private fun hasSubCategories(categoryName: String, mode: ChartMode): Boolean {
        return getBillsForCategory(categoryName, mode).any { bill ->
            val normalized = BillDisplayFormatter.stripRefundPrefix(bill.categoryName).trim()
            val parts = normalized.split(Regex("\\s*>\\s*|/::/| - |::|·"))
            parts.size > 1 && parts.getOrNull(1)?.trim().orEmpty().isNotBlank()
        }
    }

    private fun getBillsForCategory(categoryName: String, mode: ChartMode): List<Bill> {
        return filteredBills().filter { bill ->
            amountForPieByMode(bill, mode) > 0.0 &&
                topLevelCategory(bill.categoryName) == categoryName
        }
    }

    private fun getSubCategoryStats(categoryName: String, mode: ChartMode): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        getBillsForCategory(categoryName, mode).forEach { bill ->
            val amount = amountForPieByMode(bill, mode)
            if (amount <= 0.0) return@forEach
            val subCategory = secondLevelCategory(bill.categoryName)
            map[subCategory] = (map[subCategory] ?: 0.0) + amount
        }
        return map.toList()
            .sortedByDescending { it.second }
            .toMap()
    }

    private fun getBillsForSubCategory(
        categoryName: String,
        subCategory: String,
        mode: ChartMode
    ): List<Bill> {
        return getBillsForCategory(categoryName, mode).filter { bill ->
            secondLevelCategory(bill.categoryName) == subCategory
        }
    }

    private fun showCategoryDetails(categoryName: String) {
        if (supportFragmentManager.isStateSaved) return
        val mode = pieMode
        val categoryBills = getBillsForCategory(categoryName, mode)
        if (categoryBills.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_category_bills), Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasSubCategories(categoryName, mode)) {
            BillListBottomSheet(categoryName, categoryBills)
                .show(supportFragmentManager, "asset_category_bills")
            return
        }

        val subStats = getSubCategoryStats(categoryName, mode)
        if (subStats.isEmpty()) {
            BillListBottomSheet(categoryName, categoryBills)
                .show(supportFragmentManager, "asset_category_bills")
            return
        }

        SubCategoryBottomSheet(
            title = categoryName,
            isExpense = mode == ChartMode.EXPENSE,
            subStats = subStats,
            totalAmount = subStats.values.sum(),
            colors = piePalette,
            currencySymbol = currentAsset?.currency?.let { CurrencyManager.getSymbol(it) } ?: "¥"
        ) { subCategory ->
            if (!supportFragmentManager.isStateSaved) {
                val bills = getBillsForSubCategory(categoryName, subCategory, mode)
                if (bills.isEmpty()) {
                    Toast.makeText(this, getString(R.string.no_category_bills), Toast.LENGTH_SHORT).show()
                } else {
                    BillListBottomSheet(subCategory, bills)
                        .show(supportFragmentManager, "asset_sub_category_bills")
                }
            }
        }.show(supportFragmentManager, "asset_sub_categories")
    }

    private fun amountInAssetCurrency(bill: Bill, ownerAssetId: Long, isInflow: Boolean): Double {
        val assetCurrency = currentAsset?.currency ?: bill.currency
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val baseExpenseAmount = baseOriginalAmount(bill)
                BillAssetImpactService.convertAmountBetweenCurrencies(baseExpenseAmount, bill.currency, assetCurrency)
            }
            bill.type == Bill.TYPE_TRANSFER && isInflow && bill.toAccountId == ownerAssetId -> {
                bill.amount * bill.exchangeRate
            }
            bill.type == Bill.TYPE_TRANSFER && !isInflow && bill.accountId == ownerAssetId -> {
                val sourceAmount = BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
                val feeInSource = if (bill.fee > 0.0) {
                    BillAssetImpactService.convertAmountBetweenCurrencies(bill.fee, bill.currency, assetCurrency)
                } else {
                    0.0
                }
                sourceAmount + feeInSource
            }
            else -> BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
        }
    }

    private fun amountForAssetRow(bill: Bill, ownerAssetId: Long): Double {
        val isInflow = when {
            bill.subType == Bill.SUBTYPE_REFUND -> true
            bill.type == Bill.TYPE_INCOME -> true
            bill.type == Bill.TYPE_TRANSFER -> bill.toAccountId == ownerAssetId && bill.accountId != ownerAssetId
            else -> false
        }
        return amountInAssetCurrency(bill, ownerAssetId, isInflow)
    }

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun detailOwnerAssetId(bill: Bill): Long {
        return when {
            bill.subType == Bill.SUBTYPE_REFUND -> bill.accountId ?: assetId
            bill.type == Bill.TYPE_TRANSFER -> when {
                bill.accountId == assetId || bill.toAccountId == assetId -> assetId
                bill.accountId != null -> bill.accountId
                else -> bill.toAccountId ?: assetId
            }
            else -> bill.accountId ?: assetId
        }
    }

    private fun refundedAmountInBillCurrency(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || bill.subType == Bill.SUBTYPE_REFUND) return 0.0
        return (baseOriginalAmount(bill) - bill.amount).coerceAtLeast(0.0)
    }

    private fun buildAssetDetailFormula(bill: Bill, ownerAssetId: Long): String? {
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val refundedAmount = refundedAmountInBillCurrency(bill)
                if (refundedAmount > 0.0) {
                    getString(R.string.refund_deduct_formula_simple, BillDisplayFormatter.formatMoney(refundedAmount, bill.currency), BillDisplayFormatter.formatMoney(bill.amount, bill.currency))
                } else {
                    BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, "CNY")
                }
            }
            else -> BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, "CNY")
        }
    }

    private inner class DateStripAdapter(
        private val onClick: (DateChipItem) -> Unit
    ) : RecyclerView.Adapter<DateStripAdapter.VH>() {

        private var items: List<DateChipItem> = emptyList()
        private var mode: PeriodMode = PeriodMode.YEAR
        private var year: Int = 0
        private var month: Int = 0

        fun submit(items: List<DateChipItem>, mode: PeriodMode, year: Int, month: Int) {
            this.items = items
            this.mode = mode
            this.year = year
            this.month = month
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_asset_stats_date_chip, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val selected = when (mode) {
                PeriodMode.YEAR -> item.type == DateChipType.YEAR && item.year == year
                PeriodMode.MONTH -> item.type == DateChipType.MONTH && item.year == year && item.month == month
            }
            holder.bind(item, selected)
        }

        override fun getItemCount(): Int = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvChip: TextView = v.findViewById(R.id.tv_date_chip)

            fun bind(item: DateChipItem, selected: Boolean) {
                tvChip.text = if (item.type == DateChipType.YEAR) {
                    tvChip.context.getString(R.string.year_format, item.year)
                } else {
                    tvChip.context.getString(R.string.month_format, item.month)
                }
                tvChip.background = if (selected) {
                    itemView.context.getDrawable(R.drawable.bg_stats_date_capsule)
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                tvChip.setTextColor(
                    if (selected) Color.parseColor("#2B7DE9") else Color.parseColor("#7D8694")
                )
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    private inner class AssetStatsBillAdapter(
        private val currencyProvider: () -> String,
        private val amountProvider: (Bill) -> Double
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val PAYLOAD_MODE_CHANGE = "PAYLOAD_MODE_CHANGE"
        private val PAYLOAD_SELECTION_CHANGE = "PAYLOAD_SELECTION_CHANGE"
        private val PAYLOAD_HEADER_SELECTION_CHANGE = "PAYLOAD_HEADER_SELECTION_CHANGE"

        private val typeHeader = 0
        private val typeBill = 1
        private val rows = mutableListOf<Any>()
        private val iconUrlCache = mutableMapOf<String, String>()
        var isMultiSelectMode: Boolean = false
        val selectedBills = mutableSetOf<Bill>()
        var onBillItemClick: ((Bill) -> Unit)? = null
        var onSelectionChanged: ((Int) -> Unit)? = null

        private fun buildIconCacheKey(categoryName: String, type: Int): String = "$type|$categoryName"

        private fun getCachedIconUrl(key: String): String? = synchronized(iconUrlCache) { iconUrlCache[key] }

        private fun putCachedIconUrl(key: String, url: String) {
            synchronized(iconUrlCache) {
                if (iconUrlCache.size > 200) iconUrlCache.clear()
                iconUrlCache[key] = url
            }
        }

        fun replaceRows(list: List<Any>) {
            rows.clear()
            rows.addAll(list)
            val availableIds = rows.mapNotNull { (it as? BillRow)?.bill?.id }.toSet()
            selectedBills.removeAll { it.id !in availableIds }
            if (getSelectableBills().isEmpty()) {
                isMultiSelectMode = false
            }
            onSelectionChanged?.invoke(selectedBills.size)
            notifyDataSetChanged()
        }

        fun appendRows(list: List<Any>) {
            if (list.isEmpty()) return
            val start = rows.size
            rows.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun getSelectableBills(): List<Bill> = rows.mapNotNull { (it as? BillRow)?.bill }

        fun getSelectedBills(): List<Bill> = selectedBills.toList()

        fun selectAll() {
            isMultiSelectMode = true
            selectedBills.clear()
            selectedBills.addAll(getSelectableBills())
            onSelectionChanged?.invoke(selectedBills.size)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        fun deselectAll() {
            isMultiSelectMode = true
            selectedBills.clear()
            onSelectionChanged?.invoke(0)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        fun clearSelection() {
            selectedBills.clear()
            isMultiSelectMode = false
            onSelectionChanged?.invoke(0)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        private fun selectableBillsForSection(headerPosition: Int): List<Bill> {
            return rows.asSequence()
                .drop(headerPosition + 1)
                .takeWhile { it !is SectionHeaderRow }
                .mapNotNull { (it as? BillRow)?.bill }
                .toList()
        }

        private fun sectionSelectionState(headerPosition: Int): Pair<Boolean, Boolean> {
            val bills = selectableBillsForSection(headerPosition)
            if (bills.isEmpty()) return false to false
            val selectedCount = bills.count { selectedBills.contains(it) }
            return (selectedCount == bills.size) to (selectedCount in 1 until bills.size)
        }

        private fun updateSectionHeaderNear(position: Int) {
            val headerPosition = (position downTo 0).firstOrNull { rows.getOrNull(it) is SectionHeaderRow } ?: return
            notifyItemChanged(headerPosition, PAYLOAD_HEADER_SELECTION_CHANGE)
        }

        private fun setSectionSelection(headerPosition: Int, checked: Boolean) {
            val bills = selectableBillsForSection(headerPosition)
            if (bills.isEmpty()) return
            if (checked) {
                selectedBills.addAll(bills)
            } else {
                selectedBills.removeAll(bills.toSet())
            }
            isMultiSelectMode = true
            onSelectionChanged?.invoke(selectedBills.size)
            val nextHeader = ((headerPosition + 1) until rows.size)
                .firstOrNull { rows[it] is SectionHeaderRow }
                ?: rows.size
            notifyItemRangeChanged(headerPosition, nextHeader - headerPosition, PAYLOAD_SELECTION_CHANGE)
        }

        override fun getItemViewType(position: Int): Int {
            return if (rows[position] is SectionHeaderRow) typeHeader else typeBill
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == typeHeader) {
                HeaderVH(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_asset_stats_section_header, parent, false)
                )
            } else {
                BillVH(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_home_transaction, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is SectionHeaderRow -> (holder as HeaderVH).bind(row, position)
                is BillRow -> (holder as BillVH).bind(row.bill, position)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty() && holder is BillVH) {
                val row = rows.getOrNull(position) as? BillRow
                if (row != null) {
                    if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                        holder.updateMode(row.bill)
                        return
                    }
                    if (payloads.contains(PAYLOAD_SELECTION_CHANGE)) {
                        holder.updateSelection(row.bill)
                        return
                    }
                }
            }
            if (payloads.isNotEmpty() && holder is HeaderVH) {
                if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                    holder.updateMode(position)
                    return
                }
                if (payloads.contains(PAYLOAD_SELECTION_CHANGE) || payloads.contains(PAYLOAD_HEADER_SELECTION_CHANGE)) {
                    holder.updateSelection(position)
                    return
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount(): Int = rows.size

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            if (holder is BillVH) {
                Glide.with(holder.ivIcon.context).clear(holder.ivIcon)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val cbSelect = v.findViewById<CheckBox>(R.id.cb_select_section)
            private val tvTitle = v.findViewById<TextView>(R.id.tv_section_title)
            private val tvSummary = v.findViewById<TextView>(R.id.tv_section_summary)

            init {
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION || !isMultiSelectMode) return@setOnClickListener
                    val (allSelected, _) = sectionSelectionState(pos)
                    setSectionSelection(pos, checked = !allSelected)
                }
            }

            fun bind(item: SectionHeaderRow, position: Int) {
                val symbol = CurrencyManager.getSymbol(currencyProvider())
                tvTitle.text = item.title
                tvSummary.text = tvSummary.context.getString(R.string.income_expense_summary_fmt, symbol, String.format(Locale.getDefault(), "%.2f", item.income), String.format(Locale.getDefault(), "%.2f", item.expense))
                updateMode(position)
            }

            fun updateMode(position: Int) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                updateSelection(position)
            }

            fun updateSelection(position: Int) {
                val (allSelected, partiallySelected) = sectionSelectionState(position)
                cbSelect.isChecked = allSelected
                cbSelect.alpha = if (partiallySelected) 0.55f else 1f
            }
        }

        inner class BillVH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
            private val tvCategory = v.findViewById<TextView>(R.id.tv_bill_category)
            private val tvDetail = v.findViewById<TextView>(R.id.tv_bill_detail)
            private val tvAmount = v.findViewById<TextView>(R.id.tv_bill_amount)
            private val tvAsset = v.findViewById<TextView>(R.id.tv_bill_asset)
            private val tvTime = v.findViewById<TextView>(R.id.tv_bill_time)
            private val iconContainer = v.findViewById<View>(R.id.layout_icon_container)
            private val cbSelect = v.findViewById<CheckBox>(R.id.cb_bill_select)

            private fun setIconSizeDp(dp: Int) {
                val px = (itemView.resources.displayMetrics.density * dp).toInt()
                ivIcon.layoutParams = ivIcon.layoutParams.apply {
                    width = px
                    height = px
                }
            }

            private fun setIconContainerSizeDp(widthDp: Int, heightDp: Int = 44) {
                val widthPx = (itemView.resources.displayMetrics.density * widthDp).toInt()
                val heightPx = (itemView.resources.displayMetrics.density * heightDp).toInt()
                iconContainer.layoutParams = iconContainer.layoutParams.apply {
                    width = widthPx
                    height = heightPx
                }
            }

            fun updateMode(bill: Bill) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun updateSelection(bill: Bill) {
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun bind(bill: Bill, position: Int) {
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND
                val showCategoryIcon = Prefs.isShowBillCategoryIcon(itemView.context)
                val showFullCategory = Prefs.isShowBillFullCategory(itemView.context)
                val remarkPriority = Prefs.isBillRemarkPriority(itemView.context)
                val symbol = CurrencyManager.getSymbol(currencyProvider())
                val amount = amountProvider(bill)
                val baseCategory = BillDisplayFormatter.stripRefundPrefix(bill.categoryName)

                val hasHeaderAbove = rows.getOrNull(position - 1) is SectionHeaderRow
                val isGroupStart = position == 0 || hasHeaderAbove
                val isGroupEnd = position == rows.lastIndex || rows.getOrNull(position + 1) is SectionHeaderRow
                itemView.setBackgroundResource(
                    when {
                        isGroupStart && isGroupEnd ->
                            if (hasHeaderAbove) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_single
                        isGroupStart ->
                            if (hasHeaderAbove) R.drawable.bg_bill_group_middle else R.drawable.bg_bill_group_top
                        isGroupEnd -> R.drawable.bg_bill_group_bottom
                        else -> R.drawable.bg_bill_group_middle
                    }
                )

                updateMode(bill)
                tvTime.visibility = View.GONE

                val categoryText = when {
                    isRepayment -> itemView.context.getString(R.string.repayment_label)
                    isTransfer -> itemView.context.getString(R.string.transfer_label)
                    else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { itemView.context.getString(R.string.uncategorized) }
                }

                val suffix = if (isTransfer) "${bill.accountName}->${bill.toAccountName}" else bill.accountName
                val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
                    categoryText = categoryText,
                    remarkText = bill.remark,
                    suffixText = suffix,
                    remarkPriority = remarkPriority
                )
                tvCategory.text = primaryText
                if (secondaryText.isNotEmpty()) {
                    tvDetail.text = secondaryText
                    tvDetail.visibility = View.VISIBLE
                } else {
                    tvDetail.visibility = View.GONE
                }

                tvAmount.text = when {
                    isRefund -> "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
                    bill.type == Bill.TYPE_EXPENSE -> "-$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
                    bill.type == Bill.TYPE_INCOME -> "+$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
                    else -> "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
                }
                tvAmount.setTextColor(
                    when {
                        isRefund -> Color.parseColor("#9AA1AA")
                        bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                        bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                        else -> Color.parseColor("#757575")
                    }
                )
                tvCategory.setTextColor(if (isRefund) Color.parseColor("#8E98A3") else Color.parseColor("#333333"))
                tvDetail.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))
                tvAsset.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

                tvAsset.text = when {
                    bill.type == Bill.TYPE_TRANSFER -> "${bill.accountName}->${bill.toAccountName}"
                    else -> bill.accountName
                }

                if (!showCategoryIcon) {
                    iconContainer.setBackgroundColor(Color.TRANSPARENT)
                    setIconContainerSizeDp(10, 44)
                    ivIcon.clearColorFilter()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    setIconSizeDp(6)
                    ivIcon.setImageResource(
                        when (bill.type) {
                            Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                            Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                            else -> R.drawable.bg_bill_dot_neutral
                        }
                    )
                } else {
                    iconContainer.setBackgroundResource(
                        when {
                            !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                            !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                            else -> R.drawable.bg_circle_soft
                        }
                    )
                    setIconContainerSizeDp(44, 44)
                    setIconSizeDp(21)
                    val iconTint = when {
                        isRefund -> Color.parseColor("#8E98A3")
                        bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                        bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                        else -> Color.parseColor("#9E9E9E")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    ivIcon.setColorFilter(iconTint)
                    ivIcon.setImageDrawable(null)

                    val iconLookupName = if (isRefund) baseCategory else bill.categoryName
                    val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                    val iconCacheKey = buildIconCacheKey(iconLookupName, iconLookupType)
                    ivIcon.tag = iconCacheKey
                    val cachedIconUrl = getCachedIconUrl(iconCacheKey)
                    if (cachedIconUrl != null) {
                        if (cachedIconUrl.isNotEmpty()) {
                            Glide.with(itemView.context)
                                .load(cachedIconUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(ivIcon)
                        } else {
                            ivIcon.setImageDrawable(null)
                        }
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val iconUrl = CategoryIconHelper.findCategoryIcon(itemView.context, iconLookupName, iconLookupType)
                            putCachedIconUrl(iconCacheKey, iconUrl)
                            withContext(Dispatchers.Main) {
                                if (ivIcon.tag != iconCacheKey) return@withContext
                                if (iconUrl.isNotEmpty()) {
                                    Glide.with(itemView.context)
                                        .load(iconUrl)
                                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                        .into(ivIcon)
                                } else {
                                    ivIcon.setImageDrawable(null)
                                }
                            }
                        }
                    }
                }

                itemView.setOnClickListener {
                    if (isMultiSelectMode) {
                        if (selectedBills.contains(bill)) {
                            selectedBills.remove(bill)
                        } else {
                            selectedBills.add(bill)
                        }
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                            updateSectionHeaderNear(pos)
                        }
                        onSelectionChanged?.invoke(selectedBills.size)
                    } else {
                        onBillItemClick?.invoke(bill)
                    }
                }

                itemView.setOnLongClickListener {
                    if (!isMultiSelectMode) {
                        isMultiSelectMode = true
                        selectedBills.clear()
                        selectedBills.add(bill)
                        notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
                    } else {
                        selectedBills.add(bill)
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                            updateSectionHeaderNear(pos)
                        }
                    }
                    onSelectionChanged?.invoke(selectedBills.size)
                    true
                }
            }
        }
    }
}
