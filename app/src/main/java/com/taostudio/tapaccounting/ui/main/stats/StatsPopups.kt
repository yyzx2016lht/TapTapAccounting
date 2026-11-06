package com.taostudio.tapaccounting.ui.main.stats

import com.taostudio.tapaccounting.BillColors
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.billdetail.BillDetailDataLoader
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetActions
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetBinder
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class SubCategoryBottomSheet(
    private val title: String,
    private val isExpense: Boolean,
    private val subStats: Map<String, Double>,
    private val totalAmount: Double,
    private val colors: List<Int>,
    private val currencySymbol: String,
    private val onSubCategoryClick: (String) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var rvSub: RecyclerView
    private lateinit var categoryAdapter: CategoryStatsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.bottom_sheet_category_report, container, false)
        root.findViewById<TextView>(R.id.tv_sheet_title).text = title

        // 先构建并排序 categoryStats
        val categoryStats = subStats.map { (name, amount) ->
            CategoryStat(
                categoryName = name,
                amount = amount,
                percentage = if (totalAmount > 0) (amount / totalAmount * 100).toFloat() else 0f,
                amountDiffFromLastPeriod = 0.0
            )
        }.sortedByDescending { it.amount }

        // 缓存排序后的类目列表，用于饼图与列表颜色对齐
        val sortedCategoryNames = categoryStats.map { it.categoryName }

        val pieChart = root.findViewById<PieChart>(R.id.pie_chart_sub)

        rvSub = root.findViewById(R.id.rv_sub_categories)
        rvSub.layoutManager = LinearLayoutManager(context)
        categoryAdapter = CategoryStatsAdapter(colors, categoryStats, isExpense, currencySymbol) {
            onSubCategoryClick(it)
        }
        rvSub.adapter = categoryAdapter

        setupPieChart(pieChart)
        updatePieChart(pieChart, sortedCategoryNames)
        return root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).toInt()
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = maxHeight
            skipCollapsed = true
            isFitToContents = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.post {
            val contentHeight = (sheet as? ViewGroup)?.getChildAt(0)?.measuredHeight
                ?: sheet.measuredHeight
            val desiredHeight = minOf(contentHeight, maxHeight).coerceAtLeast(1)
            sheet.layoutParams = sheet.layoutParams.apply {
                height = desiredHeight
            }
            sheet.requestLayout()
        }
    }

    private fun setupPieChart(pieChart: PieChart) {
        pieChart.description.isEnabled = false
        pieChart.legend.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setTransparentCircleAlpha(0)
        pieChart.holeRadius = 56f
        pieChart.rotationAngle = 270f
        pieChart.isRotationEnabled = true
        pieChart.setDrawCenterText(true)
        pieChart.setUsePercentValues(true)
        pieChart.setEntryLabelColor(Color.TRANSPARENT)
        pieChart.setExtraOffsets(22f, 12f, 22f, 12f)
        pieChart.setNoDataText("暂无图表数据")
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
        pieChart.useSmallSliceLabelRenderer()
        pieChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val label = (e as? PieEntry)?.label ?: return
                categoryAdapter.pinCategory(label)
                (rvSub.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                rvSub.post { rvSub.smoothScrollToPosition(0) }
            }

            override fun onNothingSelected() {
                categoryAdapter.clearPinCategory()
            }
        })
    }

    private fun updatePieChart(pieChart: PieChart, sortedCategoryNames: List<String>) {
        // 同步颜色到列表，使图表和列表颜色一致
        val colorByName = sortedCategoryNames.mapIndexed { index, name ->
            name to colors[index % colors.size]
        }.toMap()
        categoryAdapter.setColorMap(colorByName)

        val chartNames = sortedCategoryNames.filter { (subStats[it] ?: 0.0) > 0.0 }
        if (chartNames.isEmpty() || totalAmount <= 0.0) {
            pieChart.clear()
            pieChart.invalidate()
            return
        }

        // build entries in the same order as the list
        val entries = chartNames.map { name ->
            val amount = subStats[name] ?: 0.0
            PieEntry(amount.toFloat(), name)
        }
        val dataSet = SmallSliceLabelPieDataSet(entries, totalAmount)
        val labeledCount = entries.count { dataSet.shouldShowLabel(it) }

        val sliceColors = chartNames.map { colorByName[it] ?: colors[0] }
        dataSet.colors = sliceColors
        dataSet.xValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
        dataSet.yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
        dataSet.valueLinePart1OffsetPercentage = 100f
        dataSet.valueLinePart1Length = if (labeledCount >= 10) 0.22f else 0.30f
        dataSet.valueLinePart2Length = if (labeledCount >= 10) 0.55f else 0.78f
        dataSet.selectionShift = 4f
        dataSet.setValueLineVariableLength(true)
        dataSet.setUsingSliceColorAsValueLineColor(true)

        dataSet.valueTextSize = when {
            labeledCount >= 12 -> 7.5f
            labeledCount >= 9 -> 8.0f
            else -> 9.0f
        }
        dataSet.setValueTextColors(sliceColors)
        
        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = ""
            override fun getPieLabel(value: Float, pieEntry: PieEntry): String {
                if (!dataSet.shouldShowLabel(pieEntry)) return ""
                val pct = if (totalAmount > 0) (pieEntry.value / totalAmount * 100f) else 0f
                return "${pieEntry.label} ${String.format(Locale.getDefault(), "%.1f%%", pct)}"
            }
        }

        pieChart.data = PieData(dataSet)
        pieChart.setDrawEntryLabels(false)
        val centerSubtitle = if (isExpense) "子分类支出" else "子分类收入"
        pieChart.centerText = "${String.format(Locale.getDefault(), "%s%.2f", currencySymbol, totalAmount)}\n$centerSubtitle"
        pieChart.rotationAngle = findBestInitialRotation(entries.map { it.value })
        pieChart.setCenterTextSize(12f)
        pieChart.setCenterTextColor(Color.parseColor("#374151"))
        pieChart.animateY(260)
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
}

class BillListBottomSheet(
    private val title: String,
    initialBills: List<Bill>
) : BottomSheetDialogFragment() {
    companion object {
        private const val DEFAULT_VISIBLE_BILL_COUNT = 5
    }

    private val dfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var newestFirst = true
    private var bills: List<Bill> = initialBills
    private var displayBills: List<Bill> = emptyList()
    private var hasResumedOnce = false

    private lateinit var rootView: View
    private lateinit var rvBills: RecyclerView
    private lateinit var tvSheetTitle: TextView
    private lateinit var btnSortTime: TextView
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private var sheetCallback: BottomSheetBehavior.BottomSheetCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.bottom_sheet_bill_list, container, false)
        rootView = root
        tvSheetTitle = root.findViewById(R.id.tv_sheet_title)
        btnSortTime = root.findViewById(R.id.btn_sheet_sort_time)
        rvBills = root.findViewById(R.id.rv_sheet_bills)

        rvBills.layoutManager = LinearLayoutManager(context)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_home_transaction, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val bill = displayBills[position]
                bindBillItem(holder.itemView, bill)
                holder.itemView.setOnClickListener {
                    if (!isAdded || parentFragmentManager.isStateSaved) return@setOnClickListener
                    if (Prefs.isIndependentDetailEnabled(requireActivity()) && bill.id > 0L) {
                        com.taostudio.tapaccounting.ui.activity.BillDetailActivity.start(requireActivity(), bill.id)
                    } else {
                        BillDetailBottomSheet(bill)
                            .show(parentFragmentManager, "bill_detail_${bill.id}_${System.currentTimeMillis()}")
                    }
                }
            }

            override fun getItemCount(): Int = displayBills.size
        }
        rvBills.adapter = adapter

        btnSortTime.setOnClickListener {
            newestFirst = !newestFirst
            applySort()
        }

        applySort()
        return root
    }

    override fun onResume() {
        super.onResume()
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = bills.map { it.id }.filter { it > 0L }
            bills = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) emptyList()
                else AppDatabase.getDatabase(appContext).billDao().getBillsByIds(ids)
            }
            applySort()
        }
    }

    override fun onStart() {
        super.onStart()
        val bsDialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet =
            bsDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)

        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        behavior.isFitToContents = false
        behavior.isHideable = true
        behavior.isDraggable = true
        behavior.skipCollapsed = false

        rootView.post {
            if (!isAdded) return@post
            
            // 让 rootView 撑满整个 BottomSheet 容器，这样 RecyclerView 凭借 weight=1 会自动填满屏幕，
            // 上滑时就能直接看到已经渲染好的内容，而不需要等待状态变为 EXPANDED 才去修改高度。
            rootView.layoutParams = rootView.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            
            val targetSheetHeight = computeTargetSheetHeight()
            behavior.peekHeight = targetSheetHeight
            behavior.expandedOffset = 0
            sheetCallback?.let(behavior::removeBottomSheetCallback)
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun applySort() {
        displayBills = if (newestFirst) {
            bills.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
        } else {
            bills.sortedWith(compareBy<Bill> { it.time }.thenBy { it.id })
        }
        tvSheetTitle.text = "$title (${displayBills.size}条)"
        btnSortTime.text = if (newestFirst) "时间 ↓" else "时间 ↑"
        adapter.notifyDataSetChanged()
    }

    private fun computeSheetChromeHeight(): Int {
        val recyclerTop = rvBills.top.takeIf { it > 0 } ?: 0
        return recyclerTop
    }

    private fun computeTargetSheetHeight(): Int {
        val chromeHeight = computeSheetChromeHeight()
        val listHeight = computeListHeight(DEFAULT_VISIBLE_BILL_COUNT) // 刚好显示 5 条
        val targetHeight = chromeHeight + listHeight

        val screenHeight = resources.displayMetrics.heightPixels

        return targetHeight.coerceAtMost((screenHeight * 0.9f).toInt())
    }

    private fun computeListHeight(itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val visibleCount = itemCount.coerceAtMost(displayBills.size)
        if (visibleCount <= 0) return rvBills.paddingTop

        // 逐条测量前 N 条真实高度（而非用单条高度乘法），
        // 避免字体缩放/文案长度差异导致第 6 条露出一截。
        val measuredContentHeight = (0 until visibleCount).sumOf { position ->
            measureBillItemHeight(position)
        }
        return measuredContentHeight + rvBills.paddingTop
    }

    private fun measureBillItemHeight(position: Int): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            rvBills.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels,
            View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val holder = adapter.createViewHolder(rvBills, 0)
        if (displayBills.isNotEmpty() && position in displayBills.indices) {
            adapter.bindViewHolder(holder, position)
        }
        holder.itemView.measure(widthSpec, heightSpec)
        return holder.itemView.measuredHeight
            .takeIf { it > 0 }
            ?: (72 * resources.displayMetrics.density).toInt()
    }

    private fun bindBillItem(itemView: View, bill: Bill) {
        val tvCategory = itemView.findViewById<TextView>(R.id.tv_bill_category)
        val tvAmount = itemView.findViewById<TextView>(R.id.tv_bill_amount)
        val tvDetail = itemView.findViewById<TextView>(R.id.tv_bill_detail)
        val tvTime = itemView.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = itemView.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = itemView.findViewById<View>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val showCategoryIcon = Prefs.isShowBillCategoryIcon(requireContext())
        val showFullCategory = Prefs.isShowBillFullCategory(requireContext())
        val remarkPriority = Prefs.isBillRemarkPriority(requireContext())
        val baseCategoryName = stripRefundPrefix(bill.categoryName)
        val symbol = CurrencyManager.getSymbol(bill.currency)

        itemView.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer.setBackgroundResource(R.drawable.bg_circle_soft)
        tvCategory.setTextColor(if (isRefund) Color.parseColor("#8E98A3") else Color.parseColor("#333333"))
        tvDetail.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

        val categoryText = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { "未分类" }
        }
        val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
            categoryText = categoryText,
            remarkText = bill.remark,
            suffixText = dfDate.format(Date(bill.time)),
            remarkPriority = remarkPriority
        )
        tvCategory.text = primaryText

        val refundAmount = BillDisplayFormatter.refundAmountOfExpenseBill(bill)
        tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val sign = when {
                isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        }
        tvAmount.setTextColor(
            when {
                isRefund -> Color.parseColor("#9AA1AA")
                bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                bill.type == Bill.TYPE_INCOME -> BillColors.income
                else -> Color.parseColor("#757575")
            }
        )

        tvDetail.text = secondaryText.ifBlank { dfDate.format(Date(bill.time)) }
        tvDetail.visibility = View.VISIBLE
        tvTime.visibility = View.GONE

        if (!showCategoryIcon) {
            iconContainer.setBackgroundColor(Color.TRANSPARENT)
            iconContainer.layoutParams = iconContainer.layoutParams.apply {
                val widthPx = (itemView.resources.displayMetrics.density * 10).toInt()
                val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                width = widthPx
                height = heightPx
            }
            ivIcon.clearColorFilter()
            ivIcon.layoutParams = ivIcon.layoutParams.apply {
                val px = (ivIcon.resources.displayMetrics.density * 6).toInt()
                width = px
                height = px
            }
            ivIcon.setImageResource(
                when (bill.type) {
                    Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                    Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                    else -> R.drawable.bg_bill_dot_neutral
                }
            )
        } else {
            iconContainer.layoutParams = iconContainer.layoutParams.apply {
                val widthPx = (itemView.resources.displayMetrics.density * 44).toInt()
                val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                width = widthPx
                height = heightPx
            }
            val iconLookupName = if (isRefund) baseCategoryName else bill.categoryName
            val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
            val iconTint = when {
                isRefund -> Color.parseColor("#8E98A3")
                bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                bill.type == Bill.TYPE_INCOME -> BillColors.income
                else -> Color.parseColor("#9E9E9E")
            }
            ivIcon.layoutParams = ivIcon.layoutParams.apply {
                val px = (ivIcon.resources.displayMetrics.density * 21).toInt()
                width = px
                height = px
            }
            ivIcon.setImageResource(R.mipmap.ic_launcher)
            ivIcon.setColorFilter(iconTint)
            CoroutineScope(Dispatchers.IO).launch {
                val iconUrl = CategoryIconHelper.findCategoryIcon(requireContext(), iconLookupName, iconLookupType)
                withContext(Dispatchers.Main) {
                    if (iconUrl.isNotEmpty()) {
                        Glide.with(this@BillListBottomSheet)
                            .load(iconUrl)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .into(ivIcon)
                    }
                }
            }
        }
    }

    private fun stripRefundPrefix(categoryName: String): String {
        val prefix = "退款："
        return if (categoryName.startsWith(prefix)) categoryName.removePrefix(prefix).trim() else categoryName.trim()
    }
}

class BillDetailBottomSheet(
    private val bill: Bill
) : BottomSheetDialogFragment() {

    /**
     * 统计页只读弹层：保留 BottomSheetDialogFragment 外壳（生命周期不动），
     * 视图绑定统一交给 [BillDetailSheetBinder]，只留「编辑」可用。
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.layout_bill_detail_bottom_sheet, container, false)
        val request = BillDetailSheetRequest(bill = bill, readOnly = true)
        val loader = BillDetailDataLoader(requireContext())
        val scope = viewLifecycleOwner.lifecycleScope
        val actions = object : BillDetailSheetActions(
            context = requireContext(),
            scope = scope
        ) {
            override fun onEdit(bill: Bill) {
                val hostActivity = activity ?: return
                val intent = Intent(hostActivity, EditBillActivity::class.java).apply {
                    putExtra("BILL_ID", bill.id)
                }
                hostActivity.startActivity(intent)
                dismissAllowingStateLoss()
            }
        }
        actions.attach(dismiss = { dismissAllowingStateLoss() }, recyclerView = null)

        BillDetailSheetBinder.bind(requireContext(), root, loader.initialState(request), actions, scope)
        scope.launch {
            val model = loader.load(request)
            BillDetailSheetBinder.bind(requireContext(), root, model, actions, scope)
        }
        return root
    }
}


