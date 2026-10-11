package com.taostudio.tapaccounting.ui.activity

import android.os.Bundle
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillStatsContribution
import com.taostudio.tapaccounting.ui.main.SharedYearMonthSession
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import java.util.Calendar
import java.util.Locale

class BookOverviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CURRENT_BOOK = "extra_current_book"
        const val EXTRA_SELECTED_YEAR = "extra_selected_year"
        const val EXTRA_SELECTED_MONTH = "extra_selected_month"
    }

    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var isYearMode: Boolean = false
    private var collapsedBooksExpanded: Boolean = false

    private lateinit var tvCurrentPeriod: TextView
    private lateinit var tvTotalExpense: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvTotalBalance: TextView
    private lateinit var btnPeriodPrev: ImageView
    private lateinit var btnPeriodNext: ImageView
    private lateinit var layoutCurrentPeriodTrigger: android.view.View
    private lateinit var btnViewMonth: TextView
    private lateinit var btnViewYear: TextView
    private lateinit var indicatorViewMonth: android.view.View
    private lateinit var indicatorViewYear: android.view.View
    private lateinit var rvBookOverview: RecyclerView

    private lateinit var adapter: BookOverviewAdapter
    private var currentBook: String = BookAccountManager.DEFAULT_BOOK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_overview)

        currentBook = intent.getStringExtra(EXTRA_CURRENT_BOOK)
            ?: BookAccountManager.getDefaultBook(this)
        val (sessionYear, sessionMonth) = SharedYearMonthSession.getYearMonth()
        selectedYear = intent.getIntExtra(EXTRA_SELECTED_YEAR, sessionYear).coerceIn(2000, 2100)
        selectedMonth = intent.getIntExtra(EXTRA_SELECTED_MONTH, sessionMonth).coerceIn(1, 12)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        bindViews()
        setupAdapter()
        setupDragDrop()
        setupPeriodControls()
        loadData()
    }

    private fun bindViews() {
        tvCurrentPeriod = findViewById(R.id.tvCurrentPeriod)
        tvTotalExpense = findViewById(R.id.tvTotalExpense)
        tvTotalIncome = findViewById(R.id.tvTotalIncome)
        tvTotalBalance = findViewById(R.id.tvTotalBalance)
        btnPeriodPrev = findViewById(R.id.btnPeriodPrev)
        btnPeriodNext = findViewById(R.id.btnPeriodNext)
        layoutCurrentPeriodTrigger = findViewById(R.id.layoutCurrentPeriodTrigger)
        btnViewMonth = findViewById(R.id.btnModeMonth)
        btnViewYear = findViewById(R.id.btnModeYear)
        indicatorViewMonth = findViewById(R.id.indicatorModeMonth)
        indicatorViewYear = findViewById(R.id.indicatorModeYear)
        rvBookOverview = findViewById(R.id.rvBookOverview)
    }

    private fun setupAdapter() {
        adapter = BookOverviewAdapter(
            onCardClick = cardClick@ { item ->
                if (item.bookName == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                    collapsedBooksExpanded = !collapsedBooksExpanded
                    loadData()
                    return@cardClick
                }
                // 点击卡片返回选中的账本
                val resultIntent = android.content.Intent()
                resultIntent.putExtra(EXTRA_CURRENT_BOOK, item.bookName)
                setResult(RESULT_OK, resultIntent)
                finish()
            },
            onOrderChanged = { newOrder ->
                // 拖拽结束后持久化新顺序
                BookAccountManager.reorderBookAccounts(this, newOrder)
            }
        )
        adapter.setYearMode(isYearMode)
        rvBookOverview.layoutManager = LinearLayoutManager(this)
        rvBookOverview.adapter = adapter
    }

    private fun setupDragDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private var dragFrom = -1
            private var dragTo = -1

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (dragFrom == -1) dragFrom = from
                dragTo = to
                adapter.onItemMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 只有真正发生了移动才持久化
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    adapter.onDragEnd()
                }
                dragFrom = -1
                dragTo = -1
                // 恢复卡片透明度
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // 拖拽中：轻微放大 + 半透明，给出被"拎起"的视觉反馈
                    viewHolder?.itemView?.apply {
                        animate().scaleX(1.03f).scaleY(1.03f).alpha(0.92f).setDuration(120).start()
                    }
                }
            }

            override fun isLongPressDragEnabled(): Boolean = true
        }

        ItemTouchHelper(callback).attachToRecyclerView(rvBookOverview)
    }

    private fun setupPeriodControls() {
        btnPeriodPrev.setOnClickListener { stepPeriod(-1) }
        btnPeriodNext.setOnClickListener { stepPeriod(+1) }
        layoutCurrentPeriodTrigger.setOnClickListener { showPeriodPicker() }

        btnViewMonth.setOnClickListener {
            if (isYearMode) {
                isYearMode = false
                updateViewModeStyle()
                updatePeriodText()
                adapter.setYearMode(isYearMode)
                loadData()
            }
        }
        btnViewYear.setOnClickListener {
            if (!isYearMode) {
                isYearMode = true
                updateViewModeStyle()
                updatePeriodText()
                adapter.setYearMode(isYearMode)
                loadData()
            }
        }
        updatePeriodText()
        updateViewModeStyle()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun showPeriodPicker() {
        if (isYearMode) {
            YearMonthPickerDialog.show(
                context = this,
                title = "选择年份",
                initialYear = selectedYear,
                initialMonth = selectedMonth,
                yearOnly = true
            ) { year, _ ->
                selectedYear = year
                updatePeriodText()
                loadData()
            }
        } else {
            YearMonthPickerDialog.show(
                context = this,
                title = "选择年月",
                initialYear = selectedYear,
                initialMonth = selectedMonth
            ) { year, month ->
                selectedYear = year
                selectedMonth = month
                updatePeriodText()
                loadData()
            }
        }
    }

    private fun stepPeriod(delta: Int) {
        if (isYearMode) {
            selectedYear += delta
        } else {
            selectedMonth += delta
            if (selectedMonth > 12) { selectedMonth = 1; selectedYear++ }
            if (selectedMonth < 1)  { selectedMonth = 12; selectedYear-- }
        }
        updatePeriodText()
        loadData()
    }

    private fun updatePeriodText() {
        tvCurrentPeriod.text = if (isYearMode) {
            "${selectedYear}年"
        } else {
            String.format(Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth)
        }
    }

    private fun updateViewModeStyle() {
        val activeColor = getColor(R.color.stats_segmented_text_active)
        val inactiveColor = getColor(R.color.stats_segmented_text_inactive)
        val transparent = android.graphics.Color.TRANSPARENT
        btnViewMonth.setTextColor(if (!isYearMode) activeColor else inactiveColor)
        btnViewYear.setTextColor(if (isYearMode) activeColor else inactiveColor)
        indicatorViewMonth.setBackgroundColor(if (!isYearMode) activeColor else transparent)
        indicatorViewYear.setBackgroundColor(if (isYearMode) activeColor else transparent)
    }

    private fun loadData() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildOverviewItems() }
            val totalExpense = items.sumOf { it.expense }
            val totalIncome = items.sumOf { it.income }
            val totalBalance = Math.round((totalIncome - totalExpense) * 100) / 100.0

            adapter.submitList(items)

            tvTotalExpense.text = "¥${AmountFormatHelper.formatAmount(totalExpense)}"
            tvTotalIncome.text = "¥${AmountFormatHelper.formatAmount(totalIncome)}"
            tvTotalExpense.setTextColor(amountColor(totalExpense))
            tvTotalIncome.setTextColor(amountColor(totalIncome))
            val balanceSign = when {
                totalBalance > 0 -> "+"
                totalBalance < 0 -> "-"
                else -> ""
            }
            tvTotalBalance.text = "${balanceSign}¥${AmountFormatHelper.formatAmount(kotlin.math.abs(totalBalance))}"
            tvTotalBalance.setTextColor(
                when {
                    totalBalance > 0 -> android.graphics.Color.parseColor("#2FA36B")
                    totalBalance < 0 -> android.graphics.Color.parseColor("#E05A5A")
                    else -> amountColor(0.0)
                }
            )
        }
    }

    private suspend fun buildOverviewItems(): List<BookOverviewItem> {
        val ctx = this
        val db = AppDatabase.getDatabase(ctx)
        val allBooks = BookAccountManager.getBookAccounts(ctx)
            .map { BookAccountManager.normalizeBookName(it) }
            .filter { it != BookAccountManager.ALL_BOOK }
        val collapsedBooks = BookAccountManager.getCollapsedBookAccounts(ctx, allBooks)
            .map { BookAccountManager.normalizeBookName(it) }
            .toSet()
        val books = BookAccountManager.getDisplayBookAccounts(
            context = ctx,
            books = allBooks,
            includeAllBook = false,
            collapsedGroupExpanded = collapsedBooksExpanded
        )
        val (startMs, endMs) = getPeriodRange()
        val allBills: List<Bill> = db.billDao().getBillsBetweenTimesList(startMs, endMs)

        return books.map { normalizedBook ->
            val targetBooks = if (normalizedBook == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                collapsedBooks
            } else {
                setOf(normalizedBook)
            }
            val billsForBook = allBills.filter {
                targetBooks.any { book -> BookAccountManager.isBillInBook(it.bookName, book) }
            }
            val expense = billsForBook
                .sumOf { bill ->
                    val amount = if (bill.currency == "CNY") bill.amount else bill.amount * bill.exchangeRate
                    BillStatsContribution.from(bill, amount).expense
                }
            val income = billsForBook
                .sumOf { bill ->
                    val amount = if (bill.currency == "CNY") bill.amount else bill.amount * bill.exchangeRate
                    BillStatsContribution.from(bill, amount).income
                }

            BookOverviewItem(
                bookName = normalizedBook,
                themeColor = if (normalizedBook == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                    android.graphics.Color.parseColor("#8E9CAF")
                } else {
                    BookAccountManager.getBookColor(ctx, normalizedBook)
                },
                bannerPath = if (normalizedBook == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                    null
                } else {
                    BookAccountManager.getBookBannerPath(ctx, normalizedBook)
                },
                expense = expense,
                income = income,
                isCurrentBook = normalizedBook == BookAccountManager.normalizeBookName(currentBook)
            )
        }
    }

    private fun getPeriodRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        return if (isYearMode) {
            cal.set(selectedYear, Calendar.JANUARY, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(selectedYear, Calendar.DECEMBER, 31, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        } else {
            cal.set(selectedYear, selectedMonth - 1, 1)
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(selectedYear, selectedMonth - 1, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(selectedYear, selectedMonth - 1, daysInMonth, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        }
    }
}

