package com.taostudio.tapaccounting.ui.main.home

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class HomeBillSheetsController(
    private val fragment: Fragment,
    private val dfDetailTime: SimpleDateFormat,
    private val dfDetailTimeShort: SimpleDateFormat,
    private val onDataChanged: () -> Unit = {}
) {
    private var activeDetailSheet: BottomSheetDialog? = null
    private var lastDetailBillId: Long = -1L
    private var lastDetailShowAtMs: Long = 0L
    private val detailShowDebounceMs: Long = 500L

    private val layoutInflater: LayoutInflater
        get() = fragment.layoutInflater

    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        val showCategoryIcon = Prefs.isShowBillCategoryIcon(fragment.requireContext())
        val showFullCategory = Prefs.isShowBillFullCategory(fragment.requireContext())
        val remarkPriority = Prefs.isBillRemarkPriority(fragment.requireContext())
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = HomeBillFormatHelper.stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        val categoryText = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { "未分类" }
        }

        val refundAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
        tvAmount.text = if (!forceGrayStyle && !isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val sign = when {
                forceGrayStyle || isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        }

        if (forceGrayStyle || isRefund) {
            tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
            tvCategory.setTextColor(Color.parseColor("#8E98A3"))
            tvDetail.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#1A1A1A"))
            tvDetail.setTextColor(Color.parseColor("#8A8A8E"))
            tvTime.setTextColor(Color.parseColor("#8A8A8E"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#5F6772"))
            }
        }

        val detailStr = buildString {
            if (isTransfer) {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            } else {
                if (bill.accountName.isNotEmpty()) append(bill.accountName)
                if (!forceGrayStyle) {
                    val linkedRefundAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
                    if (linkedRefundAmount > 0.0 && bill.type == Bill.TYPE_EXPENSE) {
                        append("(退款")
                        append(symbol)
                        append(String.format(Locale.getDefault(), "%.2f", linkedRefundAmount))
                        append(")")
                    }
                }
            }
            if (bill.remark.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append(bill.remark)
            }
        }

        val detailSuffix = if (isTransfer) {
            buildString {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            }
        } else {
            bill.accountName
        }
        val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
            categoryText = categoryText,
            remarkText = bill.remark,
            suffixText = detailSuffix,
            remarkPriority = remarkPriority
        )
        tvCategory.text = primaryText

        if (forceGrayStyle) {
            tvDetail.text = dfDetailTimeShort.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            if (bill.accountName.isNotEmpty()) {
                tvTime.text = bill.accountName
                tvTime.visibility = View.VISIBLE
            } else {
                tvTime.visibility = View.GONE
            }
        } else {
            if (secondaryText.isNotEmpty()) {
                tvDetail.text = secondaryText
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }
            tvTime.text = dfDetailTimeShort.format(Date(bill.time))
            tvTime.visibility = View.VISIBLE
        }

        if (!showCategoryIcon) {
            iconContainer?.setBackgroundColor(Color.TRANSPARENT)
            iconContainer?.layoutParams = iconContainer?.layoutParams?.apply {
                val widthPx = (row.resources.displayMetrics.density * 10).toInt()
                val heightPx = (row.resources.displayMetrics.density * 44).toInt()
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
            iconContainer?.layoutParams = iconContainer?.layoutParams?.apply {
                val widthPx = (row.resources.displayMetrics.density * 44).toInt()
                val heightPx = (row.resources.displayMetrics.density * 44).toInt()
                width = widthPx
                height = heightPx
            }
            val iconLookupName = if (isRefund) baseCategory else bill.categoryName
            val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
            val iconTint = when {
                forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
                bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF3B30")
                bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#9E9E9E")
            }
            ivIcon.layoutParams = ivIcon.layoutParams.apply {
                val px = (ivIcon.resources.displayMetrics.density * 21).toInt()
                width = px
                height = px
            }
            ivIcon.setImageResource(R.mipmap.ic_launcher)
            ivIcon.setColorFilter(iconTint)
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val iconUrl = CategoryIconHelper.findCategoryIcon(fragment.requireContext(), iconLookupName, iconLookupType)
                withContext(Dispatchers.Main) {
                    if (iconUrl.isNotEmpty()) {
                        Glide.with(row)
                            .load(iconUrl)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                            .into(ivIcon)
                    }
                }
            }
        }
    }

    private fun addLinkedBillRow(container: LinearLayout, bill: Bill, forceGrayStyle: Boolean, onClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(row, bill, forceGrayStyle)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { onClick?.invoke() }
        container.addView(row)
    }

    private fun renderRefundRecords(view: View, sourceBill: Bill, onItemClick: (Bill) -> Unit) {
        val section = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        section.visibility = View.GONE
        container.removeAllViews()

        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val refunds = AppDatabase.getDatabase(fragment.requireContext()).billDao().getRefundBillsBySourceId(sourceBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    section.visibility = View.GONE
                    return@withContext
                }
                section.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addLinkedBillRow(container, refundBill, forceGrayStyle = true) {
                        onItemClick(refundBill)
                    }
                }
            }
        }
    }

    private fun renderOriginalBill(view: View, originalBill: Bill) {
        val section = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        container.removeAllViews()
        section.visibility = View.VISIBLE
        addLinkedBillRow(container, originalBill, forceGrayStyle = false) {
            showBillDetailSheet(originalBill)
        }
    }

    fun showBillDetailSheet(bill: Bill) {
        if (Prefs.isIndependentDetailEnabled(fragment.requireContext()) && bill.id > 0L) {
            com.taostudio.tapaccounting.ui.activity.BillDetailActivity.start(fragment.requireContext(), bill.id)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (activeDetailSheet?.isShowing == true) {
            if (lastDetailBillId == bill.id) {
                return
            }
            activeDetailSheet?.dismiss()
        }
        if (lastDetailBillId == bill.id && now - lastDetailShowAtMs < detailShowDebounceMs) {
            return
        }
        lastDetailBillId = bill.id
        lastDetailShowAtMs = now

        val bottomSheet = BottomSheetDialog(fragment.requireContext())
        activeDetailSheet = bottomSheet
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val layoutIncoming = view.findViewById<View>(R.id.layout_detail_incoming)
        val lineIncoming = view.findViewById<View>(R.id.line_incoming)
        val tvIncomingAmount = view.findViewById<TextView>(R.id.tv_detail_incoming_amount)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val layoutCategory = view.findViewById<View>(R.id.layout_detail_category)
        val lineCategory = view.findViewById<View>(R.id.line_category)
        val tvCategory = view.findViewById<TextView>(R.id.tv_detail_category)
        val tvAccountLabel = view.findViewById<TextView>(R.id.tv_detail_account_label)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val tvTimeLabel = view.findViewById<TextView>(R.id.tv_detail_time_label)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)

        val btnExcludeStats = view.findViewById<TextView>(R.id.btn_exclude_stats)
        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnDelete = view.findViewById<View>(R.id.btn_delete)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        var linkedOriginalForRefund: Bill? = null
        var currentExcludeFromStats = bill.excludeFromStats

        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) "还款详情" else "转账详情"
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = "账户"
            tvTimeLabel.text = "时间"

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${HomeBillFormatHelper.formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                val toAsset = db.assetDao().getAssetById(bill.toAccountId ?: -1)
                val toName = toAsset?.name ?: "未知账户"
                val toAssetCurrency = toAsset?.currency ?: "CNY"
                withContext(Dispatchers.Main) {
                    tvAccount.text = "${bill.accountName} -> $toName"
                    val sourceCurrency = bill.currency
                    val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        tvAmountLabel.text = "转出金额"
                        val sourceSymbol = CurrencyManager.getSymbol(sourceCurrency)
                        tvAmount.text = "$sourceSymbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
                        val targetAmount = bill.amount * bill.exchangeRate
                        val toSymbol = CurrencyManager.getSymbol(toAssetCurrency)
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = "$toSymbol${String.format(Locale.getDefault(), "%.2f", targetAmount)}"
                    } else {
                        tvAmountLabel.text = if (isRepayment) "还款金额" else "转账金额"
                        tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                    }
                }
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = "详情"
            tvAmountLabel.text = "金额"
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, true).ifBlank { "未分类" }

            if (isRefund) {
                tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                tvAccountLabel.text = "入账账户"
                tvTimeLabel.text = "入账时间"
                tvAccount.text = bill.accountName

                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(fragment.requireContext())
                    val original = com.taostudio.tapaccounting.logic.BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccountLabel.text = "账户"
                tvTimeLabel.text = "时间"
                tvAccount.text = bill.accountName

                if (bill.type == Bill.TYPE_EXPENSE) {
                    val refundedAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
                    if (refundedAmount > 0.0) {
                        tvAmount.text = BillDisplayFormatter.buildRefundedExpenseAmountText(
                            netAmount = bill.amount,
                            originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                            currency = bill.currency
                        )
                        tvAmountFormula.visibility = View.VISIBLE
                        tvAmountFormula.text =
                            "退款${HomeBillFormatHelper.formatMoney(refundedAmount, bill.currency)}，实际支出${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                        renderRefundRecords(view, bill) { refundBill -> showBillDetailSheet(refundBill) }
                    } else {
                        tvAmount.text = "-${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                        fragment.lifecycleScope.launch(Dispatchers.IO) {
                            val crossCurrencyText = HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
                            withContext(Dispatchers.Main) {
                                if (!crossCurrencyText.isNullOrBlank()) {
                                    tvAmountFormula.visibility = View.VISIBLE
                                    tvAmountFormula.text = crossCurrencyText
                                }
                            }
                        }
                    }
                    tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                } else {
                    tvAmount.text = "+${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                    tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        val crossCurrencyText = HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
                        withContext(Dispatchers.Main) {
                            if (!crossCurrencyText.isNullOrBlank()) {
                                tvAmountFormula.visibility = View.VISIBLE
                                tvAmountFormula.text = crossCurrencyText
                            }
                        }
                    }
                }
            }
        }

        val timeStr = dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_time).text = timeStr

        val recordTimeStr = dfDetailTime.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text = "记录于 $recordTimeStr"
        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = bill.remark.ifEmpty { "无备注" }
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(fragment.requireContext()) }

        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && HomeBillFormatHelper.refundAmountOfExpenseBill(bill) > 0.0) {
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val refunds = AppDatabase.getDatabase(fragment.requireContext()).billDao().getRefundBillsBySourceId(bill.id)
                withContext(Dispatchers.Main) {
                    tvRemark.text = BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refunds)
                }
            }
        }

        if (isRefund) {
            btnExcludeStats.visibility = View.GONE
            btnRefund.visibility = View.GONE
        } else if (bill.type == Bill.TYPE_INCOME || bill.type == Bill.TYPE_TRANSFER || bill.amount <= 0.0) {
            btnRefund.visibility = View.GONE
        } else {
            btnRefund.visibility = View.VISIBLE
        }

        fun updateExcludeStatsButton() {
            if (currentExcludeFromStats) {
                btnExcludeStats.text = "不计入"
                btnExcludeStats.setBackgroundResource(R.drawable.bg_dialog_button_outline)
                btnExcludeStats.setTextColor(fragment.requireContext().getColor(R.color.text_secondary))
            } else {
                btnExcludeStats.text = "计入"
                btnExcludeStats.setBackgroundResource(R.drawable.bg_dialog_button_primary)
                btnExcludeStats.setTextColor(fragment.requireContext().getColor(R.color.dialog_button_primary_text))
            }
        }
        updateExcludeStatsButton()

        btnExcludeStats.setOnClickListener {
            currentExcludeFromStats = !currentExcludeFromStats
            updateExcludeStatsButton()
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                com.taostudio.tapaccounting.logic.BillMutationService.setExcludeFromStats(db, bill, currentExcludeFromStats)
            }
        }

        btnRefund.setOnClickListener {
            bottomSheet.dismiss()
            showRefundSheet(bill)
        }

        btnEdit.setOnClickListener {
            bottomSheet.dismiss()
            if (isRefund) {
                val cachedOriginal = linkedOriginalForRefund
                if (cachedOriginal != null) {
                    showRefundSheet(cachedOriginal, bill)
                    return@setOnClickListener
                }
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(fragment.requireContext())
                    val source = com.taostudio.tapaccounting.logic.BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(fragment.requireContext(), EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            fragment.startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(fragment.requireContext(), EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                fragment.startActivity(intent)
            }
        }

        btnDelete.setOnClickListener {
            showDeleteConfirmDialog(bill, bottomSheet)
        }

        bottomSheet.setContentView(view)
        configureDetailBottomSheet(bottomSheet)
        bottomSheet.setOnDismissListener {
            if (activeDetailSheet === bottomSheet) {
                activeDetailSheet = null
            }
        }
        bottomSheet.show()
    }

    private fun showDeleteConfirmDialog(bill: Bill, bottomSheet: BottomSheetDialog) {
        val context = fragment.requireContext()
        val panel = LayoutInflater.from(context).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = "确认删除"
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = "删除后可在回收站恢复，是否继续？"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(
            androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_TapAccounting)
        )
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = "确认删除"
            setBackgroundResource(R.drawable.bg_delete_followup_danger_btn)
            setOnClickListener {
                dialog.dismiss()
                bottomSheet.dismiss()
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context)
                    com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                    withContext(Dispatchers.Main) {
                        onDataChanged()
                        Toast.makeText(fragment.context, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = context,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun configureDetailBottomSheet(bottomSheet: BottomSheetDialog) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val maxHeight = (fragment.resources.displayMetrics.heightPixels * 0.88f).toInt()
            bottomSheetView.post {
                val contentHeight = (bottomSheetView as? ViewGroup)?.getChildAt(0)?.measuredHeight
                    ?: bottomSheetView.measuredHeight
                val desiredHeight = minOf(contentHeight, maxHeight).coerceAtLeast(1)
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

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = fragment.resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + fragment.resources.displayMetrics.density.times(24).toInt(),
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

    fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = BottomSheetDialog(fragment.requireContext())
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

        val sourceOriginalAmount = HomeBillFormatHelper.originalAmountOfExpenseBill(originalBill)
        tvOrigAmount.text = HomeBillFormatHelper.formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = BillDisplayFormatter.formatCategoryByPreference(originalBill.categoryName, true).ifBlank { "未分类" }

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
            OverlayDialogs.showGridAssetPicker(fragment.requireContext(), tvRefundAccount.text.toString(), "选择退款入账账户", allowClear = false) { account ->
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
            OverlayDialogs.showCustomTimePicker(fragment.requireContext(), initialTimeMillis = initialTimeMillis) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val amountStr = etRefundAmount.text.toString()
            val refundAmount = amountStr.toDoubleOrNull() ?: 0.0

            if (refundAmount <= 0) {
                Toast.makeText(fragment.context, "请输入有效的退款金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "选择账户") {
                Toast.makeText(fragment.context, "请选择入账账户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "退款：${HomeBillFormatHelper.stripRefundPrefix(originalBill.categoryName)}"
            }

            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
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
                        Toast.makeText(fragment.context, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(fragment.context, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(fragment.context, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                    onDataChanged()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }
}
