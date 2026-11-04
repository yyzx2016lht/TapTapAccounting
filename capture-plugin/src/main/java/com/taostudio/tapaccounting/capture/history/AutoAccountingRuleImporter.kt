package com.taostudio.tapaccounting.capture.history

import android.content.Context
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CategoryRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从同机 AutoAccounting（自动记账，端口 52045）或导出的 JSON 中导入资产映射与分类规则。
 *
 * 保证开箱插件对所有普通用户是干净的，而老用户可一键将已有配置和规则完整迁移过来。
 */
object AutoAccountingRuleImporter {

    private const val BASE = "http://127.0.0.1:52045"
    private const val TIMEOUT_MS = 3500

    data class Result(
        val assetCount: Int = 0,
        val categoryCount: Int = 0,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /**
     * 从正在运行的 AutoAccounting 本地服务拉取映射与规则并写入 Room 数据库。
     */
    suspend fun importFromLocalServer(context: Context): Result = withContext(Dispatchers.IO) {
        var assetImported = 0
        var categoryImported = 0

        // 1. 获取资产映射 (GET /assets/map/list?limit=0)
        val assetJson = try {
            httpGet("$BASE/assets/map/list?limit=0")
        } catch (t: Throwable) {
            return@withContext Result(
                error = "无法连接自动记账本地服务（127.0.0.1:52045）：${t.message ?: t.javaClass.simpleName}"
            )
        }

        // 2. 获取分类规则 (POST /category/rule/list?page=1&limit=1000)
        val categoryJson = try {
            httpPost("$BASE/category/rule/list?page=1&limit=1000", "{}")
        } catch (t: Throwable) {
            // 如果只有资产映射成功，分类失败，仍可继续解析资产
            null
        }

        val db = CaptureApp.from(context).database
        val assetDao = db.assetMappingDao()
        val categoryDao = db.categoryRuleDao()

        // 解析并入库资产映射
        runCatching {
            val root = JSONObject(assetJson)
            val data = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val mapName = item.optString("mapName").trim()
                val regex = item.optBoolean("regex", false)
                val sort = item.optInt("sort", i)
                if (name.isNotEmpty() && mapName.isNotEmpty()) {
                    assetDao.upsert(
                        AssetMappingRule(
                            pattern = name,
                            targetAssetName = mapName,
                            isRegex = regex,
                            sort = sort,
                            enabled = true,
                        )
                    )
                    assetImported++
                }
            }
        }

        // 解析并入库分类规则
        if (!categoryJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(categoryJson)
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val enabled = item.optBoolean("enabled", true)
                    val sort = item.optInt("sort", i)
                    val elementStr = item.optString("element")
                    val jsStr = item.optString("js")

                    val rule = parseCategoryRule(elementStr, jsStr, sort, enabled)
                    if (rule != null) {
                        categoryDao.upsert(rule)
                        categoryImported++
                    }
                }
            }
        }

        Result(assetCount = assetImported, categoryCount = categoryImported)
    }

    /**
     * 将从 AutoAccounting 导出的 assets_map.json 与 category_rules.json 文本直接导入。
     */
    suspend fun importFromJson(
        context: Context,
        assetsJson: String?,
        categoryJson: String?,
    ): Result = withContext(Dispatchers.IO) {
        val db = CaptureApp.from(context).database
        var assetImported = 0
        var categoryImported = 0

        if (!assetsJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(assetsJson)
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val mapName = item.optString("mapName").trim()
                    val regex = item.optBoolean("regex", false)
                    val sort = item.optInt("sort", i)
                    if (name.isNotEmpty() && mapName.isNotEmpty()) {
                        db.assetMappingDao().upsert(
                            AssetMappingRule(
                                pattern = name,
                                targetAssetName = mapName,
                                isRegex = regex,
                                sort = sort,
                                enabled = true,
                            )
                        )
                        assetImported++
                    }
                }
            }
        }

        if (!categoryJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(categoryJson)
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val enabled = item.optBoolean("enabled", true)
                    val sort = item.optInt("sort", i)
                    val elementStr = item.optString("element")
                    val jsStr = item.optString("js")

                    val rule = parseCategoryRule(elementStr, jsStr, sort, enabled)
                    if (rule != null) {
                        db.categoryRuleDao().upsert(rule)
                        categoryImported++
                    }
                }
            }
        }

        Result(assetCount = assetImported, categoryCount = categoryImported)
    }

    /**
     * 从 AutoAccounting 的 element 或 js 字段解析出匹配关键词与目标分类。
     */
    private fun parseCategoryRule(
        elementStr: String?,
        jsStr: String?,
        sort: Int,
        enabled: Boolean,
    ): CategoryRule? {
        var keyword = ""
        var matchField = CategoryRule.MATCH_ANY
        var targetCategory = ""
        var targetBook: String? = null

        // 1. 优先尝试解析 element 数组
        if (!elementStr.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(elementStr)
                for (j in 0 until array.length()) {
                    val obj = array.optJSONObject(j) ?: continue
                    if (obj.has("category")) {
                        targetCategory = obj.optString("category").trim()
                        targetBook = obj.optString("book").trim().takeIf { it.isNotEmpty() }
                    }
                    if (obj.has("content")) {
                        val c = obj.optString("content").trim()
                        if (c.isNotEmpty() && keyword.isEmpty()) {
                            keyword = c
                            val type = obj.optString("type")
                            matchField = when (type) {
                                "shopName" -> CategoryRule.MATCH_MERCHANT
                                "remark" -> CategoryRule.MATCH_NOTE
                                else -> CategoryRule.MATCH_ANY
                            }
                        }
                    }
                }
            }
        }

        // 2. 如果 element 无法提取，从 js 代码中正则提取兜底
        if ((keyword.isEmpty() || targetCategory.isEmpty()) && !jsStr.isNullOrBlank()) {
            val kwRegex = Regex("""(?:shopName|shopItem|remark)\.indexOf\("([^"]+)"\)""")
            val kwMatch = kwRegex.find(jsStr)
            if (kwMatch != null && keyword.isEmpty()) {
                keyword = kwMatch.groupValues[1].trim()
                if (jsStr.contains("shopName.indexOf")) {
                    matchField = CategoryRule.MATCH_MERCHANT
                }
            }

            val catRegex = Regex("""category:\s*['"]([^'"]+)['"]""")
            val catMatch = catRegex.find(jsStr)
            if (catMatch != null && targetCategory.isEmpty()) {
                targetCategory = catMatch.groupValues[1].trim()
            }
        }

        if (keyword.isBlank() || targetCategory.isBlank()) return null

        return CategoryRule(
            keyword = keyword,
            matchField = matchField,
            isRegex = false,
            targetCategoryName = targetCategory,
            targetBookName = targetBook,
            sort = sort,
            enabled = enabled,
            source = CategoryRule.SOURCE_IMPORT,
        )
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${conn.responseMessage}")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${conn.responseMessage}")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
