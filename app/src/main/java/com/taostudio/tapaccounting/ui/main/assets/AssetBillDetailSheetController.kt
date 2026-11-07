package com.taostudio.tapaccounting.ui.main.assets

import com.taostudio.tapaccounting.BillColors
import android.content.Intent
import android.graphics.Color
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
import com.taostudio.tapaccounting.logic.BillDeleteUndo
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.ui.billdetail.AssetDisplay
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetActions
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetController
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetRequest
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.ui.common.BillDetailStatsToggle
import com.taostudio.tapaccounting.ui.common.BillRowAnimator
import com.taostudio.tapaccounting.ui.common.BillUndoController
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
    private val onDataChanged: () -> Unit = {},
    /** 账单列表：删除/撤销时用它定位那一行播行动画；拿不到就退化成无动画。 */
    private val getRecyclerView: () -> RecyclerView? = { null }
) {
    private val layoutInflater: LayoutInflater
        get() = activity.layoutInflater

    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val detailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        BillDetailSheetController(activity, scope)
    }

    fun showBillDetailSheet(bill: Bill, displayAssetId: Long = detailOwnerAssetId(bill)) {
        val amountOwnerId = if (displayAssetId > 0L) displayAssetId else getDefaultAssetId()
        val assetDisplay = AssetDisplay(
            assetId = amountOwnerId,
            currency = getCurrentAssetCurrency() ?: bill.currency,
            amount = amountForAssetRow(bill, amountOwnerId),
            formula = buildAssetDetailFormula(bill, amountOwnerId)
        )
        detailSheetController.show(
            request = BillDetailSheetRequest(
                bill = bill,
                assetDisplay = assetDisplay,
                listRecyclerView = getRecyclerView()
            ),
            actions = object : BillDetailSheetActions(
                context = activity,
                scope = scope,
                onDataChanged = onDataChanged,
                undoAnchor = { activity.findViewById(android.R.id.content) }
            ) {
                override fun onRefund(bill: Bill) {
                    showRefundSheet(bill)
                }

                override fun onEditRefund(originalBill: Bill, refundBill: Bill) {
                    showRefundSheet(originalBill, refundBill)
                }

                override fun onLinkedBillClick(bill: Bill) {
                    showBillDetailSheet(bill, detailOwnerAssetId(bill))
                }
            }
        )
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
