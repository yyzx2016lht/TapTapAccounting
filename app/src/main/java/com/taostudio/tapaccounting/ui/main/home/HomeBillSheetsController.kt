package com.taostudio.tapaccounting.ui.main.home

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetActions
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetController
import com.taostudio.tapaccounting.ui.billdetail.BillDetailSheetRequest
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class HomeBillSheetsController(
    private val fragment: Fragment,
    private val dfDetailTime: SimpleDateFormat,
    @Suppress("unused") private val dfDetailTimeShort: SimpleDateFormat,
    private val onDataChanged: () -> Unit = {},
    /** 账单列表：删除/撤销时用它定位那一行播行动画；拿不到就退化成无动画。 */
    private val getRecyclerView: () -> RecyclerView? = { null }
) {
    private val layoutInflater: LayoutInflater
        get() = fragment.layoutInflater

    private val detailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        BillDetailSheetController(fragment.requireContext(), fragment.lifecycleScope)
    }

    fun showBillDetailSheet(bill: Bill) {
        detailSheetController.show(
            request = BillDetailSheetRequest(
                bill = bill,
                listRecyclerView = getRecyclerView()
            ),
            actions = object : BillDetailSheetActions(
                context = fragment.requireContext(),
                scope = fragment.lifecycleScope,
                onDataChanged = onDataChanged,
                undoAnchor = { fragment.requireActivity().findViewById(android.R.id.content) }
            ) {
                override fun onRefund(bill: Bill) {
                    showRefundSheet(bill)
                }

                override fun onEditRefund(originalBill: Bill, refundBill: Bill) {
                    showRefundSheet(originalBill, refundBill)
                }

                override fun onLinkedBillClick(bill: Bill) {
                    showBillDetailSheet(bill)
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
                    BillMutationService.saveRefundBill(
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
