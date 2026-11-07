package com.taostudio.tapaccounting.capture.history

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.data.RuleBundleCodec
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 从同机 AutoAccounting（端口 52045）同步“数据规则”到本地 bundle。
 *
 * 只读本机环回 HTTP，不依赖云端订阅：
 * - `GET /rule/list?page=N&limit=1000` 分页拉全部规则；
 * - `GET /setting/get?key=setting_js_common` / `setting_js_category` 拉公共脚本。
 *
 * 同步只替换系统/导入规则，**永远保留用户自定义规则**；写入走
 * [RuleBundleStore.save] 的临时文件 + rename，失败不会碰旧 bundle。
 */
object AutoAccountingRuleSync {

    private const val BASE = "http://127.0.0.1:52045"
    private const val TAG = "TapCaptureRule"
    private const val PAGE_SIZE = 1_000
    private const val MAX_PAGES = 50
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000

    data class Result(
        val success: Boolean,
        val ruleCount: Int = 0,
        val userCount: Int = 0,
        val commonChars: Int = 0,
        val categoryChars: Int = 0,
        val error: String? = null,
    )

    suspend fun sync(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val existing = RuleBundleStore.load(context)
            // common 必须来自当前 AutoAccounting，避免新规则配到旧 common 上执行失败。
            val common = try {
                fetchSetting("setting_js_common")
            } catch (t: Throwable) {
                return@withContext Result(
                    success = false,
                    error = "同步失败：读不到自动记账的 common 规则脚本（${t.message ?: t.javaClass.simpleName}）。\n\n" +
                        "请先打开自动记账（本机服务 127.0.0.1:52045），再回来点一次同步。",
                )
            }
            if (common.isNullOrBlank()) {
                return@withContext Result(
                    success = false,
                    error = "自动记账没有返回 common 规则脚本。为避免覆盖现有规则，本次同步已取消。",
                )
            }
            // 分类脚本只保存备用，读取失败不阻断数据规则同步。
            val category = runCatching { fetchSetting("setting_js_category") }.getOrNull()
            val syncedRules = fetchAllRules()
            if (syncedRules.isEmpty()) {
                return@withContext Result(
                    success = false,
                    error = "自动记账没有返回任何数据规则。请先打开自动记账，确认本地服务 127.0.0.1:52045 正常运行。",
                )
            }
            val bundle = RuleBundleCodec.mergeForSync(
                existing = existing,
                syncedRules = syncedRules,
                common = common,
                category = category.orEmpty(),
            )
            val saved = RuleBundleStore.save(context, bundle)
            Result(
                success = true,
                ruleCount = syncedRules.size,
                userCount = saved.userCount,
                commonChars = saved.common.length,
                categoryChars = saved.category.length,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "local rule sync failed", t)
            Result(
                success = false,
                error = buildString {
                    append("同步失败：")
                    append(t.message ?: t.javaClass.simpleName)
                    append("\n\n请先打开自动记账（本机服务 127.0.0.1:52045），再回来点一次同步。")
                },
            )
        }
    }

    private fun fetchAllRules(): List<RuleBundleCodec.Rule> {
        val collected = LinkedHashMap<String, RuleBundleCodec.Rule>()
        var page = 1
        var total: Int? = null
        while (page <= MAX_PAGES) {
            val text = httpGet("$BASE/rule/list?page=$page&limit=$PAGE_SIZE").trim()
            val root = if (text.startsWith("[")) {
                JSONObject().put("data", JSONArray(text))
            } else {
                JSONObject(text)
            }
            val array = findRuleArray(root) ?: break
            if (total == null) total = findTotal(root)
            var addedOnPage = 0
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index) ?: continue
                val item = raw.optJSONObject("rule") ?: raw
                val rule = parseServerRule(item) ?: continue
                val key = "${rule.app}|${rule.type}|${rule.systemRuleName}"
                if (collected.put(key, rule) == null) addedOnPage++
            }
            // 服务端可能把 limit=1000 截成 100 条/页，因此不能按“本页不足 1000”判断结束；
            // 用 total / 本页无新增 / 空页来判断，避免漏规则或死循环。
            if (array.length() == 0) break
            if (addedOnPage == 0) break
            val knownTotal = total
            if (knownTotal != null && collected.size >= knownTotal) break
            page++
        }
        return collected.values.toList()
    }

    private fun parseServerRule(item: JSONObject): RuleBundleCodec.Rule? {
        if (item.isNull("app") || item.isNull("js") || item.isNull("systemRuleName")) return null
        val app = item.optString("app").trim()
        val type = item.optString("type").trim().uppercase(Locale.US)
        val js = item.optString("js")
        val systemRuleName = item.optString("systemRuleName").trim()
        if (app.isBlank() || js.isBlank() || !RuleBundleCodec.Rule.isValidRuleName(systemRuleName)) {
            return null
        }
        if (type != RuleBundleCodec.TYPE_DATA && type != RuleBundleCodec.TYPE_NOTICE) return null
        return RuleBundleCodec.Rule(
            app = app,
            type = type,
            name = item.optString("name").trim().ifBlank { systemRuleName },
            enabled = RuleBundleCodec.booleanValue(item, "enabled", true),
            js = js,
            systemRuleName = systemRuleName,
            source = RuleBundleCodec.SOURCE_IMPORT,
            creator = RuleBundleCodec.CREATOR_SYSTEM,
            id = item.optLong("id", 0L),
            struct = item.optString("struct", ""),
            autoRecord = RuleBundleCodec.booleanValue(item, "autoRecord", false),
            updateAt = item.optLong("updateAt", 0L),
        )
    }

    private fun fetchSetting(key: String): String? {
        val text = httpGet("$BASE/setting/get?key=$key").trim()
        // 少数版本会直接把脚本正文当 text/plain 返回。
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return text.takeIf { it.isNotBlank() }
        }
        val root = JSONObject(text)
        return extractString(root.opt("data"), 0)
            ?: extractString(root.opt("value"), 0)
            ?: extractString(root, 0)
    }

    /** 兼容 data 为字符串、`{value:...}`、`{settingValue:...}` 等常见返回结构。 */
    private fun extractString(value: Any?, depth: Int): String? {
        if (depth > 4) return null
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotBlank() }
            is JSONObject -> {
                val keys = listOf(
                    "value", "settingValue", "content", "js", "common", "category", "data", "text",
                )
                for (key in keys) {
                    if (!value.has(key) || value.isNull(key)) continue
                    extractString(value.opt(key), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    extractString(value.opt(index), depth + 1)?.let { return it }
                }
                null
            }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun findRuleArray(root: JSONObject): JSONArray? {
        root.optJSONArray("data")?.let { return it }
        root.optJSONArray("rules")?.let { return it }
        root.optJSONArray("list")?.let { return it }
        val data = root.optJSONObject("data")
        if (data != null) {
            for (key in listOf("list", "records", "rows", "items", "rules", "ruleList")) {
                data.optJSONArray(key)?.let { return it }
            }
        }
        return null
    }

    private fun findTotal(root: JSONObject): Int? {
        // 只信 total；count 在不同服务里可能表示页数，误用会提前停止分页。
        val direct = root.optInt("total", -1)
        if (direct > 0) return direct
        val data = root.optJSONObject("data") ?: return null
        val dataTotal = data.optInt("total", -1)
        return dataTotal.takeIf { it > 0 }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${conn.responseMessage}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
