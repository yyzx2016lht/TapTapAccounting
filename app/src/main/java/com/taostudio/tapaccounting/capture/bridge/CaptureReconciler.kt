package com.taostudio.tapaccounting.capture.bridge

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * 插件本地状态与 App `external_imports` 的对账。
 *
 * 只把状态单向推进到 [CaptureStatus.RECORDED]：App 的回答是权威的，
 * UNKNOWN 说明 App 还没写入回执，本地保持原状态（PENDING/IMPORTING/...）。
 */
object CaptureReconciler {

    /** 需要向 App 对账的本地状态。 */
    val RECONCILE_STATUSES: Set<String> = setOf(
        CaptureStatus.PENDING,
        CaptureStatus.IMPORTING,
        CaptureStatus.POSSIBLE_DUPLICATE,
    )

    /**
     * 对 [items]（为空时现查 Room）里的候选条目调用 `getImportStatus`，
     * 把 App 确认已写入的条目更新为 RECORDED。
     *
     * @param captureIds 非空时只对账这些 id（例如 BILL_CREATED 广播携带的 captureId）。
     * @return 实际更新的行数。
     */
    suspend fun reconcile(
        repository: CaptureRepository,
        client: CaptureBridgeClient,
        items: List<PaymentCapture>? = null,
        captureIds: Set<String>? = null,
    ): Int {
        val snapshot = items ?: repository.list(MAX_ROWS)
        val candidates = snapshot
            .asSequence()
            .filter { it.status in RECONCILE_STATUSES }
            .filter { captureIds == null || it.id in captureIds }
            .map { it.id }
            .toList()
        if (candidates.isEmpty()) return 0

        val statuses = client.getImportStatus(candidates)
        if (statuses.isEmpty()) return 0

        var updated = 0
        statuses.forEach { (captureId, status) ->
            if (status.equals(CaptureStatus.RECORDED, ignoreCase = true) &&
                repository.updateStatus(captureId, CaptureStatus.RECORDED, notify = false)
            ) {
                updated++
            }
        }
        return updated
    }

    private const val MAX_ROWS = 200
}
