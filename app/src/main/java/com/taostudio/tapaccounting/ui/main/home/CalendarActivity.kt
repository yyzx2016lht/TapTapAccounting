package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BillColors
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.icu.util.ChineseCalendar
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.bumptech.glide.Glide
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.view.CalendarView
import com.kizitonwose.calendar.view.DaySize
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.logic.BillStatsContribution
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.main.SharedYearMonthSession
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetActions
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetController
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetRequest
import com.taostudio.tapaccounting.ui.common.BillDetailStatsToggle
import com.taostudio.tapaccounting.ui.common.MultiSelectBarSummary
import com.taostudio.tapaccounting.ui.common.MultiSelectMotion
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScopeStore
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.max

class CalendarActivity : AppCompatActivity() {

    private lateinit var tvMonthSelector: TextView
    private lateinit var rvCalendar: CalendarView
    private lateinit var rvDailyBills: RecyclerView
    private lateinit var tvMonthSummary: TextView
    private lateinit var tvDailyDate: TextView
    private lateinit var tvDailySummary: TextView
    private lateinit var tvDailyEmpty: TextView
    private lateinit var btnMore: ImageView

    private lateinit var tvW1: TextView
    private lateinit var tvW2: TextView
    private lateinit var tvW3: TextView
    private lateinit var tvW4: TextView
    private lateinit var tvW5: TextView
    private lateinit var tvW6: TextView
    private lateinit var tvW7: TextView

    private lateinit var btnModeBoth: TextView
    private lateinit var btnModeBalance: TextView
    private lateinit var btnModeIncome: TextView
    private lateinit var btnModeExpense: TextView
    private lateinit var modesContainer: LinearLayout

    // Multi-select UI elements
    private lateinit var layoutMultiSelectActions: View
    private lateinit var tvMsSummary: TextView
    private lateinit var btnMsCancel: TextView
    private lateinit var btnMsSelectAll: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var btnMsExcludeStats: TextView
    private var isMultiSelectModeActive = false

    private var selectedYear: Int = 2025
    private var selectedMonth: Int = 12
    private var selectedDay: Int = 1
    private var selectedBookName: String = BookAccountManager.DEFAULT_BOOK
    private var selectedViewScope: ResolvedLedgerViewScope? = null
    private var selectedScopeRevision: Long = -1L

    private val dfChartKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var firstDayOfWeekOption = Calendar.MONDAY
    private var currentMode = MODE_BOTH // 0=Both, 1=Balance, 2=Income, 3=Expense

    companion object {
        const val MODE_BOTH = 0
        const val MODE_BALANCE = 1
        const val MODE_INCOME = 2
        const val MODE_EXPENSE = 3
    }

    private var currentMonthBills = listOf<Bill>()
    private val dailySummaryMap = mutableMapOf<String, DailySummary>()
    private val incomeLevelMap = mutableMapOf<String, Int>()
    private val expenseLevelMap = mutableMapOf<String, Int>()
    private val balanceLevelMap = mutableMapOf<String, Int>()

    private val solarFestivals = mapOf(
        Pair(1, 1) to "元旦",
        Pair(2, 14) to "情人节",
        Pair(3, 8) to "妇女节",
        Pair(3, 12) to "植树节",
        Pair(5, 1) to "劳动节",
        Pair(6, 1) to "儿童节",
        Pair(10, 1) to "国庆",
        Pair(12, 25) to "圣诞节"
    )

    private val lunarFestivals = mapOf(
        Pair(1, 1) to "春节",
        Pair(1, 15) to "元宵",
        Pair(5, 5) to "端午",
        Pair(7, 7) to "七夕",
        Pair(8, 15) to "中秋",
        Pair(9, 9) to "重阳",
        Pair(12, 8) to "腊八",
        Pair(12, 23) to "小年"
    )

    data class DailySummary(var income: Double = 0.0, var expense: Double = 0.0)

    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var dailyAdapter: HomeAdapter
    private var monthLoadJob: Job? = null
    private var hasResumedOnce = false
    private lateinit var calendarStartMonth: YearMonth
    private lateinit var calendarEndMonth: YearMonth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        val prefs = getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        firstDayOfWeekOption = prefs.getInt("first_day_of_week", Calendar.MONDAY)

        val sessionYearMonth = SharedYearMonthSession.getYearMonth()
        selectedYear = intent.getIntExtra("YEAR", sessionYearMonth.first)
        selectedMonth = intent.getIntExtra("MONTH", sessionYearMonth.second)
        selectedDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        selectedBookName = BookAccountManager.normalizeBookName(
            intent.getStringExtra("BOOK_NAME").orEmpty().ifBlank {
                BookAccountManager.getSelectedBook(this)
            }
        )

        initViews()
        updateWeekdaysHeader()
        setupCalendar()
        setupDailyList()
        setupListeners()
        setupMultiSelectActions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isMultiSelectModeActive) {
                    dailyAdapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressed()
                }
            }
        })

        updateMonthText()
        loadDataForMonth()
    }

    override fun onResume() {
        super.onResume()
        val latestBook = BookAccountManager.normalizeBookName(BookAccountManager.getSelectedBook(this))
        val bookChanged = latestBook != selectedBookName
        val scopeChanged = LedgerViewScopeStore.revision(this) != selectedScopeRevision
        if (bookChanged) {
            selectedBookName = latestBook
        }
        if (hasResumedOnce || bookChanged || scopeChanged) {
            // 独立详情页编辑/删除后返回时刷新当前月份。
            loadDataForMonth()
        }
        hasResumedOnce = true
        BillUndoController.consumePending(findViewById(android.R.id.content)) {
            loadDataForMonth()
        }
    }

    private fun initViews() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        tvMonthSelector = findViewById(R.id.tvMonthSelector)
        btnMore = findViewById(R.id.btnMore)
        rvCalendar = findViewById(R.id.rvCalendar)
        rvDailyBills = findViewById(R.id.rvDailyBills)
        tvMonthSummary = findViewById(R.id.tvMonthSummary)
        tvDailyDate = findViewById(R.id.tvDailyDate)
        tvDailySummary = findViewById(R.id.tvDailySummary)
        tvDailyEmpty = findViewById(R.id.tvDailyEmpty)

        tvW1 = findViewById(R.id.tvW1)
        tvW2 = findViewById(R.id.tvW2)
        tvW3 = findViewById(R.id.tvW3)
        tvW4 = findViewById(R.id.tvW4)
        tvW5 = findViewById(R.id.tvW5)
        tvW6 = findViewById(R.id.tvW6)
        tvW7 = findViewById(R.id.tvW7)

        btnModeBoth = findViewById(R.id.btnModeBoth)
        btnModeBalance = findViewById(R.id.btnModeBalance)
        btnModeIncome = findViewById(R.id.btnModeIncome)
        btnModeExpense = findViewById(R.id.btnModeExpense)
        modesContainer = findViewById(R.id.modesContainer)

        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        tvMsSummary = findViewById(R.id.tv_ms_summary)
        btnMsCancel = findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = findViewById(R.id.btn_ms_select_all)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
        btnMsExcludeStats = findViewById(R.id.btn_ms_exclude_stats)
    }

    private fun updateWeekdaysHeader() {
        val weekdays = when(firstDayOfWeekOption) {
            Calendar.SUNDAY -> listOf("日", "一", "二", "三", "四", "五", "六")
            Calendar.SATURDAY -> listOf("六", "日", "一", "二", "三", "四", "五")
            else -> listOf("一", "二", "三", "四", "五", "六", "日")
        }
        tvW1.text = weekdays[0]
        tvW2.text = weekdays[1]
        tvW3.text = weekdays[2]
        tvW4.text = weekdays[3]
        tvW5.text = weekdays[4]
        tvW6.text = weekdays[5]
        tvW7.text = weekdays[6]
    }

    private fun updateMonthText() {
        tvMonthSelector.text = "${selectedYear}.${String.format(Locale.getDefault(), "%02d", selectedMonth)}"
    }

    private fun setupListeners() {
        tvMonthSelector.setOnClickListener { showUnifiedMonthYearPicker() }

        btnMore.setOnClickListener { showFirstDayOfWeekDialog() }

        btnModeBoth.setOnClickListener { setDisplayMode(MODE_BOTH) }
        btnModeBalance.setOnClickListener { setDisplayMode(MODE_BALANCE) }
        btnModeIncome.setOnClickListener { setDisplayMode(MODE_INCOME) }
        btnModeExpense.setOnClickListener { setDisplayMode(MODE_EXPENSE) }
    }

    private fun moveMonth(delta: Int) {
        rvCalendar.smoothScrollToMonth(YearMonth.of(selectedYear, selectedMonth).plusMonths(delta.toLong()))
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener {
            dailyAdapter.clearSelection()
        }
        btnMsSelectAll.setOnClickListener {
            val allItems = dailyAdapter.items.mapNotNull { if (it is HomeAdapter.ListItem.Item) it.displayBill.bill else null }.toSet()
            if (dailyAdapter.selectedBills.size == allItems.size) {
                dailyAdapter.deselectAll()
            } else {
                dailyAdapter.selectAll()
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = dailyAdapter.selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            val deletedIds = billsToDelete.map { it.id }
            BillRowAnimator.deleteThen(rvDailyBills, deletedIds) {
                val db = AppDatabase.getDatabase(this)
                lifecycleScope.launch {
                    val rows = BillDeleteUndo.delete(db, billsToDelete) ?: return@launch

                    dailyAdapter.deselectAll()
                    loadDataForMonth()
                    BillUndoController.showAfterSelectionExit(
                        anchor = findViewById(android.R.id.content),
                        message = getString(R.string.deleted_bills_fmt, billsToDelete.size),
                        rows = rows,
                        onRestored = {
                            BillRowAnimator.restoreThen(
                                rvDailyBills,
                                rows.map { it.originalBillId }
                            ) { loadDataForMonth() }
                        }
                    )
                }
            }
        }
        btnMsExcludeStats.setOnClickListener {
            val bills = dailyAdapter.selectedBills.toList()
            if (bills.isEmpty()) return@setOnClickListener
            com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.apply(
                context = this,
                scope = lifecycleScope,
                bills = bills
            ) {
                // 保留多选模式，只清空勾选，方便连续处理多批。
                dailyAdapter.deselectAll()
                loadDataForMonth()
            }
        }
    }

    private fun showFirstDayOfWeekDialog() {
        val options = arrayOf("星期一", "星期六", "星期日")
        val values = arrayOf(Calendar.MONDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val checkedItem = values.indexOf(firstDayOfWeekOption).takeIf { it >= 0 } ?: 0

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.first_day_of_week))
            .setSingleChoiceItems(options, checkedItem) { d, which ->
                firstDayOfWeekOption = values[which]
                getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("first_day_of_week", firstDayOfWeekOption)
                    .apply()
                updateWeekdaysHeader()
                setupCalendar()
                rvCalendar.scrollToDate(selectedLocalDate())
                rvCalendar.notifyCalendarChanged()
                d.dismiss()
            }
            .create()
        OverlayDialogs.showPageCenterDialog(dialog, this, widthRatio = 0.86f)
    }
    private fun setDisplayMode(mode: Int) {
        currentMode = mode

        for (i in 0 until modesContainer.childCount) {
            val child = modesContainer.getChildAt(i) as TextView
            child.setBackgroundResource(0)
            child.setTextColor(Color.parseColor("#666666"))
        }

        val selectedBtn = when(mode) {
            MODE_BOTH -> btnModeBoth
            MODE_BALANCE -> btnModeBalance
            MODE_INCOME -> btnModeIncome
            else -> btnModeExpense
        }
        selectedBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
        selectedBtn.setTextColor(Color.parseColor("#333333"))

        rvCalendar.notifyCalendarChanged()
    }

    private fun showMonthYearPicker() {
        val pickerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 50)
        }

        val npYear = NumberPicker(this).apply {
            minValue = 2000
            maxValue = 2050
            value = selectedYear
        }

        val npMonth = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            value = selectedMonth
            setFormatter { i -> String.format(Locale.getDefault(), "%02d", i) }
        }

        pickerLayout.addView(npYear)
        pickerLayout.addView(npMonth)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_month))
            .setView(pickerLayout)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                selectedYear = npYear.value
                selectedMonth = npMonth.value
                selectedDay = 1
                SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
                updateMonthText()
                loadDataForMonth()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog, this, widthRatio = 0.86f)
    }
    private fun setupCalendar() {
        calendarAdapter = CalendarAdapter()
        val currentMonth = YearMonth.of(selectedYear, selectedMonth)
        calendarStartMonth = currentMonth.minusYears(30)
        calendarEndMonth = currentMonth.plusYears(10)
        rvCalendar.apply {
            orientation = RecyclerView.HORIZONTAL
            scrollPaged = true
            outDateStyle = OutDateStyle.EndOfRow
            daySize = DaySize.SeventhWidth
            dayBinder = calendarAdapter
            setup(
                calendarStartMonth,
                calendarEndMonth,
                firstDayOfWeekOption.toDayOfWeek()
            )
            scrollToMonth(currentMonth)
            monthScrollListener = { month ->
                val visibleMonth = month.yearMonth
                syncCalendarHeightToMonth(visibleMonth, month.weekDays.count { it.isNotEmpty() })
                if (visibleMonth.year != selectedYear || visibleMonth.monthValue != selectedMonth) {
                    selectedYear = visibleMonth.year
                    selectedMonth = visibleMonth.monthValue
                    selectedDay = 1
                    SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
                    updateMonthText()
                    loadDataForMonth()
                }
            }
        }
    }

    private fun showUnifiedMonthYearPicker() {
        YearMonthPickerDialog.show(
            context = this,
            title = "选择月份",
            initialYear = selectedYear,
            initialMonth = selectedMonth
        ) { year, month ->
            val targetMonth = YearMonth.of(year, month)
            val currentMonth = YearMonth.of(selectedYear, selectedMonth)
            selectedDay = 1
            if (targetMonth == currentMonth) {
                SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
                updateMonthText()
                loadDataForMonth()
            } else {
                rvCalendar.smoothScrollToMonth(targetMonth)
            }
        }
    }

    private fun setupDailyList() {
        rvDailyBills.layoutManager = LinearLayoutManager(this)
        dailyAdapter = HomeAdapter()
        dailyAdapter.onBillItemClick = { bill ->
            showBillDetailSheet(bill)
        }
        dailyAdapter.onSelectionChanged = { count ->
            if (dailyAdapter.isMultiSelectMode) {
                isMultiSelectModeActive = true
                MultiSelectMotion.animateIn(layoutMultiSelectActions)
                tvMsSummary.text = MultiSelectBarSummary.build(this, dailyAdapter.selectedBills.toList())
                btnMsDelete.text = getString(R.string.delete_label)
                com.taostudio.tapaccounting.ui.common.BillStatsMultiSelect.refreshButton(
                    btnMsExcludeStats,
                    dailyAdapter.selectedBills.toList()
                )
            } else {
                isMultiSelectModeActive = false
                MultiSelectMotion.animateOut(layoutMultiSelectActions)
            }
        }
        rvDailyBills.adapter = dailyAdapter
    }

    private fun stripRefundPrefix(categoryName: String): String {
        return BillDisplayFormatter.stripRefundPrefix(categoryName)
    }

    private fun originalAmountOfExpenseBill(bill: Bill): Double {
        val base = if (bill.originalAmount > 0.0) bill.originalAmount else bill.amount
        return max(base, bill.amount)
    }

    private fun formatMoney(amount: Double, currency: String = "CNY"): String {
        val symbol = CurrencyManager.getSymbol(currency)
        return "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
    }

    private val billDetailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        BillDetailSheetController(this, lifecycleScope)
    }

    private fun showBillDetailSheet(bill: Bill) {
        billDetailSheetController.show(
            request = BillDetailSheetRequest(
                bill = bill,
                listRecyclerView = rvDailyBills
            ),
            actions = object : BillDetailSheetActions(
                context = this,
                scope = lifecycleScope,
                onDataChanged = { loadDataForMonth() },
                undoAnchor = { findViewById(android.R.id.content) }
            ) {
                override fun onRefund(bill: Bill) {
                    showRefundSheet(bill)
                }

                override fun onEditRefund(originalBill: Bill, refundBill: Bill) {
                    showRefundSheet(originalBill, refundBill)
                }

                override fun onLinkedBillClick(bill: Bill) {
                    showBillDetailSheet(bill)
                }
            }
        )
    }

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + resources.displayMetrics.density.times(24).toInt(),
                    (screenHeight * 0.88f).toInt()
                )
                bottomSheetView.layoutParams = bottomSheetView.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheetView.requestLayout()
                behavior.peekHeight = desiredHeight
            }
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_refund_bottom_sheet, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvOrigAmount = view.findViewById<TextView>(R.id.tv_orig_amount)
        val tvOrigCategory = view.findViewById<TextView>(R.id.tv_orig_category)

        val etRefundAmount = view.findViewById<EditText>(R.id.et_refund_amount)
        val layoutRefundAccount = view.findViewById<View>(R.id.layout_refund_account)
        val tvRefundAccount = view.findViewById<TextView>(R.id.tv_refund_account)
        val layoutRefundTime = view.findViewById<View>(R.id.layout_refund_time)
        val tvRefundTime = view.findViewById<TextView>(R.id.tv_refund_time)
        val etRefundRemark = view.findViewById<EditText>(R.id.et_refund_remark)
        val btnSaveRefund = view.findViewById<View>(R.id.btn_save_refund)
        val btnBack = view.findViewById<View>(R.id.btn_back)

        tvTitle.text = if (editingRefund == null) "退款" else "编辑退款"

        val sourceOriginalAmount = originalAmountOfExpenseBill(originalBill)
        tvOrigAmount.text = formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = BillDisplayFormatter.formatCategoryByPreference(originalBill.categoryName, true).ifEmpty { "未分类" }

        val defaultRefundAmount = editingRefund?.amount ?: originalBill.amount
        etRefundAmount.setText(String.format(Locale.getDefault(), "%.2f", defaultRefundAmount))

        var selectedAccount = editingRefund?.accountName ?: originalBill.accountName
        tvRefundAccount.text = selectedAccount

        var selectedTimeStr = if (editingRefund == null) {
            dfDetailTime.format(Date())
        } else {
            dfDetailTime.format(Date(editingRefund.time))
        }
        tvRefundTime.text = selectedTimeStr

        if (editingRefund != null) {
            etRefundRemark.setText(editingRefund.remark)
        }

        btnBack?.setOnClickListener {
            bottomSheet.cancel()
        }

        bottomSheet.setOnCancelListener {
            showBillDetailSheet(editingRefund ?: originalBill)
        }

        layoutRefundAccount.setOnClickListener {
            OverlayDialogs.showGridAssetPicker(this, tvRefundAccount.text.toString(), "选择退款入账账户", allowClear = false) { account ->
                selectedAccount = account
                tvRefundAccount.text = account
            }
        }

        layoutRefundTime.setOnClickListener {
            val initialTimeMillis = try {
                dfDetailTime.parse(selectedTimeStr)?.time
            } catch (_: Exception) {
                null
            }
            OverlayDialogs.showCustomTimePicker(this, initialTimeMillis = initialTimeMillis) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val amountStr = etRefundAmount.text.toString()
            val refundAmount = amountStr.toDoubleOrNull() ?: 0.0

            if (refundAmount <= 0) {
                Toast.makeText(this, "请输入有效的退款金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "选择账户") {
                Toast.makeText(this, "请选择入账账户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "退款：${stripRefundPrefix(originalBill.categoryName)}"
            }

            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@CalendarActivity)
                val account = db.assetDao().getAssetByName(selectedAccount)
                val refundBill = Bill(
                    id = editingRefund?.id ?: 0,
                    amount = refundAmount,
                    originalAmount = refundAmount,
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_REFUND,
                    accountId = account?.id ?: editingRefund?.accountId,
                    accountName = selectedAccount,
                    categoryName = originalBill.categoryName,
                    time = refundTimeLong,
                    remark = finalRemark,
                    currency = originalBill.currency
                )

                try {
                    com.taostudio.tapaccounting.logic.BillMutationService.saveRefundBill(
                        db = db,
                        originalBill = originalBill,
                        refundBill = refundBill,
                        previousRefundBill = editingRefund
                    )
                } catch (_: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CalendarActivity, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CalendarActivity, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CalendarActivity, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                    loadDataForMonth()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }

    private fun loadDataForMonth() {
        val db = AppDatabase.getDatabase(this)

        val calStart = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val calEnd = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        monthLoadJob?.cancel()
        monthLoadJob = lifecycleScope.launch {
            val scope = withContext(Dispatchers.IO) {
                LedgerViewScopeStore.resolve(applicationContext, db, selectedBookName)
            }
            selectedViewScope = scope
            selectedScopeRevision = LedgerViewScopeStore.revision(this@CalendarActivity)
            selectedBookName = scope.legacyBookName
            dailyAdapter.setViewContext(
                contextsByBookName = scope.memberContextsByBookName,
                showMembers = scope.scope.members == LedgerMemberScope.EVERYONE,
                showBookNames = false
            )
            dailyAdapter.detailSuffixProvider = if (scope.isAggregate) {
                { bill -> "账本：${BookAccountManager.normalizeBookName(bill.bookName)}" }
            } else null
            db.billDao().getBillsBetweenTimes(calStart.timeInMillis, calEnd.timeInMillis).collectLatest { bills ->
                currentMonthBills = bills
                    .filter(scope::includes)
                    .sortedByDescending { it.time }
                processMonthData()
                updateSelectedDayDetails()
            }
        }
    }

    private fun processMonthData() {
        dailySummaryMap.clear()
        incomeLevelMap.clear()
        expenseLevelMap.clear()
        balanceLevelMap.clear()
        var totalIncome = 0.0
        var totalExpense = 0.0

        for (bill in currentMonthBills) {
            val dateKey = dfChartKey.format(Date(bill.time))
            val summary = dailySummaryMap.getOrPut(dateKey) { DailySummary() }
            val convertedAmount = if (bill.currency.equals("CNY", ignoreCase = true)) {
                bill.amount
            } else {
                bill.amount * bill.exchangeRate
            }
            val contribution = BillStatsContribution.from(bill, convertedAmount)
            summary.expense += contribution.expense
            totalExpense += contribution.expense
            summary.income += contribution.income
            totalIncome += contribution.income
        }

        tvMonthSummary.text = "月收入￥${String.format(Locale.getDefault(), "%,.2f", totalIncome)}, 月支出￥${String.format(Locale.getDefault(), "%,.2f", totalExpense)}, 月结余￥${String.format(Locale.getDefault(), "%,.2f", totalIncome - totalExpense)}"
        buildLevelMapForMode(MODE_INCOME, incomeLevelMap)
        buildLevelMapForMode(MODE_EXPENSE, expenseLevelMap)
        buildLevelMapForMode(MODE_BALANCE, balanceLevelMap)

        rvCalendar.notifyMonthChanged(YearMonth.of(selectedYear, selectedMonth))
        syncCalendarHeightToMonth(
            YearMonth.of(selectedYear, selectedMonth),
            calculateCalendarRowCount(YearMonth.of(selectedYear, selectedMonth))
        )
    }

    private fun buildLevelMapForMode(mode: Int, target: MutableMap<String, Int>) {
        val values = mutableListOf<Pair<String, Double>>()
        for ((date, summary) in dailySummaryMap) {
            val value = when (mode) {
                MODE_INCOME -> summary.income
                MODE_EXPENSE -> summary.expense
                else -> kotlin.math.abs(summary.income - summary.expense)
            }
            if (value > 0.0) {
                values.add(date to value)
            }
        }

        values.sortByDescending { it.second }
        if (values.isEmpty()) return

        val n = values.size
        values.forEachIndexed { index, (date, _) ->
            val level = if (n == 1) 31 else (31 - (index * 30 / (n - 1)))
            target[date] = level.coerceIn(1, 31)
        }
    }

    private fun updateSelectedDayDetails() {
        val selectedDateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay)
        val dailyBills = currentMonthBills.filter { dfChartKey.format(Date(it.time)) == selectedDateStr }

        val calNow = Calendar.getInstance()
        val todayStr = dfChartKey.format(calNow.time)
        calNow.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dfChartKey.format(calNow.time)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, selectedDay)
        }
        val weekdayStr = SimpleDateFormat("E", Locale.CHINESE).format(cal.time)

        val suffix = when (selectedDateStr) {
            todayStr -> "今天"
            yesterdayStr -> "昨天"
            else -> weekdayStr
        }

        tvDailyDate.text = "${String.format(Locale.getDefault(), "%02d.%02d", selectedMonth, selectedDay)} $suffix"

        val summary = dailySummaryMap[selectedDateStr] ?: DailySummary()

        val sb = java.lang.StringBuilder()
        if (summary.income > 0) sb.append("收 ¥${String.format(Locale.getDefault(), "%.2f", summary.income)} ")
        if (summary.expense > 0) sb.append("支 ¥${String.format(Locale.getDefault(), "%.2f", summary.expense)}")
        tvDailySummary.text = sb.toString().trim()

        if (dailyBills.isEmpty()) {
            rvDailyBills.visibility = View.GONE
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.GONE
            tvDailyEmpty.visibility = View.VISIBLE
        } else {
            rvDailyBills.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.VISIBLE
            tvDailyEmpty.visibility = View.GONE

            // To prevent duplicated headers inside HomeAdapter, we just pass the raw list
            // HomeAdapter will automatically generate ONE header at the top matching the day.
            // Since we already have tvDailyDate, we hide ours.
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.GONE
            dailyAdapter.submitList(dailyBills)
        }
    }

    private fun lunarOrFestivalText(year: Int, month: Int, day: Int): String {
        val solar = solarFestivals[Pair(month, day)]
        if (solar != null) return solar

        return try {
            val g = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
            }
            val cc = ChineseCalendar().apply { timeInMillis = g.timeInMillis }
            val lunarMonth = cc.get(ChineseCalendar.MONTH) + 1
            val lunarDay = cc.get(ChineseCalendar.DAY_OF_MONTH)

            lunarFestivals[Pair(lunarMonth, lunarDay)]
                ?: if (lunarDay == 1) lunarMonthName(lunarMonth) else lunarDayName(lunarDay)
        } catch (e: Exception) {
            ""
        }
    }

    private fun lunarMonthName(month: Int): String {
        val names = arrayOf(
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
        )
        return names.getOrElse((month - 1).coerceIn(0, 11)) { "" }
    }

    private fun lunarDayName(day: Int): String {
        val names = arrayOf(
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
        )
        return names.getOrElse((day - 1).coerceIn(0, 29)) { "" }
    }
    private fun buildCellBackgroundColor(dateStr: String, summary: DailySummary): Int {
        if (currentMode == MODE_BOTH) {
            return Color.parseColor("#F5F6F8")
        }

        val level: Int
        val positive: Boolean
        when (currentMode) {
            MODE_INCOME -> {
                level = incomeLevelMap[dateStr] ?: 0
                positive = true
            }
            MODE_EXPENSE -> {
                level = expenseLevelMap[dateStr] ?: 0
                positive = false
            }
            else -> {
                val balance = summary.income - summary.expense
                level = balanceLevelMap[dateStr] ?: 0
                positive = balance > 0
            }
        }

        if (level <= 0) return Color.parseColor("#F5F6F8")
        val t = (level - 1) / 30f
        return if (positive) {
            blendColor(Color.parseColor("#ECF9F2"), Color.parseColor("#7AD9B1"), t)
        } else {
            blendColor(Color.parseColor("#FDF0F2"), Color.parseColor("#F39AA5"), t)
        }
    }

    private fun blendColor(startColor: Int, endColor: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val sr = Color.red(startColor)
        val sg = Color.green(startColor)
        val sb = Color.blue(startColor)
        val er = Color.red(endColor)
        val eg = Color.green(endColor)
        val eb = Color.blue(endColor)
        return Color.rgb(
            (sr + (er - sr) * clamped).toInt(),
            (sg + (eg - sg) * clamped).toInt(),
            (sb + (eb - sb) * clamped).toInt()
        )
    }

    inner class CalendarAdapter : MonthDayBinder<CalendarAdapter.DayViewContainer> {

        override fun create(view: View): DayViewContainer = DayViewContainer(view)

        override fun bind(container: DayViewContainer, data: CalendarDay) {
            container.bind(data)
        }

        inner class DayViewContainer(itemView: View) : ViewContainer(itemView) {
            private val tvDayNumber: TextView = itemView.findViewById(R.id.tvDayNumber)
            private val tvTopValue: TextView = itemView.findViewById(R.id.tvTopValue)
            private val tvBottomValue: TextView = itemView.findViewById(R.id.tvBottomValue)
            private val layoutCell: View = itemView.findViewById(R.id.layoutCell)

            fun bind(day: CalendarDay) {
                if (day.position != DayPosition.MonthDate) {
                    tvDayNumber.text = ""
                    tvTopValue.visibility = View.GONE
                    tvBottomValue.visibility = View.GONE
                    layoutCell.background = null
                    view.setOnClickListener(null)
                    view.isClickable = false
                    view.alpha = 0f
                    return
                }

                view.alpha = 1f
                view.isClickable = true

                val date = day.date
                val dateStr = date.toDateKey()
                val summary = dailySummaryMap[dateStr] ?: DailySummary()
                val selected = date == selectedLocalDate()

                tvDayNumber.text = date.dayOfMonth.toString()
                tvTopValue.visibility = View.INVISIBLE
                tvBottomValue.visibility = View.INVISIBLE
                tvTopValue.text = ""
                tvBottomValue.text = ""

                when (currentMode) {
                    MODE_BOTH -> {
                        when {
                            summary.expense > 0 -> {
                                tvTopValue.visibility = View.VISIBLE
                                tvTopValue.text = "-${formatAmt(summary.expense)}"
                                tvTopValue.setTextColor(BillColors.expense)
                                if (summary.income > 0) {
                                    tvBottomValue.visibility = View.VISIBLE
                                    tvBottomValue.text = "+${formatAmt(summary.income)}"
                                    tvBottomValue.setTextColor(Color.parseColor("#4CAF50"))
                                }
                            }
                            summary.income > 0 -> {
                                tvTopValue.visibility = View.VISIBLE
                                tvTopValue.text = "+${formatAmt(summary.income)}"
                                tvTopValue.setTextColor(Color.parseColor("#4CAF50"))
                            }
                            else -> {
                                tvTopValue.visibility = View.VISIBLE
                                tvTopValue.text = lunarOrFestivalText(date.year, date.monthValue, date.dayOfMonth)
                                tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                            }
                        }
                    }
                    MODE_BALANCE -> {
                        val balance = summary.income - summary.expense
                        if (balance != 0.0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = if (balance > 0) "+${formatAmt(balance)}" else formatAmt(balance)
                            tvTopValue.setTextColor(if (balance > 0) BillColors.income else BillColors.expense)
                        } else if (summary.expense <= 0 && summary.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(date.year, date.monthValue, date.dayOfMonth)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                    MODE_INCOME -> {
                        if (summary.income > 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = "+${formatAmt(summary.income)}"
                            tvTopValue.setTextColor(Color.parseColor("#4CAF50"))
                        } else if (summary.expense <= 0 && summary.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(date.year, date.monthValue, date.dayOfMonth)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                    MODE_EXPENSE -> {
                        if (summary.expense > 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = "-${formatAmt(summary.expense)}"
                            tvTopValue.setTextColor(BillColors.expense)
                        } else if (summary.expense <= 0 && summary.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(date.year, date.monthValue, date.dayOfMonth)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                }

                val bgColor = if (selected) Color.parseColor("#DCEBFF") else buildCellBackgroundColor(dateStr, summary)
                layoutCell.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * resources.displayMetrics.density
                    setColor(bgColor)
                }

                if (selected) {
                    tvDayNumber.setTextColor(Color.parseColor("#4080FF"))
                    tvTopValue.setTextColor(Color.parseColor("#4080FF"))
                    tvBottomValue.setTextColor(Color.parseColor("#4080FF"))
                } else {
                    tvDayNumber.setTextColor(Color.parseColor("#333333"))
                }

                view.setOnClickListener {
                    val oldDate = selectedLocalDate()
                    selectedYear = date.year
                    selectedMonth = date.monthValue
                    selectedDay = date.dayOfMonth
                    rvCalendar.notifyDateChanged(oldDate)
                    rvCalendar.notifyDateChanged(date)
                    updateSelectedDayDetails()
                }
            }
        }
    }

    private fun selectedLocalDate(): LocalDate = LocalDate.of(selectedYear, selectedMonth, selectedDay)

    private fun Int.toDayOfWeek(): DayOfWeek {
        return when (this) {
            Calendar.SUNDAY -> DayOfWeek.SUNDAY
            Calendar.MONDAY -> DayOfWeek.MONDAY
            Calendar.TUESDAY -> DayOfWeek.TUESDAY
            Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
            Calendar.THURSDAY -> DayOfWeek.THURSDAY
            Calendar.FRIDAY -> DayOfWeek.FRIDAY
            Calendar.SATURDAY -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
    }

    private fun LocalDate.toDateKey(): String {
        return String.format(Locale.getDefault(), "%04d-%02d-%02d", year, monthValue, dayOfMonth)
    }

    private fun syncCalendarHeightToMonth(month: YearMonth, rowCount: Int) {
        rvCalendar.post {
            val position = ChronoUnit.MONTHS.between(calendarStartMonth, month).toInt()
            if (position < 0) return@post

            val holder = rvCalendar.findViewHolderForAdapterPosition(position) ?: run {
                rvCalendar.requestLayout()
                findViewById<View>(R.id.layoutCalendarPanel)?.requestLayout()
                return@post
            }

            holder.itemView.requestLayout()
            holder.itemView.post {
                val root = holder.itemView as? ViewGroup
                val visibleChildren = root?.children?.filter { !it.isGone }?.toList().orEmpty()
                val sampleRowHeight = visibleChildren.firstOrNull()?.let { child ->
                    val childHeight = child.measuredHeight.takeIf { it > 0 }
                        ?: child.height.takeIf { it > 0 }
                        ?: 0
                    val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                    childHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                } ?: 0
                val contentHeight = if (sampleRowHeight > 0 && rowCount > 0) {
                    sampleRowHeight * rowCount
                } else {
                    visibleChildren.sumOf { child ->
                        val childHeight = child.measuredHeight.takeIf { it > 0 }
                            ?: child.height.takeIf { it > 0 }
                            ?: 0
                        val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                        childHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                    }
                }
                val height = contentHeight.takeIf { it > 0 }
                    ?: holder.itemView.measuredHeight.takeIf { it > 0 }
                    ?: holder.itemView.height.takeIf { it > 0 }
                    ?: return@post
                if (rvCalendar.layoutParams.height != height) {
                    rvCalendar.layoutParams = rvCalendar.layoutParams.apply {
                        this.height = height
                    }
                    rvCalendar.requestLayout()
                    findViewById<View>(R.id.layoutCalendarPanel)?.requestLayout()
                }
            }
        }
    }

    private fun calculateCalendarRowCount(yearMonth: YearMonth): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, yearMonth.year)
            set(Calendar.MONTH, yearMonth.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstDayOfCurrentMonth = cal.get(Calendar.DAY_OF_WEEK)
        var offset = firstDayOfCurrentMonth - firstDayOfWeekOption
        if (offset < 0) offset += 7
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return ((offset + daysInMonth) + 6) / 7
    }

    private fun formatAmt(amt: Double): String =
        AmountFormatHelper.formatCompact(amt)
}
