package com.taostudio.tapaccounting.capture.hook

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 * 原始事件投递器：把 hook 抓到的 JSON 通过显式广播交给插件。
 *
 * 为什么不用 Provider：微信/支付宝的 manifest 没有声明本插件，Android 11+ 的
 * 包可见性会过滤掉 ContentResolver 对 [CaptureContract.INGEST_AUTHORITY] 的解析，
 * 而显式广播不受这个限制（真机验证：WeChat UID 发出的显式广播可送达插件）。
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
        val dedupKey = md5(raw)
        if (!markSeen(dedupKey, now)) {
            log("forward skipped (dup in ${DEDUP_WINDOW_MS / 1000}s) source=$source")
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

            // 1. 首选直连插件进程内 TCP 端口（127.0.0.1:52047）：
            //    插件未冻结时 1~3ms 内立即返回 ACK 并完成入库与弹窗；
            //    若插件在后台被 ColorOS Hans 冻结，100ms 快速超时后立即交由 com.android.phone (52046) 强制解冻。
            if (trySocketDeliver(
                    port = CaptureContract.PLUGIN_DIRECT_PORT,
                    payload = payload,
                    captureId = captureId,
                    connectTimeoutMs = 80,
                    soTimeoutMs = 100,
                )
            ) {
                log("forward direct delivered source=$source captureId=$captureId")
                return@launch
            }

            // 2. 插件被 Hans 冻结或未启动：立即投递给常驻 com.android.phone (UID 1001) 的 RelayServer (52046)，
            //    由 UID 1001 拉起 1px 透明 CaptureWakeActivity，3ms 内解除 Hans 冻结并弹窗（与自动记账完全一致）。
            val relayDelivered = trySocketDeliver(
                port = CaptureContract.RELAY_PORT,
                payload = payload,
                captureId = captureId,
                connectTimeoutMs = 250,
                soTimeoutMs = 800,
            )
            if (relayDelivered) {
                log("forward relay queued source=$source captureId=$captureId")
            }

            // 3. 若当前宿主（如微信/支付宝）本身在前台，也并行发送前台唤醒作为双保险（插件侧按 captureId 幂等去重）。
            val context = contextOverride ?: appContext()
            if (context != null) {
                wakePluginViaContext(context, payload, captureId)
            } else if (!relayDelivered) {
                log("forward failed source=$source: direct/relay down and no application context")
                seen.remove(dedupKey)
            }
        }
    }

    /**
     * 长度前缀 + UTF-8 JSON 的 localhost 协议；不需要 Android Context，
     * 因此 system_server 与微信/支付宝都能直接用。
     */
    private fun trySocketDeliver(
        port: Int,
        payload: String,
        captureId: String,
        connectTimeoutMs: Int,
        soTimeoutMs: Int,
    ): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port),
                    connectTimeoutMs,
                )
                socket.soTimeout = soTimeoutMs
                val out = DataOutputStream(socket.getOutputStream())
                val envelope = JSONObject()
                    .put("token", BuildConfig.CAPTURE_INGEST_TOKEN)
                    .put("captureId", captureId)
                    .put("payload", payload)
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length <= 0 || length > 64 * 1024) return false
                val response = ByteArray(length)
                input.readFully(response)
                JSONObject(String(response, Charsets.UTF_8)).optBoolean("ok", false)
            }
        } catch (t: Throwable) {
            log("socket($port) unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun tryProviderDeliver(context: Context, payload: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putString(CaptureContract.EXTRA_PAYLOAD, payload)
            }
            val res = context.contentResolver.call(
                Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}"),
                CaptureContract.INGEST_METHOD,
                BuildConfig.CAPTURE_INGEST_TOKEN,
                extras,
            )
            val ack = res?.getString(CaptureContract.ACK_RESULT) ?: return false
            JSONObject(ack).optBoolean("ok", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun wakePluginViaContext(context: Context, payload: String, captureId: String) {
        try {
            // 宿主当前处于前台（PROC_STATE_TOP）时，直接拉起前台服务可满足 ColorOS mAllowStart_noBinding=PROC_STATE_TOP
            val serviceIntent = Intent(CaptureContract.ACTION_SERVICE_INGEST).apply {
                component = ComponentName(
                    CaptureContract.PLUGIN_PACKAGE,
                    CaptureContract.INGEST_SERVICE_CLASS,
                )
                putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                putExtra(CaptureContract.EXTRA_RELAY_ID, captureId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(serviceIntent) }
            } else {
                runCatching { context.startService(serviceIntent) }
            }
        } catch (_: Throwable) {
        }

        try {
            val broadcastIntent = Intent(CaptureContract.ACTION_INGEST).apply {
                component = ComponentName(
                    CaptureContract.PLUGIN_PACKAGE,
                    CaptureContract.INGEST_RECEIVER_CLASS,
                )
                putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(broadcastIntent)
            log("forward wake broadcast sent source captureId=$captureId")
        } catch (t: Throwable) {
            log("forward wake broadcast failed: ${t.javaClass.simpleName}: ${t.message}")
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
            // 微信/支付宝用 WECHAT/ALIPAY；系统通知/短信直接使用真实包名，
            // 不能统一大写，否则规则资产的 cmb.pb 等 key 匹配不到。
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

    /** MD5(rawJson) + 窗口去重；在调用线程做，保证 DB 插入回调不会被拖慢。 */
    private fun markSeen(key: String, now: Long): Boolean {
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
        try {
            // 同时写入 LSPosed 模块日志，方便 adb 拉取完整的 cross-process 诊断。
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
