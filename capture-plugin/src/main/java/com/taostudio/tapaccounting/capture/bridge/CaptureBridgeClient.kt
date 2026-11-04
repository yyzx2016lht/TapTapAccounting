package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.AssetInfo
import com.taostudio.tapaccounting.capture.contract.BookInfo
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CategoryInfo
import com.taostudio.tapaccounting.capture.contract.ImportBill
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        AccountingContext(
            contractVersion = json.optInt("contractVersion", CaptureContract.VERSION),
            defaultBookName = defaultBook,
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
