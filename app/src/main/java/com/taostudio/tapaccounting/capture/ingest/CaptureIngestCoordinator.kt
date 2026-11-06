package com.taostudio.tapaccounting.capture.ingest

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CaptureEventRepository
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import com.taostudio.tapaccounting.capture.rule.CaptureRuleBundleBootstrap
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * 主应用侧的唯一待记入库入口。
 *
 * 流程与插件版 `CaptureIngestHandler` 保持一致：规则解析 → 资产映射 → 去重 → 落库 → 通知。
 * 区别是这里不再有跨进程桥接：通知直接读本地 DB，入账由主应用自己的
 * [com.taostudio.tapaccounting.capturebridge.CaptureImportService] 完成。
 *
 * P1 过渡期默认静默（见 [CapturePrefs.getPromptMode]），旧插件仍然负责它自己的气泡/通知；
 * 主应用只做双写落库，避免同一条支付弹两次。
 */
internal object CaptureIngestCoordinator {

    private const val TAG = "TapCaptureIngestApp"
    private const val DEDUP_TTL_MS = 60_000L
    private const val MAX_SEEN = 512
    private const val POSSIBLE_DUPLICATE_WINDOW_MS = 5 * 60_000L

    private val ingestMutex = Mutex()
    private val recentIngestedIds = ConcurrentHashMap<String, Long>()
    private val eventInsertCounter = AtomicInteger(0)
    private var bubbleJob: Job? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun ingest(context: Context, token: String?, payloadJson: String?): String {
        val expectedToken = BuildConfig.CAPTURE_INGEST_TOKEN
        if (expectedToken.isBlank() || token != expectedToken) {
            return ackError("invalid token")
        }
        if (payloadJson.isNullOrBlank()) {
            return ackError("missing payload")
        }

        val app = context.applicationContext ?: context
        return try {
            val payload = CapturePayloadParser.parse(payloadJson)
            ingestMutex.withLock {
                val now = System.currentTimeMillis()
                recentIngestedIds[payload.captureId]?.let { previous ->
                    if (now - previous < DEDUP_TTL_MS) {
                        CaptureRelayAck.ack(payload.captureId)
                        return@withLock ackOk(payload.captureId, inserted = false, count = 0)
                    }
                }
                if (recentIngestedIds.size > MAX_SEEN) {
                    recentIngestedIds.entries.removeAll { now - it.value > DEDUP_TTL_MS }
                }
                recentIngestedIds[payload.captureId] = now

                AutoAccountingRuleEngine.init(app)
                // 主 APK 不内置规则包；首次入库前确保已经从插件/文件导入。
                CaptureRuleBundleBootstrap.ensureImported(app)

                val analysis = enrichWithRules(payload)
                val enriched = analysis.payload?.let { item ->
                    val mappedAsset = item.assetName
                        ?: CaptureRuleMatcher.resolveAssetName(app, item.paymentMethodRaw)
                    val mappedToAsset = item.toAssetName?.let { rawTo ->
                        CaptureRuleMatcher.resolveAssetName(app, rawTo) ?: rawTo
                    }
                    // 分类只认用户备注/规则/AI；hook 入库不推断分类。
                    item.copy(
                        assetName = mappedAsset,
                        toAssetName = mappedToAsset,
                        categoryName = null,
                        status = CaptureStatus.PENDING,
                        channel = item.channel ?: analysis.channel,
                    )
                }

                val db = AppDatabase.getDatabase(app)
                val repository = CaptureRepository(db.paymentCaptureDao())
                val eventRepository = CaptureEventRepository(db.captureEventDao())

                val rawJson = payload.rawJson
                if (!rawJson.isNullOrBlank() &&
                    (analysis.matched || enriched != null || (payload.amountCents ?: 0L) > 0L)
                ) {
                    eventRepository.insert(
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
                        categoryName = null,
                        channel = analysis.channel,
                    )
                    // 原始报文只做诊断，保留最近 2000 条，避免 JSON 无限占空间。
                    if (eventInsertCounter.incrementAndGet() % 100 == 0) {
                        runCatching { eventRepository.trimToLatest(2000) }
                    }
                }

                val outcome = enriched?.let {
                    repository.insert(
                        it,
                        transferIncomeDedupEnabled = CapturePrefs.isTransferIncomeDedupEnabled(app),
                        transferIncomeDedupWindowMillis = CapturePrefs.getTransferIncomeDedupWindowMillis(app),
                    )
                }
                val onlyExpense = CapturePrefs.isOnlyExpense(app)
                val pendingItems = repository.listPending(50, onlyExpense = onlyExpense)
                val count = pendingItems.size

                if (outcome != null && outcome.inserted) {
                    maybeNotify(app, pendingItems)
                    maybeCheckPossibleDuplicate(app, outcome.capture)
                }

                val ack = if (outcome == null) {
                    ackIgnored(payload.captureId, count)
                } else {
                    ackOk(outcome.capture.id, outcome.inserted, count)
                }
                CaptureRelayAck.ack(payload.captureId)
                ack
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ingest failed", t)
            ackError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun maybeNotify(app: Context, pendingItems: List<PaymentCapture>) {
        val bubbleEnabled = CapturePrefs.isBubbleEnabled(app)
        val notifEnabled = CapturePrefs.isNotificationEnabled(app)
        if (!bubbleEnabled && !notifEnabled) {
            return
        }

        if (notifEnabled) {
            CaptureNotifier.refreshWithItems(app, pendingItems)
        }

        if (bubbleEnabled) {
            val latest = pendingItems.firstOrNull()
            if (latest == null ||
                !com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
                    .canDrawOverlays(app)
            ) {
                if (!notifEnabled) {
                    CaptureNotifier.refreshWithItems(app, pendingItems)
                }
                return
            }
            // 屏幕上已经有气泡：只更新笔数，不再弹第二个。
            if (com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager.isShowing()) {
                com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager
                    .updatePendingCount(pendingItems.size)
                return
            }
            // 同一笔支付可能连续到达 WCDB/AppMessage/到账通知多条事件；
            // 450ms 内只保留最后一次，避免一个动作连弹多个气泡。
            bubbleJob?.cancel()
            bubbleJob = bgScope.launch {
                delay(450L)
                val intent = android.content.Intent(
                    app,
                    com.taostudio.tapaccounting.OverlayService::class.java,
                ).apply {
                    action = com.taostudio.tapaccounting.OverlayService.ACTION_SHOW_CAPTURE_BUBBLE
                    putExtra(
                        com.taostudio.tapaccounting.OverlayService.EXTRA_CAPTURE_ID,
                        latest.id,
                    )
                }
                // 后台起 FGS 可能被 ROM 拒绝；失败时至少保留通知，不静默丢提醒。
                runCatching {
                    com.taostudio.tapaccounting.OverlayService.startCompat(app, intent)
                }.onFailure {
                    Log.w(TAG, "start bubble service failed: ${it.message}")
                    if (!notifEnabled) {
                        CaptureNotifier.refreshWithItems(app, pendingItems)
                    }
                }
            }
        }
    }

    private fun maybeCheckPossibleDuplicate(app: Context, capture: PaymentCapture) {
        val amountCents = capture.amountCents ?: return
        if (amountCents <= 0L) return
        bgScope.launch {
            val matched = runCatching {
                hasRecentMatchingBill(app, capture.paidAt, amountCents)
            }.getOrDefault(false)
            if (!matched) return@launch
            runCatching {
                val repository = CaptureRepository(AppDatabase.getDatabase(app).paymentCaptureDao())
                val current = repository.findById(capture.id)
                if (current != null && current.status == CaptureStatus.PENDING) {
                    repository.updateStatus(capture.id, CaptureStatus.POSSIBLE_DUPLICATE)
                }
            }.onFailure {
                Log.w(TAG, "possible duplicate check failed: ${it.message}")
            }
        }
    }

    private suspend fun hasRecentMatchingBill(
        context: Context,
        paidAt: Long,
        amountCents: Long,
    ): Boolean {
        val bills = AppDatabase.getDatabase(context).billDao()
            .getBillsBetweenTimesList(
                paidAt - POSSIBLE_DUPLICATE_WINDOW_MS,
                paidAt + POSSIBLE_DUPLICATE_WINDOW_MS,
            )
        return bills.any { bill ->
            bill.type == Bill.TYPE_EXPENSE &&
                bill.subType != Bill.SUBTYPE_REFUND &&
                (bill.amount * 100.0).roundToLong() == amountCents
        }
    }

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
                    Log.i(TAG, "rule no match, keep explicit type=${payload.billType}")
                    Enriched(payload, matched = false, ruleName = null, autoType = null, channel = null)
                }
            }

            is AutoAccountingRuleEngine.ParseOutcome.Matched -> {
                val parsed = outcome.rule
                val enriched = payload.copy(
                    amountCents = parsed.amountCents,
                    paidAt = parsed.paidAt.takeIf { it > 0L } ?: payload.paidAt,
                    merchant = parsed.merchant ?: payload.merchant,
                    paymentMethodRaw = parsed.accountNameFrom ?: payload.paymentMethodRaw,
                    toAssetName = parsed.accountNameTo ?: payload.toAssetName,
                    currency = parsed.currency,
                    billType = parsed.localType ?: payload.billType,
                    subType = parsed.localSubType ?: payload.subType,
                    needsRefundLink =
                        if (parsed.localType != null) parsed.needsRefundLink else payload.needsRefundLink,
                    ruleVersion = AutoAccountingRuleEngine.RULE_VERSION,
                    needsAmount = false,
                    channel = parsed.channel ?: payload.channel,
                )
                Log.i(
                    TAG,
                    "rule matched rule=${parsed.ruleName} auto=${parsed.type} " +
                        "local=${parsed.localType ?: "-"}/${parsed.localSubType ?: "-"} " +
                        "refundLink=${enriched.needsRefundLink} amount=${parsed.amountCents}",
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
}
