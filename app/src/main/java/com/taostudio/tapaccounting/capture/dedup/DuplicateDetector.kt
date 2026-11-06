package com.taostudio.tapaccounting.capture.dedup

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
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
     * 转账 ↔ 到账收款 判重。
     *
     * 典型场景（真机 2026-11-05）：微信零钱提现到招商银行，微信先产生一条转账记录，
     * 两秒后招商银行再推送一条“收款人民币 10.00”。来源渠道不同、billType 一边是转账
     * 一边是收入，普通去重会认为是两笔；但它其实是同一笔资金动作的流出与流入。
     *
     * 判定条件（全部满足）：
     * 1. [income] 是收入，[transfer] 是转账；
     * 2. 金额完全一致（宁可漏判，也不把金额不同的人情往来吞掉）；
     * 3. 时间差在用户配置的窗口内（默认 300 秒）；
     * 4. 入账账户与转账的目标账户**映射后完全一致**（两侧都必须有资产名；
     *    招商银行1 / 招商银行2 这类同前缀的不同卡不会互判；转给对方个人不参与判重）。
     */
    fun isTransferIncomeDuplicate(
        income: PaymentCapture,
        transfer: PaymentCapture,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): Boolean {
        if (income.id == transfer.id) return false
        if (income.billType != LocalBillType.TYPE_INCOME) return false
        if (transfer.billType != LocalBillType.TYPE_TRANSFER) return false

        val amount = income.amountCents ?: return false
        if (amount <= 0L || amount != transfer.amountCents) return false

        if (abs(income.paidAt - transfer.paidAt) > windowMillis.coerceAtLeast(0L)) return false

        return assetsCompatible(income.assetName, transfer.toAssetName)
    }

    /**
     * 入账账户 vs 转账目标账户。
     *
     * 只看**映射后的资产名**：两边都是“招商银行”才算同一账户。
     * 不再做前缀/包含匹配 —— 用户给两张卡分别映射“招商银行1”“招商银行2”时，
     * 它们共享“招商银行”前缀，但必须是两个不同资产，不能互判重复。
     * 卡号后缀（如“(4852)”）在归一化时去掉；两侧都必须有资产名。
     */
    private fun assetsCompatible(incomeAsset: String?, transferTargetAsset: String?): Boolean {
        val a = normalizeAssetName(incomeAsset)
        val b = normalizeAssetName(transferTargetAsset)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b
    }

    private fun normalizeAssetName(raw: String?): String = raw.orEmpty()
        .replace(Regex("""[（(][^）)]*[）)]"""), "")
        .replace(Regex("""[\s\-_·•/]"""), "")
        .trim()

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
     * 7. 同来源的提现/转账阶段报文（发起/到账/确认收款，5分钟内、金额一致）-> 判定为【重复】，
     *    即使流水号不同、billType 分别被识别成支出与转账。
     */
    fun isDuplicate(incoming: PaymentCapture, existing: PaymentCapture): Boolean {
        if (incoming.amountCents != existing.amountCents) return false

        val timeDiff = abs(incoming.paidAt - existing.paidAt)
        if (timeDiff > DEFAULT_WINDOW_MILLIS) return false

        // 提现/转账会先后产生“发起”“到账”两三条不同报文，它们不是两笔付款。
        // 这些报文常常流水号不同、billType 也会被规则识别成支出/收入/转账中的不同值，
        // 所以必须在下面的类型与流水号校验之前裁决，否则永远合并不了（真机已验证）。
        if (isTransferStatusPair(incoming, existing)) return true

        // 银行公众号/动账通知 vs 支付账单（如微信支付凭证 vs 招行公众号），5分钟内、金额一致视为重复
        if (isBankPaymentPair(incoming, existing)) return true

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
     * 转账/提现的“发起/到账/确认收款”是同一笔资金动作的不同阶段，不是两笔付款。
     * 要求来源相同、至少一侧是转账、文案明确包含阶段关键词；
     * 普通两人转账、连续同金额付款不会被误合并。
     */
    private fun isTransferStatusPair(a: PaymentCapture, b: PaymentCapture): Boolean {
        val aTransfer = a.billType == LocalBillType.TYPE_TRANSFER
        val bTransfer = b.billType == LocalBillType.TYPE_TRANSFER
        // 至少一侧是转账；另一侧可能是“到账”被规则识别成收入，仍属于同一笔提现。
        if (!aTransfer && !bTransfer) return false
        if (a.source != b.source) return false
        val text = buildString {
            append(a.merchant.orEmpty())
            append(' ')
            append(b.merchant.orEmpty())
            append(' ')
            append(a.channel.orEmpty())
            append(' ')
            append(b.channel.orEmpty())
            append(' ')
            append(a.note.orEmpty())
            append(' ')
            append(b.note.orEmpty())
        }
        return text.contains("提现") ||
            text.contains("到账") ||
            text.contains("发起") ||
            text.contains("确认收款")
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
        val betterBillType = when {
            existing.billType == LocalBillType.TYPE_TRANSFER ||
                incoming.billType == LocalBillType.TYPE_TRANSFER -> LocalBillType.TYPE_TRANSFER
            else -> existing.billType ?: incoming.billType
        }
        val betterSubType = when {
            existing.subType == LocalBillType.SUBTYPE_REFUND ||
                incoming.subType == LocalBillType.SUBTYPE_REFUND -> LocalBillType.SUBTYPE_REFUND
            existing.subType == LocalBillType.SUBTYPE_REPAYMENT ||
                incoming.subType == LocalBillType.SUBTYPE_REPAYMENT -> LocalBillType.SUBTYPE_REPAYMENT
            else -> existing.subType ?: incoming.subType ?: LocalBillType.SUBTYPE_NORMAL
        }

        return existing.copy(
            billType = betterBillType,
            subType = betterSubType,
            needsRefundLink = existing.needsRefundLink || incoming.needsRefundLink,
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

    private val BANK_KEYWORDS = listOf(
        "银行", "招商", "工行", "建设", "农业", "中行", "交行", "邮储",
        "平安", "浦发", "民生", "光大", "中信", "兴业", "广发",
        "储蓄卡", "信用卡", "银行卡"
    )

    private val BANK_ACTION_KEYWORDS = listOf(
        "动账", "消费", "入账", "支出", "交易", "转账"
    )

    /**
     * 判断账单是否为银行通知（公众号/短信/动账提醒）。
     */
    fun isBankNotification(c: PaymentCapture): Boolean {
        if (c.channel?.contains("+") == true) return false
        val m = c.merchant?.trim().orEmpty()
        val ch = c.channel?.trim().orEmpty()
        val n = c.note?.trim().orEmpty()

        // 商户直接为银行机构（如 "招商银行"、"招商银行信用卡"、"中国工商银行" 等）
        if (BANK_KEYWORDS.any { m.contains(it) }) return true

        // 或渠道为纯银行渠道且非通用支付平台
        if (BANK_KEYWORDS.any { ch.contains(it) } && !ch.contains("微信支付") && !ch.contains("支付宝")) {
            return true
        }

        // 或文本明确包含动账与银行关键词
        val text = "$m $ch $n"
        return text.contains("动账") ||
            (BANK_KEYWORDS.any { text.contains(it) } && BANK_ACTION_KEYWORDS.any { text.contains(it) } && !text.contains("微信支付凭证"))
    }

    /**
     * 判断是否属于“银行通知 ↔ 支付凭证/商户消费”对。
     *
     * 场景：用户使用银行卡微信支付，微信产生一条支付凭证（source = WECHAT），
     * 银行公众号紧接着推送一条动账提醒（source = WECHAT，不同流水号或无流水号）。
     * 两者同源但属于跨机构通知，在 300s 窗口与金额一致时应合并判重，不按同渠道流水号不相等误杀。
     */
    fun isBankPaymentPair(a: PaymentCapture, b: PaymentCapture): Boolean {
        // 已合并过多渠道的父账单不参与此判断（避免误将后续真实的同渠道消费合并进来）
        if (a.channel?.contains("+") == true || b.channel?.contains("+") == true) return false

        val aBank = isBankNotification(a)
        val bBank = isBankNotification(b)
        // 必须恰好一方为银行通知类消息，另一方为非银行的普通支付/商户账单
        if (aBank == bBank) return false

        // 交易类型若均存在且明确冲突（如一收一支），不能误判为重复
        val t1 = a.billType
        val t2 = b.billType
        if (t1 != null && t2 != null && t1 != t2) return false

        return true
    }

    private fun isBankMerchant(name: String): Boolean =
        BANK_KEYWORDS.any { name.contains(it) }

    private fun selectBetterMerchant(m1: String?, m2: String?): String? {
        val t1 = m1?.trim().orEmpty()
        val t2 = m2?.trim().orEmpty()
        return when {
            t1.isEmpty() -> t2.takeIf { it.isNotEmpty() }
            t2.isEmpty() -> t1.takeIf { it.isNotEmpty() }
            isDirtyMerchant(t1) && !isDirtyMerchant(t2) -> t2
            !isDirtyMerchant(t1) && isDirtyMerchant(t2) -> t1
            // 真实商户名优先于银行公众号名（如 "瑞幸咖啡" 优于 "招商银行" / "招商银行信用卡"）
            !isBankMerchant(t1) && isBankMerchant(t2) -> t1
            isBankMerchant(t1) && !isBankMerchant(t2) -> t2
            t2.length > t1.length -> t2
            else -> t1
        }
    }

    private fun isDirtyMerchant(name: String): Boolean =
        name.startsWith("待") ||
            name.startsWith("wxid_") ||
            name == "财付通" ||
            name == "支付宝 - 交易" ||
            name == "微信支付凭证" ||
            name == "微信支付"

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
