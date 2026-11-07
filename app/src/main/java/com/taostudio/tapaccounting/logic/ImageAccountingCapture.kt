package com.taostudio.tapaccounting.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「待记 → 图片记账」的纯计算部分：金额校验与入账 payload 组装。
 *
 * 视觉识别仍复用 App 的图片记账链路；这里只做两件可单测的事：
 * 1. 把 AI 识别的明细与 capture 的实付金额对齐（差额按 F06 规则补一笔“其他”，超出则拒绝）；
 * 2. 组装 `importBillGroup` 的请求，资产/时间/账本/类型一律取 capture，明细无权覆盖。
 *
 * 真正的幂等和原子写入仍由 [com.taostudio.tapaccounting.capturebridge.CaptureImportService.importBillGroup]
 * 负责（同一个 captureId 只写一次、N 条账单共用 paymentGroupId）。
 */
object ImageAccountingCapture {

    /** capture 上下文；这些字段入账时强制使用，AI 结果不参与。 */
    data class Capture(
        val captureId: String,
        val amountCents: Long,
        val currency: String = "CNY",
        val paidAt: Long,
        val type: Int,
        val subType: Int = 0,
        val needsRefundLink: Boolean = false,
        val assetId: Long? = null,
        val assetName: String? = null,
        val bookName: String? = null,
    )

    /** AI 识别出的一条明细。 */
    data class BillInput(
        val name: String,
        val amountCents: Long?,
        val categoryName: String?,
    )

    data class Item(
        val name: String,
        val amountCents: Long,
        val categoryName: String,
        val inferred: Boolean = false,
    )

    private data class CleanedBill(
        val name: String,
        val amountCents: Long?,
        val categoryName: String,
    )

    sealed interface Outcome {
        data class Ok(
            val items: List<Item>,
            /** 补差额调整的金额；0 表示识别合计与付款一致。 */
            val adjustmentCents: Long,
        ) : Outcome

        data class Error(
            val code: String,
            val message: String,
        ) : Outcome
    }

    fun plan(
        capture: Capture,
        bills: List<BillInput>,
        fallbackCategoryName: String?,
    ): Outcome {
        if (capture.captureId.isBlank()) {
            return Outcome.Error("missing_captureId", "缺少 captureId")
        }
        if (capture.amountCents <= 0L) {
            return Outcome.Error("invalid_total", "待记金额缺失，无法图片记账")
        }
        if (capture.type != 0 && capture.type != 1) {
            return Outcome.Error("split_unsupported_type", "图片记账只支持普通支出/收入")
        }
        if (capture.subType != 0 || capture.needsRefundLink) {
            return Outcome.Error("split_unsupported_type", "退款/还款账单不支持图片记账")
        }
        if (bills.isEmpty()) {
            return Outcome.Error("image_empty", "没有从图片识别到可入账的明细")
        }

        // 名称/分类在进入金额校验前先归一化，后续步骤都拿非空值。
        val cleaned = bills.map { bill ->
            CleanedBill(
                name = bill.name.trim().ifEmpty { "未命名账单" },
                amountCents = bill.amountCents,
                categoryName = bill.categoryName?.trim().orEmpty(),
            )
        }
        val missingCategory = cleaned.firstOrNull { it.categoryName.isBlank() }
        if (missingCategory != null) {
            return Outcome.Error(
                "missing_category",
                "「${missingCategory.name}」缺少分类，请先在敲敲记账中配置 AI 或规则",
            )
        }

        // 直接复用 F06 的金额校验：超出拒绝，单项缺金额按差额推算。
        val validated = SplitAmounts.validate(
            totalCents = capture.amountCents,
            rawItems = cleaned.map { SplitAmounts.RawItem(it.name, it.amountCents) },
        )
        val ok = when (validated) {
            is SplitAmounts.Outcome.Error -> return Outcome.Error(validated.code, validated.message)
            is SplitAmounts.Outcome.Ok -> validated
        }

        val items = ok.items.mapIndexed { index, item ->
            Item(
                name = item.name,
                amountCents = item.amountCents,
                categoryName = cleaned[index].categoryName,
                inferred = item.inferred,
            )
        }.toMutableList()

        if (ok.diffCents > 0L) {
            val fallback = fallbackCategoryName?.trim().orEmpty()
            if (fallback.isEmpty()) {
                return Outcome.Error(
                    "missing_fallback_category",
                    "识别合计还差 ${formatCents(ok.diffCents)}，但没有“其他”分类可补差额",
                )
            }
            items += Item(
                name = "补差额调整",
                amountCents = ok.diffCents,
                categoryName = fallback,
            )
            return Outcome.Ok(items = items, adjustmentCents = ok.diffCents)
        }

        return Outcome.Ok(items = items, adjustmentCents = 0L)
    }

    /**
     * `importBillGroup` 的请求体。
     *
     * `assetId/assetName/paidAt/bookName/currency/type` 全部来自 capture；
     * items 只携带名称、金额、分类，无法覆盖这些字段。
     */
    fun buildImportPayload(capture: Capture, items: List<Item>): JSONObject {
        val itemArray = JSONArray()
        items.forEach { item ->
            itemArray.put(
                JSONObject()
                    .put("name", item.name)
                    .put("amountCents", item.amountCents)
                    .put("categoryName", item.categoryName)
            )
        }
        return JSONObject()
            .put("captureId", capture.captureId)
            .put("type", capture.type)
            .put("subType", capture.subType)
            .put("needsRefundLink", capture.needsRefundLink)
            .put("totalCents", capture.amountCents)
            .put("currency", capture.currency.ifBlank { "CNY" })
            .put("paidAt", capture.paidAt)
            .put("items", itemArray)
            .apply {
                capture.assetId?.takeIf { it > 0L }?.let { put("assetId", it) }
                capture.assetName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("assetName", it) }
                capture.bookName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("bookName", it) }
            }
    }

    private fun formatCents(cents: Long): String =
        "¥" + String.format(java.util.Locale.CHINA, "%.2f", cents / 100.0)
}
