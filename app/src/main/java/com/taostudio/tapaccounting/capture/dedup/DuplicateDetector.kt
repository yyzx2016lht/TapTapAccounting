package com.taostudio.tapaccounting.capture.dedup

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import kotlin.math.abs

/**
 * 跨渠道交易自动去重检测与合并器。
 *
 * 核心对齐 AutoAccounting 权威去重逻辑：
 * 1. 同一笔交易被多个渠道捕获（如微信支付凭单 + 银行卡动账短信）：
 *    - 时间窗口：±300 秒（5分钟）
 *    - 金额完全一致
 *    - 渠道不同 -> 判定为同一笔交易的重复记录，智能合并为一条最完整的待记账单。
 * 2. 同一渠道连续支付（如在自动售货机连续买两次 5 元饮料）：
 *    - 渠道与来源一致，时间相差 > 2 秒 -> 判定为多次真实付款，绝不误杀！
 */
object DuplicateDetector {

    /** 默认时间窗口（毫秒）：5分钟 */
    const val DEFAULT_WINDOW_MILLIS: Long = 300_000L

    /**
     * 在候选列表中查找最匹配的父账单。
     */
    fun findDuplicate(
        incoming: PaymentCapture,
        candidates: List<PaymentCapture>,
    ): PaymentCapture? {
        val incomingAmount = incoming.amountCents ?: return null
        if (incomingAmount <= 0L) return null

        return candidates.firstOrNull { candidate ->
            candidate.id != incoming.id &&
                candidate.groupId == null &&
                candidate.status != CaptureStatus.DUPLICATE &&
                isDuplicate(incoming, candidate)
        }
    }

    /**
     * 重复裁决规则：
     * 1. 金额必须完全一致。
     * 2. 交易类型（支出/收入）若均存在，必须一致。
     * 3. 时间差超过时间窗口（300s） -> 非重复。
     * 4. 时间完全一致 -> 重复（同一事件多次重放/重发）。
     * 5. 渠道存在重叠（如均为微信支付，或一方已合并该渠道）：
     *    - 时间差 <= 2秒 -> 系统并发重复广播，判定为重复。
     *    - 时间差 > 2秒 -> 真实连续付款，判定为【非重复】！
     * 6. 无重叠渠道（如 微信支付 vs 招商银行短信） -> 判定为【重复】！
     */
    fun isDuplicate(incoming: PaymentCapture, existing: PaymentCapture): Boolean {
        if (incoming.amountCents != existing.amountCents) return false

        // 交易类型不同不属于普通重复（应由转账识别处理）
        if (incoming.billType != null && existing.billType != null && incoming.billType != existing.billType) {
            return false
        }

        // 同一来源下如果都有明确的流水号/订单号且不相等，必定为两笔不同交易，绝不误杀
        val txn1 = incoming.sourceTxnId?.trim().orEmpty()
        val txn2 = existing.sourceTxnId?.trim().orEmpty()
        if (txn1.isNotEmpty() && txn2.isNotEmpty() && incoming.source == existing.source && txn1 != txn2) {
            return false
        }

        val timeDiff = abs(incoming.paidAt - existing.paidAt)
        if (timeDiff > DEFAULT_WINDOW_MILLIS) return false

        // 1. 时间戳完全相同 -> 视为同一笔交易
        if (incoming.paidAt == existing.paidAt) return true

        // 2. 检查渠道是否有重叠
        val overlap = hasChannelOverlap(incoming, existing)
        if (overlap) {
            // 同渠道/同方式：只有极短时间差（<= 2000ms）才判定为广播重发；相隔较长说明是连续付款给同一商户
            return timeDiff <= 2_000L
        }

        // 3. 渠道不同（如 微信支付 vs 银行动账短信） -> 判定为跨渠道重复
        return true
    }

    /**
     * 提取并规范化账单所包含的渠道集合。
     * 格式如 "微信支付 + 招商银行短信" -> {"微信支付", "招商银行短信"}
     */
    fun extractChannels(channel: String?, source: String?): Set<String> {
        val result = mutableSetOf<String>()
        val raw = channel?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.forEach { result.add(it) }
        } else {
            val fallback = when (source?.trim()) {
                "WECHAT" -> "微信支付"
                "ALIPAY" -> "支付宝"
                "SMS" -> "银行短信"
                else -> source?.trim().orEmpty()
            }
            if (fallback.isNotEmpty()) result.add(fallback)
        }
        return result
    }

    /**
     * 判断两笔账单是否存在重叠渠道。
     */
    fun hasChannelOverlap(c1: PaymentCapture, c2: PaymentCapture): Boolean {
        val set1 = extractChannels(c1.channel, c1.source)
        val set2 = extractChannels(c2.channel, c2.source)
        if (set1.any { it in set2 }) return true

        // 如果 source 相同且 paymentMethodRaw 相同且非空
        val m1 = c1.paymentMethodRaw?.trim().orEmpty()
        val m2 = c2.paymentMethodRaw?.trim().orEmpty()
        if (c1.source.isNotBlank() && c1.source == c2.source && m1.isNotEmpty() && m1 == m2) {
            return true
        }

        return false
    }

    /**
     * 将两笔重复账单智能合并为一笔更完善的主账单：
     * - 资产：优先选择更具体的名称（长名称优先），若一方为空则补充另一方
     * - 商户：优先选择更具体非脏文本的商户名称
     * - 渠道：合并记录所有捕获渠道（如 "微信支付 + 招商银行短信"）
     * - 备注：合并互补备注
     */
    fun merge(existing: PaymentCapture, incoming: PaymentCapture): PaymentCapture {
        val (betterAsset, betterRaw) = selectBetterAsset(
            existing.assetName,
            incoming.assetName,
            existing.paymentMethodRaw,
            incoming.paymentMethodRaw,
        )
        val betterMerchant = selectBetterMerchant(existing.merchant, incoming.merchant)
        val mergedChannel = mergeChannels(
            existing.channel,
            incoming.channel,
            existing.source,
            incoming.source,
        )
        val betterNote = selectBetterNote(existing.note, incoming.note)
        val betterCategory = existing.categoryName?.takeIf { it.isNotBlank() }
            ?: incoming.categoryName?.takeIf { it.isNotBlank() }
        val betterCategorySource = existing.categorySource ?: incoming.categorySource
        val betterBook = existing.bookName?.takeIf { it.isNotBlank() }
            ?: incoming.bookName?.takeIf { it.isNotBlank() }
        val excludeStats = existing.excludeFromStats || incoming.excludeFromStats

        return existing.copy(
            assetName = betterAsset,
            paymentMethodRaw = betterRaw,
            merchant = betterMerchant,
            channel = mergedChannel,
            note = betterNote,
            categoryName = betterCategory,
            categorySource = betterCategorySource,
            bookName = betterBook,
            excludeFromStats = excludeStats,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun selectBetterAsset(
        asset1: String?,
        asset2: String?,
        raw1: String?,
        raw2: String?,
    ): Pair<String?, String?> {
        val a1 = asset1?.trim().orEmpty()
        val a2 = asset2?.trim().orEmpty()
        val chosenAsset = when {
            a1.isNotEmpty() && a2.isNotEmpty() -> if (a2.length > a1.length) a2 else a1
            a1.isNotEmpty() -> a1
            a2.isNotEmpty() -> a2
            else -> null
        }

        val r1 = raw1?.trim().orEmpty()
        val r2 = raw2?.trim().orEmpty()
        val chosenRaw = when {
            r1.isNotEmpty() && r2.isNotEmpty() -> if (r2.length > r1.length) r2 else r1
            r1.isNotEmpty() -> r1
            r2.isNotEmpty() -> r2
            else -> null
        }
        return Pair(chosenAsset, chosenRaw)
    }

    private fun selectBetterMerchant(m1: String?, m2: String?): String? {
        val t1 = m1?.trim().orEmpty()
        val t2 = m2?.trim().orEmpty()
        return when {
            t1.isEmpty() -> t2.takeIf { it.isNotEmpty() }
            t2.isEmpty() -> t1.takeIf { it.isNotEmpty() }
            isDirtyMerchant(t1) && !isDirtyMerchant(t2) -> t2
            !isDirtyMerchant(t1) && isDirtyMerchant(t2) -> t1
            t2.length > t1.length -> t2
            else -> t1
        }
    }

    private fun isDirtyMerchant(name: String): Boolean =
        name.startsWith("待") ||
            name.startsWith("wxid_") ||
            name == "财付通" ||
            name == "支付宝 - 交易"

    private fun selectBetterNote(n1: String?, n2: String?): String? {
        val t1 = n1?.trim().orEmpty()
        val t2 = n2?.trim().orEmpty()
        return when {
            t1.isEmpty() -> t2.takeIf { it.isNotEmpty() }
            t2.isEmpty() -> t1.takeIf { it.isNotEmpty() }
            t1 == t2 -> t1
            t1.contains(t2) -> t1
            t2.contains(t1) -> t2
            else -> "$t1 $t2"
        }
    }

    fun mergeChannels(chan1: String?, chan2: String?, source1: String?, source2: String?): String {
        val list = mutableListOf<String>()
        fun addLabel(ch: String?, src: String?) {
            val raw = ch?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    if (!list.contains(it)) list.add(it)
                }
            } else {
                val fallback = when (src) {
                    "WECHAT" -> "微信支付"
                    "ALIPAY" -> "支付宝"
                    "SMS" -> "银行短信"
                    else -> src?.trim().orEmpty()
                }
                if (fallback.isNotEmpty() && !list.contains(fallback)) {
                    list.add(fallback)
                }
            }
        }
        addLabel(chan1, source1)
        addLabel(chan2, source2)
        return list.joinToString(" + ")
    }
}
