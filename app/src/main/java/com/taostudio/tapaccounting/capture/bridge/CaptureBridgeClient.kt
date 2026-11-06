package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.AssetInfo
import com.taostudio.tapaccounting.capture.contract.BookInfo
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CategoryInfo
import com.taostudio.tapaccounting.capture.contract.ImportBill
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 插件 → 敲敲记账 [CaptureContract.BRIDGE_AUTHORITY] 的唯一调用入口。
 */
class CaptureBridgeClient(context: Context) {

    private val appContext: Context = context.applicationContext
    private val bridgeUri: Uri = Uri.parse("content://${CaptureContract.BRIDGE_AUTHORITY}")

    data class PingStatus(
        val ok: Boolean,
        val aiEnabled: Boolean = false,
        val appVersion: String = "",
    )

    data class ClassifiedDraft(
        val captureId: String,
        val categoryName: String?,
        val assetName: String?,
        val remarks: String?,
    )

    /** 轻量智能归类的输入：只带用户备注和账单类型，不带金额/时间/资产/商户。 */
    data class NoteClassifyInput(
        val captureId: String,
        val note: String?,
        val billType: Int? = null,
    )

    data class ClassifyNotesResult(
        val ok: Boolean,
        /** captureId -> 分类路径；没有结果的条目不在 map 里。 */
        val categories: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    data class SuggestRuleResult(
        val ok: Boolean,
        val keyword: String = "",
        val error: String? = null,
    )

    /** 拆分预览的一条明细；金额已由 App 用付款差额补齐（必要时 inferred=true）。 */
    data class SplitItem(
        val name: String,
        val amountCents: Long,
        val categoryName: String? = null,
        /** 金额是否按付款差额推算，确认页显示“按差额”。 */
        val inferred: Boolean = false,
    )

    data class SplitPreviewResult(
        val ok: Boolean,
        val items: List<SplitItem> = emptyList(),
        val totalCents: Long = 0L,
        val sumCents: Long = 0L,
        val diffCents: Long = 0L,
        val fallbackCategoryName: String? = null,
        val error: String? = null,
        val message: String? = null,
    )

    data class GroupImportResult(
        val ok: Boolean,
        val duplicate: Boolean = false,
        val captureId: String = "",
        val paymentGroupId: String? = null,
        val billIds: List<Long> = emptyList(),
        val count: Int = 0,
        val error: String? = null,
        val message: String? = null,
    )

    data class OpenImageAccountingResult(
        val ok: Boolean,
        val accepted: Boolean = false,
        /** App 已有该 captureId 的回执：这笔之前已经入账。 */
        val duplicate: Boolean = false,
        val captureId: String = "",
        val billId: Long? = null,
        val error: String? = null,
        val message: String? = null,
    )

    /** App 桥接是否可用。 */
    suspend fun ping(): Boolean = pingDetail().ok

    suspend fun pingDetail(): PingStatus = withContext(Dispatchers.IO) {
        val json = callForObject(CaptureContract.METHOD_PING, null, null)
            ?: return@withContext PingStatus(ok = false)
        PingStatus(
            ok = json.optBoolean("ok", false),
            aiEnabled = json.optBoolean("aiEnabled", false),
            appVersion = json.optString("appVersion", ""),
        )
    }

    /**
     * 读取敲敲记账的账本、资产、分类上下文。
     */
    suspend fun getAccountingContext(): AccountingContext? = withContext(Dispatchers.IO) {
        val json = callForObject(CaptureContract.METHOD_GET_ACCOUNTING_CONTEXT, null, null)
            ?: return@withContext null
        if (!json.optBoolean("ok", false)) return@withContext null

        val books = buildList {
            val arr = json.optJSONArray("books") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    add(BookInfo(id = obj.optLong("id", (i + 1).toLong()), name = name))
                }
            }
        }
        val assets = buildList {
            val arr = json.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    add(
                        AssetInfo(
                            id = obj.optLong("id", 0L),
                            name = name,
                            type = obj.optString("type", ""),
                            assetCategory = obj.optString("assetCategory", ""),
                            currency = obj.optString("currency", "CNY"),
                        )
                    )
                }
            }
        }
        val categories = buildList {
            val arr = json.optJSONArray("categories") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    val parentId = if (obj.has("parentId") && !obj.isNull("parentId")) {
                        obj.optLong("parentId")
                    } else {
                        null
                    }
                    add(
                        CategoryInfo(
                            id = obj.optLong("id", 0L),
                            name = name,
                            type = obj.optInt("type", 0),
                            parentId = parentId,
                        )
                    )
                }
            }
        }
        val defaultBook = if (json.has("defaultBookName") && !json.isNull("defaultBookName")) {
            json.optString("defaultBookName", "").takeIf { it.isNotBlank() }
        } else {
            null
        }
        val currentBook = if (json.has("currentBookName") && !json.isNull("currentBookName")) {
            json.optString("currentBookName", "").takeIf { it.isNotBlank() }
        } else {
            defaultBook
        }
        AccountingContext(
            contractVersion = json.optInt("contractVersion", CaptureContract.VERSION),
            defaultBookName = defaultBook,
            currentBookName = currentBook,
            assetFeatureEnabled = json.optBoolean("assetFeatureEnabled", true),
            books = books,
            assets = assets,
            categories = categories,
        )
    }

    /**
     * 查询某时间点附近的已有支出账单，用于检测用户是否已经手动记过（标“可能已记”）。
     */
    suspend fun hasRecentMatchingBill(paidAt: Long, amountCents: Long, windowMinutes: Int = 10): Boolean =
        withContext(Dispatchers.IO) {
            if (amountCents <= 0L) return@withContext false
            val extras = Bundle().apply {
                putLong(CaptureContract.EXTRA_PAID_AT, paidAt)
                putInt("windowMinutes", windowMinutes)
                putInt("limit", 30)
            }
            val array = callForArray(CaptureContract.METHOD_GET_RECENT_BILLS, null, extras)
                ?: return@withContext false
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optLong("amountCents", -1L) == amountCents) {
                    return@withContext true
                }
            }
            false
        }

    data class ClassifyResult(
        val ok: Boolean,
        val drafts: List<ClassifiedDraft> = emptyList(),
        val error: String? = null,
    )

    /**
     * 调用敲敲记账内置的 AI 服务对多笔待记一次性分类（时间、金额、资产保持锁定）。
     */
    suspend fun classifyCaptures(captures: List<PaymentCapture>): ClassifyResult =
        withContext(Dispatchers.IO) {
            if (captures.isEmpty()) return@withContext ClassifyResult(ok = true)
            val arr = JSONArray()
            captures.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("captureId", item.id)
                        put("amountCents", item.amountCents ?: 0L)
                        put("paidAt", item.paidAt)
                        put("source", item.source)
                        putOpt("merchant", item.merchant)
                        putOpt("paymentMethodRaw", item.paymentMethodRaw)
                        putOpt("assetName", item.assetName)
                        putOpt("categoryName", item.categoryName)
                        putOpt("note", item.note)
                        putOpt("billType", item.billType)
                    }
                )
            }
            val extras = Bundle().apply {
                putString("captures", arr.toString())
            }
            val res = callForObject(CaptureContract.METHOD_CLASSIFY_CAPTURES, arr.toString(), extras)
                ?: return@withContext ClassifyResult(ok = false, error = "无法连接敲敲记账")
            if (!res.optBoolean("ok", false)) {
                val msg = res.optString("message", "").ifBlank { res.optString("error", "自动归类未完成") }
                return@withContext ClassifyResult(ok = false, error = msg)
            }
            val draftsArr = res.optJSONArray("drafts") ?: return@withContext ClassifyResult(ok = true)
            val drafts = buildList {
                for (i in 0 until draftsArr.length()) {
                    val obj = draftsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("captureId", "").trim()
                    if (id.isEmpty()) continue
                    add(
                        ClassifiedDraft(
                            captureId = id,
                            categoryName = obj.optString("categoryName", "").trim().takeIf { it.isNotEmpty() },
                            assetName = obj.optString("assetName", "").trim().takeIf { it.isNotEmpty() },
                            remarks = obj.optString("remarks", "").trim().takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }
            ClassifyResult(ok = true, drafts = drafts)
        }

    /**
     * 轻量智能归类：只把备注与账单类型发给 App，不带金额/时间/资产/商户。
     *
     * 没有备注的条目不请求 AI；调用失败、超时或 App 未配置 AI 时返回 ok=false，
     * 调用方应保持这些待记原样（PENDING / 待归类）。
     */
    suspend fun classifyNotes(inputs: List<NoteClassifyInput>): ClassifyNotesResult =
        withContext(Dispatchers.IO) {
            val valid = inputs
                .asSequence()
                .filter { it.captureId.isNotBlank() && !it.note.isNullOrBlank() }
                .toList()
            if (valid.isEmpty()) return@withContext ClassifyNotesResult(ok = true)

            val arr = JSONArray()
            valid.forEach { input ->
                arr.put(
                    JSONObject().apply {
                        put("captureId", input.captureId)
                        put("note", input.note!!.trim())
                        input.billType?.let { put("billType", it) }
                    }
                )
            }
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_NOTES, arr.toString())
            }
            val res = try {
                callForObject(CaptureContract.METHOD_CLASSIFY_NOTES, arr.toString(), extras)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "classifyNotes failed", t)
                null
            } ?: return@withContext ClassifyNotesResult(ok = false, error = "无法连接敲敲记账")

            if (!res.optBoolean("ok", false)) {
                val msg = res.optString("message", "").ifBlank {
                    res.optString("error", "智能归类未完成")
                }
                return@withContext ClassifyNotesResult(ok = false, error = msg)
            }
            val categories = LinkedHashMap<String, String>()
            val drafts = res.optJSONArray("drafts") ?: JSONArray()
            for (i in 0 until drafts.length()) {
                val obj = drafts.optJSONObject(i) ?: continue
                val captureId = obj.optString("captureId", "").trim()
                if (captureId.isEmpty()) continue
                if (!obj.has("categoryName") || obj.isNull("categoryName")) continue
                val categoryName = obj.optString("categoryName", "").trim()
                if (categoryName.isNotEmpty()) categories[captureId] = categoryName
            }
            ClassifyNotesResult(ok = true, categories = categories)
        }

    /**
     * 文字拆分记账预览：App 只拆条目 + 金额；这里再补分类。
     *
     * 每个条目先跑本地备注规则 [CaptureRuleMatcher.resolveNoteCategory]；
     * 没命中的条目用 F03 的轻量 AI 分类批量兜底；仍无分类时用 App 返回的
     * `fallbackCategoryName`（通常是“其他”）。
     *
     * 只有用户勾选“拆分记账”时才调用；未勾选不会走这里。
     */
    suspend fun previewSplit(
        captureId: String,
        note: String?,
        totalCents: Long,
        billType: Int,
    ): SplitPreviewResult = withContext(Dispatchers.IO) {
        val cleanNote = note?.trim().orEmpty()
        if (cleanNote.isEmpty()) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "split_empty",
                message = "备注为空，请先写「苹果30 雪梨40」",
            )
        }
        if (totalCents <= 0L) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "invalid_total",
                message = "付款金额无效，无法拆分",
            )
        }
        if (billType != 0 && billType != 1) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "split_unsupported_type",
                message = "转账/还款/退款不支持文字拆分",
            )
        }

        val payload = JSONObject()
            .put("captureId", captureId)
            .put("note", cleanNote)
            .put("totalCents", totalCents)
            .put("billType", billType)
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_SPLIT_PAYLOAD, payload.toString())
        }
        val res = try {
            callForObject(CaptureContract.METHOD_PREVIEW_SPLIT, payload.toString(), extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "previewSplit failed", t)
            null
        } ?: return@withContext SplitPreviewResult(
            ok = false,
            error = "bridge_unavailable",
            message = "无法连接敲敲记账",
        )

        if (!res.optBoolean("ok", false)) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = res.optString("error", "").trim()
                    .takeUnless { it.isEmpty() || it == "null" }
                    ?: "split_failed",
                message = res.optString("message", "").trim()
                    .takeUnless { it.isEmpty() || it == "null" },
            )
        }

        val itemsArr = res.optJSONArray("items") ?: JSONArray()
        val rawItems = buildList {
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                add(
                    ParsedSplitItem(
                        name = name,
                        amountCents = obj.optLong("amountCents", 0L),
                        inferred = obj.optBoolean("inferred", false),
                    )
                )
            }
        }
        if (rawItems.isEmpty()) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "split_empty",
                message = "没从备注里拆出条目，请改备注",
            )
        }

        val ruleCategories = mutableMapOf<Int, String>()
        rawItems.forEachIndexed { index, item ->
            CaptureRuleMatcher.resolveNoteCategory(appContext, item.name)
                ?.takeIf { it.isNotBlank() }
                ?.let { ruleCategories[index] = it }
        }

        val aiCategories = mutableMapOf<Int, String>()
        val missing = rawItems.indices.filter { ruleCategories[it] == null }
        if (missing.isNotEmpty()) {
            val classified = classifyNotes(
                missing.map { index ->
                    NoteClassifyInput(
                        captureId = "$captureId#split$index",
                        note = rawItems[index].name,
                        billType = billType,
                    )
                }
            )
            if (classified.ok) {
                missing.forEach { index ->
                    classified.categories["$captureId#split$index"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { aiCategories[index] = it }
                }
            }
        }

        val fallbackCategory = res.optString("fallbackCategoryName", "")
            .trim()
            .takeUnless { it.isEmpty() || it == "null" }
        val items = rawItems.mapIndexed { index, item ->
            SplitItem(
                name = item.name,
                amountCents = item.amountCents,
                categoryName = ruleCategories[index] ?: aiCategories[index] ?: fallbackCategory,
                inferred = item.inferred,
            )
        }
        SplitPreviewResult(
            ok = true,
            items = items,
            totalCents = res.optLong("totalCents", totalCents),
            sumCents = res.optLong("sumCents", items.sumOf { it.amountCents }),
            diffCents = res.optLong("diffCents", 0L),
            fallbackCategoryName = fallbackCategory,
        )
    }

    /**
     * 图片拆分记账预览：把图片与 capture 金额发给 App 后台，App 多模态识图并分类后返回条目。
     * 本地规则仍享有最高优先级：若本地存在对应规则，覆盖 AI 返回的分类。
     */
    suspend fun previewImageSplit(
        captureId: String,
        totalCents: Long,
        billType: Int,
        imageUri: Uri,
    ): SplitPreviewResult = withContext(Dispatchers.IO) {
        if (totalCents <= 0L) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "invalid_total",
                message = "付款金额无效，无法拆分",
            )
        }
        if (billType != 0 && billType != 1) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "split_unsupported_type",
                message = "转账/还款/退款不支持图片拆分",
            )
        }

        val tempFile = try {
            File.createTempFile("cap_split_", ".tmp", appContext.cacheDir).apply {
                appContext.contentResolver.openInputStream(imageUri)?.use { input ->
                    outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选图片")
            }
        } catch (t: Throwable) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "image_read_failed",
                message = "读取图片失败：${t.message}",
            )
        }

        val pfd = try {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            tempFile.delete()
            return@withContext SplitPreviewResult(
                ok = false,
                error = "image_read_failed",
                message = "打开图片描述符失败：${t.message}",
            )
        }

        val mime = appContext.contentResolver.getType(imageUri) ?: "image/jpeg"
        val payload = JSONObject()
            .put("captureId", captureId)
            .put("totalCents", totalCents)
            .put("billType", billType)

        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_SPLIT_PAYLOAD, payload.toString())
            putString(CaptureContract.EXTRA_IMAGE_MIME, mime)
            putParcelable(CaptureContract.EXTRA_IMAGE_PFD, pfd)
        }

        val res = try {
            callForObject(CaptureContract.METHOD_PREVIEW_IMAGE_SPLIT, payload.toString(), extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "previewImageSplit failed", t)
            null
        } finally {
            runCatching { pfd.close() }
            tempFile.delete()
        } ?: return@withContext SplitPreviewResult(
            ok = false,
            error = "bridge_unavailable",
            message = "无法连接敲敲记账",
        )

        if (!res.optBoolean("ok", false)) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = res.optString("error", "").trim()
                    .takeUnless { it.isEmpty() || it == "null" }
                    ?: "split_failed",
                message = res.optString("message", "").trim()
                    .takeUnless { it.isEmpty() || it == "null" },
            )
        }

        val itemsArr = res.optJSONArray("items") ?: JSONArray()
        data class RawItem(
            val name: String,
            val amountCents: Long,
            val inferred: Boolean,
            val aiCategory: String?,
        )
        val rawItems = buildList {
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val cat = obj.optString("categoryName", "").trim()
                    .takeUnless { it.isEmpty() || it == "null" }
                add(
                    RawItem(
                        name = name,
                        amountCents = obj.optLong("amountCents", 0L),
                        inferred = obj.optBoolean("inferred", false),
                        aiCategory = cat,
                    )
                )
            }
        }
        if (rawItems.isEmpty()) {
            return@withContext SplitPreviewResult(
                ok = false,
                error = "split_empty",
                message = "未能从图片识别出有效条目，请改用清晰小票",
            )
        }

        val fallbackCategory = res.optString("fallbackCategoryName", "")
            .trim()
            .takeUnless { it.isEmpty() || it == "null" }

        val items = rawItems.map { raw ->
            val localRuleCat = CaptureRuleMatcher.resolveNoteCategory(appContext, raw.name)
                ?.takeIf { it.isNotBlank() }
            SplitItem(
                name = raw.name,
                amountCents = raw.amountCents,
                categoryName = localRuleCat ?: raw.aiCategory ?: fallbackCategory,
                inferred = raw.inferred,
            )
        }
        SplitPreviewResult(
            ok = true,
            items = items,
            totalCents = res.optLong("totalCents", totalCents),
            sumCents = res.optLong("sumCents", items.sumOf { it.amountCents }),
            diffCents = res.optLong("diffCents", 0L),
            fallbackCategoryName = fallbackCategory,
        )
    }

    private data class ParsedSplitItem(
        val name: String,
        val amountCents: Long,
        val inferred: Boolean,
    )

    /**
     * 拆分入账：把 capture 的资产/时间/账本和确认后的明细交给 App，
     * App 在同一事务里写 N 条账单 + 一条 captureId 回执。
     */
    suspend fun importBillGroup(
        capture: PaymentCapture,
        items: List<SplitItem>,
        requestId: String?,
    ): GroupImportResult? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        val itemArray = JSONArray()
        items.forEach { item ->
            itemArray.put(
                JSONObject()
                    .put("name", item.name)
                    .put("amountCents", item.amountCents)
                    .put("categoryName", item.categoryName ?: JSONObject.NULL)
            )
        }
        val payload = JSONObject()
            .put("captureId", capture.id)
            .put("type", capture.billType ?: 0)
            .put("subType", capture.subType ?: 0)
            .put("needsRefundLink", capture.needsRefundLink)
            .put("totalCents", capture.amountCents ?: items.sumOf { it.amountCents })
            .put("currency", capture.currency)
            .put("paidAt", capture.paidAt)
        capture.assetId?.let { payload.put("assetId", it) }
        (capture.assetName ?: capture.paymentMethodRaw)?.let { payload.put("assetName", it) }
        capture.bookName?.let { payload.put("bookName", it) }
        payload.put("excludeFromStats", capture.excludeFromStats)
        payload.put("items", itemArray)
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_BILL_GROUP_PAYLOAD, payload.toString())
            if (!requestId.isNullOrBlank()) {
                putString(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        val res = try {
            callForObject(CaptureContract.METHOD_IMPORT_BILL_GROUP, payload.toString(), extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "importBillGroup failed", t)
            null
        } ?: return@withContext null

        val billIds = buildList {
            val arr = res.optJSONArray("billIds") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optLong(i, -1L)
                if (id > 0L) add(id)
            }
        }
        GroupImportResult(
            ok = res.optBoolean("ok", false),
            duplicate = res.optBoolean("duplicate", false),
            captureId = res.optString("captureId", capture.id),
            paymentGroupId = res.optString("paymentGroupId", "")
                .trim()
                .takeUnless { it.isEmpty() || it == "null" },
            billIds = billIds,
            count = res.optInt("count", billIds.size),
            error = res.optString("error", "").trim()
                .takeUnless { it.isEmpty() || it == "null" },
            message = res.optString("message", "").trim()
                .takeUnless { it.isEmpty() || it == "null" },
        )
    }

    /**
     * AI 规则学习：只发备注、已确认分类和账单类型，取回 1-8 字可复用关键词。
     *
     * 失败、超时或 App 未配置 AI 都返回 ok=false，由调用方保留任务稍后重试。
     */
    suspend fun suggestRuleKeyword(
        note: String,
        categoryName: String,
        billType: Int,
    ): SuggestRuleResult = withContext(Dispatchers.IO) {
        val cleanNote = note.trim()
        val cleanCategory = categoryName.trim()
        if (cleanNote.isEmpty() || cleanCategory.isEmpty()) {
            return@withContext SuggestRuleResult(ok = true)
        }
        val payload = JSONObject()
            .put("note", cleanNote)
            .put("categoryName", cleanCategory)
            .put("billType", billType)
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_RULE_PAYLOAD, payload.toString())
        }
        val res = try {
            callForObject(CaptureContract.METHOD_SUGGEST_RULE, payload.toString(), extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "suggestRuleKeyword failed", t)
            null
        } ?: return@withContext SuggestRuleResult(ok = false, error = "无法连接敲敲记账")

        if (!res.optBoolean("ok", false)) {
            val msg = res.optString("message", "").ifBlank {
                res.optString("error", "规则学习未完成")
            }
            return@withContext SuggestRuleResult(ok = false, error = msg)
        }
        SuggestRuleResult(
            ok = true,
            keyword = res.optString("keyword", "").trim(),
        )
    }

    data class GenerateRuleResult(
        val ok: Boolean,
        val rule: JSONObject? = null,
        val error: String? = null,
    )

    /**
     * AI 自动生成记账规则：基于样本报文分析正则与字段映射。
     */
    suspend fun generateRule(
        app: String,
        type: String,
        rawJson: String,
        userInstruction: String? = null,
        currentPattern: String? = null,
    ): GenerateRuleResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("app", app)
            .put("type", type)
            .put("rawJson", rawJson)
        if (!userInstruction.isNullOrBlank()) payload.put("userInstruction", userInstruction)
        if (!currentPattern.isNullOrBlank()) payload.put("currentPattern", currentPattern)
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_GENERATE_RULE_PAYLOAD, payload.toString())
        }
        val res = try {
            callForObject(CaptureContract.METHOD_GENERATE_RULE, payload.toString(), extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "generateRule failed", t)
            null
        } ?: return@withContext GenerateRuleResult(ok = false, error = "无法连接主应用 AI 服务")

        if (!res.optBoolean("ok", false)) {
            val msg = res.optString("message", "").ifBlank {
                res.optString("error", "AI 生成规则失败")
            }
            return@withContext GenerateRuleResult(ok = false, error = msg)
        }
        GenerateRuleResult(
            ok = true,
            rule = res.optJSONObject("rule"),
        )
    }

    /**
     * 查询 [captureIds] 在 App 侧 `external_imports` 里的状态。
     */
    suspend fun getImportStatus(captureIds: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val ids = captureIds.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_IDS)
                .toList()
            if (ids.isEmpty()) return@withContext emptyMap()

            val arg = JSONArray(ids).toString()
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_CAPTURE_IDS, arg)
            }
            val array = callForArray(CaptureContract.METHOD_GET_IMPORT_STATUS, arg, extras)
                ?: return@withContext emptyMap()

            val result = LinkedHashMap<String, String>(ids.size)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("captureId", "").trim()
                if (id.isEmpty()) continue
                result[id] = item.optString("status", "")
                    .trim()
                    .uppercase(Locale.US)
                    .ifEmpty { STATUS_UNKNOWN }
            }
            ids.forEach { id -> result.putIfAbsent(id, STATUS_UNKNOWN) }
            result
        }

    /**
     * 让 App 打开悬浮窗“自己记”表单。
     */
    suspend fun openQuickRecord(
        prefill: JSONObject,
        captureId: String,
        requestId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_PREFILL, prefill.toString())
            putString(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            if (!requestId.isNullOrBlank()) {
                putString(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        val json = callForObject(CaptureContract.METHOD_OPEN_QUICK_RECORD, null, extras)
            ?: return@withContext false
        json.optBoolean("ok", false)
    }

    /**
     * 待记图片记账：把 capture 上下文交给 App，由 App 打开图片选择器、识别并合并入账。
     *
     * 资产/时间/账本/类型全部随 capture 一起下发，AI 只负责明细分类和金额。
     */
    suspend fun openImageAccounting(
        capture: PaymentCapture,
        requestId: String?,
    ): OpenImageAccountingResult = withContext(Dispatchers.IO) {
        if (capture.id.isBlank()) {
            return@withContext OpenImageAccountingResult(
                ok = false,
                error = "missing_captureId",
                message = "缺少 captureId",
            )
        }
        val payload = JSONObject()
            .put("captureId", capture.id)
            .put("amountCents", capture.amountCents ?: 0L)
            .put("currency", capture.currency)
            .put("paidAt", capture.paidAt)
            .put("type", capture.billType ?: 0)
            .put("subType", capture.subType ?: 0)
            .put("needsRefundLink", capture.needsRefundLink)
        capture.assetId?.let { payload.put("assetId", it) }
        (capture.assetName ?: capture.paymentMethodRaw)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { payload.put("assetName", it) }
        capture.bookName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("bookName", it) }
        capture.merchant?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("merchant", it) }
        capture.note?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("note", it) }

        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_CAPTURE_ID, capture.id)
            putString(CaptureContract.EXTRA_CAPTURE_PAYLOAD, payload.toString())
            if (!requestId.isNullOrBlank()) {
                putString(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        val json = try {
            callForObject(CaptureContract.METHOD_OPEN_IMAGE_ACCOUNTING, null, extras)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "openImageAccounting failed", t)
            null
        } ?: return@withContext OpenImageAccountingResult(
            ok = false,
            error = "bridge_unavailable",
            message = "无法连接敲敲记账",
        )

        OpenImageAccountingResult(
            ok = json.optBoolean("ok", false),
            accepted = json.optBoolean("accepted", false),
            duplicate = json.optBoolean("duplicate", false),
            captureId = json.optString("captureId", capture.id),
            billId = if (json.has("billId") && !json.isNull("billId")) json.optLong("billId") else null,
            error = json.optString("error", "").trim()
                .takeUnless { it.isEmpty() || it == "null" },
            message = json.optString("message", "").trim()
                .takeUnless { it.isEmpty() || it == "null" },
        )
    }

    /**
     * 把插件里已经确认好类型的待记直接交给 App 写入账本。
     */
    suspend fun importBills(
        bills: List<ImportBill>,
        requestId: String?,
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (bills.isEmpty()) return@withContext null
        val array = JSONArray()
        bills.forEach { bill ->
            array.put(
                JSONObject().apply {
                    put("captureId", bill.captureId)
                    put("type", bill.type)
                    put("subType", bill.subType)
                    put("needsRefundLink", bill.needsRefundLink)
                    put("amountCents", bill.amountCents)
                    put("currency", bill.currency)
                    put("paidAt", bill.paidAt)
                    putOpt("assetId", bill.assetId)
                    putOpt("assetName", bill.assetName)
                    putOpt("toAssetId", bill.toAssetId)
                    putOpt("toAssetName", bill.toAssetName)
                    putOpt("categoryId", bill.categoryId)
                    putOpt("categoryName", bill.categoryName)
                    put("remarks", bill.remarks)
                    putOpt("note", bill.note)
                    putOpt("bookName", bill.bookName)
                    put("excludeFromStats", bill.excludeFromStats)
                    put("feeCents", bill.feeCents)
                    putOpt("relatedBillId", bill.relatedBillId)
                }
            )
        }
        val extras = Bundle().apply {
            putString("bills", array.toString())
            if (!requestId.isNullOrBlank()) {
                putString(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        callForObject(CaptureContract.METHOD_IMPORT_BILLS, null, extras)
    }

    // ---------------- 底层调用 ----------------

    private fun callForObject(method: String, arg: String?, extras: Bundle?): JSONObject? {
        val raw = callRaw(method, arg, extras) ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure { Log.w(TAG, "bad JSONObject from $method", it) }
            .getOrNull()
    }

    private fun callForArray(method: String, arg: String?, extras: Bundle?): JSONArray? {
        val raw = callRaw(method, arg, extras) ?: return null
        return runCatching { JSONArray(raw) }
            .onFailure { Log.w(TAG, "bad JSONArray from $method", it) }
            .getOrNull()
    }

    private fun callRaw(method: String, arg: String?, extras: Bundle?): String? = try {
        val bundle = appContext.contentResolver.call(bridgeUri, method, arg, extras)
        bundle?.getString(CaptureContract.ACK_RESULT)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Log.w(TAG, "bridge call failed: $method", t)
        null
    }

    companion object {
        private const val TAG = "TapCaptureBridge"
        private const val MAX_IDS = 200
        private const val STATUS_UNKNOWN = "UNKNOWN"
    }
}
