package com.taostudio.tapaccounting.ui.billdetail

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.taostudio.tapaccounting.BillColors
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.ui.common.BillDetailStatsToggle
import com.taostudio.tapaccounting.ui.main.home.HomeBillFormatHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 底部账单详情弹层的唯一视图绑定。
 *
 * 只做「模型 → 控件」的映射，不查库、不弹业务对话框；按钮点击全部交给
 * [BillDetailSheetActions]，由各入口决定后续行为。
 */
object BillDetailSheetBinder {

    private const val TEXT_PRIMARY = "#1A1A1A"
    private const val TEXT_MUTED = "#9AA1AA"
    private const val TEXT_LINKED = "#8A8A8E"
    private const val TEXT_LINKED_LIGHT = "#A1A8AF"
    private const val TEXT_LINKED_CATEGORY = "#8E98A3"

    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun bind(
        context: Context,
        view: View,
        model: BillDetailUiModel,
        actions: BillDetailSheetActions,
        scope: CoroutineScope
    ) {
        bindHeader(context, view, model, scope)
        bindLinkedBills(context, view, model, actions, scope)
        bindButtons(context, view, model, actions, scope)
    }

    // ---------------------------------------------------------------- 头部信息

    private fun bindHeader(
        context: Context,
        view: View,
        model: BillDetailUiModel,
        scope: CoroutineScope
    ) {
        val bill = model.bill
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
        val tvCategorySourceBadge = view.findViewById<TextView>(R.id.tv_detail_category_source_badge)
        val tvAccountLabel = view.findViewById<TextView>(R.id.tv_detail_account_label)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val tvTimeLabel = view.findViewById<TextView>(R.id.tv_detail_time_label)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)

        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE

        val displayAmountText = formatDisplayAmount(model)

        if (model.isTransfer) {
            tvTitle.text = context.getString(
                if (model.isRepayment) R.string.repayment_detail else R.string.transfer_detail
            )
            tvAmount.setTextColor(Color.parseColor(TEXT_PRIMARY))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = context.getString(R.string.account)
            tvTimeLabel.text = context.getString(R.string.time)

            tvAccount.text = buildString {
                append(bill.accountName)
                val toName = model.toAccountName
                when {
                    !toName.isNullOrBlank() -> {
                        append(" -> ")
                        append(toName)
                    }

                    bill.toAccountId != null -> {
                        append(" -> ")
                        append(context.getString(R.string.account))
                    }
                }
            }

            if (!model.isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            val toCurrency = model.toAccountCurrency ?: "CNY"
            val isCrossCurrency = !model.isRepayment &&
                !bill.currency.equals(toCurrency, ignoreCase = true) &&
                bill.exchangeRate != 1.0
            if (isCrossCurrency) {
                tvAmountLabel.text = context.getString(R.string.from_amount)
                tvAmount.text = formatMoney(bill.amount, bill.currency)
                layoutIncoming.visibility = View.VISIBLE
                lineIncoming.visibility = View.VISIBLE
                tvIncomingAmount.text = formatMoney(bill.amount * bill.exchangeRate, toCurrency)
            } else {
                tvAmountLabel.text = context.getString(
                    if (model.isRepayment) R.string.repayment_amount else R.string.transfer_amount_label
                )
                tvAmount.text = displayAmountText
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = context.getString(R.string.detail)
            tvAmountLabel.text = context.getString(R.string.amount)
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = BillDisplayFormatter
                .formatCategoryByPreference(bill.categoryName, true)
                .ifBlank { context.getString(R.string.uncategorized) }
            bindCategorySourceBadge(model, tvCategorySourceBadge)

            if (model.isRefund) {
                tvAmount.text = displayAmountText
                tvAmount.setTextColor(Color.parseColor(TEXT_MUTED))
                tvAccountLabel.text = context.getString(R.string.entry_account)
                tvTimeLabel.text = context.getString(R.string.entry_time)
            } else {
                tvAccountLabel.text = context.getString(R.string.account)
                tvTimeLabel.text = context.getString(R.string.time)
                when (bill.type) {
                    Bill.TYPE_EXPENSE -> {
                        val refundedAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
                        tvAmount.text = if (refundedAmount > 0.0) {
                            BillDisplayFormatter.buildRefundedExpenseAmountText(
                                netAmount = bill.amount,
                                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                                currency = bill.currency
                            )
                        } else {
                            "-$displayAmountText"
                        }
                        tvAmount.setTextColor(amountColor(bill, bill.excludeFromStats))
                    }

                    else -> {
                        tvAmount.text = "+$displayAmountText"
                        tvAmount.setTextColor(amountColor(bill, bill.excludeFromStats))
                    }
                }
            }

            tvAccount.text = bill.accountName
            val formula = model.amountFormula
            if (!formula.isNullOrBlank()) {
                tvAmountFormula.visibility = View.VISIBLE
                tvAmountFormula.text = formula
            }
        }

        view.findViewById<TextView>(R.id.tv_detail_time).text =
            dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text =
            context.getString(R.string.recorded_at_fmt, dfDetailTime.format(Date(bill.time)))
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(context) }

        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = model.refundFlowRemark
            ?: bill.remark.ifEmpty { context.getString(R.string.no_remark) }
    }

    /** 金额：资产页用本资产分摊金额 + 资产币种，其他入口用账单金额 + 账单币种。 */
    private fun formatDisplayAmount(model: BillDetailUiModel): String {
        val bill = model.bill
        val assetDisplay = model.assetDisplay
        return if (assetDisplay != null) {
            val symbol = CurrencyManager.getSymbol(assetDisplay.currency)
            "$symbol${String.format(Locale.getDefault(), "%.2f", assetDisplay.amount)}"
        } else {
            formatMoney(bill.amount, bill.currency)
        }
    }

    private fun formatMoney(amount: Double, currency: String): String =
        HomeBillFormatHelper.formatMoney(amount, currency)

    private fun amountColor(bill: Bill, excludedFromStats: Boolean): Int = when {
        excludedFromStats -> Color.parseColor(TEXT_MUTED)
        bill.type == Bill.TYPE_INCOME -> BillColors.income
        bill.type == Bill.TYPE_EXPENSE -> BillColors.expense
        else -> Color.parseColor("#5F6772")
    }

    /** 分类来源徽标：AI 建议 / 规则命中：X / 用户自选；开关关闭或没有来源时隐藏。 */
    private fun bindCategorySourceBadge(model: BillDetailUiModel, badge: TextView) {
        val text = model.categorySourceText
        if (text.isNullOrBlank()) {
            badge.visibility = View.GONE
            return
        }
        badge.text = text
        badge.setTextColor(
            when (model.categorySource?.uppercase()) {
                "AI" -> Color.parseColor("#4A68D6")
                "RULE" -> Color.parseColor("#B45309")
                else -> Color.parseColor("#6B7280")
            }
        )
        badge.visibility = View.VISIBLE
    }

    // ------------------------------------------------------- 退款记录 / 原账单

    private fun bindLinkedBills(
        context: Context,
        view: View,
        model: BillDetailUiModel,
        actions: BillDetailSheetActions,
        scope: CoroutineScope
    ) {
        val refundSection = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val refundContainer = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        refundContainer.removeAllViews()
        refundSection.isVisible = model.refundRecords.isNotEmpty()
        model.refundRecords.forEach { refundBill ->
            addLinkedBillRow(context, refundContainer, refundBill, forceGrayStyle = true, scope, actions)
        }

        val originalSection = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val originalContainer = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        originalContainer.removeAllViews()
        val originalBill = model.originalBill
        originalSection.isVisible = originalBill != null
        if (originalBill != null) {
            addLinkedBillRow(context, originalContainer, originalBill, forceGrayStyle = false, scope, actions)
        }
    }

    private fun addLinkedBillRow(
        context: Context,
        container: LinearLayout,
        bill: Bill,
        forceGrayStyle: Boolean,
        scope: CoroutineScope,
        actions: BillDetailSheetActions
    ) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(context, row, bill, forceGrayStyle, scope)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { actions.onLinkedBillClick(bill) }
        container.addView(row)
    }

    private fun fillLinkedBillRow(
        context: Context,
        row: View,
        bill: Bill,
        forceGrayStyle: Boolean,
        scope: CoroutineScope
    ) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val showCategoryIcon = Prefs.isShowBillCategoryIcon(context)
        val showFullCategory = Prefs.isShowBillFullCategory(context)
        val remarkPriority = Prefs.isBillRemarkPriority(context)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = HomeBillFormatHelper.stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        val categoryText = when {
            isRepayment -> context.getString(R.string.repayment_label)
            isTransfer -> context.getString(R.string.transfer_label)
            else -> BillDisplayFormatter
                .formatCategoryByPreference(bill.categoryName, showFullCategory)
                .ifEmpty { context.getString(R.string.uncategorized) }
        }

        val refundAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
        tvAmount.text = if (!forceGrayStyle && !isRefund &&
            bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0
        ) {
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
            tvAmount.setTextColor(Color.parseColor(TEXT_MUTED))
            tvCategory.setTextColor(Color.parseColor(TEXT_LINKED_CATEGORY))
            tvDetail.setTextColor(Color.parseColor(TEXT_LINKED_LIGHT))
            tvTime.setTextColor(Color.parseColor(TEXT_LINKED_LIGHT))
        } else {
            tvCategory.setTextColor(Color.parseColor(TEXT_PRIMARY))
            tvDetail.setTextColor(Color.parseColor(TEXT_LINKED))
            tvTime.setTextColor(Color.parseColor(TEXT_LINKED))
            if (bill.excludeFromStats) {
                tvAmount.setTextColor(Color.parseColor(TEXT_MUTED))
            } else {
                when (bill.type) {
                    Bill.TYPE_EXPENSE -> tvAmount.setTextColor(BillColors.expense)
                    Bill.TYPE_INCOME -> tvAmount.setTextColor(BillColors.income)
                    else -> tvAmount.setTextColor(Color.parseColor("#5F6772"))
                }
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
                forceGrayStyle || isRefund -> Color.parseColor(TEXT_LINKED_CATEGORY)
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
            scope.launch(Dispatchers.IO) {
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

    // ------------------------------------------------------------ 顶部四按钮

    private fun bindButtons(
        context: Context,
        view: View,
        model: BillDetailUiModel,
        actions: BillDetailSheetActions,
        scope: CoroutineScope
    ) {
        val bill = model.bill
        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val btnExcludeStats = view.findViewById<TextView>(R.id.btn_exclude_stats)
        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnDelete = view.findViewById<View>(R.id.btn_delete)

        if (model.readOnly) {
            // 统计页只读：只留「编辑」，其余三个按钮隐藏。
            btnExcludeStats.visibility = View.GONE
            btnRefund.visibility = View.GONE
            btnDelete.visibility = View.GONE
            btnEdit.visibility = View.VISIBLE
        } else {
            if (model.isRefund) {
                btnExcludeStats.visibility = View.GONE
                btnRefund.visibility = View.GONE
            } else if (bill.type == Bill.TYPE_INCOME ||
                bill.type == Bill.TYPE_TRANSFER ||
                bill.amount <= 0.0
            ) {
                btnRefund.visibility = View.GONE
            } else {
                btnRefund.visibility = View.VISIBLE
            }

            BillDetailStatsToggle.bind(
                btnExcludeStats,
                bill = bill,
                context = context,
                scope = scope
            ) { excluded ->
                tvAmount.setTextColor(amountColor(bill, excluded))
                actions.onToggleExcludeStats(excluded)
            }
        }

        btnRefund.setOnClickListener {
            actions.dismissSheet()
            actions.onRefund(bill)
        }

        btnEdit.setOnClickListener {
            actions.dismissSheet()
            val originalBill = model.originalBill
            if (model.isRefund && originalBill != null) {
                actions.onEditRefund(originalBill, bill)
            } else {
                actions.onEdit(bill)
            }
        }

        btnDelete.setOnClickListener {
            actions.dismissSheet()
            actions.onDelete(bill)
        }
    }
}
