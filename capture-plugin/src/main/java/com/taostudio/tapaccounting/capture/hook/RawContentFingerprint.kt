package com.taostudio.tapaccounting.capture.hook

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 通知级内容指纹：只用于判断“是不是同一条通知被重复投递”。
 *
 * 不判断交易是否相同：
 * - 去掉每次投递都会变的字段（t、cachedAt、消息/通知 id、发布时间）；
 * - 保留金额、描述、商户、对方、paysubtype、交易号、cachedPay* 等业务内容；
 * - 同一 source + rawType 下内容完全相同，才认为可能是同一条通知。
 */
internal object RawContentFingerprint {

    private val VOLATILE_KEYS = setOf(
        "t",
        "cachedat",
        "msgid",
        "msgsvrid",
        "notificationid",
        "posttime",
        "when",
    )

    fun of(source: String, rawType: String, rawJson: String?): String? {
        val raw = rawJson?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val canonical = runCatching { canonicalJson(raw) }.getOrNull()
            ?: normalizePlainText(raw)
        if (canonical.isBlank()) return null

        val payload = buildString {
            append(source.trim().uppercase())
            append('|')
            append(rawType.trim().uppercase())
            append('|')
            append(canonical)
        }
        return md5(payload)
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
