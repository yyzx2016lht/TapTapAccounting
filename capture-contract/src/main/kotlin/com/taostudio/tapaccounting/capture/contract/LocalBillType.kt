package com.taostudio.tapaccounting.capture.contract

/**
 * 敲敲记账本地账单类型（与 app 模块 `Bill` 的常量保持一致）。
 *
 * App 里有 4 种主类型，但规范表示以 3 个持久化主类型为骨架：
 * - 支出 = [TYPE_EXPENSE] + [SUBTYPE_NORMAL]
 * - 收入 = [TYPE_INCOME] + [SUBTYPE_NORMAL]；退款 = [TYPE_INCOME] + [SUBTYPE_REFUND]
 * - 转账 = [TYPE_TRANSFER] + [SUBTYPE_NORMAL]；还款 = [TYPE_TRANSFER] + [SUBTYPE_REPAYMENT]
 *
 * 还款不要写 [TYPE_REPAYMENT]，退款必须关联原支出账单。
 *
 * [TYPE_REPAYMENT] 只是 App 历史数据/UI 的兼容值；新增数据一律输出规范值 2/1。
 */
object LocalBillType {
    const val TYPE_EXPENSE: Int = 0
    const val TYPE_INCOME: Int = 1
    const val TYPE_TRANSFER: Int = 2

    /** 旧表示；只用于读取历史数据，不要作为新账单的输出。 */
    const val TYPE_REPAYMENT: Int = 3

    const val SUBTYPE_NORMAL: Int = 0
    const val SUBTYPE_REPAYMENT: Int = 1
    const val SUBTYPE_REFUND: Int = 2
}

/**
 * 一条 AutoAccounting 规则命中后，折算出的本地类型。
 *
 * @property needsRefundLink 仅退款为 true：导入方必须先找到原支出账单，
 *   不能把它当普通收入直接入账。
 */
data class LocalBillTypeMapping(
    val type: Int,
    val subType: Int = LocalBillType.SUBTYPE_NORMAL,
    val needsRefundLink: Boolean = false,
) {
    /** 是否普通支出；阶段一仍用于支出快捷路径，但不再是唯一可入库的类型。 */
    val isExpense: Boolean
        get() = type == LocalBillType.TYPE_EXPENSE && subType == LocalBillType.SUBTYPE_NORMAL
}
