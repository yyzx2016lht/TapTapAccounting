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
        paymentMethodRaw = paymentMethodRaw?.takeIf { it.isNotBlank() },
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
