package com.taostudio.tapaccounting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 「其他」分类的默认图标：四个空心方块。
 *
 * 几何与 `res/drawable/ic_profile_category.xml` 一致（24×24 视口里四格，描边宽 2），
 * 画到 128×128 上，和首字图标、内置线性图标同一套缩放。
 * 透明底 + 不透明描边，界面上的 `setColorFilter` 才能染色。
 */
object SquareGridIcon {

    const val URL = "icon://squares"

    private const val SIZE = 128
    private const val STROKE_COLOR = "#5C6BC0"

    /** 与 ic_profile_category 的 viewport 相同，再整体放大到 [SIZE]。 */
    private const val VIEWPORT = 24f

    fun renderPng(): InputStream? = try {
        val bitmap = render()
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        ByteArrayInputStream(out.toByteArray())
    } catch (e: Exception) {
        Logger.d("SquareGridIcon", "渲染四格图标失败：${e.javaClass.simpleName}")
        null
    }

    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val scale = SIZE / VIEWPORT
        canvas.scale(scale, scale)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor(STROKE_COLOR)
        }
        // 与矢量图四条 path 相同：左上、右上、左下、右下
        val cells = arrayOf(
            floatArrayOf(4f, 4f, 10f, 10f),
            floatArrayOf(14f, 4f, 20f, 10f),
            floatArrayOf(4f, 14f, 10f, 20f),
            floatArrayOf(14f, 14f, 20f, 20f),
        )
        cells.forEach { cell ->
            canvas.drawRect(cell[0], cell[1], cell[2], cell[3], paint)
        }
        return bitmap
    }
}
