package com.taostudio.tapaccounting.capture.dedup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 待记通知的内容指纹。
 *
 * 和插件侧同一套规则：去掉每次投递都会变的 t / cachedAt / 消息 id，
 * 保留金额、描述、商户、paysubtype、交易号、cachedPay* 等业务内容。
 */
internal object CaptureContentFingerprint {

    private val VOLATILE_KEYS = setOf(
        "t",
        "cachedat",
        "msgid",
        "msgsvrid",
        "notificationid",
        "posttime",
        "when",
    )

    fun of(source: String, rawJson: String?): String? {
        val raw = rawJson?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val canonical = runCatching { canonicalJson(raw) }.getOrNull()
            ?: normalizePlainText(raw)
        if (canonical.isBlank()) return null

        return md5(
            buildString {
                append(source.trim().uppercase())
                append('|')
                append(canonical)
            },
        )
    }

    private fun canonicalJson(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("{") -> canonicalValue(JSONObject(trimmed))
            trimmed.startsWith("[") -> canonicalValue(JSONArray(trimmed))
            else -> normalizePlainText(trimmed)
        }
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys()
            .asSequence()
            .filterNot { isVolatile(it) }
            .sorted()
            .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonicalValue(value.opt(key))
            }

        is JSONArray -> (0 until value.length())
            .joinToString(separator = ",", prefix = "[", postfix = "]") { index ->
                canonicalValue(value.opt(index))
            }

        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun isVolatile(key: String): Boolean =
        key.trim().lowercase() in VOLATILE_KEYS

    private fun normalizePlainText(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
