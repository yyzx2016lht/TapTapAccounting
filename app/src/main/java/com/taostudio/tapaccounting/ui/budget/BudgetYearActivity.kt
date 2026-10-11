package com.taostudio.tapaccounting.ui.budget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BudgetPeriodSummary
import com.taostudio.tapaccounting.logic.BudgetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** 年度预算：全年同期对比 + 12 个月的预算执行小卡片，点月份进入该月预算管理。 */
class BudgetYearActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_BOOK_NAME = "extra_book_name"

        fun intent(context: Context, year: Int, bookName: String): Intent =
            Intent(context, BudgetYearActivity::class.java)
                .putExtra(EXTRA_YEAR, year)
                .putExtra(EXTRA_BOOK_NAME, bookName)
    }

    private var year = 0
    private lateinit var bookName: String
    private lateinit var tvYear: TextView
    private val adapter = YearAdapter { month ->
        startActivity(
            BudgetManageActivity.intent(this, month.yearMonth, bookName, fromYearView = true)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget_year)

        year = intent.getIntExtra(EXTRA_YEAR, Calendar.getInstance().get(Calendar.YEAR))
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME)
            ?.takeUnless { it == BookAccountManager.ALL_BOOK }
            .orEmpty()

        tvYear = findViewById(R.id.tv_budget_year)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_prev_year).setOnClickListener { year -= 1; load() }
        findViewById<View>(R.id.btn_next_year).setOnClickListener { year += 1; load() }

        findViewById<RecyclerView>(R.id.rv_budget_months).apply {
            layoutManager = GridLayoutManager(this@BudgetYearActivity, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (position == 0) 2 else 1
                }
            }
            adapter = this@BudgetYearActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val requestedYear = year
        tvYear.text = getString(R.string.budget_year_title_fmt, requestedYear)
        lifecycleScope.launch {
            val (summary, months) = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@BudgetYearActivity)
                val budgetService = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
                val budgets = db.budgetDao().getTotalBudgetsBetween(
                    String.format(Locale.US, "%04d-01", requestedYear),
                    String.format(Locale.US, "%04d-12", requestedYear),
                    bookName
                )
                val now = Calendar.getInstance()
                val currentYearMonth = String.format(
                    Locale.US, "%04d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1
                )
                val monthSpend = (1..12)
                    .map { String.format(Locale.US, "%04d-%02d", requestedYear, it) }
                    .filter { it <= currentYearMonth }
                    .associateWith { budgetService.getMonthSpend(bookName, categoryId = null, it) }
                BudgetPeriodSummary.summarize(budgets, monthSpend, currentYearMonth) to
                    BudgetPeriodSummary.months(requestedYear, budgets, monthSpend, currentYearMonth)
            }
            if (requestedYear == year) adapter.submit(summary, months)
        }
    }

    private class YearAdapter(
        private val onMonthClick: (BudgetPeriodSummary.Month) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var summary: BudgetPeriodSummary.Summary? = null
        private var months: List<BudgetPeriodSummary.Month> = emptyList()

        fun submit(summary: BudgetPeriodSummary.Summary, months: List<BudgetPeriodSummary.Month>) {
            this.summary = summary
            this.months = months
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = if (summary == null) 0 else months.size + 1

        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_SUMMARY else TYPE_MONTH

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SUMMARY) {
                SummaryHolder(inflater.inflate(R.layout.item_budget_year_summary, parent, false))
            } else {
                MonthHolder(inflater.inflate(R.layout.item_budget_month, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SummaryHolder -> summary?.let(holder::bind)
                is MonthHolder -> holder.bind(months[position - 1], onMonthClick)
            }
        }

        private class SummaryHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvStatus: TextView = view.findViewById(R.id.tv_year_status)
            private val tvBudgetLabel: TextView = view.findViewById(R.id.tv_year_budget_label)
            private val tvBudget: TextView = view.findViewById(R.id.tv_year_budget)
            private val tvSpent: TextView = view.findViewById(R.id.tv_year_spent)
            private val tvRemainingLabel: TextView = view.findViewById(R.id.tv_year_remaining_label)
            private val tvRemaining: TextView = view.findViewById(R.id.tv_year_remaining)
            private val progress: ProgressBar = view.findViewById(R.id.progress_year)
            private val tvHint: TextView = view.findViewById(R.id.tv_year_hint)

            fun bind(summary: BudgetPeriodSummary.Summary) {
                val context = itemView.context
                val neutral = BudgetUi.neutralColor(context)
                if (summary.budgetedMonths == 0 || summary.mixedCurrency) {
                    BudgetUi.bindPill(
                        tvStatus,
                        context.getString(
                            if (summary.mixedCurrency) R.string.budget_mixed_currency_short
                            else R.string.budget_not_set_short
                        ),
                        neutral
                    )
                    tvBudgetLabel.setText(R.string.budget_amount_short)
                    tvBudget.text = "—"
                    tvSpent.text = "—"
                    tvRemainingLabel.setText(R.string.budget_surplus_short)
                    tvRemaining.text = "—"
                    tvRemaining.setTextColor(context.getColor(R.color.stats_segmented_text_active))
                    BudgetUi.bindProgress(progress, 0.0, 0.0, neutral)
                    tvHint.setText(
                        if (summary.mixedCurrency) R.string.budget_period_mixed_currency
                        else R.string.budget_year_hint_empty
                    )
                    return
                }
                val color = BudgetUi.usageColor(context, summary.spent, summary.budget)
                if (summary.overMonths > 0) {
                    BudgetUi.bindPill(
                        tvStatus,
                        context.getString(R.string.budget_year_over_fmt, summary.overMonths),
                        context.getColor(R.color.expense_color)
                    )
                } else {
                    BudgetUi.bindPill(
                        tvStatus,
                        context.getString(R.string.budget_year_all_ok),
                        context.getColor(R.color.income_color)
                    )
                }
                tvBudgetLabel.text = context.getString(R.string.budget_amount_months_fmt, summary.budgetedMonths)
                tvBudget.text = BudgetUi.money(summary.budget)
                tvSpent.text = BudgetUi.money(summary.spent)
                tvRemainingLabel.setText(
                    if (summary.remaining >= 0) R.string.budget_surplus_short else R.string.budget_overspent_short
                )
                tvRemaining.text = BudgetUi.money(kotlin.math.abs(summary.remaining))
                tvRemaining.setTextColor(color)
                BudgetUi.bindProgress(progress, summary.spent, summary.budget, color)
                tvHint.text = context.getString(R.string.budget_year_hint_fmt, summary.budgetedMonths)
            }
        }

        private class MonthHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card: MaterialCardView = view as MaterialCardView
            private val tvName: TextView = view.findViewById(R.id.tv_month_name)
            private val tvStatus: TextView = view.findViewById(R.id.tv_month_status)
            private val tvBudget: TextView = view.findViewById(R.id.tv_month_budget)
            private val progress: ProgressBar = view.findViewById(R.id.progress_month)
            private val tvSpent: TextView = view.findViewById(R.id.tv_month_spent)
            private val tvRemaining: TextView = view.findViewById(R.id.tv_month_remaining)

            fun bind(month: BudgetPeriodSummary.Month, onClick: (BudgetPeriodSummary.Month) -> Unit) {
                val context = itemView.context
                val neutral = BudgetUi.neutralColor(context)
                val isCurrent = month.timing == BudgetPeriodSummary.Timing.CURRENT
                val isFuture = month.timing == BudgetPeriodSummary.Timing.FUTURE
                tvName.text = context.getString(R.string.budget_month_name_fmt, month.month)
                card.strokeColor = context.getColor(if (isCurrent) R.color.brand_primary else R.color.stats_card_stroke)
                val density = context.resources.displayMetrics.density
                card.strokeWidth = ((if (isCurrent) 1.5f else 0.5f) * density).toInt().coerceAtLeast(1)
                itemView.setOnClickListener { onClick(month) }

                val budget = month.budget
                when {
                    budget == null -> {
                        BudgetUi.bindPill(tvStatus, context.getString(R.string.budget_not_set_short), neutral)
                        tvBudget.setText(R.string.budget_month_no_budget)
                        tvBudget.setTextColor(context.getColor(R.color.stats_empty_text))
                        progress.visibility = View.INVISIBLE
                        tvSpent.text = if (isFuture) context.getString(R.string.budget_month_not_started)
                        else context.getString(R.string.budget_month_expense_fmt, BudgetUi.money(month.spent))
                        tvRemaining.setText(R.string.budget_month_tap_to_set)
                        tvRemaining.setTextColor(context.getColor(R.color.brand_primary))
                    }
                    isFuture -> {
                        BudgetUi.bindPill(tvStatus, context.getString(R.string.budget_month_status_upcoming), neutral)
                        tvBudget.text = BudgetUi.money(budget)
                        tvBudget.setTextColor(context.getColor(R.color.stats_segmented_text_active))
                        progress.visibility = View.VISIBLE
                        BudgetUi.bindProgress(progress, 0.0, budget, neutral)
                        tvSpent.setText(R.string.budget_month_not_started)
                        tvRemaining.text = context.getString(R.string.budget_month_left_fmt, BudgetUi.money(budget))
                        tvRemaining.setTextColor(neutral)
                    }
                    else -> {
                        val color = BudgetUi.usageColor(context, month.spent, budget)
                        BudgetUi.bindPill(
                            tvStatus,
                            context.getString(
                                when {
                                    month.isOver -> R.string.budget_status_exceeded
                                    isCurrent -> R.string.budget_month_status_in_progress
                                    else -> R.string.budget_month_status_ok
                                }
                            ),
                            if (isCurrent && !month.isOver) context.getColor(R.color.brand_primary) else color
                        )
                        tvBudget.text = BudgetUi.money(budget)
                        tvBudget.setTextColor(context.getColor(R.color.stats_segmented_text_active))
                        progress.visibility = View.VISIBLE
                        BudgetUi.bindProgress(progress, month.spent, budget, color)
                        tvSpent.text = context.getString(R.string.budget_month_spent_fmt, BudgetUi.money(month.spent))
                        val remaining = budget - month.spent
                        tvRemaining.text = if (remaining >= 0) {
                            context.getString(R.string.budget_month_left_fmt, BudgetUi.money(remaining))
                        } else {
                            context.getString(R.string.budget_month_over_fmt, BudgetUi.money(-remaining))
                        }
                        tvRemaining.setTextColor(color)
                    }
                }
            }
        }

        companion object {
            private const val TYPE_SUMMARY = 0
            private const val TYPE_MONTH = 1
        }
    }
}
