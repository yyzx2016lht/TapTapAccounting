package com.taostudio.tapaccounting

import android.content.Context
import com.bumptech.glide.Glide
import org.json.JSONObject
import java.io.InputStream

/**
 * 内置图标资源表。
 *
 * 分类与资产的 icon 字段存的是远程 URL（`http://res*.qianjiapp.com/...`）。
 * 该 CDN 只提供明文 HTTP，而清单已关闭 cleartext（安全审计 P1-22），
 * 于是所有图标请求被系统拦截（`Cleartext HTTP traffic ... not permitted`），
 * 界面上一片空白；清除数据会连 Glide 磁盘缓存里的旧图一起抹掉。
 *
 * 现在这些图标由 `tools/sync-bundled-icons.py` 固化进 `assets/icons/`，
 * 本对象负责 URL → assets 路径的查表，并把本地优先加载器挂进 Glide：
 * 命中内置资源的 URL 直接从 APK 读取，未命中的 URL（例如以后新增的
 * 自定义图标）仍走原来的网络加载路径。
 *
 * 重新生成资源：`python tools/sync-bundled-icons.py`（改动 raw JSON 后必须重跑，
 * 否则 `BundledIconCoverageTest` 会失败）。
 */
object BundledIcons {

    private const val TAG = "BundledIcons"
    private const val MAP_ASSET = "icons/icon_map.json"

    @Volatile
    private var urlToAsset: Map<String, String>? = null
    private val lock = Any()

    /** 返回 URL 对应的 assets 路径（如 `icons/cateic_licai.png`）；未内置则返回 null。 */
    fun bundledAssetPath(ctx: Context, url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return table(ctx)[url]
    }

    private fun table(ctx: Context): Map<String, String> {
        urlToAsset?.let { return it }
        synchronized(lock) {
            urlToAsset?.let { return it }
            return loadMap(ctx).also { urlToAsset = it }
        }
    }

    private fun loadMap(ctx: Context): Map<String, String> = try {
        val text = ctx.applicationContext.assets.open(MAP_ASSET)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val result = HashMap<String, String>(json.length() * 2)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.getString(key)
        }
        Logger.d(TAG, "内置图标表加载完成：${result.size} 条")
        result
    } catch (e: Exception) {
        Logger.d(TAG, "内置图标表加载失败：${e.javaClass.simpleName}")
        emptyMap()
    }

    /**
     * 在 IO 线程预热图标表，避免首次 `handles()` 在主线程解析 JSON。
     * 已加载过则立即返回。
     */
    fun warmUp(ctx: Context) {
        table(ctx)
    }

    /** 当前已加载的映射条数（未加载则为 0），仅用于日志与自检。 */
    val loadedCount: Int
        get() = urlToAsset?.size ?: 0

    /**
     * 把「本地优先」加载器挂到 Glide 的 `String -> InputStream` 这条链的最前面。
     *
     * 选择 prepend 而非 replace 的原因：加载器只对已内置的 URL 声明 `handles() = true`，
     * 其余 URL 由 Glide 原有的加载器继续处理，无需保留旧工厂引用、也不会破坏
     * 现有任何 `Glide.with(...).load(iconUrl)` 调用点。
     *
     * 必须在任何图片请求发起之前调用（见 [TapApplication.onCreate]）。
     */
    fun install(ctx: Context) {
        try {
            Glide.get(ctx.applicationContext).registry
                .prepend(String::class.java, InputStream::class.java, BundledIconLoaderFactory(ctx))
            Logger.d(TAG, "已启用内置图标本地优先加载")
        } catch (e: Exception) {
            // 注册失败不应影响应用启动：最坏情况退回原来的网络加载行为
            Logger.d(TAG, "内置图标加载器注册失败：${e.javaClass.simpleName}")
        }
    }
}
