package com.taostudio.tapaccounting.ui.budget

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.data.sync.SharedBudgetHooks
import com.taostudio.tapaccounting.data.sync.SharedSyncScheduler
import com.taostudio.tapaccounting.logic.BudgetService
import com.taostudio.tapaccounting.logic.BudgetCategoryOptions
import com.taostudio.tapaccounting.logic.MemberBudgetAllocation
import com.taostudio.tapaccounting.logic.SharedBudgetDisplayDefaultPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 预算管理页。
 * 展示当月各分类预算列表，支持新增/编辑/删除预算。
 */
class BudgetManageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_YEAR_MONTH = "extra_year_month"
        const val EXTRA_BOOK_NAME = "extra_book_name"
        const val EXTRA_FROM_YEAR_VIEW = "extra_from_year_view"

        fun intent(
            context: Context,
            yearMonth: String? = null,
            bookName: String? = null,
            fromYearView: Boolean = false
        ): Intent = Intent(context, BudgetManageActivity::class.java)
            .putExtra(EXTRA_YEAR_MONTH, yearMonth)
            .putExtra(EXTRA_BOOK_NAME, bookName)
            .putExtra(EXTRA_FROM_YEAR_VIEW, fromYearView)
    }

    private lateinit var budgetService: BudgetService
    private lateinit var currentBook: String
    private lateinit var preferenceBook: String
    private val yearMonthFlow = MutableStateFlow("")
    private val yearMonth: String get() = yearMonthFlow.value
    private var totalBudget: Budget? = null
    private lateinit var adapter: BudgetAdapter
    private lateinit var rvBudgets: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvMonth: TextView
    private lateinit var layoutSummary: View
    private lateinit var layoutSummaryEmpty: View
    private lateinit var tvSummaryStatus: TextView
    private lateinit var tvSummaryAmount: TextView
    private lateinit var tvSummaryUsed: TextView
    private lateinit var tvSummaryRemainingLabel: TextView
    private lateinit var tvSummaryRemaining: TextView
    private lateinit var tvSummaryDetail: TextView
    private lateinit var progressSummary: ProgressBar

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget_manage)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        val db = AppDatabase.getDatabase(this)
        budgetService = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
        preferenceBook = BookAccountManager.getSelectedBook(this)
        currentBook = (intent.getStringExtra(EXTRA_BOOK_NAME) ?: preferenceBook).let { book ->
            if (book == BookAccountManager.ALL_BOOK) "" else book
        }
        yearMonthFlow.value = intent.getStringExtra(EXTRA_YEAR_MONTH)
            ?: SimpleDateFormat("yyyy-MM", Locale.US).format(Calendar.getInstance().time)

        rvBudgets = findViewById(R.id.rv_budgets)
        rvBudgets.layoutManager = LinearLayoutManager(this)
        tvEmpty = findViewById(R.id.tv_budget_empty)
        tvMonth = findViewById(R.id.tv_budget_month)
        layoutSummary = findViewById(R.id.layout_budget_summary)
        layoutSummaryEmpty = findViewById(R.id.layout_budget_summary_empty)
        tvSummaryStatus = findViewById(R.id.tv_budget_summary_status)
        tvSummaryAmount = findViewById(R.id.tv_budget_summary_amount)
        tvSummaryUsed = findViewById(R.id.tv_budget_summary_used)
        tvSummaryRemainingLabel = findViewById(R.id.tv_budget_summary_remaining_label)
        tvSummaryRemaining = findViewById(R.id.tv_budget_summary_remaining)
        tvSummaryDetail = findViewById(R.id.tv_budget_summary_detail)
        progressSummary = findViewById(R.id.progress_budget_summary)
        val btnSuggest = findViewById<View>(R.id.btn_suggest_budget)
        val btnCopyPrevious = findViewById<View>(R.id.btn_copy_previous_budget)
        val btnAiBudget = findViewById<View>(R.id.btn_ai_budget)
        val btnAdd = findViewById<View>(R.id.btn_add_budget)

        tvMonth.text = yearMonth
        findViewById<View>(R.id.btn_prev_month).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btn_next_month).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.btn_budget_year).apply {
            if (intent.getBooleanExtra(EXTRA_FROM_YEAR_VIEW, false)) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    startActivity(
                        BudgetYearActivity.intent(
                            this@BudgetManageActivity,
                            year = yearMonth.take(4).toInt(),
                            bookName = currentBook
                        )
                    )
                }
            }
        }
        findViewById<View>(R.id.card_budget_summary).apply {
            setOnClickListener { totalBudget?.let(::showEditDialog) }
            setOnLongClickListener { totalBudget?.let(::showDeleteDialog); totalBudget != null }
        }
        findViewById<View>(R.id.btn_add_total_budget).setOnClickListener {
            showAddBudgetDialog(forceTotalBudget = true)
        }
        val rowHomeBudgetSummary = findViewById<View>(R.id.row_home_budget_summary)
        val switchHomeBudgetSummary = findViewById<SwitchMaterial>(R.id.switch_home_budget_summary)
        val rowStatsBudgetMode = findViewById<View>(R.id.row_stats_budget_mode)
        val switchStatsBudgetMode = findViewById<SwitchMaterial>(R.id.switch_stats_budget_mode)

        switchHomeBudgetSummary.isChecked = Prefs.isHomeBudgetSummaryEnabled(this, preferenceBook)
        switchHomeBudgetSummary.setOnCheckedChangeListener { _, enabled ->
            Prefs.setHomeBudgetSummaryEnabled(this, preferenceBook, enabled)
        }
        rowHomeBudgetSummary.setOnClickListener { switchHomeBudgetSummary.performClick() }
        switchStatsBudgetMode.isChecked = Prefs.isStatsBudgetModeEnabled(this, preferenceBook)
        switchStatsBudgetMode.setOnCheckedChangeListener { _, enabled ->
            Prefs.setStatsBudgetModeEnabled(this, preferenceBook, enabled)
        }
        rowStatsBudgetMode.setOnClickListener { switchStatsBudgetMode.performClick() }

        adapter = BudgetAdapter(
            onItemClick = { budget -> showEditDialog(budget) },
            onItemLongClick = { budget -> showDeleteDialog(budget) }
        )
        rvBudgets.adapter = adapter

        btnAdd.setOnClickListener { showAddBudgetDialog() }
        loadBudgets()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                yearMonthFlow.flatMapLatest { month ->
                    db.budgetDao().observeBudgetsByMonthAndBook(month, currentBook)
                }.collectLatest {
                    val (isSharedLedger, hasBudget) = withContext(Dispatchers.IO) {
                        if (currentBook.isBlank()) {
                            false to false
                        } else {
                            db.sharedLedgerDao().getByBookName(currentBook)?.let { ledger ->
                                true to db.budgetDao().getAllByBookId(ledger.bookId).isNotEmpty()
                            } ?: (false to false)
                        }
                    }
                    if (SharedBudgetDisplayDefaultPolicy.shouldEnable(
                            isSharedLedger = isSharedLedger,
                            hasBudget = hasBudget,
                            hasExplicitPreference = Prefs.hasHomeBudgetSummaryPreference(
                                this@BudgetManageActivity,
                                preferenceBook
                            )
                        )
                    ) {
                        Prefs.setHomeBudgetSummaryEnabled(this@BudgetManageActivity, preferenceBook, true)
                        switchHomeBudgetSummary.isChecked = true
                    }
                    if (SharedBudgetDisplayDefaultPolicy.shouldEnable(
                            isSharedLedger = isSharedLedger,
                            hasBudget = hasBudget,
                            hasExplicitPreference = Prefs.hasStatsBudgetModePreference(
                                this@BudgetManageActivity,
                                preferenceBook
                            )
                        )
                    ) {
                        Prefs.setStatsBudgetModeEnabled(this@BudgetManageActivity, preferenceBook, true)
                        switchStatsBudgetMode.isChecked = true
                    }
                    loadBudgets()
                }
            }
        }

        btnSuggest.setOnClickListener {
            showBudgetSuggestions()
        }

        btnCopyPrevious.setOnClickListener {
            btnCopyPrevious.isEnabled = false
            lifecycleScope.launch {
                try {
                    val copied = withContext(Dispatchers.IO) {
                        val copied = budgetService.copyPreviousMonthBudgets(currentBook, yearMonth)
                        if (copied > 0 && currentBook.isNotBlank()) {
                            val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                            db.budgetDao().getBudgetsByMonthAndBook(yearMonth, currentBook)
                                .filter { !it.isShared }
                                .forEach { SharedBudgetHooks.save(db, it) }
                        }
                        copied
                    }
                    Toast.makeText(
                        this@BudgetManageActivity,
                        if (copied > 0) getString(R.string.budget_copy_previous_done, copied)
                        else getString(R.string.budget_copy_previous_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (copied > 0) loadBudgets()
                } catch (_: Exception) {
                    Toast.makeText(
                        this@BudgetManageActivity,
                        getString(R.string.save_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    btnCopyPrevious.isEnabled = true
                }
            }
        }

        btnAiBudget.setOnClickListener {
            analyzeBudgetWithAi()
        }
    }

    override fun onResume() {
        super.onResume()
        SharedSyncScheduler.enqueueFullNow(this)
    }

    private fun shiftMonth(delta: Int) {
        val parts = yearMonth.split('-')
        val calendar = Calendar.getInstance().apply {
            clear()
            set(parts[0].toInt(), parts[1].toInt() - 1, 1)
            add(Calendar.MONTH, delta)
        }
        yearMonthFlow.value = String.format(
            Locale.US, "%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1
        )
        tvMonth.text = yearMonth
    }

    private fun loadBudgets() {
        val requestedMonth = yearMonth
        lifecycleScope.launch {
            val budgetsWithProgress = withContext(Dispatchers.IO) {
                budgetService.getMonthBudgetsWithProgress(currentBook, requestedMonth)
            }
            if (requestedMonth != yearMonth) return@launch

            val total = budgetsWithProgress.firstOrNull { it.budget.categoryId == null }
            val categories = budgetsWithProgress.filter { it.budget.categoryId != null }
            totalBudget = total?.budget
            bindSummary(total)
            adapter.submitList(categories)
            rvBudgets.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
            tvEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun bindSummary(total: BudgetService.BudgetOverview?) {
        if (total == null) {
            layoutSummary.visibility = View.GONE
            layoutSummaryEmpty.visibility = View.VISIBLE
            BudgetUi.bindPill(tvSummaryStatus, getString(R.string.budget_not_set_short), BudgetUi.neutralColor(this))
            return
        }
        val budget = total.budget
        val progress = total.progress
        val statusColor = BudgetUi.statusColor(this, progress.status)
        layoutSummary.visibility = View.VISIBLE
        layoutSummaryEmpty.visibility = View.GONE
        BudgetUi.bindPill(
            tvSummaryStatus,
            when (progress.status) {
                BudgetService.BudgetStatus.EXCEEDED -> getString(R.string.budget_status_exceeded)
                BudgetService.BudgetStatus.WARNING -> getString(R.string.budget_status_warning)
                BudgetService.BudgetStatus.NORMAL -> getString(R.string.budget_status_normal)
            },
            statusColor
        )
        tvSummaryAmount.text = BudgetUi.money(budget.amount)
        tvSummaryUsed.text = BudgetUi.money(progress.usedAmount)
        tvSummaryRemainingLabel.setText(
            if (progress.remaining >= 0) R.string.budget_remaining_short else R.string.budget_overspent_short
        )
        tvSummaryRemaining.text = BudgetUi.money(kotlin.math.abs(progress.remaining))
        tvSummaryRemaining.setTextColor(statusColor)
        tvSummaryDetail.text = when {
            progress.isMonthClosed -> getString(R.string.budget_month_closed)
            progress.remaining < 0 -> getString(R.string.budget_reason_exceeded)
            else -> getString(
                R.string.budget_summary_pace_fmt,
                progress.remainingDays,
                progress.dailyRemainingAllowance,
                progress.timeProgress * 100
            )
        }
        BudgetUi.bindProgress(progressSummary, progress.usedAmount, budget.amount, statusColor)
    }

    private fun showBudgetSuggestions() {
        lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                val total = budgetService.suggestBudgetPlanFromHistory(
                    currentBook,
                    null,
                    getString(R.string.budget_monthly_total),
                    yearMonth
                )
                val unbudgeted = budgetService.suggestUnbudgetedCategoryPlans(currentBook, yearMonth)
                listOfNotNull(total) + unbudgeted
            }
            if (suggestions.isEmpty()) {
                Toast.makeText(
                    this@BudgetManageActivity,
                    getString(R.string.budget_suggest_empty),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            showSuggestionDialog(suggestions)
        }
    }

    private fun showSuggestionDialog(suggestions: List<BudgetService.BudgetSuggestionPlan>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_budget_suggestions, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.layout_budget_suggestions)
        val dialog = BottomSheetDialog(this)
        suggestions.forEach { plan ->
            val row = buildSuggestionRow(plan) {
                showAddBudgetDialog(
                    prefillAmount = plan.normalAmount,
                    forceTotalBudget = plan.categoryId == null,
                    preselectCategoryId = plan.categoryId
                )
                dialog.dismiss()
            }
            container.addView(row)
        }
        dialog.setContentView(dialogView)
        dialog.show()
    }

    private fun buildSuggestionRow(
        plan: BudgetService.BudgetSuggestionPlan,
        onApply: () -> Unit
    ): View {
        val context = this
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_stats_metric_tile)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val name = plan.categoryName ?: getString(R.string.budget_monthly_total)
        row.addView(TextView(context).apply {
            text = name
            setTextColor(Color.parseColor("#111827"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(context).apply {
            text = getString(
                R.string.budget_suggestion_amounts_fmt,
                plan.conservativeAmount,
                plan.normalAmount,
                plan.looseAmount
            )
            setTextColor(Color.parseColor("#374151"))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })
        row.addView(TextView(context).apply {
            text = getString(
                R.string.budget_suggestion_reason_fmt,
                plan.historyAverage,
                plan.activeMonths,
                plan.reason
            )
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(MaterialButton(context).apply {
            text = getString(R.string.budget_suggestion_apply_normal)
            textSize = 13f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_primary))
            cornerRadius = dp(10)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(10) }
            setOnClickListener { onApply() }
        })
        return row
    }

    private fun analyzeBudgetWithAi() {
        if (!Prefs.isAiConfigured(this)) {
            Toast.makeText(this, getString(R.string.budget_ai_no_key), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@BudgetManageActivity, getString(R.string.budget_ai_loading), Toast.LENGTH_SHORT).show()
            val result = runCatching {
                val prompt = withContext(Dispatchers.IO) { buildBudgetAiPrompt() }
                withContext(Dispatchers.IO) { AIService.simpleChat(this@BudgetManageActivity, prompt) }
            }
            result.onSuccess { content ->
                showAiBudgetResult(content)
            }.onFailure { error ->
                Toast.makeText(
                    this@BudgetManageActivity,
                    getString(R.string.budget_ai_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun buildBudgetAiPrompt(): String {
        val budgets = budgetService.getMonthBudgetsWithProgress(currentBook, yearMonth)
        val suggestions = budgetService.suggestUnbudgetedCategoryPlans(currentBook, yearMonth, limit = 3)
        val budgetLines = budgets.joinToString("\n") { item ->
            val name = item.budget.categoryName ?: "总预算"
            val progress = item.progress
            "- $name: budget=${item.budget.amount}, used=${progress.usedAmount}, remaining=${progress.remaining}, percent=${"%.2f".format(Locale.US, progress.percent)}, timeProgress=${"%.2f".format(Locale.US, progress.timeProgress)}, remainingDays=${progress.remainingDays}, dailyAllowance=${"%.2f".format(Locale.US, progress.dailyRemainingAllowance)}, status=${progress.status}, pace=${progress.pace}, reason=${progress.riskReason}"
        }
        val suggestionLines = suggestions.joinToString("\n") { plan ->
            "- ${plan.categoryName}: normal=${plan.normalAmount}, average=${"%.2f".format(Locale.US, plan.historyAverage)}, activeMonths=${plan.activeMonths}"
        }
        return """
你是记账 App 里的预算分析助手。只基于下面的本地聚合预算数据分析，不要假设原始账单细节。
目标：解释当前预算节奏，给出可执行建议。语气简短、具体、像产品内提示，不要聊天寒暄，不要说“作为 AI”。
必须引用下面数据中的事实，例如剩余金额、剩余天数、每日可用、超支分类或消费节奏。

只输出一个 JSON 对象，不要 markdown，不要额外解释。字段：
{
  "summary": "不超过 36 个中文字符的一句话结论",
  "risk_level": "normal|warning|exceeded",
  "category_insights": ["每条不超过 42 个中文字符，最多 3 条"],
  "daily_suggestion": "不超过 48 个中文字符的每日行动建议",
  "budget_drafts": [{"category_name":"分类名或总预算", "amount": 1234, "reason":"不超过 28 个中文字符"}]
}
budget_drafts 最多 3 条，只在数据支持时给出。

月份：$yearMonth
账本：${currentBook.ifBlank { "全部账本" }}
预算进度：
$budgetLines

未设置预算但支出较高的本地建议：
${suggestionLines.ifBlank { "无" }}
""".trimIndent()
    }

    private fun showAiBudgetResult(rawContent: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_budget_ai_result, null)
        val dialog = BottomSheetDialog(this)
        val parsed = parseBudgetAiResult(rawContent)
        val tvRisk = dialogView.findViewById<TextView>(R.id.tv_budget_ai_risk)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tv_budget_ai_summary)
        val tvDaily = dialogView.findViewById<TextView>(R.id.tv_budget_ai_daily)
        val insights = dialogView.findViewById<LinearLayout>(R.id.layout_budget_ai_insights)
        val drafts = dialogView.findViewById<LinearLayout>(R.id.layout_budget_ai_drafts)

        tvRisk.text = when (parsed.riskLevel) {
            "exceeded" -> getString(R.string.budget_ai_risk_exceeded)
            "warning" -> getString(R.string.budget_ai_risk_warning)
            else -> getString(R.string.budget_ai_risk_normal)
        }
        tvRisk.setTextColor(
            when (parsed.riskLevel) {
                "exceeded" -> Color.parseColor("#E35D5D")
                "warning" -> Color.parseColor("#E58A00")
                else -> Color.parseColor("#2E9D55")
            }
        )
        tvSummary.text = parsed.summary
        tvDaily.text = parsed.dailySuggestion
        if (parsed.insights.isNotEmpty()) {
            addSectionTitle(insights, getString(R.string.budget_ai_section_insights))
            parsed.insights.forEach { addBullet(insights, it) }
        }
        if (parsed.drafts.isNotEmpty()) {
            addSectionTitle(drafts, getString(R.string.budget_ai_section_drafts))
            parsed.drafts.forEach { draft ->
                addBullet(drafts, "${draft.name} ¥${String.format(Locale.getDefault(), "%.0f", draft.amount)} · ${draft.reason}")
            }
        }
        dialogView.findViewById<View>(R.id.btn_budget_ai_close).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(dialogView)
        dialog.show()
    }

    private data class BudgetAiDraft(val name: String, val amount: Double, val reason: String)

    private data class BudgetAiUiResult(
        val summary: String,
        val riskLevel: String,
        val dailySuggestion: String,
        val insights: List<String>,
        val drafts: List<BudgetAiDraft>
    )

    private fun parseBudgetAiResult(rawContent: String): BudgetAiUiResult {
        val jsonText = extractJsonObject(rawContent)
        if (jsonText == null) {
            return BudgetAiUiResult(
                summary = getString(R.string.budget_ai_unstructured),
                riskLevel = "warning",
                dailySuggestion = rawContent.take(240),
                insights = emptyList(),
                drafts = emptyList()
            )
        }
        return runCatching {
            val root = org.json.JSONObject(jsonText)
            val insights = root.optJSONArray("category_insights")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            val drafts = root.optJSONArray("budget_drafts")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val amount = item.optDouble("amount", 0.0)
                    if (amount <= 0.0) return@mapNotNull null
                    BudgetAiDraft(
                        name = item.optString("category_name", getString(R.string.budget_monthly_total))
                            .ifBlank { getString(R.string.budget_monthly_total) },
                        amount = amount,
                        reason = item.optString("reason", "").ifBlank { "参考当前预算节奏" }
                    )
                }
            }.orEmpty()
            BudgetAiUiResult(
                summary = root.optString("summary", "").ifBlank { "AI 已完成预算分析" },
                riskLevel = root.optString("risk_level", "normal"),
                dailySuggestion = root.optString("daily_suggestion", "").ifBlank { "按当前每日可用金额控制接下来支出。" },
                insights = insights,
                drafts = drafts
            )
        }.getOrElse {
            BudgetAiUiResult(
                summary = getString(R.string.budget_ai_unstructured),
                riskLevel = "warning",
                dailySuggestion = rawContent.take(240),
                insights = emptyList(),
                drafts = emptyList()
            )
        }
    }

    private fun extractJsonObject(rawContent: String): String? {
        val cleaned = rawContent
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    private fun addSectionTitle(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#111827"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun addBullet(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = "· $text"
            setTextColor(Color.parseColor("#4B5563"))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        })
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showAddBudgetDialog(
        prefillAmount: Double? = null,
        forceTotalBudget: Boolean = false,
        preselectCategoryId: Long? = null
    ) {
        lifecycleScope.launch {
            val (categories, sharedMembers) = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                val categories = CategoryRepository(db.categoryDao()).getCategoriesListByType(0)
                categories to loadSharedBudgetMembers(db)
            }
            val options = BudgetCategoryOptions.build(
                totalBudgetLabel = getString(R.string.budget_monthly_total),
                categories = categories
            )
            val labels = options.map { it.label }
            val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
            val spinner = dialogView.findViewById<Spinner>(R.id.spinner_budget_category)
            val etAmount = dialogView.findViewById<EditText>(R.id.et_budget_amount)
            if (prefillAmount != null) {
                etAmount.setText(String.format(Locale.getDefault(), "%.0f", prefillAmount))
            }
            spinner.adapter = ArrayAdapter(
                this@BudgetManageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            if (preselectCategoryId != null) {
                val index = options.indexOfFirst { it.categoryId == preselectCategoryId }
                if (index >= 0) spinner.setSelection(index)
            } else if (!forceTotalBudget && totalBudget != null) {
                val index = options.indexOfFirst { it.categoryId != null }
                if (index >= 0) spinner.setSelection(index)
            }
            spinner.isEnabled = !forceTotalBudget
            dialogView.findViewById<TextView>(R.id.tv_budget_dialog_title).text = getString(R.string.budget_set)
            if (forceTotalBudget && prefillAmount != null) {
                Toast.makeText(
                    this@BudgetManageActivity,
                    getString(R.string.budget_suggest_apply_fmt, prefillAmount ?: 0.0),
                    Toast.LENGTH_SHORT
                ).show()
            }
            setupMemberBudgetSplit(dialogView, sharedMembers, existingAllocations = null)
            fun updateSplitVisibility() {
                val selected = options.getOrNull(spinner.selectedItemPosition)
                dialogView.findViewById<View>(R.id.layout_member_budget_split).visibility =
                    if (sharedMembers.size >= 2 && selected?.categoryId == null) View.VISIBLE else View.GONE
            }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateSplitVisibility()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    updateSplitVisibility()
                }
            }
            updateSplitVisibility()

            val dialog = BottomSheetDialog(this@BudgetManageActivity)
            dialog.setContentView(dialogView)
            dialogView.findViewById<View>(R.id.btn_budget_cancel).setOnClickListener { dialog.dismiss() }
            val saveButton = dialogView.findViewById<View>(R.id.btn_budget_save)
            saveButton.setOnClickListener {
                    val amount = etAmount.text.toString().toDoubleOrNull()
                    if (amount == null || amount <= 0) {
                        Toast.makeText(this@BudgetManageActivity, getString(R.string.budget_amount_invalid), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    saveButton.isEnabled = false
                    val selectedIndex = spinner.selectedItemPosition
                    val selectedOption = options.getOrElse(selectedIndex) { options.first() }
                    val categoryId = selectedOption.categoryId
                    val categoryName = selectedOption.label.takeIf { categoryId != null }
                    val memberAllocations = try {
                        if (categoryId == null) readMemberBudgetAllocations(dialogView, sharedMembers, amount) else null
                    } catch (_: IllegalArgumentException) {
                        Toast.makeText(
                            this@BudgetManageActivity,
                            getString(R.string.budget_member_split_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                        saveButton.isEnabled = true
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                                val now = System.currentTimeMillis()
                                SharedBudgetHooks.save(db,
                                    Budget(
                                        bookId = db.bookDao().resolveOrCreateId(currentBook),
                                        bookName = currentBook,
                                        categoryId = categoryId,
                                        categoryName = categoryName,
                                        yearMonth = yearMonth,
                                        amount = amount,
                                        createdAt = now,
                                        updatedAt = now,
                                        memberBudgetAllocations = memberAllocations
                                    )
                                )
                            }
                            loadBudgets()
                            dialog.dismiss()
                        } catch (_: Exception) {
                            Toast.makeText(
                                this@BudgetManageActivity,
                                getString(R.string.save_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            if (dialog.isShowing) saveButton.isEnabled = true
                        }
                    }
                }
            dialog.show()
        }
    }

    private fun showEditDialog(budget: Budget) {
        lifecycleScope.launch {
            val sharedMembers = withContext(Dispatchers.IO) {
                loadSharedBudgetMembers(AppDatabase.getDatabase(this@BudgetManageActivity))
            }
            val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
            val spinner = dialogView.findViewById<Spinner>(R.id.spinner_budget_category)
            val etAmount = dialogView.findViewById<EditText>(R.id.et_budget_amount)
            val label = budget.categoryName ?: getString(R.string.budget_monthly_total)
            dialogView.findViewById<TextView>(R.id.tv_budget_dialog_title).text = getString(R.string.budget_edit)
            etAmount.setText(editableAmount(budget.amount))
            spinner.adapter = ArrayAdapter(
                this@BudgetManageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(label)
            )
            spinner.isEnabled = false
            setupMemberBudgetSplit(dialogView, sharedMembers, budget.memberBudgetAllocations)
            dialogView.findViewById<View>(R.id.layout_member_budget_split).visibility =
                if (budget.categoryId == null && sharedMembers.size >= 2) View.VISIBLE else View.GONE

            val dialog = BottomSheetDialog(this@BudgetManageActivity)
            dialog.setContentView(dialogView)
            dialogView.findViewById<View>(R.id.btn_budget_cancel).setOnClickListener { dialog.dismiss() }
            val saveButton = dialogView.findViewById<View>(R.id.btn_budget_save)
            saveButton.setOnClickListener {
                val newAmount = etAmount.text.toString().toDoubleOrNull()
                if (newAmount == null || newAmount <= 0) {
                    Toast.makeText(this@BudgetManageActivity, getString(R.string.budget_amount_invalid), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val memberAllocations = try {
                    if (budget.categoryId == null) readMemberBudgetAllocations(dialogView, sharedMembers, newAmount)
                    else null
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(
                        this@BudgetManageActivity,
                        getString(R.string.budget_member_split_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                            SharedBudgetHooks.save(db, budget.copy(
                                amount = newAmount,
                                updatedAt = System.currentTimeMillis(),
                                memberBudgetAllocations = memberAllocations
                            ))
                        }
                        loadBudgets()
                        dialog.dismiss()
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@BudgetManageActivity,
                            getString(R.string.save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        if (dialog.isShowing) saveButton.isEnabled = true
                    }
                }
            }
            dialog.show()
        }
    }

    private suspend fun loadSharedBudgetMembers(db: AppDatabase): List<SharedMember> {
        if (currentBook.isBlank()) return emptyList()
        val ledger = db.sharedLedgerDao().getByBookName(currentBook) ?: return emptyList()
        return db.sharedMemberDao().getByLedgerId(ledger.id)
            .filter { it.displayName.isNotBlank() }
            .sortedBy { it.joinOrder }
    }

    private fun setupMemberBudgetSplit(
        dialogView: View,
        members: List<SharedMember>,
        existingAllocations: String?
    ) {
        val container = dialogView.findViewById<LinearLayout>(R.id.layout_budget_member_rows)
        container.removeAllViews()
        if (members.size < 2) return
        val allocations = MemberBudgetAllocation.decode(existingAllocations)
        members.forEachIndexed { index, member ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (index > 0) setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = member.resolvedName()
                setTextColor(Color.parseColor("#4B5563"))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(EditText(this).apply {
                tag = memberBudgetInputTag(member.memberId)
                layoutParams = LinearLayout.LayoutParams(dp(132), dp(44))
                setBackgroundColor(Color.WHITE)
                hint = getString(R.string.budget_member_amount_hint)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(Color.parseColor("#111827"))
                textSize = 15f
                allocations[member.memberId]?.let { setText(editableAmount(it)) }
            })
            container.addView(row)
        }
    }

    private fun readMemberBudgetAllocations(
        dialogView: View,
        members: List<SharedMember>,
        totalBudget: Double
    ): String? {
        if (members.size < 2) return null
        val container = dialogView.findViewById<LinearLayout>(R.id.layout_budget_member_rows)
        val memberAmounts = members.associateTo(linkedMapOf()) { member ->
            val raw = container.findViewWithTag<EditText>(memberBudgetInputTag(member.memberId))
                ?.text?.toString()?.trim().orEmpty()
            member.memberId to if (raw.isEmpty()) null else (raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("invalid member budget"))
        }
        return MemberBudgetAllocation.encode(
            MemberBudgetAllocation.complete(
                totalBudget = totalBudget,
                memberAmounts = memberAmounts
            )
        )
    }

    private fun memberBudgetInputTag(memberId: String): String = "member-budget:$memberId"

    private fun editableAmount(amount: Double): String =
        if (amount % 1.0 == 0.0) String.format(Locale.US, "%.0f", amount)
        else String.format(Locale.US, "%.2f", amount)

    private fun showDeleteDialog(budget: Budget) {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle(getString(R.string.budget_delete))
            .setMessage(getString(R.string.budget_delete_confirm, budget.categoryName ?: getString(R.string.budget_monthly_total)))
            .setPositiveButton(getString(R.string.rule_learn_confirm)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                        SharedBudgetHooks.delete(db, budget)
                    }
                    loadBudgets()
                }
            }
            .setNegativeButton(getString(R.string.rule_learn_cancel), null)
            .show()
    }
}
