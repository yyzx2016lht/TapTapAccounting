package com.taostudio.tapaccounting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 送进多模态模型前的图片压缩（微信级：保留细节，放大后仍能看清文字）。
 *
 * 旧策略「长边 ≤1280px / JPEG 75」对长截图太狠：1216×3790 的订单长截图会被
 * 2 的幂次 inSampleSize 直接砍到 304×948，字已经压糊。
 * 现策略只做温和压缩：
 *  - 总像素上限 4MP：常见截图（1080×2400、1216×3790、1440×3200）保持原始分辨率；
 *  - 超过上限才按面积等比缩小，并按精确目标尺寸重采样，不再用 2 的幂次粗砍；
 *  - JPEG 88，文字边缘依然锐利。
 *
 * 「聊天附件」「图片记账」和截屏捕获三条链路必须共用同一套参数，
 * 否则同一张图在不同链路上体积和清晰度能差近 20 倍。
 */
object AiImageCompressor {

    /** 压缩后的总像素上限（4MP）。 */
    const val MAX_PIXELS = 4_000_000

    /** JPEG 质量。 */
    const val JPEG_QUALITY = 88

    /** 压缩成功后的实际格式。 */
    const val JPEG_MIME = "image/jpeg"

    /**
     * 原地压缩 [file]：先写临时文件，成功后原子替换，避免压缩中途失败把原图截断成 0 字节。
     *
     * 返回是否真的重写了文件（成功重写后文件内容一定是 JPEG）。
     * 任何一步失败都返回 false 并保留原文件——宁可用大图，也不能丢图。
     */
    fun compressInPlace(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val (targetWidth, targetHeight) = targetSize(bounds.outWidth, bounds.outHeight)

        // 内存兜底：用 2 的幂次 inSampleSize 把解码尺寸压到目标像素的 75% 以上，
        // 高像素照片（12MP/48MP）不会整图解码，超长截图也不会 OOM
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidth * 3 / 4 &&
            bounds.outHeight / (sample * 2) >= targetHeight * 3 / 4
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        // 采样后略小于目标时保持解码尺寸即可，不做放大
        val bitmap = scaleDown(decoded, targetWidth, targetHeight)

        val tmp = File(file.parentFile, file.name + ".compressing")
        return try {
            FileOutputStream(tmp, false).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (tmp.length() > 0L && tmp.renameTo(file)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    /**
     * 同一套参数，处理已经在内存里的 Bitmap（截屏捕获等）：必要时等比缩小再写 JPEG。
     */
    fun compressBitmapToFile(bitmap: Bitmap, outFile: File): Boolean {
        val (targetWidth, targetHeight) = targetSize(bitmap.width, bitmap.height)
        val scaled = scaleDown(bitmap, targetWidth, targetHeight)
        return try {
            outFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            outFile.length() > 0L
        } catch (e: Exception) {
            false
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** 保持宽高比缩到总像素不超过 [MAX_PIXELS]；不放大。 */
    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1.0, sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height.toDouble())))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun scaleDown(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        // 采样解码的结果可能略小于目标，此时直接用解码尺寸，避免放大
        val width = min(source.width, targetWidth)
        val height = min(source.height, targetHeight)
        return if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
    }
}
