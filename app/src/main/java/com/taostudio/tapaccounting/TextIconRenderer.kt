package com.taostudio.tapaccounting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 「首字图标」渲染器。
 *
 * 用户在新增分类时输入的文本，如果没有对应内置图标，就取名称的第一个字，
 * 实时画成一张图标（透明底 + 不透明字形），这样任何自定义分类都有贴合自己的图标，
 * 而不是千篇一律的「···」占位图。
 *
 * 约定与内置图标一致：**透明底 + 不透明字形**，这样界面上的 `setColorFilter(tint)`
 * 才能正确染色（支出/收入用不同颜色），渲染流程与 PNG 图标完全一致。
 *
 * 与 [BundledIcons] 配合：`text://<编码后的名称>` 这类 URL 不落 assets，
 * 由 [BundledIconLoader] 在内存中即时渲染。
 */
object TextIconRenderer {

    /** 文字图标的 URL 前缀，`BundledIcons` 与覆盖度测试都靠它识别。 */
    const val SCHEME = "text://"

    /** 画布边长：与 assets 里主流图标（128×128）保持一致，缩放行为才完全一致。 */
    private const val SIZE = 128
    private const val TEXT_COLOR = "#5C6BC0"

    /**
     * 字形外接盒高度占画布的比例。
     *
     * 实测 assets 里 1132 张图标：墨迹高度占比中位数 100%、均值 90%
     * （线性图标自身就画得比较满）。文字图标直接对齐中位数会顶到边缘，
     * 取 0.82 时视觉重量与「宠物 / 快递 / 美妆」这批图标最接近。
     */
    private const val HEIGHT_RATIO = 0.82f

    /** 横向最大占比，防止 "W""M" 这类宽字形顶到画布边缘。 */
    private const val MAX_WIDTH_RATIO = 0.90f

    /** 用名称构造一个「首字图标」URL；没有可用字符时返回 null。 */
    fun urlFor(rawName: String?): String? {
        val ch = firstGlyph(rawName) ?: return null
        return SCHEME + ch
    }

    /** 解析首字图标 URL；不是首字图标则返回 null。 */
    fun glyphOf(url: String?): String? =
        if (url != null && url.startsWith(SCHEME)) url.removePrefix(SCHEME).ifBlank { null } else null

    fun isTextIcon(url: String?): Boolean = url != null && url.startsWith(SCHEME)

    /**
     * 取名称的第一个「可显示字符」。
     * 跳过空白与常见装饰性括号前缀，例如「（自定义）宠物」→「宠」。
     */
    fun firstGlyph(rawName: String?): String? {
        val name = rawName?.trim().orEmpty()
        for (ch in name) {
            if (ch.isWhitespace()) continue
            if (ch in SKIP_PREFIX) continue
            return ch.toString()
        }
        return null
    }

    private val SKIP_PREFIX = setOf('（', '(', '【', '[', '「', '『', '·', '-', '_', '+', '*', '#')

    /** 渲染成 PNG 字节流，交给 Glide 解码；失败返回 null 由调用方回落。 */
    fun renderPng(rawName: String?): InputStream? {
        val glyph = firstGlyph(rawName) ?: return null
        return try {
            val bitmap = render(glyph)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            ByteArrayInputStream(out.toByteArray())
        } catch (e: Exception) {
            Logger.d("TextIconRenderer", "渲染首字图标失败：${e.javaClass.simpleName}")
            null
        }
    }

    private fun render(glyph: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 透明底，保证界面 tint 染色时不会糊成一整块色
        canvas.drawColor(Color.TRANSPARENT)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(TEXT_COLOR)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            // 必须用 LEFT：下面的 x 已经用字形外接盒的 exactCenterX 做过居中补偿，
            // 若再用 CENTER，drawText 会把 x 当成「文字中心」再平移一次，
            // 结果整字左移半个字宽、左半截被裁掉（历史 bug）。
            textAlign = Paint.Align.LEFT
        }

        // 目标：字形「外接盒」的高度占画布 HEIGHT_RATIO（而不是宽度）。
        // 用高度而非宽度定标，汉字（方块字）与拉丁字母才不会一大一小。
        val target = SIZE * HEIGHT_RATIO

        val bounds = Rect()
        paint.textSize = 100f
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val glyphHeight = bounds.height().toFloat()
        if (glyphHeight > 0f) {
            paint.textSize = 100f * (target / glyphHeight)
        }
        // 极宽的字形（如 "W"）再按宽度收一次，避免横向超出画布
        val maxWidth = SIZE * MAX_WIDTH_RATIO
        if (paint.measureText(glyph) > maxWidth) {
            paint.textSize *= maxWidth / paint.measureText(glyph)
        }

        // 用字形外接盒做垂直+水平居中：汉字重心天然居中，拉丁字母不会因基线留白而偏上
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val x = SIZE / 2f - bounds.exactCenterX()
        val y = SIZE / 2f - bounds.exactCenterY()
        canvas.drawText(glyph, x, y, paint)
        return bitmap
    }

    /** 供预览控件直接使用的字体度量（避免调用方重复计算）。 */
    internal fun fontBounds(paint: Paint, glyph: String): Rect {
        val rect = Rect()
        paint.getTextBounds(glyph, 0, glyph.length, rect)
        return rect
    }
}
