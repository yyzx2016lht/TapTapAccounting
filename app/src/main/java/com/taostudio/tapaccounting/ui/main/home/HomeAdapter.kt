package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BillColors
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.CategoryIcons
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.TextIconRenderer
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.CategoryIconUrlCache
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.common.UiMotion
import com.taostudio.tapaccounting.ui.common.RowModeMotion
import com.taostudio.tapaccounting.ui.common.UiMotion.applyItemPressFeedback
import com.taostudio.tapaccounting.viewscope.SharedBookMemberContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class DisplayBill(
        val bill: Bill,
        val isDeprecated: Boolean = false
    )

    // 图标查询放在 IO 线程，避免主线程阻塞。
    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // 列表分组构建放 Default 线程，提交用 notifyDataSetChanged（与资产详情同款），不走 DiffUtil。
    private val buildScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var submitJob: Job? = null
    private var submitGeneration: Long = 0L
    private val itemTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** 列表数据已落到 Adapter 后回调（账本切换 reveal / 首次 stagger）。 */
    var onListDispatched: (() -> Unit)? = null

    companion object {
        const val TYPE_CHART = 2
        const val TYPE_EMPTY = 3
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        const val PAYLOAD_MODE_CHANGE = "PAYLOAD_MODE_CHANGE"
        const val PAYLOAD_SELECTION_CHANGE = "PAYLOAD_SELECTION_CHANGE"
        const val PAYLOAD_HEADER_SELECTION_CHANGE = "PAYLOAD_HEADER_SELECTION_CHANGE"
        private const val COLOR_TEXT_REFUND = "#8E98A3"
        private const val COLOR_TEXT_DETAIL_REFUND = "#A1A8AF"
        private const val COLOR_TEXT_NORMAL = "#333333"
        private const val COLOR_TEXT_DETAIL_NORMAL = "#999999"
        private const val COLOR_AMOUNT_REFUND = "#9AA1AA"
        private const val COLOR_AMOUNT_OTHER = "#757575"
        private const val COLOR_ICON_OTHER = "#9E9E9E"
    }

    private val textColorRefund = Color.parseColor(COLOR_TEXT_REFUND)
    private val textColorDetailRefund = Color.parseColor(COLOR_TEXT_DETAIL_REFUND)
    private val textColorNormal = Color.parseColor(COLOR_TEXT_NORMAL)
    private val textColorDetailNormal = Color.parseColor(COLOR_TEXT_DETAIL_NORMAL)
    private val amountColorExpense = BillColors.expense
    private val amountColorIncome = BillColors.income
    private val amountColorRefund = Color.parseColor(COLOR_AMOUNT_REFUND)
    private val amountColorOther = Color.parseColor(COLOR_AMOUNT_OTHER)
    private val iconColorOther = Color.parseColor(COLOR_ICON_OTHER)

    sealed class ListItem {
        // 用于承载首页图表占位项。
        object Chart : ListItem()

        // 空账单提示紧跟趋势卡展示。
        object Empty : ListItem()

        data class Header(
            val dateStr: String,
            val weekdayStr: String,
            val income: Double,
            val expense: Double,
            val rawDateKey: String
        ) : ListItem()

        data class Item(val displayBill: DisplayBill) : ListItem()
    }

    val items = mutableListOf<ListItem>()
    private val rawBills = mutableListOf<DisplayBill>()

    // 外部注入的图表视图，作为列表的第 0 项展示。
    var chartView: android.view.View? = null
    var emptyView: android.view.View? = null
    // 控制是否在列表顶部显示图表项。
    var showChart: Boolean = false
    var showEmptyState: Boolean = false
    private var lastSubmittedShowChart: Boolean = false
    private var lastSubmittedShowEmptyState: Boolean = false

    var isMultiSelectMode: Boolean = false
    val selectedBills = mutableSetOf<Bill>()

    var onBillItemClick: ((Bill) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null
    var detailSuffixProvider: ((Bill) -> String?)? = null
    private var memberContexts: Map<String, SharedBookMemberContext> = emptyMap()
    private var showMemberLabels: Boolean = true
    private var showBookNamesWithMembers: Boolean = false

    fun setViewContext(
        contextsByBookName: Map<String, SharedBookMemberContext>,
        showMembers: Boolean,
        showBookNames: Boolean
    ) {
        val normalized = contextsByBookName.mapKeys { BookAccountManager.normalizeBookName(it.key) }
        if (
            memberContexts == normalized &&
            showMemberLabels == showMembers &&
            showBookNamesWithMembers == showBookNames
        ) return
        memberContexts = normalized
        showMemberLabels = showMembers
        showBookNamesWithMembers = showBookNames
        notifyItemRangeChanged(0, itemCount)
    }
    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    /** 共享账本里别人创建的账单不可编辑/删除，也不可多选。 */
    fun isOwnedByLocal(bill: Bill): Boolean {
        if (!bill.isShared) return true
        val ctx = memberContexts[BookAccountManager.normalizeBookName(bill.bookName)] ?: return true
        return bill.memberId != null && bill.memberId == ctx.localMemberId
    }

    private fun isSelectable(bill: Bill, isDeprecated: Boolean): Boolean =
        !isDeprecated && isOwnedByLocal(bill)

    private fun refundAmountOfExpenseBill(bill: Bill): Double {
        return BillDisplayFormatter.refundAmountOfExpenseBill(bill)
    }

    private fun stripRefundPrefix(categoryName: String): String {
        return BillDisplayFormatter.stripRefundPrefix(categoryName)
    }

    private fun buildIconCacheKey(categoryName: String, type: Int): String =
        CategoryIconUrlCache.key(categoryName, type)

    private fun getCachedIconUrl(key: String): String? = CategoryIconUrlCache.get(key)

    private fun putCachedIconUrl(key: String, url: String) {
        CategoryIconUrlCache.put(key, url)
    }

    /**
     * 预热「分类名+类型 → 图标 URL」缓存。
     *
     * 绑定阶段命中缓存后只需要发一次 Glide 请求；不预热的话，刚进入列表时
     * 每行都要先异步查库解析再加载，整列图标会有一段可见的加载空窗。
     * 解析规则与 [ItemViewHolder.bind] 一致（含共享账单的快照兜底）。
     */
    private fun preloadIconUrls(bills: List<DisplayBill>) {
        if (bills.isEmpty()) return
        adapterScope.launch {
            val revision = CategoryIcons.revision
            val seen = mutableSetOf<String>()
            for (displayBill in bills) {
                if (revision != CategoryIcons.revision) return@launch  // 预热期间分类变了：结果已过期
                val bill = displayBill.bill
                val isRefund = isRefundBill(bill)
                val lookupName = if (isRefund) stripRefundPrefix(bill.categoryName) else bill.categoryName
                val lookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                val key = buildIconCacheKey(lookupName, lookupType)
                if (!seen.add(key)) continue
                if (getCachedIconUrl(key) != null) continue
                var iconUrl = CategoryIconHelper.findCategoryIcon(TapApplication.app(), lookupName, lookupType)
                val snapshot = bill.cateIcon
                if (!snapshot.isNullOrBlank() &&
                    !CategoryIcons.isAutoAssigned(snapshot) &&
                    (iconUrl.isEmpty() || TextIconRenderer.isTextIcon(iconUrl))
                ) {
                    iconUrl = snapshot
                }
                putCachedIconUrl(key, iconUrl)
            }
        }
    }

    fun submitList(newBills: List<Bill>, deprecatedBills: List<Bill> = emptyList()) {
        val activeIds = newBills.map { it.id }.toSet()
        val combinedBills = buildList {
            addAll(newBills.map { DisplayBill(it, isDeprecated = false) })
            addAll(
                deprecatedBills
                    .filter { it.id !in activeIds }
                    .map { DisplayBill(it, isDeprecated = true) }
            )
        }.sortedByDescending { it.bill.time }
        // 数据和展示状态都没变时，直接跳过，避免无效刷新。
        if (rawBills == combinedBills &&
            lastSubmittedShowChart == showChart &&
            lastSubmittedShowEmptyState == showEmptyState
        ) {
            Log.d("HomePerf", "submitList: skip (list unchanged)")
            onListDispatched?.invoke()
            return
        }

        // 预热图标缓存：绑定阶段直接命中，避免刚进入时整列图标逐个异步解析
        preloadIconUrls(combinedBills)

        submitJob?.cancel()
        val generation = ++submitGeneration
        // 固定当前提交的图表开关，避免异步阶段读取到后续变化。
        val includeChart = showChart
        val includeEmptyState = showEmptyState
        val oldSize = items.size

        submitJob = buildScope.launch {
            val newItems = buildHomeListItems(
                combinedBills = combinedBills,
                includeChart = includeChart,
                includeEmptyState = includeEmptyState
            )

            // 与资产详情 TransactionAdapter.submitList 同款：整表 notifyDataSetChanged，
            // 不走 DiffUtil insert/remove，从根上避开 CoordinatorLayout 下的 attach 闪退。
            withContext(Dispatchers.Main) {
                if (generation != submitGeneration) {
                    Log.d("HomePerf", "submitList: drop stale generation=$generation latest=$submitGeneration")
                    return@withContext
                }
                val t0 = System.currentTimeMillis()
                rawBills.clear()
                rawBills.addAll(combinedBills)
                lastSubmittedShowChart = includeChart
                lastSubmittedShowEmptyState = includeEmptyState
                items.clear()
                items.addAll(newItems)

                val copy = selectedBills.toList()
                selectedBills.clear()
                selectedBills.addAll(copy.filter { selected ->
                    rawBills.any { !it.isDeprecated && it.bill == selected }
                })

                notifyDataSetChanged()
                Log.d(
                    "HomePerf",
                    "notifyDataSetChanged: oldSize=$oldSize newSize=${newItems.size}  [${System.currentTimeMillis() - t0}ms on main]"
                )
                onListDispatched?.invoke()
            }
        }
    }

    private fun buildHomeListItems(
        combinedBills: List<DisplayBill>,
        includeChart: Boolean,
        includeEmptyState: Boolean
    ): List<ListItem> {
        val dfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dfDisplay = SimpleDateFormat("MM.dd", Locale.getDefault())
        val dfWeekday = SimpleDateFormat("E", Locale.CHINESE)
        val newItems = mutableListOf<ListItem>()
        // 趋势卡在前，空账单提示在后；二者可同时存在。
        resolveHomeLeadingItems(
            showChart = includeChart,
            showEmptyState = includeEmptyState
        ).forEach { item ->
            newItems.add(
                when (item) {
                    HomeLeadingItem.TREND_CARD -> ListItem.Chart
                    HomeLeadingItem.EMPTY_STATE -> ListItem.Empty
                }
            )
        }
        if (combinedBills.isEmpty()) return newItems

        val grouped = combinedBills.groupBy { dfKey.format(Date(it.bill.time)) }
        for ((dateKey, billsInDay) in grouped) {
            var dailyIncome = 0.0
            var dailyExpense = 0.0
            billsInDay.forEach {
                if (it.isDeprecated) return@forEach
                val amountCny = it.bill.amount * it.bill.exchangeRate
                if (isRefundBill(it.bill)) {
                    dailyIncome += amountCny
                } else if (it.bill.type == Bill.TYPE_EXPENSE) {
                    dailyExpense += amountCny
                } else if (it.bill.type == Bill.TYPE_INCOME) {
                    dailyIncome += amountCny
                }
            }
            val sampleDate = Date(billsInDay.first().bill.time)
            newItems.add(
                ListItem.Header(
                    dateStr = dfDisplay.format(sampleDate),
                    weekdayStr = dfWeekday.format(sampleDate),
                    income = dailyIncome,
                    expense = dailyExpense,
                    rawDateKey = dateKey
                )
            )
            billsInDay.forEach { displayBill -> newItems.add(ListItem.Item(displayBill)) }
        }
        return newItems
    }

    /**
     * 长按进入多选：先把账单并入选中，再统一刷一次界面，最后才回调。
     * 与资产详情页的进入顺序一致（先选中 → 再通知），不再出现「已选 0 笔」触发一次、
     * 「已选 1 笔」再触发一次把底部操作栏动画打断重启的情况。
     */
    private fun enterMultiSelect(bills: List<Bill>) {
        isMultiSelectMode = true
        selectedBills.clear()
        selectedBills.addAll(bills)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
        onSelectionChanged?.invoke(selectedBills.size)
    }

    fun clearSelection() {
        selectedBills.clear()
        isMultiSelectMode = false
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
        onSelectionChanged?.invoke(0)
    }

    /** 勾选框/行内容的联动动画统一实现在 [RowModeMotion]（资产详情页共用同一套）。 */
    private fun applyRowMode(
        checkBox: CheckBox,
        show: Boolean,
        animate: Boolean,
        shownAlpha: Float = 1f,
        spacer: View? = null,
        afterShown: (() -> Unit)? = null
    ) = RowModeMotion.apply(
        checkBox = checkBox,
        show = show,
        animate = animate,
        isModeActive = { isMultiSelectMode },
        shownAlpha = shownAlpha,
        spacer = spacer,
        afterShown = afterShown
    )

    private fun resetRowMotion(row: View, checkBox: CheckBox, spacer: View? = null) =
        RowModeMotion.reset(row, checkBox, spacer)

    fun deselectAll() {
        selectedBills.clear()
        isMultiSelectMode = true
        onSelectionChanged?.invoke(0)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    fun selectAll() {
        isMultiSelectMode = true
        selectedBills.clear()
        selectedBills.addAll(
            rawBills.filterNot { it.isDeprecated }
                .map { it.bill }
                .filter { isOwnedByLocal(it) }
        )
        onSelectionChanged?.invoke(selectedBills.size)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    fun getSelectedBills(): List<Bill> = selectedBills.toList()

    private fun selectableBillsForHeader(headerPosition: Int): List<Bill> {
        return items.asSequence()
            .drop(headerPosition + 1)
            .takeWhile { it !is ListItem.Header }
            .mapNotNull { item ->
                val displayBill = (item as? ListItem.Item)?.displayBill ?: return@mapNotNull null
                if (displayBill.isDeprecated) null
                else displayBill.bill.takeIf { isOwnedByLocal(it) }
            }
            .toList()
    }

    private fun headerSelectionState(headerPosition: Int): Pair<Boolean, Boolean> {
        val bills = selectableBillsForHeader(headerPosition)
        if (bills.isEmpty()) return false to false
        val selectedCount = bills.count { selectedBills.contains(it) }
        return (selectedCount == bills.size) to (selectedCount in 1 until bills.size)
    }

    private fun updateHeaderSelectionNear(position: Int) {
        val headerPosition = (position downTo 0).firstOrNull { items.getOrNull(it) is ListItem.Header } ?: return
        notifyItemChanged(headerPosition, PAYLOAD_HEADER_SELECTION_CHANGE)
    }

    private fun setDaySelection(headerPosition: Int, checked: Boolean) {
        val bills = selectableBillsForHeader(headerPosition)
        if (bills.isEmpty()) return
        if (checked) {
            selectedBills.addAll(bills)
        } else {
            selectedBills.removeAll(bills.toSet())
        }
        isMultiSelectMode = true
        onSelectionChanged?.invoke(selectedBills.size)
        val nextHeader = ((headerPosition + 1) until items.size).firstOrNull { items[it] is ListItem.Header } ?: items.size
        notifyItemRangeChanged(headerPosition, nextHeader - headerPosition, PAYLOAD_SELECTION_CHANGE)
        if (!isMultiSelectMode) notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Chart -> TYPE_CHART
            is ListItem.Empty -> TYPE_EMPTY
            is ListItem.Header -> TYPE_HEADER
            is ListItem.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHART -> {
                // 图表项使用独立容器，便于承载外部传入的 view。
                val container = android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                ChartViewHolder(container)
            }
            TYPE_EMPTY -> {
                val container = android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                EmptyViewHolder(container)
            }
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_bill_header, parent, false))
            else -> ItemViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = items[position]
            if (holder is ItemViewHolder && item is ListItem.Item) {
                val bill = item.displayBill.bill
                when {
                    payloads.contains(PAYLOAD_MODE_CHANGE) -> holder.updateMode(selectedBills.contains(bill), animate = true)
                    payloads.contains(PAYLOAD_SELECTION_CHANGE) -> holder.updateSelection(selectedBills.contains(bill))
                }
            } else if (holder is HeaderViewHolder && item is ListItem.Header) {
                when {
                    payloads.contains(PAYLOAD_MODE_CHANGE) -> holder.updateMode(position, animate = true)
                    payloads.contains(PAYLOAD_SELECTION_CHANGE) || payloads.contains(PAYLOAD_HEADER_SELECTION_CHANGE) -> holder.updateSelection(position)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Chart -> (holder as ChartViewHolder).bind(chartView)
            is ListItem.Empty -> (holder as EmptyViewHolder).bind(emptyView)
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item, position)
            is ListItem.Item -> {
                // 行动画（删除折叠 / 撤销展开）靠这个 tag 定位被操作的行。
                holder.itemView.setTag(R.id.tag_bill_row_id, item.displayBill.bill.id)
                (holder as ItemViewHolder).bind(item.displayBill, position)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ItemViewHolder) {
            // 回收时取消图标任务并清除图片，避免错位和资源泄漏。
            holder.iconJob?.cancel()
            holder.iconJob = null
            Glide.with(holder.ivIcon.context).clear(holder.ivIcon)
            holder.boundIconUrl = null
            // 入场动画 / 多选勾选动画直接往行及其子视图上写 translation/alpha/scale，
            // RecyclerView 回收时不会重置它们。行若在半路被回收再复用给别的位置，
            // 上一次的偏移会跟着走，所以回收点统一清干净（含勾选框与行内容位移）。
            holder.resetRowMotionOnRecycle()
        } else if (holder is HeaderViewHolder) {
            holder.resetRowMotionOnRecycle()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is ItemViewHolder) {
            holder.itemView.applyItemPressFeedback()
        }
    }

    // 只负责承载外部传入的图表 view，不参与图表内部状态管理。
    inner class ChartViewHolder(val container: android.widget.FrameLayout) : RecyclerView.ViewHolder(container) {
        fun bind(view: android.view.View?) {
            container.removeAllViews()
            if (view != null) {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                container.addView(view)
            }
        }
    }

    inner class EmptyViewHolder(val container: android.widget.FrameLayout) : RecyclerView.ViewHolder(container) {
        fun bind(view: android.view.View?) {
            container.removeAllViews()
            if (view != null) {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                container.addView(view)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        submitJob?.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun resolveGroupBackground(
        isGroupStart: Boolean,
        isGroupEnd: Boolean,
        hasHeaderAbove: Boolean
    ): Int {
        if (hasHeaderAbove) {
            return if (isGroupEnd) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_middle
        }
        return when {
            isGroupStart && isGroupEnd -> R.drawable.bg_bill_group_single
            isGroupStart -> R.drawable.bg_bill_group_top
            isGroupEnd -> R.drawable.bg_bill_group_bottom
            else -> R.drawable.bg_bill_group_middle
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cbSelectDay: CheckBox = itemView.findViewById(R.id.cb_select_day)
        /** 勾选框占位：宽度随多选 0 ↔ 38dp 动画，带动日期文字平移；勾选框本身是覆盖层，不影响行高。 */
        private val spHeaderCheck: View = itemView.findViewById(R.id.sp_header_check)
        private val tvDate: TextView = itemView.findViewById(R.id.tv_header_date)
        private val tvSummary: TextView? = itemView.findViewById(
            itemView.context.resources.getIdentifier("tv_header_summary", "id", itemView.context.packageName)
        )

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || !isMultiSelectMode) return@setOnClickListener
                val (allSelected, _) = headerSelectionState(pos)
                setDaySelection(pos, checked = !allSelected)
            }
        }

        fun bind(header: ListItem.Header, position: Int) {
            tvDate.text = "${header.dateStr} ${header.weekdayStr}"

            val summaryBuilder = StringBuilder()
            if (header.income > 0) {
                summaryBuilder.append("\u6536 \u00A5${AmountFormatHelper.formatAmount(header.income)} ")
            }
            if (header.expense > 0) {
                summaryBuilder.append("\u652F \u00A5${AmountFormatHelper.formatAmount(header.expense)}")
            }
            tvSummary?.text = summaryBuilder.toString().trim()
            if (tvSummary?.text?.isEmpty() == true) {
                tvSummary.visibility = View.GONE
            } else {
                tvSummary?.visibility = View.VISIBLE
            }
            updateMode(position, animate = false)
        }

        fun updateMode(position: Int, animate: Boolean) {
            applyRowMode(
                checkBox = cbSelectDay,
                show = isMultiSelectMode,
                animate = animate,
                spacer = spHeaderCheck,
                afterShown = { updateSelection(position) }
            )
            if (!animate || !isMultiSelectMode) {
                updateSelection(position)
            }
        }

        fun updateSelection(position: Int) {
            val (allSelected, partiallySelected) = headerSelectionState(position)
            cbSelectDay.isChecked = allSelected
            cbSelectDay.alpha = if (partiallySelected) 0.55f else 1f
        }

        fun resetRowMotionOnRecycle() {
            resetRowMotion(itemView, cbSelectDay, spHeaderCheck)
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategory: TextView = itemView.findViewById(R.id.tv_bill_category)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_bill_amount)
        private val tvMember: TextView = itemView.findViewById(R.id.tv_bill_member)
        private val tvAsset: TextView = itemView.findViewById(R.id.tv_bill_asset)
        private val tvExcludeStats: TextView = itemView.findViewById(R.id.tv_bill_exclude_stats)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_bill_time)
        private val tvDetail: TextView = itemView.findViewById(R.id.tv_bill_detail)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_bill_select)
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_bill_category_icon)
        private val iconContainer: View? = itemView.findViewById(
            itemView.context.resources.getIdentifier("layout_icon_container", "id", itemView.context.packageName)
        )

        // 当前 ViewHolder 的图标加载任务，复用/回收时可取消。
        var iconJob: Job? = null
        // 当前已绑定的图标 URL；重绑时 URL 不变就跳过清空/重载，避免多选切换等场景闪一下。
        var boundIconUrl: String? = null
        private var boundBill: Bill? = null
        private var boundDeprecated: Boolean = false
        private var boundSelectable: Boolean = true

        init {
            checkBox.isClickable = false
            checkBox.isFocusable = false

            itemView.setOnClickListener {
                val bill = boundBill ?: return@setOnClickListener
                if (boundDeprecated) return@setOnClickListener
                if (isMultiSelectMode) {
                    if (!boundSelectable) return@setOnClickListener
                    if (selectedBills.contains(bill)) {
                        selectedBills.remove(bill)
                    } else {
                        selectedBills.add(bill)
                    }
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                        updateHeaderSelectionNear(pos)
                    }
                    onSelectionChanged?.invoke(selectedBills.size)
                } else {
                    onBillItemClick?.invoke(bill)
                }
            }

            itemView.setOnLongClickListener {
                val bill = boundBill ?: return@setOnLongClickListener true
                if (boundDeprecated) return@setOnLongClickListener true
                if (!isMultiSelectMode) {
                    if (!boundSelectable) return@setOnLongClickListener true
                    enterMultiSelect(listOf(bill))
                }
                true
            }
        }

        fun updateMode(isSelected: Boolean, animate: Boolean) {
            itemView.animate().cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.translationY = 0f
            if (isMultiSelectMode) {
                checkBox.isEnabled = boundSelectable
                checkBox.isChecked = boundSelectable && isSelected
                applyRowMode(
                    checkBox = checkBox,
                    show = true,
                    animate = animate,
                    shownAlpha = if (boundSelectable) 1f else 0.45f
                )
                iconContainer?.visibility = View.VISIBLE
            } else {
                checkBox.isEnabled = true
                checkBox.isChecked = false
                applyRowMode(
                    checkBox = checkBox,
                    show = false,
                    animate = animate
                )
                iconContainer?.visibility = View.VISIBLE
            }
        }

        fun updateSelection(isSelected: Boolean) {
            checkBox.isChecked = boundSelectable && isSelected
        }

        fun resetRowMotionOnRecycle() {
            resetRowMotion(itemView, checkBox)
        }

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
            iconContainer?.layoutParams = iconContainer?.layoutParams?.apply {
                width = widthPx
                height = heightPx
            }
        }

        fun bind(displayBill: DisplayBill, position: Int) {
            val bill = displayBill.bill
            val isDeprecated = displayBill.isDeprecated
            boundBill = bill
            boundDeprecated = isDeprecated
            boundSelectable = isSelectable(bill, isDeprecated)
            val isTransfer = bill.type == Bill.TYPE_TRANSFER
            val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
            val isRefund = isRefundBill(bill)
            val showCategoryIcon = Prefs.isShowBillCategoryIcon(itemView.context)
            val showFullCategory = Prefs.isShowBillFullCategory(itemView.context)
            val remarkPriority = Prefs.isBillRemarkPriority(itemView.context)
            val symbol = CurrencyManager.getSymbol(bill.currency)
            val refundAmount = refundAmountOfExpenseBill(bill)
            val baseCategoryName = stripRefundPrefix(bill.categoryName)
            val hasHeaderAbove = items.getOrNull(position - 1) is ListItem.Header
            val isGroupStart = position == 0 || hasHeaderAbove
            val isGroupEnd = position == items.lastIndex || items.getOrNull(position + 1) is ListItem.Header

            itemView.setBackgroundResource(
                resolveGroupBackground(
                    isGroupStart = isGroupStart,
                    isGroupEnd = isGroupEnd,
                    hasHeaderAbove = hasHeaderAbove
                )
            )
            iconContainer?.setBackgroundResource(
                when {
                    !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                    !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                    else -> R.drawable.bg_circle_soft
                }
            )
            tvCategory.setTextColor(
                when {
                    isDeprecated -> textColorRefund
                    isRefund -> textColorRefund
                    else -> textColorNormal
                }
            )
            tvDetail.setTextColor(
                when {
                    isDeprecated -> textColorRefund
                    isRefund -> textColorDetailRefund
                    else -> textColorDetailNormal
                }
            )
            tvAsset.setTextColor(
                when {
                    isDeprecated -> textColorRefund
                    isRefund -> textColorDetailRefund
                    else -> textColorDetailNormal
                }
            )

            val categoryText = when {
                isRepayment -> "\u8FD8\u6B3E"
                isTransfer -> "\u8F6C\u8D26"
                else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { "\u672A\u5206\u7C7B" }
            }

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

            if (isDeprecated) {
                tvAmount.setTextColor(amountColorRefund)
            } else if (bill.excludeFromStats) {
                tvAmount.setTextColor(amountColorRefund)
            } else if (isRefund) {
                tvAmount.setTextColor(amountColorRefund)
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                tvAmount.setTextColor(amountColorExpense)
            } else if (bill.type == Bill.TYPE_INCOME) {
                tvAmount.setTextColor(amountColorIncome)
            } else {
                tvAmount.setTextColor(amountColorOther)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ivIcon.imageTintList = null
            }

            val tintColor = when {
                isRefund -> textColorRefund
                bill.type == Bill.TYPE_EXPENSE -> amountColorExpense
                bill.type == Bill.TYPE_INCOME -> amountColorIncome
                else -> iconColorOther
            }
            ivIcon.setColorFilter(tintColor)

            tvTime.text = itemTimeFormatter.format(Date(bill.time))

            // \u663E\u793A\u6210\u5458\u540D\u79F0\uFF08\u5982\u679C\u662F\u5171\u4EAB\u8D26\u5355\uFF09
            if (showMemberLabels && bill.isShared && bill.memberId != null) {
                val bookName = BookAccountManager.normalizeBookName(bill.bookName)
                val name = memberContexts[bookName]?.memberNames?.get(bill.memberId) ?: "未知成员"
                tvMember.text = if (showBookNamesWithMembers) "$bookName · $name" else name
                tvMember.visibility = View.VISIBLE
            } else {
                tvMember.visibility = View.GONE
            }

            val assetBuilder = SpannableStringBuilder()
            if (isTransfer) {
                if (bill.accountName.isNotEmpty()) {
                    assetBuilder.append(BillDisplayFormatter.formatAccountNameWithDeletedTag(bill.accountName))
                }
                if (bill.toAccountName.isNotEmpty()) {
                    if (assetBuilder.isNotEmpty()) assetBuilder.append(" -> ")
                    assetBuilder.append(BillDisplayFormatter.formatAccountNameWithDeletedTag(bill.toAccountName))
                }
            } else if (isRefund) {
                if (bill.accountName.isNotEmpty()) {
                    assetBuilder.append(BillDisplayFormatter.formatAccountNameWithDeletedTag(bill.accountName))
                }
            } else {
                if (bill.accountName.isNotEmpty()) {
                    assetBuilder.append(BillDisplayFormatter.formatAccountNameWithDeletedTag(bill.accountName))
                    if (refundAmount > 0.0) {
                        assetBuilder.append("(\u9000\u6B3E")
                        assetBuilder.append(symbol)
                        assetBuilder.append(String.format(Locale.getDefault(), "%.2f", refundAmount))
                        assetBuilder.append(")")
                    }
                }
            }
            if (assetBuilder.isNotEmpty()) {
                tvAsset.text = assetBuilder
                tvAsset.visibility = View.VISIBLE
            } else {
                tvAsset.visibility = View.GONE
            }

            tvExcludeStats.visibility = if (bill.excludeFromStats && !isDeprecated) View.VISIBLE else View.GONE

            val suffix = detailSuffixProvider?.invoke(bill).orEmpty()
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

            val strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            if (isDeprecated) {
                tvCategory.paintFlags = tvCategory.paintFlags or strike
                tvAmount.paintFlags = tvAmount.paintFlags or strike
                tvAsset.paintFlags = tvAsset.paintFlags or strike
                tvTime.paintFlags = tvTime.paintFlags or strike
                tvDetail.paintFlags = tvDetail.paintFlags or strike
                itemView.alpha = 0.55f
            } else {
                tvCategory.paintFlags = tvCategory.paintFlags and strike.inv()
                tvAmount.paintFlags = tvAmount.paintFlags and strike.inv()
                tvAsset.paintFlags = tvAsset.paintFlags and strike.inv()
                tvTime.paintFlags = tvTime.paintFlags and strike.inv()
                tvDetail.paintFlags = tvDetail.paintFlags and strike.inv()
                itemView.alpha = 1f
            }

            val iconLookupName = if (isRefund) baseCategoryName else bill.categoryName
            val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
            val iconCacheKey = buildIconCacheKey(iconLookupName, iconLookupType)
            iconJob?.cancel()
            if (!showCategoryIcon) {
                iconContainer?.setBackgroundColor(Color.TRANSPARENT)
                setIconContainerSizeDp(10, 44)
                ivIcon.clearColorFilter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ivIcon.imageTintList = null
                }
                setIconSizeDp(6)
                val dotRes = when (bill.type) {
                    Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                    Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                    else -> R.drawable.bg_bill_dot_neutral
                }
                ivIcon.setImageResource(dotRes)
                // 点模式与图标模式互切时强制重载
                boundIconUrl = null
            } else {
                iconContainer?.setBackgroundResource(
                    when {
                        !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                        !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                        else -> R.drawable.bg_circle_soft
                    }
                )
                setIconContainerSizeDp(44, 44)
                setIconSizeDp(21)
                // 分类换图标后由共享缓存按 revision 整表作废（见 CategoryIconUrlCache.get），这里不用再手动比对
                val cachedIconUrl = getCachedIconUrl(iconCacheKey)
                if (cachedIconUrl != null) {
                    // 图标没变（多选切换、局部刷新等重绑）时不清空不重载，避免闪一下
                    if (cachedIconUrl != boundIconUrl) {
                        boundIconUrl = cachedIconUrl
                        ivIcon.setImageDrawable(null)
                        if (cachedIconUrl.isNotEmpty()) {
                            Glide.with(itemView.context)
                                .load(cachedIconUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(ivIcon)
                        }
                    }
                } else {
                    iconJob = adapterScope.launch {
                        // 图标已打包进 App：优先按分类实时解析，分类换图标后列表能跟着变。
                        // 解析不出真实图标时（如共享账本里别人的分类），退回账单自带的图标快照。
                        var iconUrl = CategoryIconHelper.findCategoryIcon(itemView.context, iconLookupName, iconLookupType)
                        val snapshot = bill.cateIcon
                        if (!snapshot.isNullOrBlank() &&
                            !CategoryIcons.isAutoAssigned(snapshot) &&
                            (iconUrl.isEmpty() || TextIconRenderer.isTextIcon(iconUrl))
                        ) {
                            iconUrl = snapshot
                        }
                        putCachedIconUrl(iconCacheKey, iconUrl)
                        withContext(Dispatchers.Main) {
                            if (iconUrl != boundIconUrl) {
                                boundIconUrl = iconUrl
                                ivIcon.setImageDrawable(null)
                                if (iconUrl.isNotEmpty()) {
                                    Glide.with(itemView.context)
                                        .load(iconUrl)
                                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                        .into(ivIcon)
                                }
                            }
                        }
                    }
                }
            }

            updateMode(!isDeprecated && selectedBills.contains(bill), animate = false)
        }
    }
}
