package com.taostudio.tapaccounting.capture.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * hook -> 插件的唯一入口。
 *
 * hook 跑在微信/支付宝 UID，拿不到 signature 权限，因此：
 * - exported Provider + token（BuildConfig.CAPTURE_INGEST_TOKEN）；
 * - 只实现 call() 的 ingest；
 * - 调用方应放到 IO 线程并设置 ~2s 超时，避免阻塞宿主 DB 线程。
 *
 * 返回 Bundle[CaptureContract.ACK_RESULT] = JSON ack 字符串。
 */
class CaptureIngestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != CaptureContract.INGEST_METHOD) {
            return ackError("unknown method: $method")
        }

        val expectedToken = BuildConfig.CAPTURE_INGEST_TOKEN
        val token = extras?.getString(CaptureContract.EXTRA_TOKEN) ?: arg
        if (expectedToken.isBlank() || token != expectedToken) {
            return ackError("invalid token")
        }

        val payloadJson = extras?.getString(CaptureContract.EXTRA_PAYLOAD)
            ?: return ackError("missing payload")

        return try {
            val payload = parsePayload(payloadJson)
            val app = context?.applicationContext as? CaptureApp
                ?: return ackError("provider not attached")
            AutoAccountingRuleEngine.init(app)
            val enriched = enrichWithRules(payload)

            val (outcome, count) = runBlocking(Dispatchers.IO) {
                val result = app.repository.insert(enriched)
                result to app.repository.countPending()
            }

            CaptureNotifier.refresh(app, count)
            ackOk(outcome.capture.id, outcome.inserted, count)
        } catch (t: Throwable) {
            Log.w(TAG, "ingest failed", t)
            ackError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun ackOk(captureId: String, inserted: Boolean, count: Int): Bundle =
        bundleOf(
            JSONObject()
                .put("ok", true)
                .put("contractVersion", CaptureContract.VERSION)
                .put("captureId", captureId)
                .put("duplicate", !inserted)
                .put("count", count)
                .toString(),
        )

    private fun ackError(message: String): Bundle =
        bundleOf(
            JSONObject()
                .put("ok", false)
                .put("contractVersion", CaptureContract.VERSION)
                .put("error", message)
                .toString(),
        )

    private fun bundleOf(json: String): Bundle = Bundle().apply {
        putString(CaptureContract.ACK_RESULT, json)
    }

    /**
     * 用 AutoAccounting 原始规则解析 rawJson。
     * 即使探针已经抓到了金额，也以规则输出为准（规则包含商户/支付方式/时间等更完整的语义）。
     * 规则失败/未命中 → 保留探针值，绝不因此拒绝写入。
     *
     * 阶段一（组合 B）：支出/收入/转账/还款/退款都写入待记，并带上本地类型；
     * 规则命中但本地类型无法判断（如 Expend+退款）时不写类型字段，标记待分类。
     */
    private fun enrichWithRules(payload: CapturePayload): CapturePayload {
        val raw = payload.rawJson ?: return payload
        if (raw.isBlank()) return payload

        val outcome = try {
            AutoAccountingRuleEngine.parse(payload.source, payload.rawType, raw)
        } catch (t: Throwable) {
            Log.w(TAG, "rule engine failed", t)
            return payload
        }

        return when (outcome) {
            is AutoAccountingRuleEngine.ParseOutcome.NoMatch -> {
                Log.i(TAG, "rule no match, keep payload type=${payload.billType ?: "unclassified"}")
                payload
            }

            is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                val parsed = outcome.rule
                val amount = parsed.amountCents
                val enriched = payload.copy(
                    amountCents = amount,
                    paidAt = parsed.paidAt.takeIf { it > 0L } ?: payload.paidAt,
                    merchant = parsed.merchant ?: payload.merchant,
                    paymentMethodRaw = parsed.accountNameFrom ?: payload.paymentMethodRaw,
                    currency = parsed.currency,
                    billType = parsed.localType ?: payload.billType,
                    subType = parsed.localSubType ?: payload.subType,
                    needsRefundLink =
                        if (parsed.localType != null) parsed.needsRefundLink else payload.needsRefundLink,
                    ruleVersion = AutoAccountingRuleEngine.RULE_VERSION,
                    // 规则只有金额有效时才会命中，所以这里一定是补全了金额。
                    needsAmount = false,
                )
                Log.i(
                    TAG,
                    "rule matched rule=${parsed.ruleName} auto=${parsed.type} " +
                        "local=${parsed.localType ?: "-"}/${parsed.localSubType ?: "-"} " +
                        "refundLink=${enriched.needsRefundLink} amount=${amount}",
                )
                enriched
            }
        }
    }

    /**
     * 宽容解析：constract 用 camelCase，文档示例用 snake_case，两者都接受。
     * 缺失字段保持 null，不编造；金额缺失时 needsAmount=true。
     */
    private fun parsePayload(json: String): CapturePayload {
        val obj = JSONObject(json)
        val now = System.currentTimeMillis()

        val captureId = obj.stringOrNull("captureId", "capture_id", "id")
            ?: UUID.randomUUID().toString()
        val source = (obj.stringOrNull("source") ?: "").uppercase(Locale.US)
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
            paymentMethodRaw = obj.stringOrNull("paymentMethodRaw", "payment_method_raw"),
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

    // P0 只读 call()，其余入口最小化。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("ingest only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ingest only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ingest only")

    private companion object {
        const val TAG = "TapCaptureIngest"
    }
}
