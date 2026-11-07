package com.taostudio.tapaccounting.capture.hook

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P5 后的插件只做「Hook → 原始报文 → 投递」：
 *
 * 1. 显式广播直接投给主应用 `CaptureIngestReceiver`（主应用在后台也能被拉起）；
 * 2. 同时把同一份 payload 丢给 `com.android.phone` 的 relay（52046）兜底排队；
 *    relay 会优先用 ContentResolver.call 调主应用 ingest Provider，失败会 5 秒重试。
 *
 * 插件不再持有 Room/规则/气泡/UI，也不再启动 1px Activity 或前台服务。
 * 主应用按 captureId 幂等，广播 + relay 双投递不会产生重复待记。
 */
internal object CaptureForwarder {

    private const val RULE_VERSION = "probe-2"
    private const val DEDUP_WINDOW_MS = 30_000L
    private const val CONTENT_DEDUP_WINDOW_MS = 2_000L
    private const val MAX_SEEN = 512
    private const val TAG = "TapCaptureForward"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 完全相同的原始报文：防止广播/relay/重试重复投递。 */
    private val seenRaw = ConcurrentHashMap<String, Long>()

    /** 去掉易变字段后的内容指纹：防止同一条通知 t/消息 id 不同被重复投递。 */
    private val seenContent = ConcurrentHashMap<String, Long>()

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

        // 核心兜底过滤：非明确金额且不含记账关键词的内容一律不投递（杜绝唤醒与落库开销）
        if (amountCents == null && !DataFilter.matches(raw) && !DataFilter.matches(merchant)) {
            return
        }

        val now = System.currentTimeMillis()
        val rawKey = md5(raw)
        if (!markSeen(seenRaw, rawKey, now, DEDUP_WINDOW_MS)) {
            log("forward skipped (exact dup ${DEDUP_WINDOW_MS / 1000}s) source=$source hash=${rawKey.take(8)}")
            return
        }

        val contentKey = RawContentFingerprint.of(source, dataType, raw)
        if (contentKey != null &&
            !markSeen(seenContent, contentKey, now, CONTENT_DEDUP_WINDOW_MS)
        ) {
            log(
                "forward skipped (content dup ${CONTENT_DEDUP_WINDOW_MS / 1000}s) " +
                    "source=$source hash=${contentKey.take(8)}",
            )
            return
        }

        val captureId = UUID.randomUUID().toString()

        scope.launch {
            log(
                "forward start source=$source captureId=$captureId txn=${sourceTxnId ?: "-"} " +
                    "amount=${amountCents ?: "-"} raw=${ProbeText.truncate(raw)}",
            )

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

            val context = contextOverride ?: appContext()
            if (context != null) {
                forwardToApp(context, payload, captureId)
            }

            val relayQueued = tryRelay(payload, captureId)
            if (relayQueued) {
                log("forward relay queued source=$source captureId=$captureId")
            } else if (context == null) {
                log("forward failed source=$source: no app context and relay down")
                seenRaw.remove(rawKey)
                contentKey?.let { seenContent.remove(it) }
            }
        }
    }

    /**
     * 显式广播给主应用。
     *
     * 显式组件不受 Android 11+ 包可见性影响；主应用没装/版本旧时广播会被系统静默丢弃，
     * 不影响下面再走 relay 队列。
     */
    private fun forwardToApp(context: Context, payload: String, captureId: String) {
        try {
            val intent = Intent(CaptureContract.APP_INGEST_ACTION).apply {
                component = ComponentName(
                    CaptureContract.APP_PACKAGE,
                    CaptureContract.APP_INGEST_RECEIVER_CLASS,
                )
                putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            log("forward app broadcast sent captureId=$captureId")
        } catch (t: Throwable) {
            log("forward app broadcast failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * relay 队列投递：长度前缀 + UTF-8 JSON。
     * relay 在 com.android.phone（UID 1001）常驻，负责把数据送到主应用并在成功后被 ACK 删除。
     */
    private fun tryRelay(payload: String, captureId: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", CaptureContract.RELAY_PORT),
                    250,
                )
                socket.soTimeout = 800
                val envelope = JSONObject()
                    .put("token", BuildConfig.CAPTURE_INGEST_TOKEN)
                    .put("captureId", captureId)
                    .put("payload", payload)
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                DataOutputStream(socket.getOutputStream()).use { out ->
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length <= 0 || length > 64 * 1024) return false
                val response = ByteArray(length)
                input.readFully(response)
                JSONObject(String(response, Charsets.UTF_8)).optBoolean("ok", false)
            }
        } catch (t: Throwable) {
            log("relay unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
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
            // 微信/支付宝用 WECHAT/ALIPAY；系统通知/短信直接使用真实包名。
            .put("source", source.trim())
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

    /** 在调用线程做，保证 DB 插入回调不会被拖慢。 */
    private fun markSeen(
        seen: ConcurrentHashMap<String, Long>,
        key: String,
        now: Long,
        windowMs: Long,
    ): Boolean {
        synchronized(seen) {
            if (seen.size > MAX_SEEN) {
                val cutoff = now - maxOf(windowMs, DEDUP_WINDOW_MS)
                seen.entries.removeAll { it.value < cutoff }
            }
            val previous = seen.put(key, now)
            return previous == null || now - previous > windowMs
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
        try {
            XposedBridge.log("[$TAG] $message")
        } catch (_: Throwable) {
            // XposedBridge 不可用时只保留 logcat。
        }
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
