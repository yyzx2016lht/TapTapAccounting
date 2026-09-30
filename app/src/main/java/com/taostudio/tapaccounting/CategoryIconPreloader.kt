package com.taostudio.tapaccounting

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.repository.CategoryRepository

/**
 * 分类图标预热缓存工具。
 *
 * 策略：**方案二** ——「分类名 ↔ URL」映射已存在数据库，无需额外表；
 * 利用 Glide 的磁盘缓存（DiskCacheStrategy.DATA），在联网时把所有分类图标
 * 提前下载到本地，断网后也能正常显示，刷新时会自动补全遗漏的图标。
 *
 * **自 2026-09-24 起**：内置图标已随 APK 打包（见 [BundledIcons]），
 * 不再需要联网预热，这里只对「未内置的 URL」（例如用户以后新增的自定义图标）
 * 做网络预热。内置 URL 会被直接跳过。
 *
 * 调用时机：[TapApplication.onCreate] 中后台执行，不阻塞主线程。
 */
object CategoryIconPreloader {

    /**
     * 收集当前用户数据库中所有分类的图标 URL，通过 Glide 预下载到磁盘缓存。
     * 必须在 IO 线程中调用（已 suspend）。
     */
    suspend fun preloadAll(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val repo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())

            // 收集用户数据库中所有分类 URL（支出 + 收入）
            val dbUrls = mutableSetOf<String>()
            listOf(0, 1).forEach { type ->
                repo.getCategoryTree(type).forEach { node ->
                    if (node.icon.isNotEmpty()) dbUrls += node.icon
                    node.subs.forEach { sub -> if (sub.icon.isNotEmpty()) dbUrls += sub.icon }
                }
            }

            // 也把 default_category.json 中所有内置 URL 纳入预热
            // （用户初次安装数据库为空时，仍可预热内置图标）
            val builtinUrls = mutableSetOf<String>()
            listOf(Prefs.TYPE_EXPENSE, Prefs.TYPE_INCOME).forEach { type ->
                Prefs.loadDefaultFromRaw(ctx, type).forEach { node ->
                    if (node.icon.isNotEmpty()) builtinUrls += node.icon
                    node.subs.forEach { sub -> if (sub.icon.isNotEmpty()) builtinUrls += sub.icon }
                }
            }

            // 内置图标已打包进 APK，无需联网预热；只预热未内置的 URL
            val allUrls = (dbUrls + builtinUrls).filter { BundledIcons.bundledAssetPath(ctx, it) == null }
            if (allUrls.isEmpty()) return@withContext

            // Glide preload：下载到缓存，后续 bind 时立刻返回（无延迟）
            allUrls.forEach { url ->
                try {
                    Glide.with(ctx.applicationContext)
                        .download(url)
                        // download() returns File; caching DATA avoids trying to encode
                        // transformed/result File resources back into disk cache.
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .submit()
                        .get() // 阻塞直到该 URL 完成（已在 IO 线程中）
                } catch (_: Exception) {
                    // 单个 URL 失败不影响整体
                }
            }
        } catch (_: Exception) {
            // 整体失败静默处理，不能影响应用正常启动
        }
    }
}

