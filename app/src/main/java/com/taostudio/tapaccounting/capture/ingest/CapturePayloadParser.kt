package com.taostudio.tapaccounting.capture.ingest

import com.taostudio.tapaccounting.capture.contract.CapturePayload
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * 把 hook 投递的通用 JSON 解析成 [CapturePayload]。
 *
 * 字段名兼容插件历史写法（camelCase / snake_case / 常见别名），金额统一转成分。
 * 解析失败直接抛异常，由 ingestion 边界转成 `{ok:false}` ack，不把异常抛过 Binder。
 */
internal object CapturePayloadParser {

    fun parse(json: String): CapturePayload {
        val obj = JSONObject(json)
        val now = System.currentTimeMillis()

        val captureId = obj.stringOrNull("captureId", "capture_id", "id")
            ?: UUID.randomUUID().toString()
        val source = obj.stringOrNull("source") ?: ""
        require(source.isNotEmpty()) { "missing source" }

        val amountCents = when {
            obj.hasValue("amountCents") -> obj.longOrNull("amountCents")
            obj.hasValue("amount_cents") -> obj.longOrNull("amount_cents")
            obj.hasValue("amount") -> (obj.optDouble("amount", 0.0) * 100.0).roundToLong().takeIf {
                obj.opt("amount") is Number
            }
            else -> null
        }
        require(amountCents == null || amountCents >= 0L) { "negative amount" }

        val paidAt = obj.longOrNull("paidAt", "paid_at", "time")?.takeIf { it > 0L } ?: now
        val capturedAt = obj.longOrNull("capturedAt", "captured_at")?.takeIf { it > 0L } ?: now

        return CapturePayload(
            captureId = captureId,
            source = source,
            rawType = (obj.stringOrNull("rawType", "raw_type") ?: "DATA").uppercase(Locale.US),
            sourceTxnId = obj.stringOrNull("sourceTxnId", "source_txn_id", "txnId", "orderId"),
            amountCents = amountCents,
            currency = obj.stringOrNull("currency") ?: "CNY",
            merchant = obj.stringOrNull("merchant", "shopName", "shop_name"),
            categoryName = obj.stringOrNull("categoryName", "category_name"),
            paymentMethodRaw = obj.stringOrNull("paymentMethodRaw", "payment_method_raw"),
            assetName = obj.stringOrNull("assetName", "asset_name"),
            toAssetName = obj.stringOrNull("toAssetName", "to_asset_name"),
            paidAt = paidAt,
            capturedAt = capturedAt,
            bookName = obj.stringOrNull("bookName", "book_name"),
            note = obj.stringOrNull("note", "remarks"),
            billType = obj.intOrNull("billType", "bill_type"),
            subType = obj.intOrNull("subType", "sub_type"),
            needsRefundLink = obj.booleanOrNull("needsRefundLink", "needs_refund_link") ?: false,
            rawJson = obj.stringOrNull("rawJson", "raw_json") ?: json,
            ruleVersion = obj.stringOrNull("ruleVersion", "rule_version"),
            sourceAppVersion = obj.stringOrNull("sourceAppVersion", "source_app_version"),
            needsAmount = obj.booleanOrNull("needsAmount", "needs_amount") ?: (amountCents == null),
            excludeFromStats = obj.booleanOrNull("excludeFromStats", "exclude_from_stats") ?: false,
            channel = obj.stringOrNull("channel"),
            groupId = obj.stringOrNull("groupId", "group_id"),
        )
    }

    private fun JSONObject.hasValue(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.stringOrNull(vararg keys: String): String? {
        for (key in keys) {
            if (!hasValue(key)) continue
            val value = optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun JSONObject.longOrNull(vararg keys: String): Long? {
        for (key in keys) {
            if (!hasValue(key)) continue
            val value = when (val raw = opt(key)) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.intOrNull(vararg keys: String): Int? {
        for (key in keys) {
            if (!hasValue(key)) continue
            val value = when (val raw = opt(key)) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.booleanOrNull(vararg keys: String): Boolean? {
        for (key in keys) {
            if (!hasValue(key)) continue
            return optBoolean(key)
        }
        return null
    }
}
