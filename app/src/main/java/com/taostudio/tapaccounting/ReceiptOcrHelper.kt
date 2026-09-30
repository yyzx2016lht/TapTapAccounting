package com.taostudio.tapaccounting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 小票识别帮助类
 *
 * 图片记账统一走多模态 AI：把图片压缩成 Base64 直接发给支持视觉的模型，
 * 由模型一次性完成「读图 + 结构化」两步，省掉本地 OCR 的中间环节。
 *
 * 历史背景：早期还有一条「本地 ML Kit OCR → 文本 AI」的链路（OCR_MODE_LOCAL），
 * 但多模态模型成熟后该分支已无人使用，ML Kit 依赖与代码均已移除，
 * 因此不再需要 `text-recognition-chinese` 依赖与 mlkit OCR 模型 assets。
 */
object ReceiptOcrHelper {

    suspend fun analyzeImageByMultimodal(
        ctx: Context,
        imageUri: Uri,
        supplementText: String = "",
        onProgress: (String) -> Unit = {}
    ): String {
        return analyzeByMultimodal(ctx, imageUri, supplementText, onProgress)
    }

    /**
     * 多图自然语言分析：逐张处理，合并结果。
     */
    suspend fun analyzeImagesByMultimodal(
        ctx: Context,
        imageUris: List<Uri>,
        supplementText: String = "",
        onProgress: (String) -> Unit = {}
    ): String {
        if (imageUris.size == 1) {
            return analyzeByMultimodal(ctx, imageUris.first(), supplementText, onProgress)
        }
        val results = mutableListOf<String>()
        for ((index, uri) in imageUris.withIndex()) {
            onProgress("正在分析第 ${index + 1}/${imageUris.size} 张图片...")
            val summary = analyzeByMultimodal(ctx, uri, "", onProgress)
            if (summary.isNotBlank() && !summary.contains("未识别到")) {
                results.add(summary)
            }
        }
        if (results.isEmpty()) {
            throw IllegalArgumentException("所有图片均未识别到可记账内容")
        }
        return results.joinToString("\n")
    }

    /**
     * 多模态 AI（图片 Base64 直发）（返回自然语言摘要）
     */
    private suspend fun analyzeByMultimodal(
        ctx: Context,
        imageUri: Uri,
        supplementText: String = "",
        onProgress: (String) -> Unit
    ): String {
        onProgress("正在压缩图片...")

        val bitmap = loadBitmapFromUri(ctx, imageUri)
        val base64 = bitmapToBase64(bitmap)

        onProgress("正在发送图片给 AI 分析...")

        return AIService.analyzeReceiptByImage(ctx, base64, "image/jpeg", supplementText)
    }

    /**
     * 从 URI 加载 Bitmap，并自动缩放到合理大小（避免内存爆炸）
     */
    private suspend fun loadBitmapFromUri(ctx: Context, uri: Uri): Bitmap =
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // 计算合适的缩放比例（目标最长边 2048px，足够 OCR 精度）
            val maxDim = 2048
            val scale = maxOf(
                1,
                maxOf(
                    (options.outWidth + maxDim - 1) / maxDim,
                    (options.outHeight + maxDim - 1) / maxDim
                )
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
            }

            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: throw IllegalArgumentException("无法读取图片文件")
        }

    /**
     * 将 Bitmap 转为 Base64 字符串（用于多模态模式）
     * 压缩到 1024px 以内，质量 80%，避免请求体过大
     */
    private suspend fun bitmapToBase64(bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            val maxDim = 1024
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(
                    maxDim.toFloat() / bitmap.width,
                    maxDim.toFloat() / bitmap.height
                )
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
}

