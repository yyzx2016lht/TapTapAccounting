package com.taostudio.tapaccounting.capture.hook

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 探针版的轻量文本工具：只做“明显能确定”的提取。
 *
 * 原则：找不到就返回 null，宁可让用户补金额，也不猜字段/单位。
 */
internal object ProbeText {

    private const val MAX_AMOUNT_YUAN = 1_000_000.0

    /** 带货币符号的金额，优先使用。 */
    private val CURRENCY_AMOUNT = Regex("""[¥￥]\s*([0-9]{1,9}(?:\.[0-9]{1,2})?)""")

    /** 支付上下文里的金额，如“付款金额 12.00”“实付：12”。 */
    private val CONTEXT_AMOUNT = Regex(
        """(?:实付|金额|付款|收款|转账|消费|扣款|退款|支付)\D{0,8}([0-9]{1,9}(?:\.[0-9]{1,2})?)""",
    )

    /** JSON 里明确叫 amount 的字段；字段名足够明确，整数也按“元”处理。 */
    private val JSON_AMOUNT = Regex(
        """"(?:amount|payAmount|totalAmount|receiptAmount|tradeAmount|actualAmount)"\s*:\s*"?([0-9]{1,9}(?:\.[0-9]{1,2})?)"""",
        RegexOption.IGNORE_CASE,
    )

    /** fee 在微信里通常是元，但只在有小数时采信，避免把分当成元。 */
    private val JSON_FEE = Regex(
        """"fee"\s*:\s*"?([0-9]{1,9}\.[0-9]{1,2})"""",
        RegexOption.IGNORE_CASE,
    )

    private val PAY_METHODS = listOf(
        "零钱通",
        "零钱",
        "余额宝",
        "花呗",
        "信用卡",
        "储蓄卡",
        "借记卡",
        "银行卡",
        "经营账户",
        "余额",
    )

    private val SHOP_HINTS = listOf(
        "转账",
        "红包",
        "付款给",
        "收款方",
        "商户",
        "收款",
        "消费",
    )

    fun parseAmountCents(text: String, requireCurrency: Boolean = true): Long? {
        CURRENCY_AMOUNT.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            toCents(value)?.let { return it }
        }
        if (requireCurrency) return null
        CONTEXT_AMOUNT.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            toCents(value)?.let { return it }
        }
        return null
    }

    /** 从 JSON 文本里的 amount/fee 字段提取；失败返回 null。 */
    fun parseJsonAmountCents(text: String): Long? {
        JSON_AMOUNT.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            toCents(value)?.let { return it }
        }
        JSON_FEE.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            toCents(value)?.let { return it }
        }
        return null
    }

    fun looksLikeAmount(text: String): Boolean =
        CURRENCY_AMOUNT.containsMatchIn(text) || CONTEXT_AMOUNT.containsMatchIn(text)

    fun findPaymentMethod(text: String): String? =
        PAY_METHODS.firstOrNull { text.contains(it) }

    fun looksLikeShop(text: String): Boolean =
        SHOP_HINTS.any { text.contains(it) }

    /** 微信消息 XML 里取一个标签的文本（支持 CDATA）；失败返回 null。 */
    fun xmlTagText(xml: String, tag: String): String? {
        if (xml.isEmpty()) return null
        val pattern = Regex(
            """<$tag\b[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</$tag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return pattern.find(xml)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::decodeXmlEntities)
    }

    /** 过滤掉“微信支付”这类不是商户名的候选。 */
    fun cleanMerchant(vararg candidates: String?): String? {
        val rejected = listOf("微信支付", "微信转账", "支付宝", "￥", "¥")
        return candidates.asSequence()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() && it.length <= 80 }
            .firstOrNull { candidate -> rejected.none { candidate.contains(it) } }
    }

    fun pickString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /**
     * 在 JSON 里按 key 找交易号/订单号；只查浅层，避免把无关数字当订单号。
     */
    fun findTxnId(obj: JSONObject, keys: Set<String>, maxDepth: Int = 3): String? {
        if (maxDepth < 0) return null

        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }

        if (maxDepth == 0) return null
        val nestedObjects = obj.keys().asSequence().mapNotNull { key ->
            obj.optJSONObject(key)
        }.toList()
        for (nested in nestedObjects) {
            findTxnId(nested, keys, maxDepth - 1)?.let { return it }
        }
        val nestedArrays = obj.keys().asSequence().mapNotNull { key ->
            obj.optJSONArray(key)
        }.toList()
        for (array in nestedArrays) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                findTxnId(item, keys, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    fun truncate(raw: String, max: Int = 2000): String {
        if (raw.length <= max) return raw
        return raw.take(max) + "…(len=${raw.length})"
    }

    fun toCents(value: String): Long? {
        val normalized = value.trim()
            .replace(",", "")
            .replace("¥", "")
            .replace("￥", "")
            .trim()
        if (normalized.isEmpty()) return null

        return try {
            val yuan = BigDecimal(normalized)
            if (yuan <= BigDecimal.ZERO) return null
            if (yuan > BigDecimal.valueOf(MAX_AMOUNT_YUAN.toLong())) return null
            yuan.movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeXmlEntities(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
