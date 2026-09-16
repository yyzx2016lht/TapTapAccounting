package com.taostudio.tapaccounting.ui.main.assets

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AssetBillDetailSheetController(
    private val activity: AppCompatActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val getCurrentAssetCurrency: () -> String?,
    private val getDefaultAssetId: () -> Long,
    private val amountForAssetRow: (Bill, Long) -> Double,
    private val detailOwnerAssetId: (Bill) -> Long,
    private val refundedAmountInBillCurrency: (Bill) -> Double,
    private val baseOriginalAmount: (Bill) -> Double,
    private val buildAssetDetailFormula: (Bill, Long) -> String?,
    private val onDataChanged: () -> Unit = {}
) {
    private var activeDetailSheet: BottomSheetDialog? = null
    private var lastDetailBillId: Long = -1L
    private var lastDetailShowAtMs: Long = 0L
    private val detailShowDebounceMs: Long = 500L

    private val layoutInflater: LayoutInflater
        get() = activity.layoutInflater

    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvAsset = row.findViewById<TextView>(R.id.tv_bill_asset)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val showCategoryIcon = Prefs.isShowBillCategoryIcon(activity)
        val showFullCategory = Prefs.isShowBillFullCategory(activity)
        val remarkPriority = Prefs.isBillRemarkPriority(activity)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = BillDisplayFormatter.stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        val categoryText = when {
            isRepayment -> activity.getString(R.string.repayment_label)
            isTransfer -> activity.getString(R.string.transfer_label)
            else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { activity.getString(R.string.uncategorized) }
        }

        val refundAmount = refundedAmountInBillCurrency(bill)
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
            tvAsset.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#1A1A1A"))
            tvDetail.setTextColor(Color.parseColor("#8A8A8E"))
            tvTime.setTextColor(Color.parseColor("#8A8A8E"))
            tvAsset.setTextColor(Color.parseColor("#8A8A8E"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#5F6772"))
            }
        }

        tvAsset.text = if (isTransfer) {
            "${bill.accountName} -> ${bill.toAccountName}"
        } else {
            bill.accountName
        }

        val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
            categoryText = categoryText,
            remarkText = bill.remark,
            suffixText = if (isTransfer) "${bill.accountName} -> ${bill.toAccountName}" else bill.accountName,
            remarkPriority = remarkPriority
        )
        tvCategory.text = primaryText
        if (forceGrayStyle) {
            tvDetail.text = dfDetailTimeShort.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            tvTime.visibility = View.GONE
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
            scope.launch(Dispatchers.IO) {
                val iconUrl = CategoryIconHelper.findCategoryIcon(activity, iconLookupName, iconLookupType)
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
        onClick: (() -> Unit)? = null
    ) {
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

        scope.launch(Dispatchers.IO) {
            val refunds = db.billDao().getRefundBillsBySourceId(sourceBill.id)
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
            showBillDetailSheet(originalBill, detailOwnerAssetId(originalBill))
        }
    }

    fun showBillDetailSheet(bill: Bill, displayAssetId: Long = detailOwnerAssetId(bill)) {
        if (Prefs.isIndependentDetailEnabled(activity) && bill.id > 0L) {
            com.taostudio.tapaccounting.ui.activity.BillDetailActivity.start(activity, bill.id)
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

        val bottomSheet = BottomSheetDialog(activity)
        activeDetailSheet = bottomSheet
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)
        val assetCurrency = getCurrentAssetCurrency() ?: bill.currency
        val symbol = CurrencyManager.getSymbol(assetCurrency)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val layoutCategory = view.findViewById<View>(R.id.layout_detail_category)
        val lineCategory = view.findViewById<View>(R.id.line_category)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)
        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val layoutIncoming = view.findViewById<View>(R.id.layout_detail_incoming)
        val lineIncoming = view.findViewById<View>(R.id.line_incoming)
        val tvIncomingAmount = view.findViewById<TextView>(R.id.tv_detail_incoming_amount)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val amountOwnerId = if (displayAssetId > 0L) displayAssetId else getDefaultAssetId()
        val displayAmount = amountForAssetRow(bill, amountOwnerId)
        var linkedOriginalForRefund: Bill? = null

        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) activity.getString(R.string.repayment_detail) else activity.getString(R.string.transfer_detail)
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccount.text = buildString {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            }

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                val feeSymbol = CurrencyManager.getSymbol(bill.currency)
                tvFeeDetail.text = "-$feeSymbol${String.format(Locale.getDefault(), "%.2f", bill.fee)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            scope.launch(Dispatchers.IO) {
                val fromAsset = bill.accountId?.let { db.assetDao().getAssetById(it) }
                val toAsset = bill.toAccountId?.let { db.assetDao().getAssetById(it) }
                val fromAssetCurrency = fromAsset?.currency?.takeIf { it.isNotEmpty() } ?: bill.currency
                val toAssetCurrency = toAsset?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                withContext(Dispatchers.Main) {
                    val isCrossCurrency = !isRepayment &&
                        !fromAssetCurrency.equals(toAssetCurrency, ignoreCase = true) &&
                        bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        tvAmountLabel.text = activity.getString(R.string.from_amount)
                        val transferOutAmount = if (fromAssetCurrency.equals(bill.currency, ignoreCase = true)) {
                            bill.amount
                        } else {
                            BillAssetImpactService.convertAmountBetweenCurrencies(
                                bill.amount,
                                bill.currency,
                                fromAssetCurrency
                            )
                        }
                        tvAmount.text = BillDisplayFormatter.formatMoney(transferOutAmount, fromAssetCurrency)
                        val targetAmount = bill.amount * bill.exchangeRate
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = BillDisplayFormatter.formatMoney(targetAmount, toAssetCurrency)
                    } else {
                        tvAmountLabel.text = if (isRepayment) activity.getString(R.string.repayment_amount) else activity.getString(R.string.transfer_amount_label)
                        tvAmount.text = "$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
                    }
                }
            }
        } else {
            tvTitle.text = activity.getString(R.string.detail)
            tvAmountLabel.text = activity.getString(R.string.amount)

            val sign = when {
                isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
                BillDisplayFormatter.buildRefundedExpenseAmountText(
                    netAmount = bill.amount,
                    originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                    currency = bill.currency
                )
            } else {
                "$sign$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
            }
            tvAmount.setTextColor(
                when {
                    isRefund -> Color.parseColor("#9AA1AA")
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#5F6772")
                }
            )

            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tv_detail_category).text =
                BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, true).ifBlank { activity.getString(R.string.uncategorized) }

            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            if (isRefund) {
                tvAccount.text = bill.accountName
                scope.launch(Dispatchers.IO) {
                    val original = com.taostudio.tapaccounting.logic.BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccount.text = bill.accountName
                if (bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
                    renderRefundRecords(view, bill) { refundBill ->
                        showBillDetailSheet(refundBill, detailOwnerAssetId(refundBill))
                    }
                }
                scope.launch(Dispatchers.IO) {
                    val crossCurrencyText = buildAssetDetailFormula(bill, amountOwnerId)
                    withContext(Dispatchers.Main) {
                        if (!crossCurrencyText.isNullOrBlank()) {
                            tvAmountFormula.visibility = View.VISIBLE
                            tvAmountFormula.text = crossCurrencyText
                        }
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.tv_detail_time).text = dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text =
            activity.getString(R.string.recorded_at_fmt, dfDetailTime.format(Date(bill.time)))
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(activity) }

        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = if (bill.remark.isNotBlank()) bill.remark else activity.getString(R.string.no_remark)
        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
            scope.launch(Dispatchers.IO) {
                val refunds = db.billDao().getRefundBillsBySourceId(bill.id)
                withContext(Dispatchers.Main) {
                    tvRemark.text = BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refunds)
                }
            }
        }

        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnExcludeStats = view.findViewById<TextView>(R.id.btn_exclude_stats)
        var currentExcludeFromStats = bill.excludeFromStats

        fun updateExcludeStatsButton() {
            if (currentExcludeFromStats) {
                btnExcludeStats.text = activity.getString(R.string.exclude)
                btnExcludeStats.setBackgroundResource(R.drawable.bg_dialog_button_outline)
                btnExcludeStats.setTextColor(activity.getColor(R.color.text_secondary))
            } else {
                btnExcludeStats.text = activity.getString(R.string.include)
                btnExcludeStats.setBackgroundResource(R.drawable.bg_dialog_button_primary)
                btnExcludeStats.setTextColor(activity.getColor(R.color.dialog_button_primary_text))
            }
        }
        updateExcludeStatsButton()

        if (isRefund) {
            btnExcludeStats.visibility = View.GONE
            btnRefund.visibility = View.GONE
        } else if (bill.type == Bill.TYPE_INCOME || bill.type == Bill.TYPE_TRANSFER || bill.amount <= 0.0) {
            btnRefund.visibility = View.GONE
        } else {
            btnRefund.visibility = View.VISIBLE
        }

        btnExcludeStats.setOnClickListener {
            currentExcludeFromStats = !currentExcludeFromStats
            updateExcludeStatsButton()
            scope.launch(Dispatchers.IO) {
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
                scope.launch(Dispatchers.IO) {
                    val source = com.taostudio.tapaccounting.logic.BillMutationService.resolveRefundSourceBill(db, bill)
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(activity, EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            activity.startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(activity, EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                activity.startActivity(intent)
            }
        }

        view.findViewById<View>(R.id.btn_delete).setOnClickListener {
            bottomSheet.dismiss()
            val themeContext = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
            val dialog = AlertDialog.Builder(themeContext)
                .setTitle(activity.getString(R.string.confirm_delete))
                .setMessage(activity.getString(R.string.confirm_delete_message))
                .setPositiveButton(activity.getString(R.string.delete)) { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, activity.getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                            onDataChanged()
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .create()
            OverlayDialogs.showPageCenterDialog(
                dialog,
                activity,
                widthRatio = 0.88f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true
            )
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

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = activity.resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + activity.resources.displayMetrics.density.times(24).toInt(),
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

    private fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = BottomSheetDialog(activity)
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

        tvTitle.text = if (editingRefund == null) activity.getString(R.string.refund_label) else activity.getString(R.string.edit_refund_label)
        val sourceOriginalAmount = baseOriginalAmount(originalBill)
        tvOrigAmount.text = BillDisplayFormatter.formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = BillDisplayFormatter.formatCategoryByPreference(originalBill.categoryName, true).ifBlank { activity.getString(R.string.uncategorized) }

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

        btnBack?.setOnClickListener { bottomSheet.cancel() }
        bottomSheet.setOnCancelListener {
            showBillDetailSheet(editingRefund ?: originalBill, detailOwnerAssetId(editingRefund ?: originalBill))
        }

        layoutRefundAccount.setOnClickListener {
            OverlayDialogs.showGridAssetPicker(activity, tvRefundAccount.text.toString(), activity.getString(R.string.select_refund_account), allowClear = false) { account ->
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
            OverlayDialogs.showCustomTimePicker(activity, initialTimeMillis = initialTimeMillis) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val refundAmount = etRefundAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (refundAmount <= 0.0) {
                Toast.makeText(activity, activity.getString(R.string.invalid_refund_amount), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == activity.getString(R.string.select_refund_account_label)) {
                Toast.makeText(activity, activity.getString(R.string.select_entry_account), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> activity.getString(R.string.refund_prefix) + BillDisplayFormatter.stripRefundPrefix(originalBill.categoryName)
            }
            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            scope.launch(Dispatchers.IO) {
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
                        Toast.makeText(activity, activity.getString(R.string.refund_exceed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, activity.getString(R.string.refund_invalid), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, if (editingRefund == null) activity.getString(R.string.refund_saved) else activity.getString(R.string.refund_updated), Toast.LENGTH_SHORT).show()
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
