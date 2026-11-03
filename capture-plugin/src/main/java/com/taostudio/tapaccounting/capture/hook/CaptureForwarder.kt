package com.taostudio.tapaccounting.capture.hook

import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 原始事件投递器：把 hook 抓到的 JSON 交给插件的 [CaptureContract.INGEST_AUTHORITY]。
 *
 * 探针版只投递原始数据：
 * - amountCents / merchant / paymentMethodRaw 可为 null；
 * - rawJson 原样保留，方便真机确认字段；
 * - dataType 区分 DATA（DB/WebView）/ NOTICE（通知），规则引擎按它选规则集；
 * - 30 秒内相同 rawJson 只投一次，避免同一条消息被 WCDB/WebView 反复插入。
 *
 * 所有工作都扔到 IO；hook 回调（尤其是微信 DB 线程）只做一次 MD5 去重。
 */
internal object CaptureForwarder {

    private const val RULE_VERSION = "probe-1"
    private const val DEDUP_WINDOW_MS = 30_000L
    private const val MAX_SEEN = 512
    private const val TAG = "TapCaptureForward"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = ConcurrentHashMap<String, Long>()

    fun forward(
        source: String,
        rawJson: String,
        merchant: String? = null,
        paymentMethodRaw: String? = null,
        sourceTxnId: String? = null,
        amountCents: Long? = null,
        dataType: String = "DATA",
        contextOverride: Context? = null,
    ) {
        val raw = rawJson.trim()
        if (raw.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!markSeen(raw, now)) {
            log("forward skipped (dup in ${DEDUP_WINDOW_MS / 1000}s) source=$source")
            return
        }

        val captureId = UUID.randomUUID().toString()

        scope.launch {
            log(
                "forward start source=$source captureId=$captureId txn=${sourceTxnId ?: "-"} " +
                    "amount=${amountCents ?: "-"} raw=${ProbeText.truncate(raw)}",
            )
            try {
                val context = contextOverride ?: appContext()
                if (context == null) {
                    log("forward failed source=$source: no application context")
                    return@launch
                }

                val payload = buildPayload(
                    captureId = captureId,
                    source = source,
                    rawJson = raw,
                    merchant = merchant,
                    paymentMethodRaw = paymentMethodRaw,
                    sourceTxnId = sourceTxnId,
                    amountCents = amountCents,
                    dataType = dataType,
                    now = now,
                )
                val extras = Bundle().apply {
                    putString(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                    putString(CaptureContract.EXTRA_PAYLOAD, payload)
                }

                val ack = context.contentResolver.call(
                    Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}"),
                    CaptureContract.INGEST_METHOD,
                    BuildConfig.CAPTURE_INGEST_TOKEN,
                    extras,
                )
                log(
                    "forward done source=$source captureId=$captureId " +
                        "ack=${ack?.getString(CaptureContract.ACK_RESULT)?.take(500) ?: "-"}",
                )
            } catch (t: Throwable) {
                // 宿主进程里绝不能因为投递失败而崩溃；只留日志。
                log("forward failed source=$source: ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    private fun buildPayload(
        captureId: String,
        source: String,
        rawJson: String,
        merchant: String?,
        paymentMethodRaw: String?,
        sourceTxnId: String?,
        amountCents: Long?,
        dataType: String,
        now: Long,
    ): String {
        val json = JSONObject()
            .put("captureId", captureId)
            .put("source", source.uppercase(Locale.US))
            .put("rawType", dataType.trim().uppercase(Locale.US).ifEmpty { "DATA" })
            .put("currency", "CNY")
            .put("paidAt", now)
            .put("capturedAt", now)
            .put("rawJson", rawJson)
            .put("ruleVersion", RULE_VERSION)
            .put("needsAmount", amountCents == null)

        merchant?.trim()?.takeIf { it.isNotEmpty() }?.let {
            json.put("merchant", it.take(200))
        }
        paymentMethodRaw?.trim()?.takeIf { it.isNotEmpty() }?.let {
            json.put("paymentMethodRaw", it.take(100))
        }
        sourceTxnId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            json.put("sourceTxnId", it.take(200))
        }
        amountCents?.takeIf { it >= 0L }?.let { json.put("amountCents", it) }

        return json.toString()
    }

    /** MD5(rawJson) + 窗口去重；在调用线程做，保证 DB 插入回调不会被拖慢。 */
    private fun markSeen(raw: String, now: Long): Boolean {
        val key = md5(raw)
        synchronized(seen) {
            if (seen.size > MAX_SEEN) {
                val cutoff = now - DEDUP_WINDOW_MS
                seen.entries.removeAll { it.value < cutoff }
            }
            val previous = seen.put(key, now)
            return previous == null || now - previous > DEDUP_WINDOW_MS
        }
    }

    private fun appContext(): Context? = try {
        AndroidAppHelper.currentApplication()
    } catch (_: Throwable) {
        null
    }

    private fun log(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // 宿主进程里日志失败也不能影响业务。
        }
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
