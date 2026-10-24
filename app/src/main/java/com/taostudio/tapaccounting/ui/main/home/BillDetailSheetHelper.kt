package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BillColors
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.ui.common.BillDetailStatsToggle
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BillDetailSheetHelper {

    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun showBillDetailSheet(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bill: Bill,
        onRefund: ((Bill) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onBillChanged: (() -> Unit)? = null,
        /** 账单列表：删除/撤销时用它定位那一行播行动画；不传就没有动画。 */
        listRecyclerView: RecyclerView? = null
    ) {
        if (Prefs.isIndependentDetailEnabled(context) && bill.id > 0L) {
            com.taostudio.tapaccounting.ui.activity.BillDetailActivity.start(context, bill.id)
            return
        }
        val bottomSheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_bill_detail_bottom_sheet, null)

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
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        var linkedOriginalForRefund: Bill? = null
        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) context.getString(R.string.repayment_detail) else context.getString(R.string.transfer_detail)
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = context.getString(R.string.account)
            tvTimeLabel.text = context.getString(R.string.time)

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${HomeBillFormatHelper.formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                val toAsset = db.assetDao().getAssetById(bill.toAccountId ?: -1)
                val toName = toAsset?.name ?: context.getString(R.string.account)
                val toAssetCurrency = toAsset?.currency ?: "CNY"
                withContext(Dispatchers.Main) {
                    tvAccount.text = "${bill.accountName} -> $toName"
                    val sourceCurrency = bill.currency
                    val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        tvAmountLabel.text = context.getString(R.string.from_amount)
                        val sourceSymbol = CurrencyManager.getSymbol(sourceCurrency)
                        tvAmount.text = "$sourceSymbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
                        val targetAmount = bill.amount * bill.exchangeRate
                        val toSymbol = CurrencyManager.getSymbol(toAssetCurrency)
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = "$toSymbol${String.format(Locale.getDefault(), "%.2f", targetAmount)}"
                    } else {
                        tvAmountLabel.text = if (isRepayment) context.getString(R.string.repayment_amount) else context.getString(R.string.transfer_amount_label)
                        tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                    }
                }
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = context.getString(R.string.detail)
            tvAmountLabel.text = context.getString(R.string.amount)
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, true).ifBlank { context.getString(R.string.uncategorized) }

            if (isRefund) {
                tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                tvAccountLabel.text = context.getString(R.string.entry_account)
                tvTimeLabel.text = context.getString(R.string.entry_time)
                tvAccount.text = bill.accountName

                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context)
                    val original = BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original, context, lifecycleOwner, onRefund, onBillChanged)
                        }
                    }
                }
            } else {
                tvAccountLabel.text = context.getString(R.string.account)
                tvTimeLabel.text = context.getString(R.string.time)
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
                            context.getString(R.string.refund_deduct_formula_simple, HomeBillFormatHelper.formatMoney(refundedAmount, bill.currency), HomeBillFormatHelper.formatMoney(bill.amount, bill.currency))
                        renderRefundRecords(view, bill, context, lifecycleOwner, onRefund, onBillChanged)
                    } else {
                        tvAmount.text = "-${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val crossCurrencyText = HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
                            withContext(Dispatchers.Main) {
                                if (!crossCurrencyText.isNullOrBlank()) {
                                    tvAmountFormula.visibility = View.VISIBLE
                                    tvAmountFormula.text = crossCurrencyText
                                }
                            }
                        }
                    }
                    if (bill.excludeFromStats) {
                        tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                    } else {
                        tvAmount.setTextColor(BillColors.expense)
                    }
                } else {
                    tvAmount.text = "+${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                    if (bill.excludeFromStats) {
                        tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                    } else {
                        tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                    }
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
        view.findViewById<TextView>(R.id.tv_detail_record_time).text = context.getString(R.string.recorded_at_fmt, recordTimeStr)
        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = bill.remark.ifEmpty { context.getString(R.string.no_remark) }
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(context) }

        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && HomeBillFormatHelper.refundAmountOfExpenseBill(bill) > 0.0) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val refunds = AppDatabase.getDatabase(context).billDao().getRefundBillsBySourceId(bill.id)
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

        BillDetailStatsToggle.bind(
            btnExcludeStats,
            bill = bill,
            context = context,
            scope = lifecycleOwner.lifecycleScope
        ) { excluded ->
            tvAmount.setTextColor(
                when {
                    excluded -> Color.parseColor("#9AA1AA")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> BillColors.expense
                }
            )
        }

        btnRefund.setOnClickListener {
            bottomSheet.dismiss()
            onRefund?.invoke(bill)
        }

        btnEdit.setOnClickListener {
            bottomSheet.dismiss()
            if (isRefund) {
                val cachedOriginal = linkedOriginalForRefund
                if (cachedOriginal != null) {
                    onRefund?.invoke(cachedOriginal)
                    return@setOnClickListener
                }
                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context)
                    val source = BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            onRefund?.invoke(source)
                        } else {
                            val intent = Intent(context, EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            context.startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(context, EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                context.startActivity(intent)
            }
        }

        btnDelete.setOnClickListener {
            deleteBillDirect(context, lifecycleOwner, bill, bottomSheet, onBillChanged, listRecyclerView)
        }

        bottomSheet.setContentView(view)
        configureDetailBottomSheet(bottomSheet)
        bottomSheet.setOnDismissListener { onDismiss?.invoke() }
        bottomSheet.show()
    }

    private fun configureDetailBottomSheet(bottomSheet: BottomSheetDialog) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun deleteBillDirect(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bill: Bill,
        bottomSheet: BottomSheetDialog,
        onBillChanged: (() -> Unit)?,
        listRecyclerView: RecyclerView?
    ) {
        bottomSheet.dismiss()
        val billId = bill.id
        BillRowAnimator.collapseThen(
            rows = BillRowAnimator.findRows(listRecyclerView, listOf(billId)),
            startDelayMs = BillRowAnimator.SHEET_DISMISS_DELAY_MS
        ) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                val rows = BillDeleteUndo.delete(db, listOf(bill)) ?: return@launch
                withContext(Dispatchers.Main) {
                    onBillChanged?.invoke()
                    BillUndoController.showNow(
                        (context as? Activity)?.findViewById(android.R.id.content),
                        context.getString(R.string.bill_deleted_undo),
                        rows
                    ) {
                        onBillChanged?.invoke()
                        BillRowAnimator.expandAfterRefresh(listRecyclerView, rows.map { it.originalBillId })
                    }
                }
            }
        }
    }

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean, context: Context, lifecycleOwner: LifecycleOwner) {
        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND

        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)

        val symbol = CurrencyManager.getSymbol(bill.currency)
        val sign = when {
            isRefund || forceGrayStyle -> ""
            bill.type == Bill.TYPE_EXPENSE -> "-"
            bill.type == Bill.TYPE_INCOME -> "+"
            else -> ""
        }

        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        tvTime.text = timeFormat.format(Date(bill.time))
        tvAmount.text = "$sign${symbol}${String.format(Locale.getDefault(), "%.2f", bill.amount)}"

        if (forceGrayStyle || isRefund) {
            tvDetail.setTextColor(Color.parseColor("#8A8A8E"))
            tvTime.setTextColor(Color.parseColor("#8A8A8E"))
            tvAmount.setTextColor(Color.parseColor("#8A8A8E"))
        } else {
            if (bill.excludeFromStats) {
                tvAmount.setTextColor(Color.parseColor("#8A8A8E"))
            } else {
                when (bill.type) {
                    Bill.TYPE_EXPENSE -> tvAmount.setTextColor(BillColors.expense)
                    Bill.TYPE_INCOME -> tvAmount.setTextColor(BillColors.income)
                    else -> tvAmount.setTextColor(Color.parseColor("#5F6772"))
                }
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
        tvDetail.text = detailStr

        if (ivIcon != null) {
            val baseCategory = if (isRefund) BillDisplayFormatter.stripRefundPrefix(bill.categoryName) else bill.categoryName
            val iconLookupName = if (isRefund) baseCategory else bill.categoryName
            val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
            val iconTint = when {
                forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
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
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val iconUrl = CategoryIconHelper.findCategoryIcon(context, iconLookupName, iconLookupType)
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

    private fun addLinkedBillRow(
        container: LinearLayout,
        bill: Bill,
        forceGrayStyle: Boolean,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onClick: (() -> Unit)? = null
    ) {
        val row = LayoutInflater.from(context).inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(row, bill, forceGrayStyle, context, lifecycleOwner)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { onClick?.invoke() }
        container.addView(row)
    }

    private fun renderRefundRecords(
        view: View,
        sourceBill: Bill,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onRefund: ((Bill) -> Unit)? = null,
        onBillChanged: (() -> Unit)? = null
    ) {
        val section = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        section.visibility = View.GONE
        container.removeAllViews()

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val refunds = AppDatabase.getDatabase(context).billDao().getRefundBillsBySourceId(sourceBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    section.visibility = View.GONE
                    return@withContext
                }
                section.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addLinkedBillRow(container, refundBill, forceGrayStyle = true, context, lifecycleOwner) {
                        showBillDetailSheet(
                            context = context,
                            lifecycleOwner = lifecycleOwner,
                            bill = refundBill,
                            onRefund = onRefund,
                            onBillChanged = onBillChanged
                        )
                    }
                }
            }
        }
    }

    private fun renderOriginalBill(
        view: View,
        originalBill: Bill,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onRefund: ((Bill) -> Unit)? = null,
        onBillChanged: (() -> Unit)? = null
    ) {
        val section = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        container.removeAllViews()
        section.visibility = View.VISIBLE
        addLinkedBillRow(container, originalBill, forceGrayStyle = false, context, lifecycleOwner) {
            showBillDetailSheet(
                context = context,
                lifecycleOwner = lifecycleOwner,
                bill = originalBill,
                onRefund = onRefund,
                onBillChanged = onBillChanged
            )
        }
    }
}
