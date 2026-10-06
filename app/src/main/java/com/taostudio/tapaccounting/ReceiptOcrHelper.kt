package com.taostudio.tapaccounting

import android.content.Context
import android.net.Uri

/**
 * 小票识别帮助类
 *
 * 图片记账统一走多模态 AI：把图片（温和压缩后）转成 Base64 直接发给支持视觉的模型，
 * 由模型一次性完成「读图 + 结构化」两步，省掉本地 OCR 的中间环节。
 *
 * 压缩参数与图片记账直出链路共用（见 [AiImageCompressor] / [ReceiptImageInputHelper.readImagePayload]）：
 * 常见截图保持原始分辨率，只按像素上限温和缩放，长截图放大后仍能看清文字。
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

        val payload = ReceiptImageInputHelper.readImagePayload(ctx, imageUri)
            ?: throw IllegalArgumentException("无法读取图片文件")

        onProgress("正在发送图片给 AI 分析...")

        return AIService.analyzeReceiptByImage(ctx, payload.base64, payload.mime, supplementText)
    }
}

