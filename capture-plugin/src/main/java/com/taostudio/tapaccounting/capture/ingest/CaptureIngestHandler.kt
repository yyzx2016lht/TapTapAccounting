package com.taostudio.tapaccounting.capture.ingest

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import com.taostudio.tapaccounting.capture.service.CaptureKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * hook -> 插件的实际入库逻辑。
 */
internal object CaptureIngestHandler {

    private val ingestLock = Any()
    private val recentIngestedIds = ConcurrentHashMap<String, Long>()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

            val now = System.currentTimeMillis()
            recentIngestedIds[payload.captureId]?.let { prev ->
                if (now - prev < 60_000L) {
                    return ackOk(payload.captureId, inserted = false, count = 0)
                }
            }

            synchronized(ingestLock) {
                recentIngestedIds[payload.captureId]?.let { prev ->
                    if (now - prev < 60_000L) {
                        return ackOk(payload.captureId, inserted = false, count = 0)
                    }
                }
                if (recentIngestedIds.size > 512) {
                    recentIngestedIds.entries.removeAll { now - it.value > 60_000L }
                }
                recentIngestedIds[payload.captureId] = now

                AutoAccountingRuleEngine.init(app)
                val analysis = enrichWithRules(payload)
                val baseEnriched = analysis.payload

                val (outcome, pendingItems) = runBlocking(Dispatchers.IO) {
                    val enriched = baseEnriched?.let { item ->
                        val mappedAsset = item.assetName
                            ?: CaptureRuleMatcher.resolveAssetName(app, item.paymentMethodRaw)
                        val mappedToAsset = item.toAssetName?.let { rawTo ->
                            CaptureRuleMatcher.resolveAssetName(app, rawTo) ?: rawTo
                        }
                        val mappedCategory = CaptureRuleMatcher.resolveCategoryName(
                            context = app,
                            merchant = item.merchant,
                            shopItem = analysis.shopItem,
                            note = item.note,
                            fallbackCategory = item.categoryName,
                        )
                        // 不在入库与弹窗关键路径上同步跨进程调用主应用 ContentProvider（主应用冷启动或被冻结会阻塞 1~3 秒），
                        // 先以 PENDING 毫秒级落库并立即弹出悬浮窗，再去后台异步核对是否疑似重复。
                        item.copy(
                            assetName = mappedAsset,
                            toAssetName = mappedToAsset,
                            categoryName = mappedCategory,
                            status = CaptureStatus.PENDING,
                        )
                    }

                    // 先留原始事件（AutoAccounting AppDataModel 等价物）
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
                    val onlyExpense = CapturePrefs.isOnlyExpense(app)
                    result to app.repository.listPending(50, onlyExpense = onlyExpense)
                }

                val count = pendingItems.size

                // 第一优先级：立即在主线程弹出悬浮胶囊（不等待通知栏构建或跨进程 IPC）
                if (outcome != null && outcome.inserted) {
                    val cap = outcome.capture
                    val isExpenseOrRefund = cap.billType == null ||
                        cap.billType == LocalBillType.TYPE_EXPENSE ||
                        cap.subType == LocalBillType.SUBTYPE_REFUND ||
                        cap.needsRefundLink
                    if ((!CapturePrefs.isOnlyExpense(app) || isExpenseOrRefund) &&
                        CapturePrefs.getPromptMode(app) == CapturePrefs.MODE_BUBBLE
                    ) {
                        CaptureBubbleManager.showBubble(app, cap, count.coerceAtLeast(1))
                    }
                    // 后台异步检测主应用是否已有同金额近似时间账单
                    val amountCentsVal = cap.amountCents
                    if (amountCentsVal != null && amountCentsVal > 0L) {
                        bgScope.launch {
                            val dup = runCatching {
                                CaptureBridgeClient(app).hasRecentMatchingBill(
                                    paidAt = cap.paidAt,
                                    amountCents = amountCentsVal,
                                )
                            }.getOrDefault(false)
                            if (dup) {
                                runCatching {
                                    app.repository.updateStatus(cap.id, CaptureStatus.POSSIBLE_DUPLICATE)
                                }
                            }
                        }
                    }
                }

                CaptureNotifier.refreshWithItems(app, pendingItems)
                CaptureKeepAliveService.start(app)

                if (outcome == null) {
                    ackIgnored(payload.captureId, count)
                } else {
                    ackOk(outcome.capture.id, outcome.inserted, count)
                }
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

    private data class Enriched(
        val payload: CapturePayload?,
        val matched: Boolean,
        val ruleName: String?,
        val autoType: String?,
        val channel: String?,
        val shopItem: String? = null,
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
                    shopItem = parsed.shopItem,
                )
            }
        }
    }

    private fun parsePayload(json: String): CapturePayload {
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
