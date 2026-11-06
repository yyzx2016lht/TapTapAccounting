package com.taostudio.tapaccounting.ui.billdetail

import android.content.Context
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.RuleCorrectionCoordinator
import com.taostudio.tapaccounting.ui.main.home.HomeBillFormatHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 底部账单详情弹层的数据装配。
 *
 * 所有入口共用这一份加载逻辑：来源徽标、退款记录、原账单、转入账户、金额公式
 * 都只在这里查一次，绑定层不再各自查库。
 */
class BillDetailDataLoader(private val context: Context) {

    /** 同步就能算出来的部分：先让弹层立刻有内容，再异步补齐需要查库的字段。 */
    fun initialState(request: BillDetailSheetRequest): BillDetailUiModel {
        val bill = request.bill
        return BillDetailUiModel(
            bill = bill,
            readOnly = request.readOnly,
            assetDisplay = request.assetDisplay,
            toAccountName = bill.toAccountName.takeIf { it.isNotBlank() },
            amountFormula = buildAmountFormula(bill, request)
        )
    }

    /** 需要查库的部分：分类来源、退款记录、原账单、转入账户、退款流水备注。 */
    suspend fun load(request: BillDetailSheetRequest): BillDetailUiModel = withContext(Dispatchers.IO) {
        val bill = request.bill
        val db = AppDatabase.getDatabase(context)
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val refundedAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)

        val provenance = if (Prefs.isBillCategorySourceVisible(context) && bill.id > 0L) {
            runCatching { db.billRuleProvenanceDao().getByBillId(bill.id) }.getOrNull()
        } else {
            null
        }
        val categorySource = if (Prefs.isBillCategorySourceVisible(context) && bill.id > 0L) {
            provenance?.source
                // 老账单没有来源记录：只要有分类，就按普通记账的「用户自选」展示。
                ?: if (bill.categoryName.isNotBlank() && bill.type in 0..1) {
                    RuleCorrectionCoordinator.SOURCE_MANUAL
                } else {
                    null
                }
        } else {
            null
        }
        val categorySourceText = RuleCorrectionCoordinator.resolveProvenanceBadgeText(
            categorySource,
            provenance?.keyword
        )

        var toAccountName = bill.toAccountName.takeIf { it.isNotBlank() }
        var toAccountCurrency: String? = null
        if (isTransfer) {
            val toAsset = runCatching {
                db.assetDao().getAssetById(bill.toAccountId ?: -1L)
            }.getOrNull()
            if (toAsset != null) {
                if (toAccountName == null) toAccountName = toAsset.name
                toAccountCurrency = toAsset.currency
            }
        }

        val originalBill = if (isRefund) {
            runCatching { BillMutationService.resolveRefundSourceBill(db, bill) }.getOrNull()
        } else {
            null
        }

        val refundRecords = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundedAmount > 0.0) {
            runCatching { db.billDao().getRefundBillsBySourceId(bill.id) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        BillDetailUiModel(
            bill = bill,
            readOnly = request.readOnly,
            assetDisplay = request.assetDisplay,
            categorySource = categorySource,
            categorySourceText = categorySourceText,
            originalBill = originalBill,
            refundRecords = refundRecords,
            toAccountName = toAccountName,
            toAccountCurrency = toAccountCurrency,
            amountFormula = buildAmountFormula(bill, request),
            refundFlowRemark = if (refundRecords.isNotEmpty()) {
                BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refundRecords)
            } else {
                null
            }
        )
    }

    /**
     * 金额下方的补充说明：
     * 资产页用自己的公式（本资产分摊/退款），其他入口是退款公式或跨币种公式。
     */
    private fun buildAmountFormula(bill: Bill, request: BillDetailSheetRequest): String? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        val assetFormula = request.assetDisplay?.formula
        if (!assetFormula.isNullOrBlank()) return assetFormula
        if (bill.type == Bill.TYPE_TRANSFER) return null

        val refundedAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
        if (bill.type == Bill.TYPE_EXPENSE && refundedAmount > 0.0) {
            return context.getString(
                R.string.refund_deduct_formula_simple,
                HomeBillFormatHelper.formatMoney(refundedAmount, bill.currency),
                HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
            )
        }

        return HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
    }
}
