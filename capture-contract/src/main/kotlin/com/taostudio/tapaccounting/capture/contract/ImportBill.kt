package com.taostudio.tapaccounting.capture.contract

/**
 * 交给 App `importBills(billsJson, requestId)` 的单条账单。
 *
 * ID 优先：assetId / categoryId 是权威字段，名称只做快照/校验。
 */
data class ImportBill(
    /** 幂等键；同一 captureId 重复导入必须返回已有 billId。 */
    val captureId: String,
    /**
     * 0 支出 / 1 收入 / 2 转账。
     * 还款用 2 + [subType] 1，退款用 1 + [subType] 2；不要写旧值 3。
     * 契约已支持这几种规范组合；阶段一 App 的 `importBills` 仍只落 0/0，
     * 其他类型先留在插件待记，等分类/资产/退款关联完成后再导入。
     */
    val type: Int = 0,
    /** 0 普通 / 1 还款 / 2 退款；与 app 的 `Bill.SUBTYPE_*` 一致。 */
    val subType: Int = 0,
    /** 退款是否还需要关联原支出账单；true 时导入方不能当普通收入直接入账。 */
    val needsRefundLink: Boolean = false,
    val amountCents: Long,
    val currency: String = "CNY",
    val paidAt: Long,
    val assetId: Long? = null,
    val assetName: String? = null,
    /** 转账/还款的转入资产；type=2 时必填。 */
    val toAssetId: Long? = null,
    val toAssetName: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val remarks: String = "",
    val bookName: String? = null,
    val feeCents: Long = 0,
    /** 退款关联的原支出账单；不能只靠名称猜。 */
    val relatedBillId: Long? = null,
)

/** `importBills` 的批次结果；部分成功时逐条看 results。 */
data class ImportResult(
    val ok: Boolean,
    val requestId: String? = null,
    val results: List<Item> = emptyList(),
) {
    data class Item(
        val captureId: String,
        val billId: Long? = null,
        /** ImportItemStatus.CREATED / DUPLICATE / ERROR。 */
        val status: String,
        val error: String? = null,
    )
}
