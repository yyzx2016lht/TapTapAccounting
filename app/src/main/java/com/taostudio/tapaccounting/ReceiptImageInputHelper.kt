package com.taostudio.tapaccounting

import android.content.Context
import android.util.Base64
import java.io.File

object ReceiptImageInputHelper {

    const val MULTIMODAL_PREFIX = "[MULTIMODAL_IMAGE]"
    const val MULTIMODAL_DIRECT_PREFIX = "[MULTIMODAL_IMAGE_DIRECT]"

    data class ImagePayload(
        val base64: String,
        val mime: String,
        val supplement: String
    )

    fun encodePayload(prefix: String, base64: String, mime: String, supplement: String = ""): String {
        val safeSupplement = supplement.replace("|", " ").trim()
        return "$prefix$base64|$mime|$safeSupplement"
    }

    fun decodePayload(raw: String): ImagePayload? {
        val prefix = when {
            raw.startsWith(MULTIMODAL_DIRECT_PREFIX) -> MULTIMODAL_DIRECT_PREFIX
            raw.startsWith(MULTIMODAL_PREFIX) -> MULTIMODAL_PREFIX
            else -> return null
        }
        val payload = raw.removePrefix(prefix)
        val parts = payload.split("|", limit = 3)
        val base64 = parts.getOrElse(0) { "" }
        if (base64.isBlank()) return null
        return ImagePayload(
            base64 = base64,
            mime = parts.getOrElse(1) { "image/jpeg" },
            supplement = parts.getOrElse(2) { "" }.trim()
        )
    }

    fun isDirectPayload(raw: String): Boolean = raw.startsWith(MULTIMODAL_DIRECT_PREFIX)

    fun mergeSupplementWithSummary(summary: String, supplement: String): String {
        val cleanedSupplement = supplement.trim()
        if (cleanedSupplement.isBlank()) return normalizeVisionSummary(summary)
        val cleanedSummary = normalizeVisionSummary(summary)
        if (cleanedSummary.isBlank()) return cleanedSupplement
        if (cleanedSummary.contains(cleanedSupplement)) return cleanedSummary
        if (isPaymentSupplement(cleanedSupplement)) {
            return applyPaymentSupplementToLines(cleanedSummary, cleanedSupplement)
        }
        return "$cleanedSupplement\n$cleanedSummary"
    }

    /** 图片草稿确认后交给记账模型的输入，确保用户补充说明不会被漏掉。 */
    fun buildAccountingInputFromImageDraft(draft: String, supplement: String): String {
        val cleanedDraft = draft.trim()
        val cleanedSupplement = supplement.trim()
        if (cleanedSupplement.isBlank()) return cleanedDraft
        if (cleanedDraft.contains(cleanedSupplement)) return cleanedDraft
        return buildString {
            append("【用户补充（高优先级）】\n")
            append(cleanedSupplement)
            append("\n请结合补充说明修正对应账单；不要把补充内容强行解释为支付方式，也不要复制到无关账单。")
            append("\n\n【待记账清单】\n")
            append(cleanedDraft)
        }
    }

    private fun isPaymentSupplement(text: String): Boolean {
        val keywords = listOf(
            "微信", "支付宝", "花呗", "银行", "信用卡", "储蓄卡", "借记卡",
            "现金", "visa", "mastercard", "银联", "零钱", "钱包", "apple pay",
            "paypal", "云闪付", "京东支付", "美团支付"
        )
        return keywords.any { text.contains(it, ignoreCase = true) } ||
            text.endsWith("支付") ||
            text.endsWith("卡")
    }

    private fun applyPaymentSupplementToLines(summary: String, payment: String): String {
        val paymentPhrase = when {
            payment.contains("用了") -> payment
            payment.contains("支付") && !payment.startsWith("用") -> "用了$payment"
            else -> "用了${payment}支付"
        }
        val paymentMarkers = listOf(
            "用了", "支付", "微信", "支付宝", "花呗", "银行", "现金",
            "visa", "mastercard", "pay", "银联", "零钱", "wallet"
        )
        return summary.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                if (paymentMarkers.any { marker -> line.contains(marker, ignoreCase = true) }) {
                    line
                } else {
                    "$line $paymentPhrase"
                }
            }
    }

    /**
     * 将视觉模型返回的清单整理为「一行一条」便于展示和后续记账。
     */
    fun normalizeVisionSummary(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.contains("未识别到可记账内容")) return trimmed

        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { line -> splitDenseReceiptLine(line) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val merged = mergeOrphanMetadataLines(lines)
        return if (merged.isNotEmpty()) merged.joinToString("\n") else trimmed
    }

    /**
     * 把误拆成独立行的日期/时间/支付方式，合并回相邻交易行。
     */
    internal fun mergeOrphanMetadataLines(lines: List<String>): List<String> {
        if (lines.size <= 1) return lines

        val result = mutableListOf<String>()
        for (line in lines) {
            when {
                isStandaloneTimeLine(line) && result.isNotEmpty() -> {
                    // 整单共用一个时间时，归到首行即可
                    result[0] = attachMetadata(result[0], "时间", normalizeTimeFragment(line))
                }
                isStandalonePaymentLine(line) && result.isNotEmpty() -> {
                    result[0] = attachMetadata(result[0], "payment", line)
                }
                isStandaloneTimeLine(line) -> result.add(line)
                isStandalonePaymentLine(line) -> result.add(line)
                else -> result.add(line)
            }
        }
        return result
    }

    /**
     * 兜底合并同名商品行（如 可乐 → 西瓜 → 可乐）。
     * 仅当所有行都符合「购买…花了…」时才合并，避免误伤截图类句子。
     */
    internal fun mergeDuplicatePurchaseLines(lines: List<String>): List<String> {
        if (lines.size <= 1) return lines
        val parsed = lines.map { parsePurchaseLine(it) }
        if (parsed.any { it == null }) return lines

        val grouped = LinkedHashMap<String, MutableList<ParsedPurchase>>()
        for (item in parsed.filterNotNull()) {
            grouped.getOrPut(item.productKey) { mutableListOf() }.add(item)
        }
        return grouped.values.map { items ->
            if (items.size == 1) {
                items.first().rawLine
            } else {
                val displayName = items.first().displayName
                val totalQty = items.sumOf { it.quantity }
                val totalAmount = items.sumOf { it.amount }
                val currency = items.first().currency
                if (totalQty > 1) {
                    "购买$displayName x$totalQty 花了 ${String.format("%.2f", totalAmount)} $currency"
                } else {
                    "购买$displayName 花了 ${String.format("%.2f", totalAmount)} $currency"
                }
            }
        }
    }

    private data class ParsedPurchase(
        val productKey: String,
        val displayName: String,
        val quantity: Int,
        val amount: Double,
        val currency: String,
        val rawLine: String
    )

    private fun parsePurchaseLine(line: String): ParsedPurchase? {
        val trimmed = line.trim()
        val match = Regex("""^购买\s*(.+?)\s*花了\s+([\d.]+)\s*([A-Za-z]{2,4})?$""").find(trimmed) ?: return null
        var name = match.groupValues[1].trim()
        val amount = match.groupValues[2].toDoubleOrNull() ?: return null
        val currency = match.groupValues[3].ifBlank { "CNY" }
        var quantity = 1
        val qtyMatch = Regex("""\s+[xX×](\d+)$""").find(name)
        if (qtyMatch != null) {
            quantity = qtyMatch.groupValues[1].toIntOrNull() ?: 1
            name = name.removeSuffix(qtyMatch.value).trim()
        }
        val displayName = name.trim()
        val productKey = displayName
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), "")
            .lowercase()
        if (productKey.isBlank()) return null
        return ParsedPurchase(
            productKey = productKey,
            displayName = displayName,
            quantity = quantity,
            amount = amount,
            currency = currency,
            rawLine = trimmed
        )
    }

    private fun attachMetadata(transactionLine: String, kind: String, fragment: String): String {
        if (transactionLine.contains(fragment, ignoreCase = true)) return transactionLine
        return when (kind) {
            "payment" -> {
                val phrase = if (fragment.contains("用了")) fragment else "用了${fragment.trim()}支付"
                if (transactionLine.contains("支付")) "$transactionLine，$phrase" else "$transactionLine，$phrase"
            }
            else -> {
                val timeText = fragment.removePrefix("时间").trim()
                if (transactionLine.contains(timeText)) transactionLine else "$transactionLine，时间$timeText"
            }
        }
    }

    private fun normalizeTimeFragment(line: String): String {
        return line.removePrefix("时间").trim()
    }

    internal fun isStandaloneTimeLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("花了") || trimmed.contains("消费") || trimmed.contains("购买") ||
            trimmed.contains("支付") && trimmed.any { it.isDigit() }
        ) {
            return false
        }
        if (trimmed.startsWith("时间")) return true
        return Regex("""^\d{4}[-/年]\d{1,2}[-/月]\d{1,2}""").containsMatchIn(trimmed) ||
            Regex("""^\d{1,2}[-/月]\d{1,2}""").containsMatchIn(trimmed)
    }

    internal fun isStandalonePaymentLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isDigit() } && Regex("""\d+\.\d{2}""").containsMatchIn(trimmed)) return false
        val paymentOnly = trimmed.startsWith("用了") && trimmed.contains("支付") && trimmed.length <= 24
        val methodOnly = trimmed in setOf("微信支付", "支付宝支付", "现金支付") ||
            Regex("""^(微信|支付宝|花呗|Visa|Mastercard|银联|现金).{0,8}支付$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)
        return paymentOnly || methodOnly
    }

    private fun splitDenseReceiptLine(line: String): List<String> {
        if (!line.contains("花了") && !line.contains("消费") && !line.contains("购买") &&
            !line.contains("收到") && !line.contains("到账") && !line.contains("转账")
        ) {
            return listOf(line)
        }
        // 不在「用了xxx支付」的「支付」处切开；只按新交易的开头动词拆分
        val segments = Regex("(?=(?:购买|消费|收到|到账|退款|转账))")
            .split(line)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (segments.size >= 2) segments else listOf(line)
    }

    @Deprecated("No longer used. Supplement dialogs are removed from all flows.")
    fun showSupplementDialog(
        ctx: android.content.Context,
        showDialog: (androidx.appcompat.app.AlertDialog, Float) -> Unit,
        onConfirm: (supplement: String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        onConfirm("")
    }

    /**
     * 读图 → 压缩 → base64。
     *
     * 这里必须压缩：图片记账拿到的大多是长截图（实测 1216×3790 / 640KB），
     * 原样 base64 后约 854KB，慢网下会长时间无响应，甚至被上游重置连接。
     * 压到长边 1280px 后同一张图只需约 33KB，识别质量不受影响。
     */
    suspend fun readImagePayload(ctx: Context, uri: android.net.Uri): ImagePayload? {
        val sourceMime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val imageDir = File(ctx.filesDir, "receipt_inputs").also { it.mkdirs() }
        val outFile = File(imageDir, "receipt_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        // 压缩成功后文件内容一定是 JPEG，MIME 必须跟着改，否则会把 JPEG 字节标成 image/png
        val compressed = AiImageCompressor.compressInPlace(outFile)
        val bytes = outFile.readBytes()
        if (bytes.isEmpty()) return null
        return ImagePayload(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mime = if (compressed) AiImageCompressor.JPEG_MIME else sourceMime,
            supplement = ""
        )
    }
}
