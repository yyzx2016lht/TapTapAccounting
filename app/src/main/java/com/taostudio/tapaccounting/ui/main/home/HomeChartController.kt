package com.taostudio.tapaccounting.ui.main.home

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal class HomeChartController(
    private val fragment: Fragment,
    private val barChart: BarChart,
    private val tvChartTotal: TextView,
    private val tvChartTitle: TextView,
    private val tvMonthExpense: TextView,
    private val tvMonthIncome: TextView,
    private val tvMonthBalance: TextView,
    private val homeAdapter: HomeAdapter,
    private val homeViewModel: HomeViewModel,
    private val getCurrentType: () -> Int,
    private val setCurrentType: (Int) -> Unit,
    private val getCurrentTimeRange: () -> Int,
    private val setCurrentTimeRange: (Int) -> Unit,
    private val getIsChartHidden: () -> Boolean,
    private val setIsChartHidden: (Boolean) -> Unit,
    private val setChartAllowedByState: (Boolean) -> Unit,
    private val getSelectedYear: () -> Int,
    private val getSelectedMonth: () -> Int,
    private val getRoundedBarChartRenderer: () -> RoundedBarChartRenderer?,
    private val setRoundedBarChartRenderer: (RoundedBarChartRenderer?) -> Unit,
    private val dfChartKey: SimpleDateFormat,
    private val dfWeekday: SimpleDateFormat,
    private val dfDay: SimpleDateFormat,
) {
    fun setupChart() {
        barChart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setScaleEnabled(false)
            isDoubleTapToZoomEnabled = false
            setTouchEnabled(false)
            isHighlightPerTapEnabled = false
            isHighlightPerDragEnabled = false
            setNoDataText("暂无图表数据")
            setNoDataTextColor(Color.parseColor("#9AA0A6"))

            axisLeft.axisMinimum = 0f
            axisLeft.setDrawGridLines(false)
            axisLeft.setDrawLabels(false)
            axisLeft.setDrawAxisLine(false)
            axisRight.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.setDrawAxisLine(false)
            xAxis.granularity = 1f

            legend.isEnabled = false
            val renderer = RoundedBarChartRenderer(this, animator, viewPortHandler)
            setRoundedBarChartRenderer(renderer)
            setRenderer(renderer)
        }
    }

    fun updateChartTitleLabel() {
        val typeStr = when (getCurrentType()) {
            2 -> "收支"
            1 -> "收入"
            else -> "支出"
        }
        val rangeStr = when (getCurrentTimeRange()) {
            0 -> "最近7日"
            1 -> "最近15日"
            2 -> "本周"
            else -> ""
        }
        tvChartTitle.text = "$rangeStr$typeStr"
    }

    fun syncTrendCardState(): Boolean {
        val showByGlobalSwitch = Prefs.isShowHomeTrendCard(fragment.requireContext())
        val isCurrentMonth = getSelectedYear() == Calendar.getInstance().get(Calendar.YEAR) &&
            getSelectedMonth() == (Calendar.getInstance().get(Calendar.MONTH) + 1)
        setIsChartHidden(!showByGlobalSwitch)

        val shouldShow = showByGlobalSwitch && isCurrentMonth
        setChartAllowedByState(shouldShow)
        val changed = homeAdapter.showChart != shouldShow
        homeAdapter.showChart = shouldShow
        return changed
    }

    fun refreshTrendCardVisibility(forceResubmit: Boolean = false) {
        val changed = syncTrendCardState()
        if (forceResubmit || changed) {
            homeAdapter.submitList(homeViewModel.uiState.value.monthlyBills)
        }
    }

    fun showChartSettingsDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(fragment.requireContext())
        val view = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.layout_chart_settings_bottom_sheet, null)
        dialog.setContentView(view)

        var tempType = getCurrentType()
        if (tempType == 1) tempType = 2
        var tempRange = getCurrentTimeRange()
        var tempHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())

        val tvTypeExpense = view.findViewById<TextView>(R.id.tvTypeExpense)
        val tvTypeBoth = view.findViewById<TextView>(R.id.tvTypeBoth)
        val tvRangeWeek = view.findViewById<TextView>(R.id.tvRangeWeek)
        val tvRange7d = view.findViewById<TextView>(R.id.tvRange7d)
        val tvRange15d = view.findViewById<TextView>(R.id.tvRange15d)
        val tvRangeHide = view.findViewById<TextView>(R.id.tvRangeHide)
        val btnConfirm = view.findViewById<TextView>(R.id.btnConfirmSettings)

        fun updateTypeBg() {
            tvTypeExpense.setBackgroundResource(if (tempType == 0) R.drawable.bg_segmented_selected else 0)
            tvTypeExpense.elevation = 0f
            tvTypeBoth.setBackgroundResource(if (tempType == 2) R.drawable.bg_segmented_selected else 0)
            tvTypeBoth.elevation = 0f
        }

        fun updateRangeBg() {
            tvRangeWeek.setBackgroundResource(if (!tempHidden && tempRange == 2) R.drawable.bg_segmented_selected else 0)
            tvRangeWeek.elevation = 0f
            tvRange7d.setBackgroundResource(if (!tempHidden && tempRange == 0) R.drawable.bg_segmented_selected else 0)
            tvRange7d.elevation = 0f
            tvRange15d.setBackgroundResource(if (!tempHidden && tempRange == 1) R.drawable.bg_segmented_selected else 0)
            tvRange15d.elevation = 0f
            tvRangeHide.setBackgroundResource(if (tempHidden) R.drawable.bg_segmented_selected else 0)
            tvRangeHide.elevation = 0f
        }

        tvTypeExpense.setOnClickListener { tempType = 0; updateTypeBg() }
        tvTypeBoth.setOnClickListener { tempType = 2; updateTypeBg() }
        tvRangeWeek.setOnClickListener { tempRange = 2; tempHidden = false; updateRangeBg() }
        tvRange7d.setOnClickListener { tempRange = 0; tempHidden = false; updateRangeBg() }
        tvRange15d.setOnClickListener { tempRange = 1; tempHidden = false; updateRangeBg() }
        tvRangeHide.setOnClickListener { tempHidden = true; updateRangeBg() }

        updateTypeBg()
        updateRangeBg()

        btnConfirm.setOnClickListener {
            setCurrentType(tempType)
            setCurrentTimeRange(tempRange)
            setIsChartHidden(tempHidden)
            Prefs.setShowHomeTrendCard(fragment.requireContext(), !tempHidden)
            updateChartTitleLabel()
            refreshTrendCardVisibility(forceResubmit = true)
            homeViewModel.setChartSettings(getCurrentTimeRange(), getCurrentType(), getIsChartHidden())
            dialog.dismiss()
        }

        dialog.show()
    }

    fun updateSummary(
        transactions: List<Bill>,
        incomeOverride: Double? = null,
        balanceOverride: Double? = null
    ) {
        var expense = 0.0
        var income = 0.0

        transactions.forEach {
            if (it.subType == Bill.SUBTYPE_REFUND) return@forEach
            val amountCny = it.amount * it.exchangeRate
            if (it.type == Bill.TYPE_EXPENSE) expense += amountCny
            else if (it.type == Bill.TYPE_INCOME) income += amountCny
        }

        tvMonthExpense.text = buildHeadlineAmount(expense)
        tvMonthIncome.text = "¥${AmountFormatHelper.formatAmount(incomeOverride ?: income)}"
        tvMonthBalance.text = "¥${AmountFormatHelper.formatAmount(balanceOverride ?: (income - expense))}"
    }

    private fun buildHeadlineAmount(amount: Double): SpannableString {
        val raw = "¥${AmountFormatHelper.formatAmount(amount)}"
        val symbolEnd = raw.indexOfFirst { it.isDigit() || it == ',' || it == '.' }
            .coerceAtLeast(1)
        return SpannableString(raw).apply {
            setSpan(RelativeSizeSpan(0.6f), 0, symbolEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 去掉紧凑金额的尾随 ".0"，如 "6.0" -> "6"、"2.0K" -> "2K"。 */
    private fun trimTrailingZero(s: String): String =
        if (s.endsWith(".0")) s.dropLast(2) else s

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

        if (rangeOpt == 2) {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        return cal.timeInMillis
    }

    fun updateChart(transactions: List<Bill>) {
        val chartT0 = System.currentTimeMillis()
        val currentTimeRange = getCurrentTimeRange()
        val currentType = getCurrentType()
        val startDateMs = getStartTimeFromRange(currentTimeRange)
        val endDateMs = getEndTimeFromRange(currentTimeRange)

        val dates = mutableListOf<String>()
        val displayLabels = mutableListOf<String>()

        val currCal = Calendar.getInstance().apply { timeInMillis = startDateMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endDateMs }

        while (currCal.timeInMillis <= endCal.timeInMillis) {
            val dStr = dfChartKey.format(currCal.time)
            if (!dates.contains(dStr)) {
                dates.add(dStr)
                val label = when (currentTimeRange) {
                    1 -> dfDay.format(currCal.time)
                    0, 2 -> dfWeekday.format(currCal.time)
                    else -> dfDay.format(currCal.time)
                }
                displayLabels.add(label)
            }
            currCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val expenseMap = mutableMapOf<String, Float>()
        val incomeMap = mutableMapOf<String, Float>()

        var totalExpense = 0f
        var totalIncome = 0f

        for (t in transactions) {
            if (t.subType == Bill.SUBTYPE_REFUND) continue
            val dStr = dfChartKey.format(Date(t.time))
            val amount = (t.amount * t.exchangeRate).toFloat()
            if (t.type == Bill.TYPE_EXPENSE) {
                expenseMap[dStr] = (expenseMap[dStr] ?: 0f) + amount
                totalExpense += amount
            } else if (t.type == Bill.TYPE_INCOME) {
                incomeMap[dStr] = (incomeMap[dStr] ?: 0f) + amount
                totalIncome += amount
            }
        }

        tvChartTotal.text = when (currentType) {
            2 -> "收入: ${String.format(Locale.getDefault(), "%,.2f", totalIncome)}, 支出: ${String.format(Locale.getDefault(), "%,.2f", totalExpense)}"
            1 -> "总计: ${String.format(Locale.getDefault(), "%,.2f", totalIncome)}"
            else -> "总计: ${String.format(Locale.getDefault(), "%,.2f", totalExpense)}"
        }

        val expenseEntries = mutableListOf<BarEntry>()
        val incomeEntries = mutableListOf<BarEntry>()
        dates.forEachIndexed { index, dStr ->
            expenseEntries.add(BarEntry(index.toFloat(), expenseMap[dStr] ?: 0f))
            incomeEntries.add(BarEntry(index.toFloat(), incomeMap[dStr] ?: 0f))
        }

        barChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx in displayLabels.indices) displayLabels[idx] else ""
            }
        }
        barChart.xAxis.setLabelCount(displayLabels.size, false)
        barChart.xAxis.textColor = fragment.requireContext().getColor(android.R.color.darker_gray)

        val dataSets = mutableListOf<BarDataSet>()
        val formatterK = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                if (value == 0f) return ""
                if (value >= 1000) {
                    val k = value / 1000f
                    val s = String.format(Locale.getDefault(), "%.2fK", k)
                    return if (s.endsWith("0K")) s.replace(".00K", "K").replace("0K", "K") else s
                }
                val s = String.format(Locale.getDefault(), "%.2f", value)
                return if (s.endsWith(".00")) s.replace(".00", "")
                else if (s.endsWith("0")) s.substring(0, s.length - 1)
                else s
            }
        }
        // 密集柱（最近15日）专用金额格式：标签最多 4 个字符（如 "85"、"1.2K"、"99K"），
        // 让 15 根柱子的柱顶金额互不压盖且清晰可读。
        val formatterCompact = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                if (value <= 0f) return ""
                return when {
                    value < 10f -> trimTrailingZero(String.format(Locale.getDefault(), "%.1f", value))
                    value < 995f -> String.format(Locale.getDefault(), "%.0f", value)
                    value < 9_950f -> trimTrailingZero(String.format(Locale.getDefault(), "%.1fK", value / 1000f))
                    value < 995_000f -> String.format(Locale.getDefault(), "%.0fK", value / 1000f)
                    else -> trimTrailingZero(String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f))
                }
            }
        }
        val isDenseRange = currentTimeRange == 1
        // 15日柱子密集（每柱槽位约20dp）：金额用紧凑格式+更小字号，由渲染器做防重叠绘制；
        // 7日/本周维持原有 K 格式与 11f 字号不变。
        val chartValueFormatter = if (isDenseRange) formatterCompact else formatterK
        val chartValueTextSize = if (isDenseRange) 9f else 11f
        val shouldDrawValues = currentType != 2

        if (currentType == 0 || currentType == 2) {
            dataSets.add(
                BarDataSet(expenseEntries, "支出").apply {
                    color = Color.parseColor("#FF5252")
                    setDrawValues(shouldDrawValues)
                    valueTextSize = chartValueTextSize
                    valueTextColor = Color.parseColor("#FF5252")
                    valueFormatter = chartValueFormatter
                }
            )
        }
        if (currentType == 1 || currentType == 2) {
            dataSets.add(
                BarDataSet(incomeEntries, "收入").apply {
                    color = Color.parseColor("#4CAF50")
                    setDrawValues(shouldDrawValues)
                    valueTextSize = chartValueTextSize
                    valueTextColor = Color.parseColor("#4CAF50")
                    valueFormatter = chartValueFormatter
                }
            )
        }

        val barData = BarData(dataSets.toList() as List<com.github.mikephil.charting.interfaces.datasets.IBarDataSet>)
        barChart.data = barData
        getRoundedBarChartRenderer()?.let {
            it.fullRound = (currentTimeRange == 0 || currentTimeRange == 2)
            it.denseValueLabels = isDenseRange
        }

        if (currentType == 2) {
            val groupSpace: Float
            val barSpace: Float
            val barWidth: Float

            if (currentTimeRange == 0 || currentTimeRange == 2) {
                groupSpace = 0.2f
                barSpace = 0.25f
                barWidth = 0.15f
            } else {
                groupSpace = 0.2f
                barSpace = 0.2f
                barWidth = 0.2f
            }

            barData.barWidth = barWidth
            barChart.xAxis.setCenterAxisLabels(true)
            barChart.groupBars(0f, groupSpace, barSpace)
            barChart.xAxis.axisMinimum = 0f
            barChart.xAxis.axisMaximum = dates.size.toFloat()
        } else {
            barData.barWidth = if (currentTimeRange == 0 || currentTimeRange == 2) 0.28f else 0.30f
            barChart.xAxis.setCenterAxisLabels(false)
            barChart.xAxis.axisMinimum = -0.5f
            barChart.xAxis.axisMaximum = dates.size - 0.5f
        }

        barChart.notifyDataSetChanged()
        barChart.invalidate()
        Log.d("HomePerf", "updateChart done  [${System.currentTimeMillis() - chartT0}ms on main thread]")
    }
}

