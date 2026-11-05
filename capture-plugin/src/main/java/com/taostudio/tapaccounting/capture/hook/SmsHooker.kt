package com.taostudio.tapaccounting.capture.hook

import android.app.AndroidAppHelper
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 短信 hook，等价于 AutoAccounting 的 SmsHooker + SmsIntentHooker。
 *
 * 运行在 com.android.phone（TeleService，常驻系统进程）。银行短信是自动记账
 * 的重要账单来源；规则资产里 com.android.phone 有 49 条 DATA 规则。
 */
internal object SmsHooker {

    private const val TAG = "TapCaptureSms"
    private const val DEDUP_TTL_MS = 60_000L
    private const val MAX_SEEN = 1024

    private val seen = ConcurrentHashMap<String, Long>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.InboundSmsHandler",
                lpparam.classLoader,
            ) ?: run {
                log("InboundSmsHandler not found")
                return
            }
            var count = 0
            clazz.declaredMethods
                .filter { it.name == "dispatchIntent" }
                .forEach { method ->
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val intent = param.args.firstOrNull { it is Intent } as? Intent
                                        ?: return
                                    if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
                                    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                                    if (parts.isEmpty()) return
                                    val sender = Normalizer.normalize(
                                        parts[0].displayOriginatingAddress.orEmpty(),
                                        Normalizer.Form.NFC,
                                    )
                                    val body = Normalizer.normalize(buildBody(parts), Normalizer.Form.NFC)
                                    if (body.isBlank()) return
                                    handle(sender, body)
                                } catch (t: Throwable) {
                                    log("dispatchIntent handle failed: ${t.javaClass.simpleName}: ${t.message}")
                                }
                            }
                        })
                        count++
                    } catch (_: Throwable) {
                        // 忽略单个重载。
                    }
                }
            log("hook installed: dispatchIntent methods=$count")
        } catch (t: Throwable) {
            log("hook unavailable: InboundSmsHandler.dispatchIntent (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private fun buildBody(parts: Array<SmsMessage>): String {
        if (parts.size == 1) return parts[0].displayMessageBody.orEmpty()
        val sb = StringBuilder(160 * parts.size)
        parts.forEach { sb.append(it.displayMessageBody.orEmpty()) }
        return sb.toString()
    }

    private fun handle(sender: String, body: String) {
        // 核心过滤：必须命中账单动账白名单（排除验证码、营销广告、个人短信）
        if (!DataFilter.matches(body)) {
            return
        }

        val key = md5("$sender|$body")
        if (!markSeen(key)) {
            log("skip duplicate sender=$sender")
            return
        }
        val rawJson = JSONObject()
            .put("sender", sender)
            .put("body", body)
            .put("t", System.currentTimeMillis())
            .toString()
        log("sms sender=$sender body=${body.take(100)}")
        CaptureForwarder.forward(
            source = "com.android.phone",
            rawJson = rawJson,
            merchant = sender.takeIf { it.isNotBlank() },
            dataType = "DATA",
            contextOverride = AndroidAppHelper.currentApplication(),
        )
    }

    private fun markSeen(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (seen.size > MAX_SEEN) {
            seen.entries.removeAll { now - it.value > DEDUP_TTL_MS }
        }
        val previous = seen.put(key, now)
        return previous == null || now - previous > DEDUP_TTL_MS
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun log(message: String) {
        try {
            XposedBridge.log("[$TAG] $message")
        } catch (_: Throwable) {
            // ignore
        }
    }
}
