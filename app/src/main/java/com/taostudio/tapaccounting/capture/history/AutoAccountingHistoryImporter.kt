package com.taostudio.tapaccounting.capture.history

import android.content.Context
import com.taostudio.tapaccounting.capture.CaptureApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 从同机 AutoAccounting 本地服务导入原始数据，等价于它数据页里的历史记录。
 *
 * 只导入 WeChat / Alipay，按 `md5(rawJson)` 去重；导入的事件只进历史表，
 * 不会自动生成待记账单，也不会替用户做二次确认。
 */
object AutoAccountingHistoryImporter {

    private const val BASE = "http://127.0.0.1:52045"
    private const val LIMIT = 500
    private const val MAX_PAGES = 40

    data class Result(
        val fetched: Int,
        val inserted: Int,
        val pages: Int,
        val error: String? = null,
    )

    private data class AppSource(val packageName: String, val source: String)

    private val apps = listOf(
        AppSource("com.tencent.mm", "WECHAT"),
        AppSource("com.eg.android.AlipayGphone", "ALIPAY"),
    )

    suspend fun import(context: Context): Result = withContext(Dispatchers.IO) {
        val repo = CaptureApp.from(context).eventRepository
        var fetched = 0
        var inserted = 0
        var pages = 0
        var error: String? = null

        for (app in apps) {
            var page = 1
            while (page <= MAX_PAGES) {
                val url = URL(
                    "$BASE/data/list?page=$page&limit=$LIMIT" +
                        "&app=${URLEncoder.encode(app.packageName, "UTF-8")}"
                )
                val body = try {
                    read(url)
                } catch (t: Throwable) {
                    error = t.message ?: t.javaClass.simpleName
                    return@withContext Result(fetched, inserted, pages, error)
                }
                val rows = try {
                    JSONObject(body).optJSONArray("data")
                } catch (t: Throwable) {
                    error = "AutoAccounting 返回不是 JSON：${t.message}"
                    return@withContext Result(fetched, inserted, pages, error)
                } ?: break

                pages++
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val raw = row.optString("data", "")
                    if (raw.isBlank()) continue
                    fetched++
                    val time = row.optLong("time", 0L).let {
                        when {
                            it <= 0L -> System.currentTimeMillis()
                            it < 10_000_000_000L -> it * 1000L
                            else -> it
                        }
                    }
                    val id = repo.insert(
                        source = app.source,
                        rawType = row.optString("type", "DATA"),
                        rawJson = raw,
                        receivedAt = time,
                        matched = row.optBoolean("match", false),
                        ruleName = row.optString("rule", "").takeIf { it.isNotBlank() },
                        autoType = null,
                        localType = null,
                        localSubType = null,
                        amountCents = null,
                        merchant = null,
                        categoryName = null,
                        channel = null,
                    )
                    if (id > 0L) inserted++
                }
                if (rows.length() < LIMIT) break
                page++
            }
        }
        Result(fetched, inserted, pages, error)
    }

    private fun read(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("AutoAccounting HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
