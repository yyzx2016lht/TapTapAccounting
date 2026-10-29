package com.taostudio.tapaccounting.ui.main.home

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * 对标钱迹搜索页的账单搜索：顶部三下拉（时间/账本/筛选）+ 关键词框 +
 * 搜索汇总（笔数/排序/六宫格）+ 按天/按月/不分组的日期分组列表。
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

    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvFilterTime: TextView
    private lateinit var tvFilterBook: TextView
    private lateinit var tvFilterMore: TextView
    private lateinit var ivFilterTimeArrow: ImageView
    private lateinit var ivFilterBookArrow: ImageView
    private lateinit var ivFilterMoreArrow: ImageView
    private lateinit var panelContainer: FrameLayout
    private lateinit var tvCount: TextView
    private lateinit var btnSort: ImageView
    private lateinit var tvSumExpense: TextView
    private lateinit var tvSumIncome: TextView
    private lateinit var tvSumBalance: TextView
    private lateinit var tvSumTransfer: TextView
    private lateinit var tvSumRefund: TextView
    private lateinit var tvSumExcluded: TextView
    private lateinit var cellTransfer: LinearLayout
    private lateinit var cellRefund: LinearLayout
    private lateinit var cellExcluded: LinearLayout
    private lateinit var layoutMultiSelectActions: View
    private lateinit var tvMsSummary: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var btnMsExcludeStats: TextView

    private var panelBooks: View? = null
    private var openPanel: Int = PANEL_NONE

    // 筛选状态（确定后才生效的用 draft 暂存，见各面板）。
    private var groupMode = HomeAdapter.GroupMode.DAY
    private var datePreset = PRESET_ALL
    private var customStart: Long? = null
    private var customEnd: Long? = null
    private var bookFilter: Set<String>? = null
    private var typeFilter: Set<Int> = emptySet()
    private var accountFilter: Set<String> = emptySet()
    private var categoryFilter: Set<String> = emptySet()
    private var minAmount: Double? = null
    private var maxAmount: Double? = null
    private var sortDesc = true

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = HomeAdapter().apply { showChart = false }
    private var allBills: List<Bill> = emptyList()
    private var scopeBookNames: List<String> = emptyList()
    private var searchJob: Job? = null
    private var hasResumedOnce = false
    private var viewScope: ResolvedLedgerViewScope? = null
    private var currentKeyword: String = ""

    private val sourceBookName by lazy {
        BookAccountManager.normalizeBookName(
            intent.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().ifBlank { BookAccountManager.ALL_BOOK }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_search)

        etSearch = findViewById(R.id.et_bill_search)
        btnClear = findViewById(R.id.btn_search_clear)
        rvResults = findViewById(R.id.rv_bill_search_results)
        tvEmpty = findViewById(R.id.tv_bill_search_empty)
        tvFilterTime = findViewById(R.id.tv_filter_time)
        tvFilterBook = findViewById(R.id.tv_filter_book)
        tvFilterMore = findViewById(R.id.tv_filter_more)
        ivFilterTimeArrow = findViewById(R.id.iv_filter_time_arrow)
        ivFilterBookArrow = findViewById(R.id.iv_filter_book_arrow)
        ivFilterMoreArrow = findViewById(R.id.iv_filter_more_arrow)
        panelContainer = findViewById(R.id.layout_search_panel_container)
        tvCount = findViewById(R.id.tv_search_count)
        btnSort = findViewById(R.id.btn_search_sort)
        tvSumExpense = findViewById(R.id.tv_sum_expense)
        tvSumIncome = findViewById(R.id.tv_sum_income)
        tvSumBalance = findViewById(R.id.tv_sum_balance)
        tvSumTransfer = findViewById(R.id.tv_sum_transfer)
        tvSumRefund = findViewById(R.id.tv_sum_refund)
        tvSumExcluded = findViewById(R.id.tv_sum_excluded)
        cellTransfer = findViewById(R.id.cell_sum_transfer)
        cellRefund = findViewById(R.id.cell_sum_refund)
        cellExcluded = findViewById(R.id.cell_sum_excluded)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        tvMsSummary = findViewById(R.id.tv_ms_summary)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        btnMsExcludeStats = findViewById(R.id.btn_ms_exclude_stats)

        findViewById<View>(R.id.btn_bill_search_back).setOnClickListener { handleBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            handleBackPressed()
        }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter
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
        btnSort.setOnClickListener {
            sortDesc = !sortDesc
            // 漏斗图标不做旋转：升序时染品牌色，降序恢复次要色。
            ImageViewCompat.setImageTintList(
                btnSort,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (sortDesc) R.color.text_secondary else R.color.brand_primary
                    )
                )
            )
            applyFilter(currentKeyword)
        }

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

        refreshFilterLabels()
        loadAllBills()

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

    private fun handleBackPressed() {
        if (openPanel != PANEL_NONE) {
            closePanel()
            return
        }
        if (adapter.isMultiSelectMode) {
            adapter.clearSelection()
            return
        }
        // 键盘展开时先收键盘（对标钱迹），再按一次才退出。
        if (etSearch.hasFocus()) {
            etSearch.clearFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
            return
        }
        finish()
    }

    // ---------- 下拉面板 ----------

    private fun togglePanel(panel: Int) {
        if (openPanel == panel) {
            closePanel()
            return
        }
        // 面板是选择操作：先收键盘，避免挡住面板底部的确定按钮。
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
        openPanel = panel
        panelContainer.removeAllViews()
        when (panel) {
            PANEL_TIME -> panelContainer.addView(getPanelTime())
            PANEL_BOOKS -> panelContainer.addView(getPanelBooks())
            PANEL_FILTER -> panelContainer.addView(getPanelFilter())
        }
        panelContainer.visibility = View.VISIBLE
        refreshFilterLabels()
    }

    private fun closePanel() {
        openPanel = PANEL_NONE
        panelContainer.visibility = View.GONE
        panelContainer.removeAllViews()
        refreshFilterLabels()
    }

    private fun refreshFilterLabels() {
        val timeLabel = when (datePreset) {
            PRESET_MONTH -> getString(R.string.search_preset_month)
            PRESET_LAST_MONTH -> getString(R.string.search_preset_last_month)
            PRESET_YEAR -> getString(R.string.search_preset_year)
            PRESET_LAST_YEAR -> getString(R.string.search_preset_last_year)
            PRESET_CUSTOM -> getString(R.string.search_custom)
            else -> getString(R.string.search_time_all)
        }
        tvFilterTime.text = timeLabel
        setFilterItemState(
            tvFilterTime, ivFilterTimeArrow,
            highlighted = false, open = openPanel == PANEL_TIME
        )
        val bookLabel = when {
            bookFilter == null -> sourceBookName
            bookFilter!!.size == 1 -> bookFilter!!.first()
            else -> getString(R.string.search_books_multi_fmt, bookFilter!!.size)
        }
        tvFilterBook.text = bookLabel
        setFilterItemState(
            tvFilterBook, ivFilterBookArrow,
            highlighted = false, open = openPanel == PANEL_BOOKS
        )
        val hasAdvanceFilter = typeFilter.isNotEmpty() || accountFilter.isNotEmpty() ||
            categoryFilter.isNotEmpty() || minAmount != null || maxAmount != null
        tvFilterMore.text = getString(R.string.search_filter)
        setFilterItemState(
            tvFilterMore, ivFilterMoreArrow,
            highlighted = hasAdvanceFilter, open = openPanel == PANEL_FILTER
        )
    }

    /** 下拉项视觉：展开或有筛选项时文字/箭头染品牌色，箭头上翻，其余保持默认。 */
    private fun setFilterItemState(
        label: TextView,
        arrow: ImageView,
        highlighted: Boolean,
        open: Boolean
    ) {
        val emphasized = highlighted || open
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
            // 轻量小药丸：无边框、无对勾，靠底色区分选中（对标钱迹筛选）。
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics
            ).toInt()
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

    // ----- 时间面板：分组 + 日期范围（点选即生效） -----

    private fun getPanelTime(): View {
        val v = LayoutInflater.from(this)
            .inflate(R.layout.panel_bill_search_time, panelContainer, false)
        val cgGroup: ChipGroup = v.findViewById(R.id.cg_search_group)
        val cgDate: ChipGroup = v.findViewById(R.id.cg_search_date)
        val btnStart: TextView = v.findViewById(R.id.btn_search_start_date)
        val btnEnd: TextView = v.findViewById(R.id.btn_search_end_date)

        val modes = listOf(
            HomeAdapter.GroupMode.DAY to getString(R.string.search_group_day),
            HomeAdapter.GroupMode.MONTH to getString(R.string.search_group_month),
            HomeAdapter.GroupMode.NONE to getString(R.string.search_group_none)
        )
        val modeId = mutableMapOf<Int, HomeAdapter.GroupMode>()
        modes.forEach { (mode, label) ->
            val chip = makeChip(label, mode == groupMode) {}
            chip.id = View.generateViewId()
            modeId[chip.id] = mode
            cgGroup.addView(chip)
        }
        cgGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val mode = checkedIds.firstOrNull()?.let(modeId::get) ?: return@setOnCheckedStateChangeListener
            if (mode != groupMode) {
                groupMode = mode
                adapter.groupMode = mode
                applyFilter(currentKeyword.trim())
            }
            // selectionRequired 已在 XML 约束单选；防呆。
            if (checkedIds.isEmpty()) group.check(modeId.entries.first { it.value == groupMode }.key)
        }

        val presets = listOf(
            PRESET_ALL to getString(R.string.search_time_all),
            PRESET_MONTH to getString(R.string.search_preset_month),
            PRESET_LAST_MONTH to getString(R.string.search_preset_last_month),
            PRESET_YEAR to getString(R.string.search_preset_year),
            PRESET_LAST_YEAR to getString(R.string.search_preset_last_year)
        )
        val presetId = mutableMapOf<Int, Int>()
        presets.forEach { (preset, label) ->
            val chip = makeChip(label, preset == datePreset && datePreset != PRESET_CUSTOM) {}
            chip.id = View.generateViewId()
            presetId[chip.id] = preset
            cgDate.addView(chip)
        }
        attachDatePresetListener(cgDate, presetId, btnStart, btnEnd)

        btnStart.setOnClickListener { pickCustomDate(true, btnStart, btnEnd, cgDate, presetId) }
        btnEnd.setOnClickListener { pickCustomDate(false, btnStart, btnEnd, cgDate, presetId) }
        refreshCustomDateButtons(btnStart, btnEnd)
        return v
    }

    private fun attachDatePresetListener(
        cgDate: ChipGroup,
        presetId: Map<Int, Int>,
        btnStart: TextView,
        btnEnd: TextView
    ) {
        cgDate.setOnCheckedStateChangeListener { _, checkedIds ->
            val preset = checkedIds.firstOrNull()?.let(presetId::get) ?: return@setOnCheckedStateChangeListener
            if (preset != datePreset || customStart != null || customEnd != null) {
                datePreset = preset
                customStart = null
                customEnd = null
                refreshCustomDateButtons(btnStart, btnEnd)
                refreshFilterLabels()
                applyFilter(currentKeyword.trim())
            }
        }
    }

    private fun refreshCustomDateButtons(btnStart: TextView, btnEnd: TextView) {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        btnStart.text = customStart?.let { df.format(Date(it)) } ?: getString(R.string.search_start_date)
        btnEnd.text = customEnd?.let { df.format(Date(it)) } ?: getString(R.string.search_end_date)
    }

    private fun pickCustomDate(
        isStart: Boolean,
        btnStart: TextView,
        btnEnd: TextView,
        cgDate: ChipGroup,
        presetId: Map<Int, Int>
    ) {
        val cal = Calendar.getInstance()
        val base = if (isStart) customStart else customEnd
        if (base != null) cal.timeInMillis = base
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val picked = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (isStart) {
                    customStart = picked.timeInMillis
                    if (customEnd != null && customEnd!! < customStart!!) {
                        customEnd = customStart!! + 24 * 60 * 60 * 1000L - 1
                    }
                } else {
                    customEnd = picked.timeInMillis + 24 * 60 * 60 * 1000L - 1
                    if (customStart != null && customStart!! > customEnd!!) {
                        customStart = picked.timeInMillis
                    }
                }
                datePreset = PRESET_CUSTOM
                refreshCustomDateButtons(btnStart, btnEnd)
                refreshFilterLabels()
                applyFilter(currentKeyword.trim())
                // 自定义日期是独立态：清掉预设单选组的高亮并重挂监听。
                cgDate.setOnCheckedStateChangeListener(null)
                cgDate.clearCheck()
                attachDatePresetListener(cgDate, presetId, btnStart, btnEnd)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
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

    // ----- 账本面板：多选 + 全选/确定 -----

    private fun getPanelBooks(): View {
        val v = LayoutInflater.from(this)
            .inflate(R.layout.panel_bill_search_books, panelContainer, false)
        val rv: RecyclerView = v.findViewById(R.id.rv_search_books)
        rv.layoutManager = LinearLayoutManager(this)
        val draft = (bookFilter ?: scopeBookNames.toSet()).toMutableSet()
        val booksAdapter = BooksAdapter(scopeBookNames, draft)
        rv.adapter = booksAdapter
        v.findViewById<View>(R.id.btn_search_books_all).setOnClickListener {
            draft.clear()
            draft.addAll(scopeBookNames)
            booksAdapter.notifyDataSetChanged()
        }
        v.findViewById<View>(R.id.btn_search_books_ok).setOnClickListener {
            bookFilter = if (draft.size == scopeBookNames.size) null else draft.toSet()
            // 全选等价于跟随当前范围，避免误过滤。
            if (bookFilter != null && bookFilter!!.containsAll(scopeBookNames)) bookFilter = null
            refreshFilterLabels()
            applyFilter(currentKeyword.trim())
            closePanel()
        }
        panelBooks = v
        return v
    }

    private inner class BooksAdapter(
        private val books: List<String>,
        private val draft: MutableSet<String>
    ) : RecyclerView.Adapter<BooksAdapter.VH>() {
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val cb: CheckBox = itemView.findViewById(R.id.cb_search_book)
            val tv: TextView = itemView.findViewById(R.id.tv_search_book_name)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_book, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = books.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val name = books[position]
            holder.tv.text = name
            holder.cb.isChecked = draft.contains(name)
            holder.itemView.setOnClickListener {
                if (draft.contains(name)) draft.remove(name) else draft.add(name)
                // 至少保留一个账本，避免空结果误导。
                if (draft.isEmpty()) {
                    draft.add(name)
                    Toast.makeText(
                        this@BillSearchActivity, "至少保留一个账本", Toast.LENGTH_SHORT
                    ).show()
                }
                notifyItemChanged(position)
            }
        }
    }

    // ----- 筛选面板：类型 chips + 资产/分类弹窗 + 金额（确定后生效） -----

    private var draftTypes: MutableSet<Int> = mutableSetOf()
    private var draftAccounts: MutableSet<String> = mutableSetOf()
    private var draftCategories: MutableSet<String> = mutableSetOf()

    private fun getPanelFilter(): View {
        val v = LayoutInflater.from(this)
            .inflate(R.layout.panel_bill_search_filter, panelContainer, false)
        draftTypes = typeFilter.toMutableSet()
        draftAccounts = accountFilter.toMutableSet()
        draftCategories = categoryFilter.toMutableSet()

        val cgTypes: ChipGroup = v.findViewById(R.id.cg_search_types)
        val tvAssetValue: TextView = v.findViewById(R.id.tv_search_asset_value)
        val tvCategoryValue: TextView = v.findViewById(R.id.tv_search_category_value)
        val etMin: EditText = v.findViewById(R.id.et_search_min_amount)
        val etMax: EditText = v.findViewById(R.id.et_search_max_amount)
        etMin.setText(minAmount?.let { trimAmount(it) } ?: "")
        etMax.setText(maxAmount?.let { trimAmount(it) } ?: "")

        buildTypeChips(cgTypes)
        refreshDrillRowValues(tvAssetValue, tvCategoryValue)

        v.findViewById<View>(R.id.row_search_asset).setOnClickListener {
            openAssetPicker { refreshDrillRowValues(tvAssetValue, tvCategoryValue) }
        }
        v.findViewById<View>(R.id.row_search_category).setOnClickListener {
            openCategoryPicker { refreshDrillRowValues(tvAssetValue, tvCategoryValue) }
        }

        v.findViewById<View>(R.id.btn_search_filter_reset).setOnClickListener {
            draftTypes.clear()
            draftAccounts.clear()
            draftCategories.clear()
            etMin.setText("")
            etMax.setText("")
            buildTypeChips(cgTypes)
            refreshDrillRowValues(tvAssetValue, tvCategoryValue)
        }
        v.findViewById<View>(R.id.btn_search_filter_ok).setOnClickListener {
            typeFilter = draftTypes.toSet()
            accountFilter = draftAccounts.toSet()
            categoryFilter = draftCategories.toSet()
            minAmount = etMin.text.toString().trim().toDoubleOrNull()
            maxAmount = etMax.text.toString().trim().toDoubleOrNull()
            refreshFilterLabels()
            applyFilter(currentKeyword.trim())
            closePanel()
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

    private fun buildTypeChips(cg: ChipGroup) {
        cg.removeAllViews()
        cg.addView(makeChip(getString(R.string.search_type_all), draftTypes.isEmpty()) {
            if (it) {
                draftTypes.clear()
                buildTypeChips(cg)
            } else if (draftTypes.isEmpty()) {
                // 全部是默认态：不允许取消，未选项。
                buildTypeChips(cg)
            }
        })
        listOf(
            Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER,
            Bill.TYPE_REPAYMENT, TYPE_FILTER_REFUND
        ).forEach { t ->
            cg.addView(makeChip(typeLabel(t), draftTypes.contains(t)) { checked ->
                if (checked) draftTypes.add(t) else draftTypes.remove(t)
                buildTypeChips(cg)
            })
        }
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
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                val scope = LedgerViewScopeStore.resolve(applicationContext, db, sourceBookName)
                scope to db.billDao().getAllBillsList()
            }
            viewScope = bills.first
            val scope = bills.first
            adapter.setViewContext(
                contextsByBookName = scope.memberContextsByBookName,
                showMembers = scope.scope.members == LedgerMemberScope.EVERYONE,
                showBookNames = false
            )
            adapter.detailSuffixProvider = if (scope.isAggregate) {
                { bill -> "账本：${BookAccountManager.normalizeBookName(bill.bookName)}" }
            } else null
            allBills = bills.second
                .asSequence()
                .filter(scope::includes)
                .toList()
            scopeBookNames = allBills
                .map { BookAccountManager.normalizeBookName(it.bookName) }
                .distinct()
                .sorted()
            if (bookFilter != null) {
                bookFilter = bookFilter!!.intersect(scopeBookNames.toSet())
                if (bookFilter!!.isEmpty()) bookFilter = null
            }
            refreshFilterLabels()
            applyFilter(etSearch.text?.toString().orEmpty().trim())
        }
    }

    private fun applyFilter(keywordRaw: String) {
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
            // 类型
            if (typeFilter.isNotEmpty() && !matchType(bill, typeFilter)) return@filter false
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
        val empty = filtered.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun matchType(bill: Bill, types: Set<Int>): Boolean {
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        if (isRefund) return TYPE_FILTER_REFUND in types
        return when (bill.type) {
            Bill.TYPE_EXPENSE -> Bill.TYPE_EXPENSE in types
            Bill.TYPE_INCOME -> Bill.TYPE_INCOME in types
            Bill.TYPE_TRANSFER -> Bill.TYPE_TRANSFER in types
            Bill.TYPE_REPAYMENT -> Bill.TYPE_REPAYMENT in types
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
        tvCount.text = getString(R.string.search_bills_count_fmt, filtered.size)
        tvSumExpense.text = formatSummaryAmount(expense)
        tvSumIncome.text = formatSummaryAmount(income)
        tvSumBalance.text = formatSummaryAmount(balance)
        tvSumTransfer.text = formatSummaryAmount(transfer)
        tvSumRefund.text = formatSummaryAmount(refund)
        tvSumExcluded.text = formatSummaryAmount(excluded)
        // 钱迹同款：为 0 的格子直接隐藏。
        cellTransfer.visibility = if (transfer > 0.005) View.VISIBLE else View.GONE
        cellRefund.visibility = if (refund > 0.005) View.VISIBLE else View.GONE
        cellExcluded.visibility = if (excluded > 0.005) View.VISIBLE else View.GONE
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
                applyFilter(etSearch.text?.toString().orEmpty().trim())
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = adapter.getSelectedBills()
            if (billsToDelete.isEmpty()) return@setOnClickListener
            val targetIds = billsToDelete.map { it.id }
            BillRowAnimator.collapseThen(BillRowAnimator.findRows(rvResults, targetIds)) {
                lifecycleScope.launch {
                    val rows = BillDeleteUndo.delete(db, billsToDelete) ?: return@launch
                    val deletedIds = billsToDelete.map { it.id }.toSet()
                    allBills = allBills.filterNot { it.id in deletedIds }
                    adapter.clearSelection()
                    applyFilter(etSearch.text?.toString().orEmpty().trim())
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
