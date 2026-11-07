package com.taostudio.tapaccounting

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capturebridge.CaptureImportService
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.ImageAccountingCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 待记「图片记账」的宿主 Activity。
 *
 * 由 [ImagePickerActivity] 在选图完成后拉起：复用 [AiAssistant.analyzeImages] 的识别与
 * 结果面板，capture 模式只提供「确认入账并合并」，不做逐条编辑。
 *
 * 取消/关闭/识别失败都不会改待记状态；确认后由
 * [CaptureImportService.importBillGroup] 原子写入 N 条账单 + 支付组 + 一条回执。
 */
class CaptureImageAccountingActivity : Activity() {

    companion object {
        /** 已复制到 App 缓存的图片 file:// URI 字符串数组。 */
        const val EXTRA_IMAGE_URIS: String = "imageUris"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var assistant: AiAssistant? = null
    private var importStarted = false
    private var capture: ImageAccountingCapture.Capture? = null
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        capture = parseCapture(intent.getStringExtra(CaptureContract.EXTRA_CAPTURE_PAYLOAD))
        requestId = intent.getStringExtra(CaptureContract.EXTRA_REQUEST_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        val uris = intent.getStringArrayListExtra(EXTRA_IMAGE_URIS)
            .orEmpty()
            .mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
        val captureContext = capture

        if (captureContext == null) {
            toast("缺少待记信息，未开始图片记账")
            finish()
            return
        }
        if (uris.isEmpty()) {
            toast("没有拿到图片，待记仍保留")
            finish()
            return
        }
        if (captureContext.amountCents <= 0L) {
            toast("待记金额缺失，请先补金额再图片记账")
            finish()
            return
        }
        if (captureContext.type != Bill.TYPE_EXPENSE && captureContext.type != Bill.TYPE_INCOME) {
            toast("图片记账只支持普通支出/收入")
            finish()
            return
        }
        if (captureContext.subType != Bill.SUBTYPE_NORMAL || captureContext.needsRefundLink) {
            toast("退款/还款账单请用悬浮窗细改")
            finish()
            return
        }

        val preset = AiProviderRegistry.resolvePreset(this)
        val apiKey = Prefs.getAiProviderKey(this, preset.id).ifBlank { Prefs.getAiKey(this) }
        if (apiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("未配置 AI 密钥")
                .setMessage("识图记账需要调用多模态 AI 模型。是否现在前往配置？（当前待记账单会保留）")
                .setPositiveButton("去配置") { _, _ ->
                    startActivity(Intent(this, AiConfigActivity::class.java))
                    finish()
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val ai = AiAssistant(this)
        assistant = ai
        ai.analyzeImages(
            imageUris = uris,
            onResult = { result -> importRecognizedBills(result) },
            onAbandoned = { finish() },
            sourceKind = "capture_image",
            captureMode = true,
            expectedTotalCents = captureContext.amountCents,
            expectedCurrency = captureContext.currency,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        assistant?.shutdown()
        assistant = null
        super.onDestroy()
    }

    private fun parseCapture(raw: String?): ImageAccountingCapture.Capture? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val captureId = json.optString("captureId", "").trim()
            .ifEmpty {
                intent.getStringExtra(CaptureContract.EXTRA_CAPTURE_ID)?.trim().orEmpty()
            }
        if (captureId.isEmpty()) return null
        val type = json.optInt("type", Bill.TYPE_EXPENSE)
        return ImageAccountingCapture.Capture(
            captureId = captureId,
            amountCents = json.optLong("amountCents", 0L),
            currency = json.optString("currency", "CNY").ifBlank { "CNY" },
            paidAt = normalizeMillis(json.optLong("paidAt", 0L)),
            type = type,
            subType = json.optInt("subType", Bill.SUBTYPE_NORMAL),
            needsRefundLink = json.optBoolean("needsRefundLink", false),
            assetId = json.optLong("assetId", 0L).takeIf { it > 0L },
            assetName = json.optString("assetName", "")
                .trim()
                .takeUnless { it.isEmpty() || it == "null" },
            bookName = json.optString("bookName", "")
                .trim()
                .takeUnless { it.isEmpty() || it == "null" },
        )
    }

    private fun importRecognizedBills(result: JSONObject) {
        if (importStarted) return
        importStarted = true
        val captureContext = capture
        if (captureContext == null) {
            finish()
            return
        }

        val bills = readRecognizedBills(result)
        scope.launch {
            val fallbackCategory = withContext(Dispatchers.IO) {
                runCatching {
                    CaptureImportService.resolveFallbackCategoryName(
                        this@CaptureImageAccountingActivity,
                        captureContext.type,
                    )
                }.getOrNull()
            }
            when (val plan = ImageAccountingCapture.plan(captureContext, bills, fallbackCategory)) {
                is ImageAccountingCapture.Outcome.Error -> {
                    showFailure(plan.message)
                }

                is ImageAccountingCapture.Outcome.Ok -> {
                    val payload = ImageAccountingCapture.buildImportPayload(captureContext, plan.items)
                    val imported = withContext(Dispatchers.IO) {
                        CaptureImportService.importBillGroup(
                            this@CaptureImageAccountingActivity,
                            payload,
                            requestId,
                        )
                    }
                    if (!imported.optBoolean("ok", false)) {
                        showFailure(
                            humanizeImportError(
                                imported.optString("error", ""),
                                imported.optString("message", ""),
                            )
                        )
                        return@launch
                    }
                    val duplicate = imported.optBoolean("duplicate", false)
                    val count = imported.optInt("count", plan.items.size)
                    toast(
                        if (duplicate) {
                            "这笔已入账，未重复建单"
                        } else {
                            "已合并入账 $count 笔，共用同一个支付组"
                        }
                    )
                    finish()
                }
            }
        }
    }

    private fun readRecognizedBills(result: JSONObject): List<ImageAccountingCapture.BillInput> {
        val array = result.optJSONArray("bills") ?: JSONArray().put(result)
        return buildList {
            for (i in 0 until array.length()) {
                val bill = array.optJSONObject(i) ?: continue
                val name = bill.optString("remarks", "")
                    .ifBlank { bill.optString("remark", "") }
                    .ifBlank { bill.optString("merchant", "") }
                    .ifBlank { "图片账单" }
                val amount = bill.optDouble("amount", Double.NaN)
                val amountCents = if (amount.isNaN()) null else Math.round(amount * 100.0)
                val category = bill.optString("category_name", "")
                    .ifBlank { bill.optString("categoryName", "") }
                    .trim()
                    .takeIf { it.isNotEmpty() }
                add(
                    ImageAccountingCapture.BillInput(
                        name = name,
                        amountCents = amountCents,
                        categoryName = category,
                    )
                )
            }
        }
    }

    private fun showFailure(message: String) {
        if (isFinishing || isDestroyed) {
            toast(message)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("图片记账未完成")
            .setMessage("$message\n\n待记仍保持 PENDING，可稍后重试。")
            .setPositiveButton("知道了") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun humanizeImportError(rawError: String, rawMessage: String): String {
        val code = rawError.trim()
        val fallback = rawMessage.trim()
        return when {
            code == "missing_category" -> fallback.ifEmpty { "有条目缺少分类，请先配置 AI 或规则" }
            code == "missing_asset" -> "缺少账户，请先在待记卡片绑定资产"
            code == "asset_not_found" || code.startsWith("asset_not_found:") ->
                fallback.ifEmpty { "敲敲记账中找不到对应账户，请在待记卡片重新绑定" }
            code == "category_not_found" || code.startsWith("category_not_found:") ->
                fallback.ifEmpty { "敲敲记账中找不到对应分类，请先配置 AI 或规则" }
            code == "split_total_mismatch" -> fallback.ifEmpty { "识别合计与付款不一致，请重试" }
            fallback.isNotEmpty() -> fallback
            code.isNotEmpty() -> code
            else -> "请检查分类、账户与识别结果"
        }
    }

    private fun normalizeMillis(value: Long): Long = when {
        value <= 0L -> System.currentTimeMillis()
        value < 10_000_000_000L -> value * 1000L
        else -> value
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
