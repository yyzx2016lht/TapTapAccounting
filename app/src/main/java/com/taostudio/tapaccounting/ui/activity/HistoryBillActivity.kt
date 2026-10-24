package com.taostudio.tapaccounting.ui.activity

import com.taostudio.tapaccounting.BillColors
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillRestoreHelper
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.common.RowModeMotion
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.widget.SecondaryPageHeaderView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryBillActivity : AppCompatActivity() {

    private lateinit var rvDeletedBills: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var header: SecondaryPageHeaderView
    private lateinit var btnHeaderActionText: TextView
    private lateinit var layoutBottomActions: View
    private lateinit var btnRestore: TextView
    private lateinit var btnPermanentDelete: TextView

    private val adapter = HistoryBillAdapter()
    private val selectedDeletedBillIds = mutableSetOf<Long>()
    private var currentDeletedBills = listOf<DeletedBill>()
    private var isSelectMode = false

    private val headerDateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEEE", Locale.CHINA)
    private val billDateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_bills)

        initViews()
        setupListeners()
        loadDeletedBills()
    }

    private fun initViews() {
        header = findViewById(R.id.secondary_header)
        btnHeaderActionText = header.findViewById(R.id.btn_header_action_text)
        btnHeaderActionText.setOnClickListener { onHeaderActionClicked() }
        header.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            if (hasActiveSelection()) exitSelectMode() else finish()
        }

        rvDeletedBills = findViewById(R.id.rv_deleted_bills)
        tvEmpty = findViewById(R.id.tv_empty)
        layoutBottomActions = findViewById(R.id.layout_bottom_actions)
        btnRestore = findViewById(R.id.btn_restore)
        btnPermanentDelete = findViewById(R.id.btn_permanent_delete)

        rvDeletedBills.layoutManager = LinearLayoutManager(this)
        rvDeletedBills.adapter = adapter

        adapter.onItemClick = { bill ->
            if (isSelectMode) {
                toggleSelection(bill.id)
            } else {
                showBillActionDialog(bill)
            }
        }

        adapter.onItemLongClick = { bill ->
            if (!isSelectMode) {
                selectedDeletedBillIds.clear()
                enterSelectMode(bill.id)
            }
        }
    }

    private fun setupListeners() {
        btnRestore.setOnClickListener {
            val billsToRestore = selectedDeletedBills()
            if (billsToRestore.isEmpty()) return@setOnClickListener

            showConfirmDialog(
                title = getString(R.string.restore_bill_title),
                message = getString(R.string.restore_bills_confirm_fmt, billsToRestore.size),
                confirmText = getString(R.string.confirm_restore),
                isDanger = false,
                iconRes = R.drawable.ic_dialog_restore
            ) {
                restoreBills(billsToRestore)
            }
        }

        btnPermanentDelete.setOnClickListener {
            val billsToDelete = selectedDeletedBills()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            showConfirmDialog(
                title = getString(R.string.delete_bill_title),
                message = getString(R.string.delete_bills_confirm_fmt, billsToDelete.size),
                confirmText = getString(R.string.permanent_delete),
                isDanger = true,
                iconRes = R.drawable.ic_set_trash
            ) {
                permanentlyDeleteBills(billsToDelete)
            }
        }
    }

    private fun onHeaderActionClicked() {
        if (!isSelectMode) {
            enterSelectMode()
            return
        }
        toggleSelectAll()
    }

    private fun selectedDeletedBills(): List<DeletedBill> {
        return currentDeletedBills.filter { selectedDeletedBillIds.contains(it.id) }
    }

    private fun hasActiveSelection(): Boolean = isSelectMode

    private fun enterSelectMode(initialBillId: Long? = null) {
        isSelectMode = true
        initialBillId?.let { selectedDeletedBillIds.add(it) }
        updateUI()
    }

    private fun toggleSelection(deletedBillId: Long) {
        if (selectedDeletedBillIds.contains(deletedBillId)) {
            selectedDeletedBillIds.remove(deletedBillId)
        } else {
            isSelectMode = true
            selectedDeletedBillIds.add(deletedBillId)
        }
        updateUI()
    }

    private fun toggleDaySelection(dayBillIds: Set<Long>) {
        if (dayBillIds.isEmpty()) return
        val allSelected = dayBillIds.all { selectedDeletedBillIds.contains(it) }
        isSelectMode = true
        if (allSelected) {
            selectedDeletedBillIds.removeAll(dayBillIds)
        } else {
            selectedDeletedBillIds.addAll(dayBillIds)
        }
        updateUI()
    }

    private fun toggleSelectAll() {
        if (currentDeletedBills.isEmpty()) return
        val allIds = currentDeletedBills.map { it.id }.toSet()
        val allSelected = allIds.isNotEmpty() && allIds.all { selectedDeletedBillIds.contains(it) }
        if (allSelected) {
            selectedDeletedBillIds.clear()
            isSelectMode = true
            updateUI()
        } else {
            isSelectMode = true
            selectedDeletedBillIds.clear()
            selectedDeletedBillIds.addAll(allIds)
            updateUI()
        }
    }

    private fun showBillActionDialog(bill: DeletedBill) {
        val themeCtx = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_trash_bill_action, null, false)

        val ivIcon = panel.findViewById<ImageView>(R.id.iv_trash_action_icon)
        val iconColor = iconColorOf(bill)
        panel.findViewById<TextView>(R.id.tv_trash_action_category).text = categoryNameOf(bill)
        panel.findViewById<TextView>(R.id.tv_trash_action_meta).text = buildList {
            add(bill.accountName.trim())
            add(getString(R.string.trash_bill_date_fmt, billDateFormat.format(Date(bill.time))))
        }.filter { it.isNotBlank() }.joinToString(" · ")
        panel.findViewById<TextView>(R.id.tv_trash_action_amount).apply {
            text = amountTextOf(bill)
            setTextColor(amountColorOf(bill))
        }

        val lookupName = if (bill.subType == Bill.SUBTYPE_REFUND) {
            BillDisplayFormatter.stripRefundPrefix(bill.categoryName)
        } else {
            bill.categoryName
        }
        val lookupType = if (bill.subType == Bill.SUBTYPE_REFUND) Bill.TYPE_EXPENSE else bill.type
        ivIcon.setColorFilter(iconColor)
        lifecycleScope.launch(Dispatchers.IO) {
            val iconUrl = CategoryIconHelper.findCategoryIcon(this@HistoryBillActivity, lookupName, lookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    ivIcon.setColorFilter(iconColor)
                    Glide.with(ivIcon)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .into(ivIcon)
                }
            }
        }

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<View>(R.id.btn_trash_action_restore).setOnClickListener {
            dialog.dismiss()
            restoreBill(bill)
        }
        panel.findViewById<View>(R.id.btn_trash_action_delete).setOnClickListener {
            dialog.dismiss()
            permanentlyDeleteBill(bill)
        }
        panel.findViewById<View>(R.id.btn_trash_action_cancel).setOnClickListener {
            dialog.dismiss()
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun categoryNameOf(bill: DeletedBill): String {
        if (bill.type == Bill.TYPE_TRANSFER) return getString(R.string.transfer_label)
        return displayOrFallback(
            BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
            getString(R.string.uncategorized),
            getString(R.string.select_category),
            getString(R.string.select_category_label)
        )
    }

    private fun amountTextOf(bill: DeletedBill): String {
        val symbol = CurrencyManager.getSymbol(bill.currency)
        return when {
            bill.subType == Bill.SUBTYPE_REFUND -> "$symbol${String.format("%.2f", bill.amount)}"
            bill.type == Bill.TYPE_EXPENSE -> "-$symbol${String.format("%.2f", bill.amount)}"
            bill.type == Bill.TYPE_INCOME -> "+$symbol${String.format("%.2f", bill.amount)}"
            else -> "$symbol${String.format("%.2f", bill.amount)}"
        }
    }

    private fun amountColorOf(bill: DeletedBill): Int {
        return when {
            bill.excludeFromStats -> Color.parseColor("#9AA1AA")
            bill.subType == Bill.SUBTYPE_REFUND -> Color.parseColor("#9AA1AA")
            bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
            bill.type == Bill.TYPE_INCOME -> BillColors.income
            else -> Color.parseColor("#757575")
        }
    }

    private fun iconColorOf(bill: DeletedBill): Int {
        return when {
            bill.subType == Bill.SUBTYPE_REFUND -> Color.parseColor("#9E9E9E")
            bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
            bill.type == Bill.TYPE_INCOME -> BillColors.income
            else -> Color.parseColor("#9E9E9E")
        }
    }

    private fun restoreBill(bill: DeletedBill) {
        showConfirmDialog(
            title = getString(R.string.restore_bill_title),
            message = getString(R.string.restore_bill_confirm),
            confirmText = getString(R.string.confirm_restore),
            isDanger = false,
            iconRes = R.drawable.ic_dialog_restore
        ) {
            restoreBills(listOf(bill))
        }
    }

    private fun restoreBills(bills: List<DeletedBill>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryBillActivity)
            BillRestoreHelper.restoreBills(db, bills)
            withContext(Dispatchers.Main) {
                exitSelectMode()
                loadDeletedBills()
                val tip = if (bills.size == 1) getString(R.string.restored_single) else getString(R.string.restored_count_fmt, bills.size)
                Toast.makeText(this@HistoryBillActivity, tip, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun permanentlyDeleteBill(bill: DeletedBill) {
        showConfirmDialog(
            title = getString(R.string.delete_bill_title),
            message = getString(R.string.delete_bill_confirm),
            confirmText = getString(R.string.permanent_delete),
            isDanger = true,
            iconRes = R.drawable.ic_set_trash
        ) {
            permanentlyDeleteBills(listOf(bill))
        }
    }

    private fun permanentlyDeleteBills(bills: List<DeletedBill>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryBillActivity)
            db.deletedBillDao().delete(bills)
            withContext(Dispatchers.Main) {
                exitSelectMode()
                loadDeletedBills()
                val tip = if (bills.size == 1) getString(R.string.deleted_single) else getString(R.string.deleted_count_fmt, bills.size)
                Toast.makeText(this@HistoryBillActivity, tip, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadDeletedBills() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryBillActivity)
            val deletedBills = db.deletedBillDao().getAllDeletedBills()
            val grouped = buildGroupedItems(deletedBills)
            withContext(Dispatchers.Main) {
                currentDeletedBills = deletedBills
                selectedDeletedBillIds.retainAll(deletedBills.map { it.id }.toSet())
                if (selectedDeletedBillIds.isEmpty()) isSelectMode = false
                adapter.submitList(grouped, selectedDeletedBillIds)
                val isEmpty = deletedBills.isEmpty()
                tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rvDeletedBills.visibility = if (isEmpty) View.GONE else View.VISIBLE
                updateUI()
            }
        }
    }

    private fun buildGroupedItems(bills: List<DeletedBill>): List<ListItem> {
        val items = mutableListOf<ListItem>()
        if (bills.isEmpty()) return items
        // 说明卡放进列表当第一项，往下滚就跟着滚走，不再常驻顶部占位。
        items.add(ListItem.Note)
        val cal = Calendar.getInstance()
        val grouped = linkedMapOf<String, MutableList<DeletedBill>>()

        bills.forEach { bill ->
            cal.timeInMillis = bill.deletedAt
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
            grouped.getOrPut(key) { mutableListOf() }.add(bill)
        }

        grouped.forEach { (_, dayBills) ->
            if (dayBills.isEmpty()) return@forEach
            val firstBill = dayBills.first()
            val deletedAt = firstBill.deletedAt
            val dateStr = getString(R.string.trash_deleted_on_fmt, headerDateFormat.format(Date(deletedAt)))
            val weekdayStr = weekdayFormat.format(Date(deletedAt))
            items.add(
                ListItem.Header(
                    dateStr = dateStr,
                    weekdayStr = weekdayStr,
                    billIds = dayBills.map { it.id }.toSet()
                )
            )
            dayBills.forEach { bill ->
                items.add(ListItem.Item(bill))
            }
        }

        return items
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedDeletedBillIds.clear()
        adapter.applySelectionState(false, emptySet())
        updateBottomActions()
        updateHeaderUi()
    }

    private fun updateUI() {
        adapter.applySelectionState(isSelectMode, selectedDeletedBillIds)
        updateBottomActions()
        updateHeaderUi()
    }

    private fun updateBottomActions() {
        if (!isSelectMode) {
            layoutBottomActions.visibility = View.GONE
        } else {
            layoutBottomActions.visibility = View.VISIBLE
            val hasSelection = selectedDeletedBillIds.isNotEmpty()
            btnRestore.text = getString(R.string.restore_count_fmt, selectedDeletedBillIds.size)
            btnPermanentDelete.text = getString(R.string.permanent_delete)
            btnRestore.isEnabled = hasSelection
            btnPermanentDelete.isEnabled = hasSelection
            btnRestore.alpha = if (hasSelection) 1f else 0.45f
            btnPermanentDelete.alpha = if (hasSelection) 1f else 0.45f
        }
    }

    private fun updateHeaderUi() {
        val hasBills = currentDeletedBills.isNotEmpty()
        if (!hasBills) {
            header.setTitle(getString(R.string.trash))
            header.setActionText(null)
            return
        }

        if (!isSelectMode) {
            header.setTitle(getString(R.string.trash))
            header.setActionText(getString(R.string.select_action))
            return
        }

        val allIds = currentDeletedBills.map { it.id }.toSet()
        val allSelected = allIds.isNotEmpty() && allIds.all { selectedDeletedBillIds.contains(it) }
        header.setTitle(getString(R.string.selected_items_fmt, selectedDeletedBillIds.size))
        header.setActionText(if (allSelected) getString(R.string.deselect_all) else getString(R.string.select_all_label))
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = getString(R.string.confirm),
        isDanger: Boolean = false,
        iconRes: Int = 0,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        if (iconRes != 0) {
            val badge = panel.findViewById<View>(R.id.fl_followup_confirm_icon)
            badge.visibility = View.VISIBLE
            badge.setBackgroundResource(
                if (isDanger) R.drawable.bg_dialog_icon_badge_danger else R.drawable.bg_dialog_icon_badge_brand
            )
            panel.findViewById<ImageView>(R.id.iv_followup_confirm_icon).apply {
                setImageResource(iconRes)
                setColorFilter(
                    if (isDanger) Color.parseColor("#C13E4A") else Color.parseColor("#5C6BC0")
                )
            }
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun displayOrFallback(raw: String, fallback: String, vararg placeholders: String): String {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || placeholders.any { it == trimmed }) fallback else trimmed
    }

    override fun onBackPressed() {
        if (hasActiveSelection()) {
            exitSelectMode()
            return
        }
        super.onBackPressed()
    }

    sealed class ListItem {
        /** 顶部说明卡，作为列表第一项随内容滚动。 */
        object Note : ListItem()

        data class Header(
            val dateStr: String,
            val weekdayStr: String,
            val billIds: Set<Long>
        ) : ListItem()

        data class Item(val bill: DeletedBill) : ListItem()
    }

    inner class HistoryBillAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = listOf<ListItem>()
        private var selectMode = false
        private var selectedIds = setOf<Long>()

        var onItemClick: ((DeletedBill) -> Unit)? = null
        var onItemLongClick: ((DeletedBill) -> Unit)? = null

        fun submitList(newItems: List<ListItem>, selected: Set<Long>) {
            items = newItems
            selectedIds = selected.toSet()
            notifyDataSetChanged()
        }

        fun applySelectionState(selectMode: Boolean, selectedIds: Set<Long>) {
            val modeChanged = this.selectMode != selectMode
            this.selectMode = selectMode
            this.selectedIds = selectedIds
            val recyclerView = this@HistoryBillActivity.rvDeletedBills
            for (i in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
                val position = holder.adapterPosition
                if (position == RecyclerView.NO_POSITION) continue
                when (holder) {
                    is HeaderViewHolder -> {
                        val header = items[position] as? ListItem.Header ?: continue
                        holder.updateSelectionUI(selectMode, selectedIds, header, animate = modeChanged)
                    }
                    is ItemViewHolder -> {
                        val item = items[position] as? ListItem.Item ?: continue
                        holder.updateSelectionUI(selectMode, selectedIds, item.bill.id, animate = modeChanged)
                    }
                }
            }
        }

        fun setSelectMode(mode: Boolean) {
            selectMode = mode
            notifyDataSetChanged()
        }

        fun setSelectedBillIds(selected: Set<Long>) {
            selectedIds = selected.toSet()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Note -> 2
                is ListItem.Header -> 0
                is ListItem.Item -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                2 -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trash_note, parent, false)
                    NoteViewHolder(view)
                }
                0 -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_header, parent, false)
                    HeaderViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_transaction, parent, false)
                    ItemViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Note -> Unit
                is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
                is ListItem.Item -> (holder as ItemViewHolder).bind(item.bill)
            }
        }

        override fun getItemCount() = items.size

        inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDate: TextView = itemView.findViewById(R.id.tv_header_date)
            private val tvSummary: TextView = itemView.findViewById(R.id.tv_header_summary)
            private val cbSelectDay: CheckBox = itemView.findViewById(R.id.cb_select_day)

            fun bind(header: ListItem.Header) {
                tvDate.text = "${header.dateStr} ${header.weekdayStr}"
                tvSummary.visibility = View.GONE

                val allSelected = header.billIds.isNotEmpty() && header.billIds.all { selectedIds.contains(it) }
                RowModeMotion.apply(
                    checkBox = cbSelectDay,
                    show = selectMode,
                    animate = false,
                    isModeActive = { this@HistoryBillAdapter.selectMode },
                    spacer = itemView.findViewById(R.id.sp_header_check)
                )
                cbSelectDay.isChecked = allSelected
                itemView.setOnClickListener {
                    if (selectMode) toggleDaySelection(header.billIds)
                }
                cbSelectDay.setOnClickListener {
                    toggleDaySelection(header.billIds)
                }
            }

            fun updateSelectionUI(
                selectMode: Boolean,
                selectedIds: Set<Long>,
                header: ListItem.Header,
                animate: Boolean = false
            ) {
                RowModeMotion.apply(
                    checkBox = cbSelectDay,
                    show = selectMode,
                    animate = animate,
                    isModeActive = { this@HistoryBillAdapter.selectMode },
                    spacer = itemView.findViewById(R.id.sp_header_check)
                )
                val allSelected = header.billIds.isNotEmpty() && header.billIds.all { selectedIds.contains(it) }
                cbSelectDay.isChecked = allSelected
            }
        }

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cbSelect: CheckBox = itemView.findViewById(R.id.cb_bill_select)
            private val layoutIcon: View = itemView.findViewById(R.id.layout_icon_container)
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_bill_category_icon)
            private val tvCategory: TextView = itemView.findViewById(R.id.tv_bill_category)
            private val tvDetail: TextView = itemView.findViewById(R.id.tv_bill_detail)
            private val tvTime: TextView = itemView.findViewById(R.id.tv_bill_time)
            private val tvAmount: TextView = itemView.findViewById(R.id.tv_bill_amount)
            private val tvDeleted: TextView = itemView.findViewById(R.id.tv_bill_exclude_stats)
            private val tvAsset: TextView = itemView.findViewById(R.id.tv_bill_asset)

            fun updateSelectionUI(
                selectMode: Boolean,
                selectedIds: Set<Long>,
                billId: Long,
                animate: Boolean = false
            ) {
                RowModeMotion.apply(
                    checkBox = cbSelect,
                    show = selectMode,
                    animate = animate,
                    isModeActive = { this@HistoryBillAdapter.selectMode }
                )
                cbSelect.isChecked = selectedIds.contains(billId)
            }

            fun bind(bill: DeletedBill) {
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND

                itemView.setBackgroundResource(R.drawable.bg_history_bill_item)

                val categoryName = when {
                    isTransfer -> itemView.context.getString(R.string.transfer_label)
                    else -> displayOrFallback(
                        BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
                        itemView.context.getString(R.string.uncategorized),
                        itemView.context.getString(R.string.select_category),
                        itemView.context.getString(R.string.select_category_label)
                    )
                }
                tvCategory.text = categoryName
                tvCategory.setTextColor(Color.parseColor("#283548"))

                val account = displayOrFallback(
                    bill.accountName,
                    "",
                    itemView.context.getString(R.string.select_asset),
                    itemView.context.getString(R.string.select_asset_dialog)
                )
                val detail = listOf(account, bill.remark.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                tvDetail.text = detail
                tvDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

                tvTime.visibility = View.VISIBLE
                tvTime.text = itemView.context.getString(
                    R.string.trash_bill_date_fmt,
                    billDateFormat.format(Date(bill.time))
                )

                val symbol = CurrencyManager.getSymbol(bill.currency)
                val amountText = when {
                    isRefund -> "$symbol${String.format("%.2f", bill.amount)}"
                    bill.type == Bill.TYPE_EXPENSE -> "-$symbol${String.format("%.2f", bill.amount)}"
                    bill.type == Bill.TYPE_INCOME -> "+$symbol${String.format("%.2f", bill.amount)}"
                    else -> "$symbol${String.format("%.2f", bill.amount)}"
                }
                tvAmount.text = amountText
                val amountColor = when {
                    bill.excludeFromStats -> Color.parseColor("#9AA1AA")
                    isRefund -> Color.parseColor("#9AA1AA")
                    bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                    bill.type == Bill.TYPE_INCOME -> BillColors.income
                    else -> Color.parseColor("#757575")
                }
                tvAmount.setTextColor(amountColor)
                tvAmount.paintFlags = tvAmount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                tvDeleted.visibility = View.VISIBLE
                tvDeleted.text = itemView.context.getString(R.string.trash_deleted_mark)
                tvAsset.visibility = View.GONE

                val iconColor = when {
                    isRefund -> Color.parseColor("#9E9E9E")
                    bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
                    bill.type == Bill.TYPE_INCOME -> BillColors.income
                    else -> Color.parseColor("#9E9E9E")
                }
                ivIcon.imageTintList = null
                ivIcon.setColorFilter(iconColor)
                layoutIcon.setBackgroundResource(
                    when {
                        !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                        !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                        else -> R.drawable.bg_circle_soft
                    }
                )

                val lookupName = if (isRefund) categoryName else bill.categoryName
                val lookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                ivIcon.setImageResource(R.mipmap.ic_launcher)
                itemView.post {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val iconUrl = CategoryIconHelper.findCategoryIcon(this@HistoryBillActivity, lookupName, lookupType)
                        withContext(Dispatchers.Main) {
                            if (iconUrl.isNotEmpty()) {
                                ivIcon.setColorFilter(iconColor)
                                Glide.with(ivIcon)
                                    .load(iconUrl)
                                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                                    .into(ivIcon)
                            }
                        }
                    }
                }

                RowModeMotion.apply(
                    checkBox = cbSelect,
                    show = selectMode,
                    animate = false,
                    isModeActive = { this@HistoryBillAdapter.selectMode }
                )
                cbSelect.isChecked = selectedIds.contains(bill.id)

                itemView.setOnClickListener {
                    onItemClick?.invoke(bill)
                }
                itemView.setOnLongClickListener {
                    onItemLongClick?.invoke(bill)
                    true
                }
            }
        }
    }
}

