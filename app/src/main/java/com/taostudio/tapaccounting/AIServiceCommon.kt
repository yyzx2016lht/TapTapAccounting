package com.taostudio.tapaccounting

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.util.Locale

/** 调用 OpenCode Go 等网关时随请求发送的客户端标识（官方要求第三方客户端自定义 UA）。 */
internal const val AI_USER_AGENT = "FlipAccounting-AI/1.0"

internal fun isOpencodeHost(url: String): Boolean =
    url.contains("opencode.ai", ignoreCase = true)

/** OpenCode 网关建议的附加请求头：自定义 UA + 稳定会话 ID（便于路由与缓存）。 */
internal fun opencodeExtraHeaders(url: String, sessionId: String?): Map<String, String> =
    if (isOpencodeHost(url)) {
        buildMap {
            put("User-Agent", AI_USER_AGENT)
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("x-opencode-session", it) }
        }
    } else {
        emptyMap()
    }

internal fun normalizeBaseUrl(url: String): String {
    var baseUrl = url
    if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
    if (!baseUrl.endsWith("/")) baseUrl += "/"
    return baseUrl
}

internal fun shortenForModel(text: String, maxChars: Int, preserveTail: Boolean = true): String {
    if (text.length <= maxChars) return text
    if (maxChars <= 200) return text.take(maxChars)
    val head = (maxChars * 0.7).toInt()
    val tail = if (preserveTail) maxChars - head - 32 else 0
    return if (preserveTail && tail > 0) {
        text.take(head) + "\n\n[内容过长，已省略中间部分]\n\n" + text.takeLast(tail)
    } else {
        text.take(maxChars) + "\n\n[内容过长，已截断]"
    }
}

internal fun detectSpeechAudioMimeType(audioFile: File): String = when (audioFile.extension.lowercase(Locale.ROOT)) {
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}

internal fun detailedHttpError(e: Exception): String {
    if (e is HttpException) {
        val code = e.code()
        val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        return if (body.isNotBlank()) "HTTP $code, errorBody=$body" else "HTTP $code, message=${e.message()}"
    }
    return e.message ?: e.javaClass.simpleName
}

internal fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").trim().takeIf { it.isNotBlank() && it != "null" }
}

internal fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getDouble(name) }.getOrNull()
}

internal fun cleanJsonString(input: String): String {
    var s = input.trim()
    if (s.startsWith("```json")) s = s.removePrefix("```json")
    if (s.startsWith("```")) s = s.removePrefix("```")
    if (s.endsWith("```")) s = s.removeSuffix("```")
    s = s.trim()
    // Models sometimes prefix valid JSON with status text like "已记账：{...}".
    if (s.startsWith("{") || s.startsWith("[")) return s
    extractFirstJsonObjectText(s)?.let { return it }
    val arrayStart = s.indexOf('[')
    if (arrayStart >= 0) {
        val arrayEnd = s.lastIndexOf(']')
        if (arrayEnd > arrayStart) {
            val candidate = s.substring(arrayStart, arrayEnd + 1).trim()
            if (candidate.startsWith("[")) return candidate
        }
    }
    return s
}

internal fun adaptChatRequestForProvider(
    providerId: String,
    requestJson: JsonObject
): JsonObject {
    val adapted = requestJson.deepCopy()
    // P1-24: 二次 adapt 时 enable_thinking 已被移除，不能据此关掉 thinking
    val thinkingEnabled = when {
        adapted.has("thinking") -> adapted.get("thinking")?.asJsonObject
            ?.get("type")?.asString == "enabled"
        else -> adapted.remove("enable_thinking")?.asBoolean == true
    }
    if (adapted.has("thinking") && !adapted.has("enable_thinking")) {
        return adapted
    }
    // glm 系列用 reasoning_effort 控制思考，二次 adapt 时保持首次结果。
    if (adapted.has("reasoning_effort") && !adapted.has("enable_thinking")) {
        return adapted
    }
    val model = adapted.get("model")?.asString.orEmpty()

    when (providerId) {
        AiProviderRegistry.PROVIDER_DEEPSEEK -> {
            adapted.add("thinking", JsonObject().apply {
                addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
            })
        }

        AiProviderRegistry.PROVIDER_MIMO -> {
            if (!model.endsWith("-asr")) {
                adapted.add("thinking", JsonObject().apply {
                    addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                })
            }
        }

        AiProviderRegistry.PROVIDER_KIMI -> {
            if (model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6")) {
                adapted.add("thinking", JsonObject().apply {
                    addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                })
                // 不覆盖调用方指定的 temperature，避免记账场景（temperature=0.3）被改为 1.0
            }
        }

        AiProviderRegistry.PROVIDER_OPENCODE_GO -> {
            // zen 网关按底层模型族接受不同的关思考参数（2026-10 实测）：
            // deepseek/kimi/mimo 收 thinking.type；glm 只收 reasoning_effort；其余不猜参数。
            when {
                model.startsWith("deepseek-") || model.startsWith("kimi-") || model.startsWith("mimo-") ->
                    adapted.add("thinking", JsonObject().apply {
                        addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                    })

                model.startsWith("glm-") ->
                    adapted.addProperty("reasoning_effort", if (thinkingEnabled) "high" else "none")

                thinkingEnabled -> adapted.addProperty("enable_thinking", true)
            }
        }

        AiProviderRegistry.PROVIDER_SILICONFLOW,
        AiProviderRegistry.PROVIDER_QWEN -> when {
            // 混合思考模型默认开启思考，必须显式传 false 才能关掉。
            supportsEnableThinkingToggle(model) ->
                adapted.addProperty("enable_thinking", thinkingEnabled)

            thinkingEnabled -> adapted.addProperty("enable_thinking", true)
        }

        else -> if (thinkingEnabled) {
            adapted.addProperty("enable_thinking", true)
        }
    }

    if (adapted.get("stream")?.asBoolean == true &&
        providerId in setOf(
            AiProviderRegistry.PROVIDER_DEEPSEEK,
            AiProviderRegistry.PROVIDER_QWEN
        )
    ) {
        adapted.add("stream_options", JsonObject().apply {
            addProperty("include_usage", true)
        })
    }
    return adapted
}

/**
 * 硅基流动 / 通义千问上接受 enable_thinking=true/false 切换的混合思考模型。
 * 纯思考（-thinking、qwq）与纯指令（-instruct）版本不接受 false，ASR 模型不涉及思考。
 */
internal fun supportsEnableThinkingToggle(model: String): Boolean {
    val name = model.substringAfterLast('/').lowercase()
    if (name.contains("thinking") || name.contains("instruct") ||
        name.contains("asr") || name.startsWith("qwq")
    ) return false
    return name.startsWith("qwen3") ||
        name.startsWith("qwen-plus") ||
        name.startsWith("qwen-flash") ||
        name.startsWith("qwen-turbo") ||
        Regex("^deepseek-v3\\.[1-9]").containsMatchIn(name) ||
        Regex("^glm-4\\.[5-9]").containsMatchIn(name) ||
        name.startsWith("hunyuan-a13b")
}

internal fun extractFirstJsonObjectText(input: String): String? {
    val start = input.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until input.length) {
        val ch = input[i]
        if (escaped) {
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> if (inString) escaped = true
            '"' -> inString = !inString
            '{' -> if (!inString) depth++
            '}' -> if (!inString) {
                depth--
                if (depth == 0) {
                    return input.substring(start, i + 1).trim()
                }
            }
        }
    }
    return null
}

/**
 * 只抓阿拉伯数字的金额 token。中文数字（三十、两百）不入账，宁可漏检也不误判。
 */
private val DIGIT_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")

/**
 * 单数字总价护栏。
 *
 * 触发条件（三者同时满足才动手，任一不满足即原样放行）：
 * 1. 用户输入里只出现一个阿拉伯数字 N；
 * 2. 模型却拆出了两条及以上账单；
 * 3. 这些账单 amount 之和恰好等于 N，且 type / currency 一致。
 *
 * 这正是「用户只给了总价、模型自行分摊」的特征，收敛成一条总额账单即可，
 * 合并前后总金额不变，不会丢钱。
 * 「各10块」「给爸妈各转500」这类 Σ≠N 的情形不会命中，保持原样。
 */
internal fun collapseSingleTotalSplitBills(root: JSONObject, userInput: String): Boolean {
    val bills = root.optJSONArray("bills") ?: return false
    if (bills.length() < 2) return false

    val numbers = DIGIT_NUMBER_REGEX.findAll(userInput)
        .mapNotNull { it.value.toDoubleOrNull() }
        .filter { it > 0.0 }
        .toList()
    if (numbers.size != 1) return false
    val statedTotal = numbers[0]

    val parsed = (0 until bills.length()).mapNotNull { bills.optJSONObject(it) }
    if (parsed.size != bills.length()) return false

    val first = parsed.first()
    val firstType = first.optInt("type", 0)
    val firstCurrency = first.optNullableString("currency") ?: "CNY"
    val sum = parsed.sumOf { bill ->
        if (bill.optInt("type", 0) != firstType) return false
        val currency = bill.optNullableString("currency") ?: "CNY"
        if (!currency.equals(firstCurrency, ignoreCase = true)) return false
        bill.optNullableDouble("amount") ?: return false
    }
    if (kotlin.math.abs(sum - statedTotal) > 0.005) return false

    val mergedRemarks = parsed
        .mapNotNull { it.optNullableString("remarks") }
        .map { it.trim('、', '，', ',', ' ', ';', '；') }
        .filter { it.isNotBlank() }
        .distinct()
    first.put("amount", statedTotal)
    if (mergedRemarks.isNotEmpty()) {
        first.put("remarks", mergedRemarks.joinToString("、").take(200))
    }
    for (index in bills.length() - 1 downTo 1) {
        bills.remove(index)
    }
    return true
}

internal fun buildProbeAudioBase64(): String {
    val wavBytes = byteArrayOf(
        82, 73, 70, 70, 40, 0, 0, 0, 87, 65, 86, 69, 102, 109, 116, 32,
        16, 0, 0, 0, 1, 0, 1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2, 0, 16, 0,
        100, 97, 116, 97, 4, 0, 0, 0, 0, 0, 0, 0
    )
    return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
}

internal fun buildProbeImageBase64(ctx: Context): String {
    val bytes = ctx.resources.openRawResource(R.drawable.ic_screenshot).use { it.readBytes() }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

