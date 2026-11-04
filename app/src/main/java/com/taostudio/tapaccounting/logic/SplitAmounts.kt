package com.taostudio.tapaccounting.logic

/**
 * 文字拆分记账的纯金额校验，不依赖 Android / Room，便于单测。
 *
 * AI 只负责拆条目和金额；是否拆分由用户在插件里勾选。这里只回答三件事：
 * 1. 明细合计是否超过付款；
 * 2. 只有一项缺金额时，能否用差额推算；
 * 3. 合计小于付款时，差额是多少（UI 再决定是否补一笔“其他”）。
 *
 * 所有金额单位均为分。
 */
object SplitAmounts {

    data class RawItem(
        val name: String,
        val amountCents: Long? = null,
    )

    data class Item(
        val name: String,
        val amountCents: Long,
        /** 金额是否由付款差额推算，用于在确认页标注“按差额”。 */
        val inferred: Boolean = false,
    )

    sealed interface Outcome {
        data class Ok(
            val items: List<Item>,
            val sumCents: Long,
            /** 付款 - 明细合计；0 表示正好，正数表示还差多少。 */
            val diffCents: Long,
        ) : Outcome

        data class Error(
            val code: String,
            val message: String,
        ) : Outcome
    }

    /**
     * AI 偶尔会把“分”当“元”输出。仅在所有条目都已有金额、合计恰好等于
     * `付款元数` 且明显小于付款分值时，按元转分，避免把 ¥70 拆成 ¥0.70。
     *
     * 例：total=7000 分，items=30/40 → 3000/4000；total=70 分时不转换。
     */
    fun normalizeYuanAmounts(totalCents: Long, items: List<RawItem>): List<RawItem> {
        if (items.isEmpty() || totalCents < 100L || totalCents % 100L != 0L) return items
        if (items.any { it.amountCents == null }) return items
        val sum = items.sumOf { it.amountCents ?: 0L }
        if (sum <= 0L || sum >= totalCents) return items
        if (sum != totalCents / 100L) return items
        return items.map { it.copy(amountCents = (it.amountCents ?: 0L) * 100L) }
    }

    fun validate(totalCents: Long, rawItems: List<RawItem>): Outcome {
        if (totalCents <= 0L) {
            return Outcome.Error("invalid_total", "付款金额无效，无法拆分")
        }
        if (rawItems.isEmpty()) {
            return Outcome.Error("split_empty", "没从备注里拆出条目，请把备注写成「苹果30 雪梨40」")
        }

        val cleaned = rawItems.map { it.copy(name = it.name.trim()) }
        if (cleaned.any { it.name.isEmpty() }) {
            return Outcome.Error("split_item_name_missing", "有条目缺少名称，请改备注")
        }
        if (cleaned.any { (it.amountCents ?: 0L) < 0L }) {
            return Outcome.Error("split_amount_invalid", "条目金额不能为负数，请改备注")
        }
        if (cleaned.size > MAX_ITEMS) {
            return Outcome.Error("split_too_many_items", "一次最多拆 $MAX_ITEMS 项，请合并备注")
        }

        val missingCount = cleaned.count { it.amountCents == null || it.amountCents == 0L }
        if (missingCount > 1) {
            return Outcome.Error(
                "split_amount_missing",
                "有 $missingCount 项没写金额，最多只能有一项按差额推算；请补金额或改备注",
            )
        }

        val knownSum = cleaned.sumOf { it.amountCents ?: 0L }
        if (knownSum > totalCents) {
            return Outcome.Error(
                "split_total_exceeds",
                "明细合计超出付款（明细 ${formatCents(knownSum)} / 付款 ${formatCents(totalCents)}），请改备注",
            )
        }

        if (missingCount == 1) {
            val diff = totalCents - knownSum
            if (diff <= 0L) {
                return Outcome.Error(
                    "split_amount_missing",
                    "只剩一项缺金额，但已知合计已等于付款，无法推算；请补金额或改备注",
                )
            }
            val items = cleaned.map {
                if (it.amountCents == null || it.amountCents == 0L) {
                    Item(name = it.name, amountCents = diff, inferred = true)
                } else {
                    Item(name = it.name, amountCents = it.amountCents, inferred = false)
                }
            }
            return Outcome.Ok(items = items, sumCents = totalCents, diffCents = 0L)
        }

        val items = cleaned.map {
            Item(name = it.name, amountCents = it.amountCents ?: 0L, inferred = false)
        }
        return Outcome.Ok(
            items = items,
            sumCents = knownSum,
            diffCents = totalCents - knownSum,
        )
    }

    /** 合计小于付款时，补一笔“其他”把差额补齐；金额由调用方保证为正。 */
    fun withDifferenceAdjustment(
        items: List<Item>,
        diffCents: Long,
        categoryName: String,
        name: String = "补差额调整",
    ): List<Item> {
        if (diffCents <= 0L) return items
        return items + Item(name = name, amountCents = diffCents, inferred = false)
    }

    private fun formatCents(cents: Long): String =
        "¥" + String.format(java.util.Locale.CHINA, "%.2f", cents / 100.0)

    private const val MAX_ITEMS = 30
}

/** 拆分预览的明确错误码；由 App 桥接转成 `{ok:false,error,message}`。 */
class SplitPreviewException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)
