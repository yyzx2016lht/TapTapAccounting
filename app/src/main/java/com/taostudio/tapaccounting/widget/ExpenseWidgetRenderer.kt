package com.taostudio.tapaccounting.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BudgetService
import java.text.SimpleDateFormat
import java.util.Locale

/** 读取真实金额并按固定样式渲染；样式不再由可变字段组合驱动。 */
object ExpenseWidgetRenderer {

    private const val DECIMAL_RELATIVE_SIZE = 0.75f
    private const val LABEL_SP = 8f
    private const val FALLBACK_WIDGET_WIDTH_DP = 110
    /** launcher 回报宽度常偏乐观，预留一点边距避免裁切。 */
    private const val WIDTH_SAFETY = 0.90f

    data class Snapshot(
        val bookLabel: String,
        val periodLabel: String,
        val expense: Double,
        val budget: Double?,
        val remaining: Double?,
        val budgetPercent: Int?
    )

    private fun daoBookName(bookName: String): String {
        val normalized = BookAccountManager.normalizeBookName(bookName)
        return if (normalized == BookAccountManager.ALL_BOOK) "" else normalized
    }

    suspend fun buildSnapshot(context: Context, config: WidgetConfig, size: WidgetSize): Snapshot {
        val now = System.currentTimeMillis()
        val db = AppDatabase.getDatabase(context)
        val daoBook = daoBookName(config.bookName)
        val expensePeriod = when (size) {
            WidgetSize.STANDARD -> null
            WidgetSize.TODAY_BUDGET -> WidgetPeriod.TODAY
            WidgetSize.COMPACT, WidgetSize.DETAILED -> config.period
        }
        val expense = expensePeriod?.let {
            val (periodStart, periodEnd) = it.range(now)
            db.billDao().sumBudgetExpense(periodStart, periodEnd, daoBook)
        } ?: 0.0

        // 纯支出样式不查询预算；其余样式按当前自然月读取总预算。
        val budgetEntity = if (size == WidgetSize.COMPACT) {
            null
        } else {
            val yearMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(now)
            db.budgetDao().getTotalBudget(yearMonth, daoBook)
        }
        val budgetProgress = budgetEntity?.let {
            BudgetService(db.budgetDao(), db.billDao(), db.categoryDao()).getBudgetProgress(it)
        }

        return Snapshot(
            bookLabel = BookAccountManager.normalizeBookName(config.bookName),
            periodLabel = config.period.label(),
            expense = expense,
            budget = budgetEntity?.amount,
            remaining = budgetProgress?.remaining,
            budgetPercent = budgetProgress?.let { (it.percent * 100).toInt().coerceAtLeast(0) }
        )
    }

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        size: WidgetSize,
        snapshot: Snapshot
    ) {
        val layoutRes = when (size) {
            WidgetSize.COMPACT -> R.layout.widget_expense_compact
            WidgetSize.STANDARD -> R.layout.widget_expense_standard
            WidgetSize.TODAY_BUDGET -> R.layout.widget_expense_today_budget
            WidgetSize.DETAILED -> R.layout.widget_expense_detailed
        }
        val views = RemoteViews(context.packageName, layoutRes)
        // 预算 / 今日预算卡去掉「账本 · 周期」行，把高度留给金额
        if (size == WidgetSize.COMPACT || size == WidgetSize.DETAILED) {
            views.setTextViewText(R.id.tv_widget_subtitle, "${snapshot.bookLabel} · ${snapshot.periodLabel}")
        }

        val widgetWidthDp = widgetWidthDp(appWidgetManager, appWidgetId)
        when (size) {
            WidgetSize.COMPACT -> renderExpenseOnly(context, views, snapshot, widgetWidthDp)
            WidgetSize.STANDARD -> renderBudgetSummary(context, views, snapshot, widgetWidthDp)
            WidgetSize.TODAY_BUDGET -> renderTodayBudget(context, views, snapshot, widgetWidthDp)
            WidgetSize.DETAILED -> renderOverview(context, views, snapshot, widgetWidthDp)
        }

        val openApp = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun renderExpenseOnly(
        context: Context,
        views: RemoteViews,
        snapshot: Snapshot,
        widgetWidthDp: Int
    ) {
        val amount = money(snapshot.expense)
        views.setTextViewText(R.id.tv_widget_primary_label, "${snapshot.periodLabel}支出")
        // compact: 左右 padding 12dp + 左侧色条 3dp + 间距 9dp
        fitAmount(context, views, R.id.tv_widget_primary_value, amount, 21f, 10f, widgetWidthDp, 36f)
    }

    private fun renderBudgetSummary(
        context: Context,
        views: RemoteViews,
        snapshot: Snapshot,
        widgetWidthDp: Int
    ) {
        val secondaryLabel = "本月预算"
        views.setTextViewText(R.id.tv_widget_primary_label, "预算剩余")
        views.setTextViewText(R.id.tv_widget_secondary_label, secondaryLabel)

        if (snapshot.budget == null || snapshot.remaining == null) {
            views.setTextViewText(R.id.tv_widget_primary_value, "未设置")
            views.setTextViewText(R.id.tv_widget_secondary_value, "—")
            views.setViewVisibility(R.id.progress_widget_budget, View.GONE)
            return
        }

        val remaining = money(snapshot.remaining)
        val budget = money(snapshot.budget)
        // 主金额独占整行，只扣左右 padding 10*2
        fitAmount(context, views, R.id.tv_widget_primary_value, remaining, 16f, 9f, widgetWidthDp, 24f)
        fitAmount(
            context, views, R.id.tv_widget_secondary_value, budget, 11f, 7f, widgetWidthDp,
            30f + measureLabelDp(context, secondaryLabel)
        )
        views.setTextColor(
            R.id.tv_widget_primary_value,
            context.getColor(if (snapshot.remaining < 0) R.color.widget_danger else R.color.widget_accent)
        )
        views.setViewVisibility(R.id.progress_widget_budget, View.VISIBLE)
        views.setProgressBar(
            R.id.progress_widget_budget,
            100,
            (snapshot.budgetPercent ?: 0).coerceAtMost(100),
            false
        )
    }

    private fun renderTodayBudget(
        context: Context,
        views: RemoteViews,
        snapshot: Snapshot,
        widgetWidthDp: Int
    ) {
        val secondaryLabel = "预算剩余"
        val expense = money(snapshot.expense)
        views.setTextViewText(R.id.tv_widget_primary_label, "今日花费")
        views.setTextViewText(R.id.tv_widget_secondary_label, secondaryLabel)
        fitAmount(context, views, R.id.tv_widget_primary_value, expense, 16f, 9f, widgetWidthDp, 24f)

        if (snapshot.remaining == null) {
            views.setTextViewText(R.id.tv_widget_secondary_value, "未设置")
            views.setViewVisibility(R.id.progress_widget_budget, View.GONE)
            return
        }

        val remaining = money(snapshot.remaining)
        fitAmount(
            context, views, R.id.tv_widget_secondary_value, remaining, 11f, 7f, widgetWidthDp,
            30f + measureLabelDp(context, secondaryLabel)
        )
        views.setTextColor(
            R.id.tv_widget_secondary_value,
            context.getColor(if (snapshot.remaining < 0) R.color.widget_danger else R.color.widget_text_primary)
        )
        views.setViewVisibility(R.id.progress_widget_budget, View.VISIBLE)
        views.setProgressBar(
            R.id.progress_widget_budget,
            100,
            (snapshot.budgetPercent ?: 0).coerceAtMost(100),
            false
        )
    }

    private fun renderOverview(
        context: Context,
        views: RemoteViews,
        snapshot: Snapshot,
        widgetWidthDp: Int
    ) {
        val expense = money(snapshot.expense)
        views.setTextViewText(R.id.tv_widget_primary_label, "${snapshot.periodLabel}支出")
        fitAmount(context, views, R.id.tv_widget_primary_value, expense, 21f, 11f, widgetWidthDp, 28f)

        if (snapshot.budget == null || snapshot.remaining == null) {
            views.setTextViewText(R.id.tv_widget_secondary_label, "本月预算")
            views.setTextViewText(R.id.tv_widget_secondary_value, "未设置")
            views.setTextViewText(R.id.tv_widget_tertiary_label, "预算剩余")
            views.setTextViewText(R.id.tv_widget_tertiary_value, "—")
            views.setTextViewText(R.id.tv_widget_status, "打开应用设置本月预算")
            views.setViewVisibility(R.id.progress_widget_budget, View.GONE)
        } else {
            val budget = money(snapshot.budget)
            val remaining = money(snapshot.remaining)
            views.setTextViewText(R.id.tv_widget_secondary_label, "本月预算")
            views.setTextViewText(R.id.tv_widget_tertiary_label, "预算剩余")
            val halfColumnOverhead = 28f + 25f + (widgetWidthDp - 28f - 25f) / 2f
            fitAmount(context, views, R.id.tv_widget_secondary_value, budget, 15f, 9f, widgetWidthDp, halfColumnOverhead)
            fitAmount(context, views, R.id.tv_widget_tertiary_value, remaining, 15f, 9f, widgetWidthDp, halfColumnOverhead)
            views.setTextColor(
                R.id.tv_widget_tertiary_value,
                context.getColor(if (snapshot.remaining < 0) R.color.widget_danger else R.color.widget_text_primary)
            )
            val percent = snapshot.budgetPercent ?: 0
            views.setTextViewText(R.id.tv_widget_status, if (percent > 100) "已超预算 · ${percent}%" else "本月预算已用 ${percent}%")
            views.setViewVisibility(R.id.progress_widget_budget, View.VISIBLE)
            views.setProgressBar(R.id.progress_widget_budget, 100, percent.coerceAtMost(100), false)
        }
    }

    /**
     * 金额用 dp 而非 sp，避免系统字体放大把 2×1 挤爆。
     * 按可用宽度 Paint 二分；小数 RelativeSizeSpan 再省一点横向空间。
     */
    private fun fitAmount(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        text: String,
        maxDp: Float,
        minDp: Float,
        widgetWidthDp: Int,
        overheadDp: Float
    ) {
        views.setTextViewText(viewId, styledMoney(text))

        val density = context.resources.displayMetrics.density
        val availablePx = ((widgetWidthDp - overheadDp) * density * WIDTH_SAFETY)
            .coerceAtLeast(40f * density)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

        var low = minDp
        var high = maxDp
        var best = minDp
        repeat(12) {
            val mid = (low + high) / 2f
            paint.textSize = mid * density
            if (measureStyledMoneyWidth(paint, text) <= availablePx) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_DIP, best)
    }

    private fun styledMoney(text: String): CharSequence {
        val dot = text.lastIndexOf('.')
        if (dot < 0 || dot >= text.length - 1) return text
        return SpannableString(text).apply {
            setSpan(
                RelativeSizeSpan(DECIMAL_RELATIVE_SIZE),
                dot,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun measureStyledMoneyWidth(paint: Paint, text: String): Float {
        val dot = text.lastIndexOf('.')
        if (dot < 0 || dot >= text.length - 1) return paint.measureText(text)
        val integerPart = paint.measureText(text, 0, dot)
        val decimalPart = paint.measureText(text, dot, text.length) * DECIMAL_RELATIVE_SIZE
        return integerPart + decimalPart
    }

    private fun measureLabelDp(context: Context, label: String): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_SP * context.resources.displayMetrics.scaledDensity
        }
        return paint.measureText(label) / context.resources.displayMetrics.density
    }

    private fun widgetWidthDp(appWidgetManager: AppWidgetManager, appWidgetId: Int): Int {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        // 用较小边保证 2×1 一定塞得下；部分 launcher 的 max 会虚高
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        return when {
            minWidth > 0 && maxWidth > 0 -> minOf(minWidth, maxWidth)
            minWidth > 0 -> minWidth
            maxWidth > 0 -> maxWidth
            else -> FALLBACK_WIDGET_WIDTH_DP
        }
    }

    private fun money(amount: Double): String = AmountFormatHelper.formatCurrency("¥", amount)
}
