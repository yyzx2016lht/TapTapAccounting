package com.taostudio.tapaccounting.ui.billdetail

import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 底部账单详情弹层一次渲染需要的全部数据，由 [BillDetailDataLoader] 装配。
 *
 * 绑定层（[BillDetailSheetBinder]）只读这份模型，不查库、不算业务逻辑。
 */
data class BillDetailUiModel(
    val bill: Bill,
    val readOnly: Boolean = false,
    val assetDisplay: AssetDisplay? = null,
    /** 分类来源（AI / RULE / MANUAL），没有来源记录时为 null。 */
    val categorySource: String? = null,
    /** 来源徽标文案（已受「显示分类来源」开关控制）。 */
    val categorySourceText: String? = null,
    /** 退款单对应的原账单。 */
    val originalBill: Bill? = null,
    /** 这笔支出关联的退款记录。 */
    val refundRecords: List<Bill> = emptyList(),
    /** 转账的转入账户名（优先账单快照，缺失时查库补齐）。 */
    val toAccountName: String? = null,
    /** 转账的转入账户币种。 */
    val toAccountCurrency: String? = null,
    /** 金额下方的补充说明（退款公式 / 跨币种公式）。 */
    val amountFormula: String? = null,
    /** 有退款时，备注后面追加的退款流水说明。 */
    val refundFlowRemark: String? = null,
) {
    val isTransfer: Boolean get() = bill.type == Bill.TYPE_TRANSFER

    val isRepayment: Boolean get() = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT

    val isRefund: Boolean get() = bill.subType == Bill.SUBTYPE_REFUND

    /** 实际展示金额：资产页是本资产分摊金额，其他入口是账单金额。 */
    val displayAmount: Double get() = assetDisplay?.amount ?: bill.amount

    /** 实际展示币种：资产页是资产币种，其他入口是账单币种。 */
    val displayCurrency: String get() = assetDisplay?.currency ?: bill.currency
}
