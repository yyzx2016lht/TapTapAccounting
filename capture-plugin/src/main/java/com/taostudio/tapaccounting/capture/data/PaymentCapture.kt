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
    ],
)
data class PaymentCapture(
    @PrimaryKey val id: String,
    val source: String,
    val sourceTxnId: String? = null,
    val amountCents: Long? = null,
    val currency: String = "CNY",
    val merchant: String? = null,
    val paymentMethodRaw: String? = null,
    val assetId: Long? = null,
    val assetName: String? = null,
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
    val needsAmount: Boolean = false,
    val rawJson: String? = null,
    val ruleVersion: String? = null,
    val sourceAppVersion: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
