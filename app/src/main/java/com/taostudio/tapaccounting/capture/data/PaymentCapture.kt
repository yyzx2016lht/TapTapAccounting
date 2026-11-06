package com.taostudio.tapaccounting.capture.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taostudio.tapaccounting.capture.contract.CaptureStatus

/**
 * 一笔待记支付。字段与 docs/pending-expense-capture.md 6.1 对齐。
 *
 * id 是 captureId（UUID），同时是幂等键；金额单位为分，时间单位为毫秒。
 */
@Entity(
    tableName = "payment_captures",
    indices = [
        Index(value = ["status"]),
        Index(value = ["paidAt"]),
        Index(value = ["source", "sourceTxnId"]),
        Index(value = ["groupId"]),
    ],
)
data class PaymentCapture(
    @PrimaryKey val id: String,
    val source: String,
    val sourceTxnId: String? = null,
    val amountCents: Long? = null,
    val currency: String = "CNY",
    val merchant: String? = null,
    /** 分类名；hook 入库永远为 null，仅由备注规则 / AI / 用户手动写入。 */
    val categoryName: String? = null,
    /** 分类来源：[CategorySource.RULE] / [CategorySource.AI] / [CategorySource.MANUAL]；null = 未归类。 */
    val categorySource: String? = null,
    val categoryRuleId: Long? = null,
    val paymentMethodRaw: String? = null,
    val assetId: Long? = null,
    val assetName: String? = null,
    /** 转账/还款的转入资产名；普通收支为 null。 */
    val toAssetName: String? = null,
    val paidAt: Long,
    val capturedAt: Long,
    val bookName: String? = null,
    val note: String? = null,
    /** 本地主类型：0 支出 / 1 收入 / 2 转账；null = 待分类。 */
    val billType: Int? = null,
    /** 本地子类型：0 普通 / 1 还款 / 2 退款；null = 未识别。 */
    val subType: Int? = null,
    /** 退款必须关联原支出账单。 */
    @ColumnInfo(defaultValue = "0")
    val needsRefundLink: Boolean = false,
    val status: String = CaptureStatus.PENDING,
    /** 用户勾选“拆分记账”；只影响插件入账分支，false 时永远按一笔入账。 */
    @ColumnInfo(defaultValue = "0")
    val splitRequested: Boolean = false,
    /** 是否不计入收支统计。 */
    @ColumnInfo(defaultValue = "0")
    val excludeFromStats: Boolean = false,
    val needsAmount: Boolean = false,
    val rawJson: String? = null,
    val ruleVersion: String? = null,
    /** 命中的系统规则名，例如「微信转账」「微信支付转账（红包）退款」。 */
    val ruleName: String? = null,
    val sourceAppVersion: String? = null,
    /** 父账单 id；若当前记录被判定为重复并合并进另一条账单，此字段记录父账单 id。 */
    val groupId: String? = null,
    /** 来源渠道与合并记录描述（如 "微信支付" 或 "微信支付 + 招商银行短信"）。 */
    val channel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 分类来源。hook 入库不产生分类，所以不会有第四种来源。 */
object CategorySource {
    /** 本地备注规则命中。 */
    const val RULE = "RULE"
    /** AI 智能归类。 */
    const val AI = "AI"
    /** 用户手动选择；自动归类不得覆盖。 */
    const val MANUAL = "MANUAL"
}
