package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * 备注提交后的智能归类入口（气泡 / 待记列表共用）。
 *
 * 本地备注规则优先：调用方已经跑过 `updateNoteAndReclassify`，这里只在
 * “有备注、仍无分类、不是手动分类”时调用一次 AI；AI 返回空就保持待归类。
 */
object CaptureNoteClassifier {

    suspend fun classifyAndApply(
        context: Context,
        client: CaptureBridgeClient,
        repository: CaptureRepository,
        captureId: String,
    ): PaymentCapture? {
        val capture = repository.findById(captureId) ?: return null
        val note = capture.note?.trim().orEmpty()
        if (note.isEmpty()) return capture
        if (!capture.categoryName.isNullOrBlank()) return capture
        if (capture.billType != null && capture.billType !in 0..1) return capture

        val billType = capture.billType ?: LocalBillType.TYPE_EXPENSE
        val result = client.classifyNotes(
            listOf(
                CaptureBridgeClient.NoteClassifyInput(
                    captureId = capture.id,
                    note = note,
                    billType = billType,
                )
            )
        )
        val categoryName = result.categories[capture.id]?.takeIf { it.isNotBlank() } ?: return capture

        // AI 返回时用户可能又改了备注或手动选了分类：以数据库最新状态为准，不覆盖。
        val latest = repository.findById(capture.id) ?: return capture
        if (latest.note?.trim().orEmpty() != note) return latest
        if (!latest.categoryName.isNullOrBlank()) return latest

        return repository.updateDraft(
            context = context,
            id = latest.id,
            note = latest.note,
            assetName = latest.assetName,
            categoryName = categoryName,
            categorySource = CategorySource.AI,
        )
    }
}
