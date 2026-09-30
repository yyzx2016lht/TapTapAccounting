package com.taostudio.tapaccounting

import android.content.Context
import android.content.res.AssetManager
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/**
 * 内置图标的 Glide 加载器，见 [BundledIcons]。
 *
 * 只对「已打包进 assets 的图标 URL」声明 [handles]，其余模型一律返回 false，
 * 由 Glide 原有的 [com.bumptech.glide.load.model.StringLoader] 继续处理，
 * 因此不会影响任何非内置图片（聊天图片、票据照片、本地文件等）。
 */
class BundledIconLoaderFactory(private val context: Context) :
    ModelLoaderFactory<String, InputStream> {

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> =
        BundledIconLoader(context.applicationContext)

    override fun teardown() = Unit
}

private class BundledIconLoader(private val context: Context) : ModelLoader<String, InputStream> {

    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        // 1) 「其他」的四格图标
        if (model == CategoryIcons.OTHER_ICON) {
            return ModelLoader.LoadData(
                ObjectKey("square-grid-icon"),
                SquareGridIconFetcher()
            )
        }

        // 2) 首字图标：运行时按名称即时渲染（用户新增/改名分类时使用）
        if (TextIconRenderer.isTextIcon(model)) {
            val glyph = TextIconRenderer.glyphOf(model) ?: return null
            return ModelLoader.LoadData<InputStream>(
                ObjectKey("text-icon:$glyph:$width:$height"),
                TextIconFetcher(glyph)
            )
        }

        // 3) 已打包进 assets 的图标
        val assetPath = BundledIcons.bundledAssetPath(context, model) ?: return null
        return ModelLoader.LoadData<InputStream>(
            ObjectKey("bundled-icon:$assetPath"),
            BundledAssetFetcher(context.assets, assetPath)
        )
    }

    override fun handles(model: String): Boolean =
        model == CategoryIcons.OTHER_ICON ||
            TextIconRenderer.isTextIcon(model) ||
            BundledIcons.bundledAssetPath(context, model) != null
}

/** 「其他」四格图标：内存渲染，不写磁盘缓存。 */
private class SquareGridIconFetcher : DataFetcher<InputStream> {

    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val rendered = SquareGridIcon.renderPng()
        if (rendered == null) {
            callback.onLoadFailed(IllegalStateException("无法渲染四格图标"))
        } else {
            stream = rendered
            callback.onDataReady(rendered)
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: Exception) {
            // 关闭失败无需处理
        } finally {
            stream = null
        }
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}

/**
 * 首字图标数据源：内存渲染成 PNG 字节流。
 * 数据源标记为 LOCAL（不写磁盘缓存），因为渲染结果只取决于名称本身，
 * 名称变了 URL 就变了，不存在缓存陈旧问题。
 */
private class TextIconFetcher(private val glyph: String) : DataFetcher<InputStream> {

    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val rendered = TextIconRenderer.renderPng(glyph)
        if (rendered == null) {
            callback.onLoadFailed(IllegalStateException("无法渲染首字图标：$glyph"))
        } else {
            stream = rendered
            callback.onDataReady(rendered)
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: Exception) {
            // 关闭失败无需处理
        } finally {
            stream = null
        }
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}

/** 从 APK 的 assets 目录读原图；数据源标记为 LOCAL，无需磁盘缓存。 */
private class BundledAssetFetcher(
    private val assets: AssetManager,
    private val assetPath: String
) : DataFetcher<InputStream> {

    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            val opened = assets.open(assetPath)
            stream = opened
            callback.onDataReady(opened)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: Exception) {
            // 关闭失败无需处理
        } finally {
            stream = null
        }
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}
