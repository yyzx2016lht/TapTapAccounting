package com.taostudio.tapaccounting.ui.main.home

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMoveTargetResolver
import com.taostudio.tapaccounting.logic.BillStatsContribution
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
import com.taostudio.tapaccounting.ui.common.MultiSelectBarSummary
import com.taostudio.tapaccounting.ui.common.MultiSelectMotion
import com.taostudio.tapaccounting.ui.dialog.BillMoveDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.dialog.SearchAssetPickerDialog
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScopeStore
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索框与筛选入口吸顶，汇总随结果滚动；筛选统一使用确认后生效的底部面板。
 */
class BillSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_BOOK = "extra_source_book"

        private const val PANEL_NONE = 0
        private const val PANEL_TIME = 1
        private const val PANEL_BOOKS = 2
        private const val PANEL_FILTER = 3

        private const val PRESET_ALL = 0
        private const val PRESET_MONTH = 1
        private const val PRESET_LAST_MONTH = 2
        private const val PRESET_YEAR = 3
        private const val PRESET_LAST_YEAR = 4
        private const val PRESET_CUSTOM = 5

        const val TYPE_FILTER_REFUND = 99
    }


    private lateinit var pillTime: View
    private lateinit var pillBook: View
    private lateinit var pillMore: View
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var rvResults: RecyclerView
    private lateinit var tvFilterTime: TextView
    private lateinit var tvFilterBook: TextView
    private lateinit var tvFilterMore: TextView
    private lateinit var ivFilterTimeArrow: ImageView
    private lateinit var ivFilterBookArrow: ImageView
    private lateinit var ivFilterMoreArrow: ImageView
    private lateinit var layoutMultiSelectActions: View
    private lateinit var tvMsSummary: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var btnMsExcludeStats: TextView

    private var filterSheet: BottomSheetDialog? = null
    private var openPanel: Int = PANEL_NONE

    // 筛选状态（确定后才生效的用 draft 暂存，见各面板）。
    private var groupMode = HomeAdapter.GroupMode.DAY
    private var datePreset = PRESET_ALL
    private var customStart: Long? = null
    private var customEnd: Long? = null
    private var bookFilter: Set<String>? = null
    private var typeFilterSingle: Int? = null
    private var accountFilter: Set<String> = emptySet()
    private var categoryFilter: Set<String> = emptySet()
    private var excludedOnly = false
    private var minAmount: Double? = null
    private var maxAmount: Double? = null
    private var sortDesc = true

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = HomeAdapter().apply { showChart = false }
    private val summaryAdapter = SummaryAdapter()
    private val emptyAdapter = EmptyAdapter()
    private var allBills: List<Bill> = emptyList()
    /** 面板用的全量账本（用户账本 + 库里出现过的，不止当前范围）。 */
    private var panelBookNames: List<String> = emptyList()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var hasResumedOnce = false
    private var viewScope: ResolvedLedgerViewScope? = null
    private var sourceViewScope: ResolvedLedgerViewScope? = null
    private var currentKeyword: String = ""

    private val sourceBookName by lazy {
        BookAccountManager.normalizeBookName(
            intent.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().ifBlank { BookAccountManager.ALL_BOOK }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_search)


        pillTime = findViewById(R.id.filter_time_touch)
        pillBook = findViewById(R.id.filter_book_touch)
        pillBook.isEnabled = false
        pillMore = findViewById(R.id.filter_more_touch)
        rvResults = findViewById(R.id.rv_bill_search_results)
        etSearch = findViewById(R.id.et_bill_search)
        btnClear = findViewById(R.id.btn_search_clear)
        tvFilterTime = findViewById(R.id.tv_filter_time)
        tvFilterBook = findViewById(R.id.tv_filter_book)
        tvFilterMore = findViewById(R.id.tv_filter_more)
        ivFilterTimeArrow = findViewById(R.id.iv_filter_time_arrow)
        ivFilterBookArrow = findViewById(R.id.iv_filter_book_arrow)
        ivFilterMoreArrow = findViewById(R.id.iv_filter_more_arrow)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        tvMsSummary = findViewById(R.id.tv_ms_summary)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        btnMsExcludeStats = findViewById(R.id.btn_ms_exclude_stats)

        findViewById<View>(R.id.btn_bill_search_back).setOnClickListener { handleBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            handleBackPressed()
        }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = ConcatAdapter(summaryAdapter, adapter, emptyAdapter)
        adapter.groupMode = groupMode
        adapter.sortDescending = sortDesc

        setupMultiSelectActions()

        adapter.onBillItemClick = { bill ->
            if (!isFinishing) {
                BillDetailSheetHelper.showBillDetailSheet(
                    context = this,
                    lifecycleOwner = this,
                    bill = bill,
                    onBillChanged = { loadAllBills() },
                    listRecyclerView = rvResults
                )
            }
        }

        findViewById<View>(R.id.filter_time_touch).setOnClickListener { togglePanel(PANEL_TIME) }
        findViewById<View>(R.id.filter_book_touch).setOnClickListener { togglePanel(PANEL_BOOKS) }
        findViewById<View>(R.id.filter_more_touch).setOnClickListener { togglePanel(PANEL_FILTER) }

        refreshFilterLabels()
        loadAllBills()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                currentKeyword = keyword
                btnClear.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    applyFilter(keyword.trim())
                }
            }
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                applyFilter(etSearch.text?.toString().orEmpty().trim())
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
                true
            } else false
        }
        btnClear.setOnClickListener {
            etSearch.setText("")
            currentKeyword = ""
            applyFilter("")
        }

        etSearch.post {
            etSearch.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce) {
            // 独立详情页编辑/删除后返回时，重新读取当前搜索结果。
            loadAllBills()
        } else {
            hasResumedOnce = true
        }
    }

    override fun onDestroy() {
        filterSheet?.setOnDismissListener(null)
        filterSheet?.dismiss()
        filterSheet = null
        super.onDestroy()
    }

    private fun handleBackPressed() {
        if (filterSheet?.isShowing == true) {
            dismissFilterSheet()
            return
        }
        if (openPanel != PANEL_NONE) {
            openPanel = PANEL_NONE
            refreshFilterLabels()
            return
        }
        if (adapter.isMultiSelectMode) {
            adapter.clearSelection()
            return
        }
        // 输入法开着时第一次返回先收键盘（输入法自己会吃掉一次返回键），同时清掉焦点，
        // 避免出现“按了返回没反应”的假象；下一次返回直接退出。
        val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(rvResults)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (etSearch.hasFocus()) etSearch.clearFocus()
        if (imeVisible) {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
            return
        }
        finish()
    }

    // ---------- 列表头：汇总 / 空态 ----------

    private inner class SummaryAdapter : RecyclerView.Adapter<SummaryAdapter.VH>() {
        var count: Int = 0
        var expense: Double = 0.0
        var income: Double = 0.0
        var balance: Double = 0.0
        var transfer: Double = 0.0
        var refund: Double = 0.0
        var excluded: Double = 0.0

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvCount: TextView = itemView.findViewById(R.id.tv_search_count)
            val btnSort: TextView = itemView.findViewById(R.id.btn_search_sort)
            val tvSumExpense: TextView = itemView.findViewById(R.id.tv_sum_expense)
            val tvSumIncome: TextView = itemView.findViewById(R.id.tv_sum_income)
            val tvSumBalance: TextView = itemView.findViewById(R.id.tv_sum_balance)
            val tvSumTransfer: TextView = itemView.findViewById(R.id.tv_sum_transfer)
            val tvSumRefund: TextView = itemView.findViewById(R.id.tv_sum_refund)
            val tvSumExcluded: TextView = itemView.findViewById(R.id.tv_sum_excluded)
            val cellTransfer: LinearLayout = itemView.findViewById(R.id.cell_sum_transfer)
            val cellRefund: LinearLayout = itemView.findViewById(R.id.cell_sum_refund)
            val cellExcluded: LinearLayout = itemView.findViewById(R.id.cell_sum_excluded)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.header_bill_search_summary, parent, false)
            val holder = VH(v)
            holder.btnSort.setOnClickListener { toggleSort(holder.btnSort) }
            return holder
        }

        override fun getItemCount(): Int = if (count > 0) 1 else 0

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tvCount.text = getString(R.string.search_bills_count_fmt, count)
            holder.tvSumExpense.text = formatSummaryAmount(expense)
            holder.tvSumIncome.text = formatSummaryAmount(income)
            holder.tvSumBalance.text = formatSummaryAmount(balance)
            holder.tvSumTransfer.text = formatSummaryAmount(transfer)
            holder.tvSumRefund.text = formatSummaryAmount(refund)
            holder.tvSumExcluded.text = formatSummaryAmount(excluded)
            holder.cellTransfer.visibility = if (transfer > 0.005) View.VISIBLE else View.GONE
            holder.cellRefund.visibility = if (refund > 0.005) View.VISIBLE else View.GONE
            holder.cellExcluded.visibility = if (excluded > 0.005) View.VISIBLE else View.GONE
            holder.btnSort.setText(if (sortDesc) R.string.search_sort_latest else R.string.search_sort_earliest)
        }

        fun update(
            count: Int,
            expense: Double,
            income: Double,
            balance: Double,
            transfer: Double,
            refund: Double,
            excluded: Double
        ) {
            val wasVisible = this.count > 0
            this.count = count
            this.expense = expense
            this.income = income
            this.balance = balance
            this.transfer = transfer
            this.refund = refund
            this.excluded = excluded
            when {
                wasVisible && count > 0 -> notifyItemChanged(0)
                wasVisible -> notifyItemRemoved(0)
                count > 0 -> notifyItemInserted(0)
            }
        }
    }

    private inner class EmptyAdapter : RecyclerView.Adapter<EmptyAdapter.VH>() {
        var showEmpty: Boolean = false

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bill_search_empty, parent, false)
            v.findViewById<View>(R.id.btn_search_empty_reset).setOnClickListener { resetSearch() }
            return VH(v)
        }

        override fun getItemCount(): Int = if (showEmpty) 1 else 0

        override fun onBindViewHolder(holder: VH, position: Int) = Unit

        fun setEmpty(empty: Boolean) {
            if (showEmpty == empty) return
            showEmpty = empty
            if (empty) notifyItemInserted(0) else notifyItemRemoved(0)
        }
    }

    private fun toggleSort(btnSort: TextView) {
        sortDesc = !sortDesc
        btnSort.setText(if (sortDesc) R.string.search_sort_latest else R.string.search_sort_earliest)
        applyFilter(currentKeyword)
    }

    private fun resetSearch() {
        groupMode = HomeAdapter.GroupMode.DAY
        datePreset = PRESET_ALL
        customStart = null
        customEnd = null
        bookFilter = null
        typeFilterSingle = null
        accountFilter = emptySet()
        categoryFilter = emptySet()
        excludedOnly = false
        minAmount = null
        maxAmount = null
        etSearch.setText("")
        searchJob?.cancel()
        currentKeyword = ""
        refreshFilterLabels()
        loadAllBills()
    }

    // ---------- 筛选面板：固定标题与操作栏，中间内容独立滚动 ----------

    private fun togglePanel(panel: Int) {
        if (openPanel == panel && filterSheet?.isShowing == true) {
            dismissFilterSheet()
            return
        }
        dismissFilterSheet()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()

        val shell = layoutInflater.inflate(R.layout.sheet_bill_search, FrameLayout(this), false)
        shell.clipToOutline = true
        val contentContainer: FrameLayout = shell.findViewById(R.id.layout_search_sheet_content)
        val content = when (panel) {
            PANEL_TIME -> getPanelTime(shell)
            PANEL_BOOKS -> getPanelBooks(shell)
            PANEL_FILTER -> getPanelFilter(shell)
            else -> return
        }
        contentContainer.addView(content)
        val title = when (panel) {
            PANEL_TIME -> R.string.search_time_title
            PANEL_BOOKS -> R.string.search_select_book
            else -> R.string.search_filter
        }
        val hint = when (panel) {
            PANEL_TIME -> R.string.search_time_hint
            PANEL_BOOKS -> R.string.search_books_hint
            else -> R.string.search_filter_hint
        }
        shell.findViewById<TextView>(R.id.tv_search_sheet_title).apply {
            setText(title)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        shell.findViewById<TextView>(R.id.tv_search_sheet_hint).setText(hint)
        shell.findViewById<View>(R.id.btn_search_sheet_close).setOnClickListener { dismissFilterSheet() }

        val dialog = BottomSheetDialog(this)
        filterSheet = dialog
        openPanel = panel
        dialog.setContentView(shell)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            val bars = ViewCompat.getRootWindowInsets(window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
            val availableHeight = resources.displayMetrics.heightPixels - (bars?.top ?: 0) - (bars?.bottom ?: 0)
            val heightRatio = if (panel == PANEL_FILTER) 0.88f else 0.76f
            val maxHeight = minOf((availableHeight * heightRatio).toInt(), dp(720f))
            bottomSheet.setBackgroundResource(android.R.color.transparent)
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            dialog.behavior.apply {
                this.maxHeight = maxHeight
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.setOnDismissListener {
            if (filterSheet === dialog) {
                filterSheet = null
                openPanel = PANEL_NONE
                refreshFilterLabels()
            }
        }
        dialog.show()
        refreshFilterLabels()
    }

    private fun dismissFilterSheet() {
        filterSheet?.dismiss()
        filterSheet = null
        openPanel = PANEL_NONE
        refreshFilterLabels()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun refreshFilterLabels() {
        val timeLabel = when (datePreset) {
            PRESET_MONTH -> getString(R.string.search_preset_month)
            PRESET_LAST_MONTH -> getString(R.string.search_preset_last_month)
            PRESET_YEAR -> getString(R.string.search_preset_year)
            PRESET_LAST_YEAR -> getString(R.string.search_preset_last_year)
            PRESET_CUSTOM -> formatCustomRange() ?: getString(R.string.search_custom)
            else -> getString(R.string.search_all_time)
        }
        tvFilterTime.text = timeLabel
        pillTime.contentDescription = "${getString(R.string.search_cap_time)}：$timeLabel"
        setFilterItemState(
            pillTime, tvFilterTime, ivFilterTimeArrow,
            highlighted = datePreset != PRESET_ALL || groupMode != HomeAdapter.GroupMode.DAY,
            open = openPanel == PANEL_TIME
        )
        val scope = viewScope
        val selectedNames = bookFilter ?: scope?.selectedBookNames
        val bookLabel = when {
            selectedNames == null -> sourceBookName
            selectedNames.size == 1 -> selectedNames.first()
            selectedNames.size == panelBookNames.size -> getString(R.string.all_books)
            bookFilter == null && scope?.isAllBooks == true && scope.hasCollapsedBooks -> getString(R.string.search_active_books)
            else -> getString(R.string.search_books_multi_fmt, selectedNames.size)
        }
        tvFilterBook.text = bookLabel
        pillBook.contentDescription = "${getString(R.string.search_cap_book)}：${scope?.displayLabel ?: bookLabel}"
        setFilterItemState(
            pillBook, tvFilterBook, ivFilterBookArrow,
            highlighted = bookFilter != null || scope?.isAllBooks == false,
            open = openPanel == PANEL_BOOKS
        )
        val advanceCount = (if (typeFilterSingle != null) 1 else 0) +
            accountFilter.size + categoryFilter.size +
            (if (minAmount != null) 1 else 0) + (if (maxAmount != null) 1 else 0) +
            (if (excludedOnly) 1 else 0)
        tvFilterMore.text = if (advanceCount == 0) {
            getString(R.string.search_filter)
        } else {
            getString(R.string.search_filter_count_fmt, advanceCount)
        }
        pillMore.contentDescription = tvFilterMore.text
        setFilterItemState(
            pillMore, tvFilterMore, ivFilterMoreArrow,
            highlighted = advanceCount > 0, open = openPanel == PANEL_FILTER
        )
    }

    /** 自定义日期短标签：M.d~M.d；只选一边时回落为“自定义”。 */
    private fun formatCustomRange(): String? {
        val s = customStart
        val e = customEnd
        if (s == null || e == null) return null
        val df = SimpleDateFormat("M.d", Locale.getDefault())
        return "${df.format(Date(s))}~${df.format(Date(e))}"
    }

    /** 筛选 pill 视觉：有筛选项/展开中用选中底色 + 品牌色文字，箭头上翻。 */
    private fun setFilterItemState(
        pill: View,
        label: TextView,
        arrow: ImageView,
        highlighted: Boolean,
        open: Boolean
    ) {
        val emphasized = highlighted || open
        pill.isSelected = highlighted
        ViewCompat.setStateDescription(pill, getString(if (open) R.string.search_panel_open else R.string.search_panel_closed))
        pill.setBackgroundResource(
            if (emphasized) R.drawable.bg_bill_search_pill_selected
            else R.drawable.bg_bill_search_pill
        )
        label.setTextColor(
            ContextCompat.getColor(
                this,
                if (emphasized) R.color.brand_primary else R.color.text_primary
            )
        )
        arrow.rotation = if (open) 180f else 0f
        ImageViewCompat.setImageTintList(
            arrow,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (emphasized) R.color.brand_primary else R.color.text_secondary
                )
            )
        )
    }

    private fun makeChip(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): Chip {
        return Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            chipMinHeight = dp(40f).toFloat()
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(10f).toFloat()).build()
            chipStartPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
            )
            chipEndPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
            )
            chipBackgroundColor = ContextCompat.getColorStateList(
                this@BillSearchActivity, R.color.search_chip_bg_selector
            )
            setTextColor(
                ContextCompat.getColorStateList(
                    this@BillSearchActivity, R.color.search_chip_text_selector
                )
            )
            chipStrokeWidth = 0f
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    // ----- 时间面板：所有改动保留在本次面板的草稿内，关闭即放弃 -----

    private fun getPanelTime(shell: View): View {
        val v = layoutInflater.inflate(R.layout.panel_bill_search_time, shell.findViewById<FrameLayout>(R.id.layout_search_sheet_content), false)
        val cgGroup: ChipGroup = v.findViewById(R.id.cg_search_group)
        val cgDate: ChipGroup = v.findViewById(R.id.cg_search_date)
        val btnStart: TextView = v.findViewById(R.id.btn_search_start_date)
        val btnEnd: TextView = v.findViewById(R.id.btn_search_end_date)
        var draftGroup = groupMode
        var draftPreset = datePreset
        var draftStart = customStart
        var draftEnd = customEnd
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        fun refreshDates() {
            btnStart.text = draftStart?.let { df.format(Date(it)) } ?: getString(R.string.search_start_date)
            btnEnd.text = draftEnd?.let { df.format(Date(it)) } ?: getString(R.string.search_end_date)
            btnStart.setTextColor(ContextCompat.getColor(this, if (draftStart != null) R.color.brand_primary else R.color.text_secondary))
            btnEnd.setTextColor(ContextCompat.getColor(this, if (draftEnd != null) R.color.brand_primary else R.color.text_secondary))
        }

        val modes = listOf(
            HomeAdapter.GroupMode.DAY to R.string.search_group_day,
            HomeAdapter.GroupMode.MONTH to R.string.search_group_month,
            HomeAdapter.GroupMode.NONE to R.string.search_group_none
        )
        val modeId = mutableMapOf<Int, HomeAdapter.GroupMode>()
        modes.forEach { (mode, label) ->
            val chip = makeChip(getString(label), mode == draftGroup) {}
            chip.id = View.generateViewId()
            modeId[chip.id] = mode
            cgGroup.addView(chip)
        }
        cgGroup.setOnCheckedStateChangeListener { _, ids ->
            ids.firstOrNull()?.let(modeId::get)?.let { draftGroup = it }
        }

        val presets = listOf(
            PRESET_ALL to R.string.search_time_all,
            PRESET_MONTH to R.string.search_preset_month,
            PRESET_LAST_MONTH to R.string.search_preset_last_month,
            PRESET_YEAR to R.string.search_preset_year,
            PRESET_LAST_YEAR to R.string.search_preset_last_year
        )
        val presetId = mutableMapOf<Int, Int>()
        cgDate.isSelectionRequired = true
        presets.forEach { (preset, label) ->
            val chip = makeChip(getString(label), preset == draftPreset) {}
            chip.id = View.generateViewId()
            presetId[chip.id] = preset
            cgDate.addView(chip)
        }
        cgDate.setOnCheckedStateChangeListener { _, ids ->
            val preset = ids.firstOrNull()?.let(presetId::get) ?: return@setOnCheckedStateChangeListener
            draftPreset = preset
            draftStart = null
            draftEnd = null
            refreshDates()
        }

        fun pickDate(isStart: Boolean) {
            val cal = Calendar.getInstance()
            (if (isStart) draftStart else draftEnd)?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = picked.timeInMillis
                picked.add(Calendar.DAY_OF_MONTH, 1)
                val endOfDay = picked.timeInMillis - 1
                if (isStart) {
                    draftStart = startOfDay
                    if (draftEnd != null && draftEnd!! < startOfDay) draftEnd = endOfDay
                } else {
                    draftEnd = endOfDay
                    if (draftStart != null && draftStart!! > endOfDay) draftStart = startOfDay
                }
                draftPreset = PRESET_CUSTOM
                cgDate.clearCheck()
                refreshDates()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnStart.setOnClickListener { pickDate(true) }
        btnEnd.setOnClickListener { pickDate(false) }
        shell.findViewById<View>(R.id.btn_search_sheet_reset).setOnClickListener {
            cgGroup.check(modeId.entries.first { it.value == HomeAdapter.GroupMode.DAY }.key)
            cgDate.check(presetId.entries.first { it.value == PRESET_ALL }.key)
            draftGroup = HomeAdapter.GroupMode.DAY
            draftPreset = PRESET_ALL
            draftStart = null
            draftEnd = null
            refreshDates()
        }
        shell.findViewById<View>(R.id.btn_search_sheet_apply).setOnClickListener {
            groupMode = draftGroup
            datePreset = draftPreset
            customStart = draftStart
            customEnd = draftEnd
            refreshFilterLabels()
            applyFilter(currentKeyword.trim(), scrollTop = true)
            dismissFilterSheet()
        }
        refreshDates()
        return v
    }

    private fun dateRangeMillis(): LongRange? {
        val cal = Calendar.getInstance()
        return when (datePreset) {
            PRESET_MONTH -> {
                val start = (cal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val end = (cal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                start..end
            }
            PRESET_LAST_MONTH -> {
                val c = (cal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                val start = (c.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val end = (c.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                start..end
            }
            PRESET_YEAR -> {
                val start = (cal.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.JANUARY)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                start..System.currentTimeMillis()
            }
            PRESET_LAST_YEAR -> {
                val y = cal.get(Calendar.YEAR) - 1
                val start = Calendar.getInstance().apply {
                    set(y, Calendar.JANUARY, 1, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val end = Calendar.getInstance().apply {
                    set(y, Calendar.DECEMBER, 31, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                start..end
            }
            PRESET_CUSTOM -> {
                val s = customStart
                val e = customEnd
                if (s == null && e == null) null else (s ?: 0L)..(e ?: Long.MAX_VALUE)
            }
            else -> null
        }
    }

    // ----- 账本面板：选中态来自范围本身，而不是该范围内是否已有账单 -----

    private fun getPanelBooks(shell: View): View {
        val v = layoutInflater.inflate(R.layout.panel_bill_search_books, shell.findViewById<FrameLayout>(R.id.layout_search_sheet_content), false)
        val rv: RecyclerView = v.findViewById(R.id.rv_search_books)
        val tvCount: TextView = v.findViewById(R.id.tv_search_books_selected_count)
        val tvHint: TextView = v.findViewById(R.id.tv_search_books_hint)
        val btnAll: TextView = shell.findViewById(R.id.btn_search_sheet_reset)
        val btnApply: TextView = shell.findViewById(R.id.btn_search_sheet_apply)
        val defaultBooks = sourceViewScope?.selectedBookNames.orEmpty()
        val selection = BillSearchBookSelection(viewScope?.availableBooks.orEmpty(), bookFilter ?: defaultBooks)

        fun refreshSelection() {
            val selected = selection.selectedNames
            tvCount.text = getString(R.string.search_books_selected_fmt, selected.size, selection.visibleNames.size)
            tvHint.setText(when {
                selected.isEmpty() -> R.string.search_books_required
                selection.collapsedNames.isNotEmpty() -> R.string.search_books_collapsed_hint
                else -> R.string.search_books_hint
            })
            btnAll.setText(if (selection.allVisibleSelected) R.string.search_clear_all else R.string.select_all)
            btnApply.isEnabled = selected.isNotEmpty()
            btnApply.alpha = if (btnApply.isEnabled) 1f else 0.45f
        }
        val booksAdapter = BooksAdapter(selection) { refreshSelection() }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = booksAdapter
        rv.itemAnimator = null
        btnAll.setOnClickListener {
            selection.toggleAll()
            booksAdapter.notifyItemRangeChanged(0, booksAdapter.itemCount)
            refreshSelection()
        }
        btnApply.setOnClickListener {
            val selected = selection.selectedNames
            if (selected.isEmpty()) return@setOnClickListener
            // null 仅表示沿用进入时的范围；跨账本全选必须保留显式集合。
            bookFilter = if (selected == defaultBooks) null else selected
            refreshFilterLabels()
            loadAllBills()
            dismissFilterSheet()
        }
        refreshSelection()
        return v
    }

    private inner class BooksAdapter(
        private val selection: BillSearchBookSelection,
        private val onChanged: () -> Unit
    ) : RecyclerView.Adapter<BooksAdapter.VH>() {
        private var rows = selection.rowNames

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val cb: CheckBox = itemView.findViewById(R.id.cb_search_book)
            val tv: TextView = itemView.findViewById(R.id.tv_search_book_name)
            val icon: ImageView = itemView.findViewById(R.id.iv_search_book_icon)
            val arrow: ImageView = itemView.findViewById(R.id.iv_search_book_arrow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_book, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val name = rows[position]
            val isGroup = name == BookAccountManager.COLLAPSED_BOOK_GROUP
            val selected = !isGroup && name in selection.selectedNames
            val color = ContextCompat.getColor(this@BillSearchActivity, when {
                isGroup -> R.color.text_secondary
                selected -> R.color.brand_primary
                else -> R.color.text_primary
            })
            holder.tv.text = if (isGroup) {
                getString(R.string.search_collapsed_books_fmt, selection.collapsedNames.size)
            } else name
            holder.tv.setTextColor(color)
            holder.tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            holder.icon.setImageResource(if (isGroup) R.drawable.ic_backup_folder_action else R.drawable.ic_profile_book_entry)
            ImageViewCompat.setImageTintList(holder.icon, ColorStateList.valueOf(color))
            holder.cb.visibility = if (isGroup) View.GONE else View.VISIBLE
            holder.cb.isChecked = selected
            holder.arrow.visibility = if (isGroup) View.VISIBLE else View.GONE
            holder.arrow.rotation = if (selection.collapsedExpanded) 180f else 0f
            holder.itemView.isSelected = selected
            holder.itemView.setPaddingRelative(
                dp(if (name in selection.collapsedNames) 28f else 12f), dp(12f), dp(12f), dp(12f)
            )
            holder.itemView.contentDescription = if (isGroup) {
                getString(if (selection.collapsedExpanded) R.string.search_collapse_books else R.string.search_expand_books)
            } else name
            ViewCompat.setStateDescription(holder.itemView, getString(when {
                isGroup && selection.collapsedExpanded -> R.string.search_panel_open
                isGroup -> R.string.search_panel_closed
                selected -> R.string.search_book_selected
                else -> R.string.search_book_unselected
            }))
            holder.itemView.setOnClickListener {
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                if (isGroup) {
                    selection.toggleCollapsed()
                    rows = selection.rowNames
                    notifyItemChanged(currentPosition)
                    if (selection.collapsedExpanded) {
                        notifyItemRangeInserted(currentPosition + 1, selection.collapsedNames.size)
                    } else {
                        notifyItemRangeRemoved(currentPosition + 1, selection.collapsedNames.size)
                    }
                } else {
                    selection.toggleBook(name)
                    notifyItemChanged(currentPosition)
                }
                onChanged()
            }
        }
    }

    // ----- 筛选面板：类型单选（对标钱迹）+ 资产/分类弹窗 + 金额 + 其它（确定后生效） -----

    private var draftType: Int? = null
    private var draftAccounts: MutableSet<String> = mutableSetOf()
    private var draftCategories: MutableSet<String> = mutableSetOf()
    private var draftExcludedOnly: Boolean = false

    private fun getPanelFilter(shell: View): View {
        val v = layoutInflater.inflate(R.layout.panel_bill_search_filter, shell.findViewById<FrameLayout>(R.id.layout_search_sheet_content), false)
        draftType = typeFilterSingle
        draftAccounts = accountFilter.toMutableSet()
        draftCategories = categoryFilter.toMutableSet()
        draftExcludedOnly = excludedOnly

        val cgTypes: ChipGroup = v.findViewById(R.id.cg_search_types)
        val cgOther: ChipGroup = v.findViewById(R.id.cg_search_other)
        val tvAssetValue: TextView = v.findViewById(R.id.tv_search_asset_value)
        val tvCategoryValue: TextView = v.findViewById(R.id.tv_search_category_value)
        val etMin: EditText = v.findViewById(R.id.et_search_min_amount)
        val etMax: EditText = v.findViewById(R.id.et_search_max_amount)
        etMin.setText(minAmount?.let { trimAmount(it) } ?: "")
        etMax.setText(maxAmount?.let { trimAmount(it) } ?: "")

        buildTypeChips(cgTypes)
        buildOtherChips(cgOther)
        refreshDrillRowValues(tvAssetValue, tvCategoryValue)

        v.findViewById<View>(R.id.row_search_asset).setOnClickListener {
            openAssetPicker { refreshDrillRowValues(tvAssetValue, tvCategoryValue) }
        }
        v.findViewById<View>(R.id.row_search_category).setOnClickListener {
            openCategoryPicker { refreshDrillRowValues(tvAssetValue, tvCategoryValue) }
        }

        shell.findViewById<View>(R.id.btn_search_sheet_reset).setOnClickListener {
            draftType = null
            draftAccounts.clear()
            draftCategories.clear()
            draftExcludedOnly = false
            etMin.setText("")
            etMax.setText("")
            etMin.error = null
            etMax.error = null
            buildTypeChips(cgTypes)
            buildOtherChips(cgOther)
            refreshDrillRowValues(tvAssetValue, tvCategoryValue)
        }
        shell.findViewById<View>(R.id.btn_search_sheet_apply).setOnClickListener {
            etMin.error = null
            etMax.error = null
            val minText = etMin.text.toString().trim().replace(',', '.')
            val maxText = etMax.text.toString().trim().replace(',', '.')
            val parsedMin = minText.toDoubleOrNull()
            val parsedMax = maxText.toDoubleOrNull()
            if (minText.isNotEmpty() && (parsedMin == null || !parsedMin.isFinite() || parsedMin < 0)) {
                etMin.error = getString(R.string.search_min_invalid)
                etMin.requestFocus()
                return@setOnClickListener
            }
            if (maxText.isNotEmpty() && (parsedMax == null || !parsedMax.isFinite() || parsedMax < 0)) {
                etMax.error = getString(R.string.search_max_invalid)
                etMax.requestFocus()
                return@setOnClickListener
            }
            if (parsedMin != null && parsedMax != null && parsedMin > parsedMax) {
                etMax.error = getString(R.string.search_amount_range_invalid)
                etMax.requestFocus()
                return@setOnClickListener
            }
            typeFilterSingle = draftType
            accountFilter = draftAccounts.toSet()
            categoryFilter = draftCategories.toSet()
            excludedOnly = draftExcludedOnly
            minAmount = parsedMin
            maxAmount = parsedMax
            refreshFilterLabels()
            applyFilter(currentKeyword.trim(), scrollTop = true)
            dismissFilterSheet()
        }
        return v
    }

    private fun typeLabel(type: Int): String = when (type) {
        Bill.TYPE_EXPENSE -> getString(R.string.search_type_expense)
        Bill.TYPE_INCOME -> getString(R.string.search_type_income)
        Bill.TYPE_TRANSFER -> getString(R.string.search_type_transfer)
        Bill.TYPE_REPAYMENT -> getString(R.string.search_type_repay)
        TYPE_FILTER_REFUND -> getString(R.string.search_type_refund)
        else -> ""
    }

    /** 账单类型单选（对标钱迹）：全部即不过滤，点选其一。 */
    private fun buildTypeChips(cg: ChipGroup) {
        cg.setOnCheckedStateChangeListener(null)
        cg.removeAllViews()
        val options = listOf(
            null,
            Bill.TYPE_EXPENSE,
            Bill.TYPE_INCOME,
            Bill.TYPE_TRANSFER,
            Bill.TYPE_REPAYMENT,
            TYPE_FILTER_REFUND
        )
        val idOf = mutableMapOf<Int, Int?>()
        options.forEach { t ->
            val chip = makeChip(if (t == null) getString(R.string.search_type_all) else typeLabel(t), draftType == t) {}
            chip.id = View.generateViewId()
            idOf[chip.id] = t
            cg.addView(chip)
        }
        cg.setOnCheckedStateChangeListener { _, checkedIds ->
            val picked = checkedIds.firstOrNull()?.let(idOf::get)
            // selectionRequired 约束下不会为空；防呆回落为全部。
            draftType = picked
        }
    }

    private fun buildOtherChips(cg: ChipGroup) {
        cg.removeAllViews()
        val chip = makeChip(getString(R.string.search_excluded_only), draftExcludedOnly) { checked ->
            draftExcludedOnly = checked
        }
        cg.addView(chip)
    }

    /** 筛选面板里资产/分类两行的右侧文案：草稿态。 */
    private fun refreshDrillRowValues(tvAssetValue: TextView, tvCategoryValue: TextView) {
        tvAssetValue.text = when {
            draftAccounts.isEmpty() -> getString(R.string.search_type_all)
            draftAccounts.size == 1 -> draftAccounts.first()
            else -> getString(R.string.search_selected_items_fmt, draftAccounts.size)
        }
        tvCategoryValue.text = if (draftCategories.isEmpty()) {
            getString(R.string.search_type_all)
        } else {
            getString(R.string.search_selected_items_fmt, draftCategories.size)
        }
    }

    /** 资产多选：图标宫格底部弹窗，点选切换，底部确认为准。 */
    private fun openAssetPicker(onPicked: () -> Unit) {
        SearchAssetPickerDialog.show(
            this,
            draftAccounts.toSet()
        ) { selected ->
            draftAccounts.clear()
            draftAccounts.addAll(selected)
            onPicked()
        }
    }

    /** 分类多选：复用分类宫格弹窗的多选版（底部弹出，一级展开二级）。 */
    private fun openCategoryPicker(onPicked: () -> Unit) {
        OverlayDialogs.showCategoryMultiPicker(
            this,
            draftCategories.toSet()
        ) { selected ->
            draftCategories.clear()
            draftCategories.addAll(selected)
            onPicked()
        }
    }

    /** 一级名代表整棵子树；否则看叶子名交集。 */
    private fun matchCategory(bill: Bill, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        val full = BillDisplayFormatter.stripRefundPrefix(bill.categoryName)
        if (full.isBlank()) return false
        val segments = full.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.first() in selected) return true
        return segments.any { it in selected }
    }

    private fun trimAmount(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    // ---------- 数据加载与过滤 ----------

    private fun loadAllBills() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val selectedBooks = bookFilter
            val loaded = withContext(Dispatchers.IO) {
                val source = LedgerViewScopeStore.resolve(applicationContext, db, sourceBookName)
                val scope = source.withSearchBooks(selectedBooks)
                val bills = db.billDao().getAllBillsList()
                Triple(source, scope, bills)
            }
            sourceViewScope = loaded.first
            viewScope = loaded.second
            val scope = loaded.second
            adapter.setViewContext(
                contextsByBookName = scope.memberContextsByBookName,
                showMembers = scope.scope.members == LedgerMemberScope.EVERYONE,
                showBookNames = false
            )
            adapter.detailSuffixProvider = if (scope.isAggregate) {
                { bill -> "账本：${BookAccountManager.normalizeBookName(bill.bookName)}" }
            } else null
            allBills = loaded.third
                .asSequence()
                .filter(scope::includes)
                .toList()
            panelBookNames = scope.availableBooks.map { BookAccountManager.normalizeBookName(it.name) }
            pillBook.isEnabled = panelBookNames.isNotEmpty()
            if (bookFilter != null) {
                bookFilter = bookFilter!!.intersect(panelBookNames.toSet())
                if (bookFilter!!.isEmpty()) bookFilter = null
            }
            refreshFilterLabels()
            applyFilter(currentKeyword.trim())
        }
    }

    private fun applyFilter(keywordRaw: String, scrollTop: Boolean = false) {
        currentKeyword = keywordRaw
        val range = dateRangeMillis()
        val keyword = keywordRaw.trim().lowercase(Locale.getDefault())
        val normalizedKeyword = keyword.replace(',', '.')

        val filtered = allBills.asSequence().filter { bill ->
            // 账本
            val books = bookFilter
            if (books != null &&
                BookAccountManager.normalizeBookName(bill.bookName) !in books
            ) return@filter false
            // 日期
            if (range != null && (bill.time < range.start || bill.time > range.endInclusive)) {
                return@filter false
            }
            // 类型（单选，null = 全部）
            if (!matchType(bill, typeFilterSingle)) return@filter false
            // 其它：只看不计收支
            if (excludedOnly && BillStatsContribution.countsInStats(bill)) return@filter false
            // 账户（含转入账户，多选）
            if (accountFilter.isNotEmpty() &&
                bill.accountName.trim() !in accountFilter &&
                bill.toAccountName.trim() !in accountFilter
            ) return@filter false
            // 分类（一级名代表整棵子树）
            if (!matchCategory(bill, categoryFilter)) return@filter false
            // 金额（折人民币绝对值，含小数写法兼容）
            val cny = kotlin.math.abs(bill.amount * bill.exchangeRate)
            if (minAmount != null && cny < minAmount!! - 1e-9) return@filter false
            if (maxAmount != null && cny > maxAmount!! + 1e-9) return@filter false
            // 关键词
            if (keyword.isBlank()) return@filter true
            matchKeyword(bill, keyword, normalizedKeyword)
        }.toList()

        updateSummary(filtered)
        adapter.groupMode = groupMode
        adapter.sortDescending = sortDesc
        adapter.submitList(filtered)
        emptyAdapter.setEmpty(filtered.isEmpty())
        if (scrollTop) rvResults.scrollToPosition(0)
    }

    private fun matchType(bill: Bill, type: Int?): Boolean {
        if (type == null) return true
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        if (isRefund) return type == TYPE_FILTER_REFUND
        return when (bill.type) {
            Bill.TYPE_EXPENSE -> Bill.TYPE_EXPENSE == type
            Bill.TYPE_INCOME -> Bill.TYPE_INCOME == type
            Bill.TYPE_TRANSFER -> Bill.TYPE_TRANSFER == type
            Bill.TYPE_REPAYMENT -> Bill.TYPE_REPAYMENT == type
            else -> false
        }
    }

    private fun matchKeyword(bill: Bill, keyword: String, normalizedKeyword: String): Boolean {
        val textMatched = listOf(
            bill.remark,
            bill.categoryName,
            BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
            bill.accountName,
            bill.toAccountName,
            BookAccountManager.normalizeBookName(bill.bookName)
        ).any { value ->
            value.isNotBlank() && value.lowercase(Locale.getDefault()).contains(keyword)
        }
        if (textMatched) return true

        val numericCandidates = listOf(
            String.format(Locale.US, "%.2f", bill.amount),
            bill.amount.toString(),
            String.format(Locale.US, "%.2f", bill.originalAmount),
            bill.originalAmount.toString(),
            String.format(Locale.US, "%.2f", bill.amount * bill.exchangeRate)
        )
        return numericCandidates.any { candidate ->
            val value = candidate.lowercase(Locale.getDefault())
            value.contains(keyword) || value.contains(normalizedKeyword)
        }
    }

    /** 汇总大金额缩写：过万用万/亿展示避免三列挤爆，小额保持原样。 */
    private fun formatSummaryAmount(value: Double): String {
        if (!value.isFinite()) return AmountFormatHelper.formatAmount(0.0)
        val abs = kotlin.math.abs(value)
        if (abs < 10_000.0) return AmountFormatHelper.formatAmount(value)
        val sign = if (value < 0) "-" else ""
        fun trim2(x: Double): String =
            String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.')
        return when {
            abs >= 100_000_000.0 -> sign + trim2(abs / 100_000_000.0) + "亿"
            else -> sign + trim2(abs / 10_000.0) + "万"
        }
    }

    private fun updateSummary(filtered: List<Bill>) {
        var expense = 0.0
        var income = 0.0
        var transfer = 0.0
        var refund = 0.0
        var excluded = 0.0
        filtered.forEach { bill ->
            val cny = bill.amount * bill.exchangeRate
            if (!BillStatsContribution.countsInStats(bill)) {
                excluded += kotlin.math.abs(cny)
                return@forEach
            }
            val c = BillStatsContribution.from(bill, cny)
            expense += c.expense
            income += c.income
            refund += c.refund
            if (bill.type == Bill.TYPE_TRANSFER || bill.type == Bill.TYPE_REPAYMENT) {
                transfer += kotlin.math.abs(cny)
            }
        }
        val balance = income - expense
        summaryAdapter.update(
            filtered.size, expense, income, balance, transfer, refund, excluded
        )
    }

    // ---------- 多选操作栏（沿用旧逻辑，作用于当前过滤结果） ----------

    private fun setupMultiSelectActions() {
        adapter.onSelectionChanged = { count ->
            if (adapter.isMultiSelectMode) MultiSelectMotion.animateIn(layoutMultiSelectActions)
            else MultiSelectMotion.animateOut(layoutMultiSelectActions)
            tvMsSummary.text = MultiSelectBarSummary.build(this, adapter.selectedBills.toList())
            btnMsDelete.text = getString(R.string.delete_label)
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.refreshButton(
                btnMsExcludeStats,
                adapter.selectedBills.toList()
            )
        }

        findViewById<View>(R.id.btn_ms_cancel).setOnClickListener {
            adapter.clearSelection()
        }
        findViewById<View>(R.id.btn_ms_select_all).setOnClickListener {
            val allCount = adapter.items.count {
                it is HomeAdapter.ListItem.Item &&
                    !it.displayBill.isDeprecated &&
                    adapter.isOwnedByLocal(it.displayBill.bill)
            }
            if (allCount > 0 && adapter.selectedBills.size >= allCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        findViewById<View>(R.id.btn_ms_move_book).setOnClickListener {
            val billsToMove = adapter.getSelectedBills()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsExcludeStats.setOnClickListener {
            val bills = adapter.getSelectedBills()
            if (bills.isEmpty()) return@setOnClickListener
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.apply(
                context = this,
                scope = lifecycleScope,
                bills = bills
            ) { exclude ->
                val ids = bills.map { it.id }.toSet()
                allBills = allBills.map { if (it.id in ids) it.copy(excludeFromStats = exclude) else it }
                // 保留多选模式，只清空勾选，方便连续处理多批。
                adapter.deselectAll()
                applyFilter(currentKeyword.trim())
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = adapter.getSelectedBills()
            if (billsToDelete.isEmpty()) return@setOnClickListener
            val targetIds = billsToDelete.map { it.id }
            BillRowAnimator.deleteThen(rvResults, targetIds) {
                lifecycleScope.launch {
                    val rows = BillDeleteUndo.delete(db, billsToDelete) ?: return@launch
                    val deletedIds = billsToDelete.map { it.id }.toSet()
                    allBills = allBills.filterNot { it.id in deletedIds }
                    // 不退出多选，只清空勾选，方便连续删多批。
                    adapter.deselectAll()
                    applyFilter(currentKeyword.trim())
                    BillUndoController.showNow(
                        findViewById(android.R.id.content),
                        getString(R.string.deleted_bills_fmt, billsToDelete.size),
                        rows
                    ) {
                        loadAllBills()
                        BillRowAnimator.expandAfterRefresh(rvResults, rows.map { it.originalBillId })
                    }
                }
            }
        }
    }

    private fun showMoveToBookDialog(bills: List<Bill>) {
        val scope = viewScope
        if (scope == null) {
            Toast.makeText(this, "账本列表尚未加载，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = BillMoveTargetResolver.resolve(
            viewScope = scope,
            selectedBillBookNames = bills.map { it.bookName }
        )
        BillMoveDialog.show(
            activity = this,
            bills = bills,
            targets = targets
        ) { targetBook ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    com.taostudio.tapaccounting.data.sync.SharedMutationHooks.moveBills(db, bills, targetBook)
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillSearchActivity, error.message ?: "移动失败", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    adapter.clearSelection()
                    loadAllBills()
                    Toast.makeText(
                        this@BillSearchActivity,
                        "已将 ${bills.size} 条账单移动到「$targetBook」",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
