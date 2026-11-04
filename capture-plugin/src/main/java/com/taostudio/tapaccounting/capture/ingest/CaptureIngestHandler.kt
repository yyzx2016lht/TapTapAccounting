package com.taostudio.tapaccounting.capture.ingest

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.service.CaptureKeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * hook -> 插件的实际入库逻辑。
 *
 * 两个入口共用：
 * - [CaptureIngestReceiver]：显式广播。微信/支付宝没有在 manifest 里声明本插件，
 *   受 Android 11+ 包可见性限制，ContentResolver 解析不到本插件的 Provider；
 *   显式广播不受包可见性过滤，是当前 hook 的主路径。
 * - [CaptureIngestProvider]：保留给同包/声明了 provider authority 的调用方和 adb 调试。
 *
 * 所有异常都在这里收敛成 ack JSON，调用方（Provider/Receiver）不需要再兜底。
 */
internal object CaptureIngestHandler {

    fun ingest(context: Context, token: String?, payloadJson: String?): String {
        val expectedToken = BuildConfig.CAPTURE_INGEST_TOKEN
        if (expectedToken.isBlank() || token != expectedToken) {
            return ackError("invalid token")
        }
        if (payloadJson.isNullOrBlank()) {
            return ackError("missing payload")
        }

        return try {
            val payload = parsePayload(payloadJson)
            val app = context.applicationContext as? CaptureApp
                ?: return ackError("app not attached")
            AutoAccountingRuleEngine.init(app)
            val analysis = enrichWithRules(payload)
            val enriched = analysis.payload
            val (outcome, count) = runBlocking(Dispatchers.IO) {
                // 先留原始事件（AutoAccounting AppDataModel 等价物），
                // 未命中的丰巢/普通通知只进历史，不建待记。
                val rawJson = payload.rawJson
                if (!rawJson.isNullOrBlank()) {
                    app.eventRepository.insert(
                        source = payload.source,
                        rawType = payload.rawType,
                        rawJson = rawJson,
                        receivedAt = payload.capturedAt,
                        matched = analysis.matched,
                        ruleName = analysis.ruleName,
                        autoType = analysis.autoType,
                        localType = enriched?.billType ?: payload.billType,
                        localSubType = enriched?.subType ?: payload.subType,
                        amountCents = enriched?.amountCents ?: payload.amountCents,
                        merchant = enriched?.merchant ?: payload.merchant,
                        categoryName = enriched?.categoryName ?: payload.categoryName,
                        channel = analysis.channel,
                    )
                }
                val result = enriched?.let { app.repository.insert(it) }
                result to app.repository.countPending()
            }

            CaptureNotifier.refresh(app, count)
            // 收到任何一笔后再尝试拉起保活服务；后台启动失败会被 start() 吞掉。
            CaptureKeepAliveService.start(app)
            if (outcome == null) {
                ackIgnored(payload.captureId, count)
            } else {
                ackOk(outcome.capture.id, outcome.inserted, count)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ingest failed", t)
            ackError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun ackOk(captureId: String, inserted: Boolean, count: Int): String =
        JSONObject()
            .put("ok", true)
            .put("contractVersion", CaptureContract.VERSION)
            .put("captureId", captureId)
            .put("duplicate", !inserted)
            .put("count", count)
            .toString()

    private fun ackIgnored(captureId: String, count: Int): String =
        JSONObject()
            .put("ok", true)
            .put("contractVersion", CaptureContract.VERSION)
            .put("captureId", captureId)
            .put("ignored", true)
            .put("count", count)
            .toString()

    private fun ackError(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("contractVersion", CaptureContract.VERSION)
            .put("error", message)
            .toString()

    /**
     * 用 AutoAccounting 原始规则解析 rawJson。
     * 即使探针已经抓到了金额，也以规则输出为准（规则包含商户/支付方式/时间等更完整的语义）。
     * 规则失败/未命中 → 保留探针值，绝不因此拒绝写入。
     *
     * 阶段一（组合 B）：支出/收入/转账/还款/退款都写入待记，并带上本地类型；
     * 规则命中但本地类型无法判断（如 Expend+退款）时不写类型字段，标记待分类。
     */
    private data class Enriched(
        val payload: CapturePayload?,
        val matched: Boolean,
        val ruleName: String?,
        val autoType: String?,
        val channel: String?,
    )

    private fun enrichWithRules(payload: CapturePayload): Enriched {
        val raw = payload.rawJson
        if (raw.isNullOrBlank()) {
            return Enriched(payload, matched = false, ruleName = null, autoType = null, channel = null)
        }

        val outcome = try {
            AutoAccountingRuleEngine.parse(payload.source, payload.rawType, raw)
        } catch (t: Throwable) {
            Log.w(TAG, "rule engine failed", t)
            return Enriched(
                payload.takeIf { it.billType != null },
                matched = false,
                ruleName = null,
                autoType = null,
                channel = null,
            )
        }

        return when (outcome) {
            is AutoAccountingRuleEngine.ParseOutcome.NoMatch -> {
                if (payload.billType == null) {
                    Log.i(TAG, "rule no match, raw event recorded but no pending bill")
                    Enriched(null, matched = false, ruleName = null, autoType = null, channel = null)
                } else {
                    // 合成/探针显式给了类型，仍允许入库，便于调试。
                    Log.i(TAG, "rule no match, keep explicit type=${payload.billType}")
                    Enriched(payload, matched = false, ruleName = null, autoType = null, channel = null)
                }
            }

            is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                val parsed = outcome.rule
                val amount = parsed.amountCents
                val enriched = payload.copy(
                    amountCents = amount,
                    paidAt = parsed.paidAt.takeIf { it > 0L } ?: payload.paidAt,
                    merchant = parsed.merchant ?: payload.merchant,
                    categoryName = parsed.categoryName ?: payload.categoryName,
                    paymentMethodRaw = parsed.accountNameFrom ?: payload.paymentMethodRaw,
                    toAssetName = parsed.accountNameTo ?: payload.toAssetName,
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
                Enriched(
                    payload = enriched,
                    matched = true,
                    ruleName = parsed.ruleName,
                    autoType = parsed.type,
                    channel = parsed.channel,
                )
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
        // 保留原始大小写：WECHAT/ALIPAY 是枚举，cmb.pb 等是真实包名。
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

    private const val TAG = "TapCaptureIngest"
}
