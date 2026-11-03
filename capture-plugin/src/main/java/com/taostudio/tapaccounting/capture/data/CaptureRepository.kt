package com.taostudio.tapaccounting.capture.data

import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureStatus

/** 一次插入的结果；inserted=false 表示命中幂等去重（id 或 source+sourceTxnId）。 */
data class InsertOutcome(
    val capture: PaymentCapture,
    val inserted: Boolean,
)

class CaptureRepository(private val dao: PaymentCaptureDao) {

    suspend fun insert(payload: CapturePayload): InsertOutcome {
        val now = System.currentTimeMillis()

        // 订单号优先去重；没有订单号时不做“金额+时间”自动合并（见需求 10.1）。
        payload.sourceTxnId?.takeIf { it.isNotBlank() }?.let { txn ->
            dao.findBySourceTxn(payload.source, txn)?.let { existing ->
                // 同一笔转账会从 WCDB message 与 AppMessage 各来一次：
                // 先到的可能没有 isSend、无法分类；后到的带类型时补全，不能只丢弃。
                if (existing.billType == null && payload.billType != null) {
                    val merged = existing.copy(
                        amountCents = payload.amountCents ?: existing.amountCents,
                        merchant = payload.merchant?.takeIf { it.isNotBlank() } ?: existing.merchant,
                        categoryName = payload.categoryName ?: existing.categoryName,
                        paymentMethodRaw = payload.paymentMethodRaw ?: existing.paymentMethodRaw,
                        toAssetName = payload.toAssetName ?: existing.toAssetName,
                        billType = payload.billType,
                        subType = payload.subType ?: existing.subType,
                        needsRefundLink = existing.needsRefundLink || payload.needsRefundLink,
                        rawJson = payload.rawJson ?: existing.rawJson,
                        ruleVersion = payload.ruleVersion ?: existing.ruleVersion,
                        updatedAt = now,
                    )
                    dao.update(merged)
                    return InsertOutcome(merged, inserted = false)
                }
                return InsertOutcome(existing, inserted = false)
            }
        }

        val entity = payload.toEntity(now)
        val rowId = dao.insert(entity)
        if (rowId == -1L) {
            val existing = dao.findById(entity.id) ?: entity
            return InsertOutcome(existing, inserted = false)
        }
        return InsertOutcome(entity, inserted = true)
    }

    suspend fun list(limit: Int = 200): List<PaymentCapture> = dao.listRecent(limit)

    suspend fun countPending(): Int = dao.countByStatus(CaptureStatus.PENDING_COUNT_STATUSES.toList())

    suspend fun updateStatus(id: String, status: String): Boolean =
        dao.updateStatus(id, status, System.currentTimeMillis()) > 0

    suspend fun updateNote(id: String, note: String?): Boolean =
        dao.updateNote(id, note?.takeIf { it.isNotBlank() }, System.currentTimeMillis()) > 0

    private fun CapturePayload.toEntity(now: Long): PaymentCapture = PaymentCapture(
        id = captureId,
        source = source,
        sourceTxnId = sourceTxnId?.takeIf { it.isNotBlank() },
        amountCents = amountCents,
        currency = currency.ifBlank { "CNY" },
        merchant = merchant?.takeIf { it.isNotBlank() },
        categoryName = categoryName?.takeIf { it.isNotBlank() },
        paymentMethodRaw = paymentMethodRaw?.takeIf { it.isNotBlank() },
        toAssetName = toAssetName?.takeIf { it.isNotBlank() },
        billType = billType,
        subType = subType,
        needsRefundLink = needsRefundLink,
        paidAt = if (paidAt > 0L) paidAt else now,
        capturedAt = if (capturedAt > 0L) capturedAt else now,
        bookName = bookName?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        // hook 投递的一律先落 PENDING；状态只能由插件自己推进。
        status = CaptureStatus.PENDING,
        needsAmount = needsAmount || amountCents == null,
        rawJson = rawJson,
        ruleVersion = ruleVersion,
        sourceAppVersion = sourceAppVersion,
        createdAt = now,
        updatedAt = now,
    )
}
