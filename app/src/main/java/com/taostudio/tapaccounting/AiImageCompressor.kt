package com.taostudio.tapaccounting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * 送进多模态模型前的图片压缩。
 *
 * 视觉识别对「长边 ≤1280px / JPEG 75」已经足够：实测一张 1216×3790 的外卖订单长截图
 * 压到 304×948 后，15 行商品仍能逐行读全。
 *
 * 不压缩的代价则是实打实的：同一张截图原图 base64 后约 854KB，慢网下表现为长时间无响应，
 * 甚至被上游直接重置连接（SocketException / StreamResetException）。
 * 「聊天附件」和「图片记账」两条链路必须共用同一套参数，否则同一张图在两条链路上体积能差近 20 倍。
 */
object AiImageCompressor {

    /** 长边上限（px）。 */
    const val TARGET_LONG_EDGE = 1280

    /** JPEG 质量。 */
    const val JPEG_QUALITY = 75

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

        var sample = 1
        while (bounds.outWidth / sample > TARGET_LONG_EDGE || bounds.outHeight / sample > TARGET_LONG_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false

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
            bitmap.recycle()
        }
    }
}
