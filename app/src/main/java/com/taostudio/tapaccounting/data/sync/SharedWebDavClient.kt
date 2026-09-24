package com.taostudio.tapaccounting.data.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit

internal class WebDavHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long?,
    message: String
) : IOException(message)

class SharedWebDavClient {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val gzip = "application/gzip".toMediaType()
    private val xml = "text/xml; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // P1-14: 禁止重定向，避免凭据被带到其他主机（对齐备份侧 WebDavClient）
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class Config(val baseUrl: String, val username: String, val password: String)
    data class TextResource(val content: String, val etag: String?)

    fun ensureDirectory(config: Config, path: String) {
        var current = ""
        segments(path).forEach {
            current += "/$it"
            val key = "${config.baseUrl}|${config.username}|$current"
            if (ensuredDirectories.contains(key)) return@forEach
            execute(config, Request.Builder().url(url(config, current)).method("MKCOL", ByteArray(0).toRequestBody(null)).build(), setOf(200, 201, 204, 301, 302, 405)).close()
            ensuredDirectories.add(key)
        }
    }

    fun put(config: Config, path: String, content: String, ifMatch: String? = null) {
        val request = Request.Builder().url(url(config, path)).put(content.toRequestBody(json)).apply {
            if (!ifMatch.isNullOrBlank()) header("If-Match", ifMatch)
        }.build()
        execute(config, request, setOf(200, 201, 204)).close()
    }

    fun putGzip(config: Config, path: String, content: ByteArray) {
        val request = Request.Builder().url(url(config, path)).put(content.toRequestBody(gzip)).build()
        execute(config, request, setOf(200, 201, 204)).close()
    }

    fun get(config: Config, path: String): String {
        return getTextResource(config, path).content
    }

    fun getTextResource(config: Config, path: String): TextResource {
        val response = execute(config, Request.Builder().url(url(config, path)).get().build(), setOf(200))
        return response.use {
            TextResource(
                content = it.body?.string() ?: error("云端文件为空"),
                etag = it.header("ETag")
            )
        }
    }

    fun getBytes(config: Config, path: String): ByteArray {
        val response = execute(config, Request.Builder().url(url(config, path)).get().build(), setOf(200))
        return response.use { it.body?.bytes() ?: error("云端文件为空") }
    }

    fun exists(config: Config, path: String): Boolean {
        val request = Request.Builder().url(url(config, path)).head().build()
        return withAuth(config, request).let { client.newCall(it).execute() }.use {
            when {
                it.isSuccessful -> true
                it.code == 404 -> false
                else -> throw WebDavHttpException(it.code, retryAfterMillis(it.header("Retry-After")), errorMessage(it.code))
            }
        }
    }

    fun listOperations(config: Config, path: String): List<String> {
        val result = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, Int>>().apply { add(path.trimEnd('/') to 0) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeFirst()
            if (!visited.add(current) || depth > 4) continue
            listHrefs(config, current).forEach { href ->
                val relativeToCurrent = href.substringAfter(current.trim('/'), "").trim('/')
                if (relativeToCurrent.isBlank()) return@forEach
                val child = "$current/$relativeToCurrent"
                when {
                    child.endsWith(".json", true) || child.endsWith(".json.gz", true) -> {
                        val relativeToRoot = child.substringAfter(path.trim('/')).trimStart('/')
                        // P1-13: 禁止静默截断；超限时显式失败，避免丢同步操作
                        if (result.size >= 50_000) error("Shared operations list exceeded safety cap")
                        result += relativeToRoot
                    }
                    depth < 4 -> pending.add(child to depth + 1)
                }
            }
        }
        return result.distinct()
    }

    private fun listHrefs(config: Config, path: String): List<String> {
        val request = Request.Builder().url(url(config, path)).header("Depth", "1")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toRequestBody(xml)).build()
        val body = execute(config, request, setOf(207)).use { it.body?.string().orEmpty() }
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(body)) }
        val result = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':').equals("href", true)) {
                result += URLDecoder.decode(parser.nextText(), "UTF-8")
            }
            event = parser.next()
        }
        return result
    }

    private fun execute(config: Config, request: Request, ok: Set<Int>) = client.newCall(withAuth(config, request)).execute().also {
        if (it.code !in ok) {
            val message = errorMessage(it.code)
            val retryAfter = retryAfterMillis(it.header("Retry-After"))
            it.close()
            throw WebDavHttpException(it.code, retryAfter, message)
        }
    }

    private fun retryAfterMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L) * 1_000L }
        return runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun errorMessage(code: Int) = when (code) {
        401, 403 -> "账号或应用密码错误"
        429 -> "同步请求过于频繁"
        412 -> "共享成员信息已被其他设备更新"
        503 -> "坚果云暂时繁忙，请稍后再试"
        else -> "WebDAV HTTP $code"
    }

    private fun withAuth(config: Config, request: Request) = request.newBuilder().header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8)).build()
    private fun url(config: Config, path: String): String {
        val base = config.baseUrl.trim()
            .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            .trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) {
            "共享账本 WebDAV 必须使用有效的 HTTPS 地址"
        }
        return base + "/" + segments(path).joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }
    private fun segments(path: String) = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** 同一进程内只确认一次稳定目录；失败的 MKCOL 不会写入缓存。 */
        private val ensuredDirectories = Collections.synchronizedSet(mutableSetOf<String>())
    }
}
