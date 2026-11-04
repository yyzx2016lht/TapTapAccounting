package com.taostudio.tapaccounting.capturebridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.AssetNameResolver
import com.taostudio.tapaccounting.logic.CategoryNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 敲敲记账 → 敲敲待记插件（[CaptureContract.INGEST_AUTHORITY]）的客户端。
 *
 * 核心原则：
 * - 如果用户手机没有安装「敲敲待记」插件（[isPluginInstalled] == false），
 *   所有方法 0 毫秒直接返回空/false，对普通无 Root 用户完全隐身、零性能开销。
 */
object CapturePluginClient {

    private val pluginUri: Uri = Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}")

    data class PendingCaptureItem(
        val id: String,
        val source: String,
        val amountCents: Long,
        val currency: String,
        val merchant: String?,
        var categoryName: String?,
        val paymentMethodRaw: String?,
        var assetName: String?,
        val toAssetName: String?,
        val paidAt: Long,
        val bookName: String?,
        var note: String?,
        val billType: Int?,
        val subType: Int?,
        val needsRefundLink: Boolean,
        val status: String,
    ) {
        fun sourceLabel(): String = CaptureSources.displayName(source)

        fun formattedAmount(): String =
            String.format(Locale.CHINA, "¥%.2f", amountCents / 100.0)

        fun formattedTime(): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(paidAt))

        fun displayAsset(): String? = assetName?.takeIf { it.isNotBlank() }
            ?: paymentMethodRaw?.takeIf { it.isNotBlank() }
    }

    /** 检查手机是否安装了「敲敲待记」插件。 */
    fun isPluginInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0) != null
    } catch (_: Throwable) {
        false
    }

    /** 拉取当前待记列表（已将资产与分类折算为敲敲记账本地有效名称）。 */
    suspend fun listPendingCaptures(context: Context, limit: Int = 30): List<PendingCaptureItem> =
        withContext(Dispatchers.IO) {
            if (!isPluginInstalled(context)) return@withContext emptyList()
            val extras = Bundle().apply { putInt("limit", limit) }
            val json = callForObject(
                context = context,
                method = CaptureContract.METHOD_LIST_PENDING_CAPTURES,
                arg = null,
                extras = extras,
            ) ?: return@withContext emptyList()
            if (!json.optBoolean("ok", false)) return@withContext emptyList()
            val itemsArr = json.optJSONArray("items") ?: return@withContext emptyList()

            val db = AppDatabase.getDatabase(context)
            val localAssets = runCatching { db.assetDao().getAllAssetsList() }.getOrDefault(emptyList())
            val localCategories = runCatching { db.categoryDao().getAllCategoriesList() }.getOrDefault(emptyList())

            buildList {
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val rawAsset = obj.optString("assetName", "").trim()
                        .ifEmpty { obj.optString("paymentMethodRaw", "").trim() }
                    val resolvedAsset = if (rawAsset.isNotEmpty()) {
                        AssetNameResolver.resolve(localAssets, rawAsset)?.name ?: rawAsset
                    } else {
                        null
                    }
                    val billType = if (obj.has("billType") && !obj.isNull("billType")) {
                        obj.optInt("billType", 0)
                    } else {
                        0
                    }
                    val rawCat = obj.optString("categoryName", "").trim()
                    val resolvedCat = if (rawCat.isNotEmpty()) {
                        (CategoryNameResolver.resolve(localCategories, rawCat, billType.coerceIn(0, 1))
                            ?: CategoryNameResolver.resolve(localCategories, rawCat, 0))
                            ?.let { CategoryNameResolver.displayPath(localCategories, it) }
                    } else {
                        null
                    }

                    add(
                        PendingCaptureItem(
                            id = id,
                            source = obj.optString("source", CaptureSource.WECHAT),
                            amountCents = obj.optLong("amountCents", 0L),
                            currency = obj.optString("currency", "CNY"),
                            merchant = obj.optString("merchant", "").trim().takeIf { it.isNotEmpty() },
                            categoryName = resolvedCat,
                            paymentMethodRaw = obj.optString("paymentMethodRaw", "").trim().takeIf { it.isNotEmpty() },
                            assetName = resolvedAsset,
                            toAssetName = obj.optString("toAssetName", "").trim().takeIf { it.isNotEmpty() },
                            paidAt = obj.optLong("paidAt", System.currentTimeMillis()),
                            bookName = obj.optString("bookName", "").trim().takeIf { it.isNotEmpty() },
                            note = obj.optString("note", "").trim().takeIf { it.isNotEmpty() },
                            billType = billType,
                            subType = if (obj.has("subType") && !obj.isNull("subType")) obj.optInt("subType", 0) else 0,
                            needsRefundLink = obj.optBoolean("needsRefundLink", false),
                            status = obj.optString("status", "PENDING"),
                        )
                    )
                }
            }
        }

    data class DraftUpdateResult(
        val ok: Boolean,
        val categoryName: String? = null,
        val assetName: String? = null,
        val note: String? = null,
    )

    suspend fun updateCaptureDraft(
        context: Context,
        captureId: String,
        note: String?,
        assetName: String? = null,
        categoryName: String? = null,
        categorySource: String? = null,
        rememberAsset: Boolean = false,
        rememberCategoryKeyword: String? = null,
    ): DraftUpdateResult = withContext(Dispatchers.IO) {
        if (!isPluginInstalled(context)) return@withContext DraftUpdateResult(ok = false)
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            note?.let { putString("note", it) }
            assetName?.let { putString("assetName", it) }
            categoryName?.let { putString("categoryName", it) }
            categorySource?.let { putString("categorySource", it) }
            putBoolean("rememberAsset", rememberAsset)
            rememberCategoryKeyword?.let { putString("rememberCategoryKeyword", it) }
        }
        val json = callForObject(
            context = context,
            method = CaptureContract.METHOD_UPDATE_CAPTURE_DRAFT,
            arg = captureId,
            extras = extras,
        ) ?: return@withContext DraftUpdateResult(ok = false)
        val ok = json.optBoolean("ok", false)
        val rawCat = json.optString("categoryName", "").trim().takeIf { it.isNotEmpty() }
        val resolvedCat = if (rawCat != null) {
            val localCategories = runCatching {
                AppDatabase.getDatabase(context).categoryDao().getAllCategoriesList()
            }.getOrDefault(emptyList())
            (CategoryNameResolver.resolve(localCategories, rawCat, 0)
                ?: CategoryNameResolver.resolve(localCategories, rawCat, 1))
                ?.let { CategoryNameResolver.displayPath(localCategories, it) }
                ?: rawCat
        } else {
            null
        }
        DraftUpdateResult(
            ok = ok,
            categoryName = resolvedCat,
            assetName = json.optString("assetName", "").trim().takeIf { it.isNotEmpty() },
            note = json.optString("note", "").trim().takeIf { it.isNotEmpty() },
        )
    }

    suspend fun ignoreCapture(context: Context, captureId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isPluginInstalled(context)) return@withContext false
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            }
            val json = callForObject(
                context = context,
                method = CaptureContract.METHOD_IGNORE_CAPTURE,
                arg = captureId,
                extras = extras,
            ) ?: return@withContext false
            json.optBoolean("ok", false)
        }

    suspend fun markCapturesRecorded(context: Context, captureIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (captureIds.isEmpty() || !isPluginInstalled(context)) return@withContext false
            val arg = JSONArray(captureIds).toString()
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_CAPTURE_IDS, arg)
            }
            val json = callForObject(
                context = context,
                method = CaptureContract.METHOD_MARK_CAPTURES_RECORDED,
                arg = arg,
                extras = extras,
            ) ?: return@withContext false
            json.optBoolean("ok", false)
        }

    /** 将单条待记转换为敲敲记账悬浮窗表单认识的 prefill JSON。 */
    fun toPrefillJson(item: PendingCaptureItem): JSONObject {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return JSONObject().apply {
            put("type", item.billType ?: 0)
            put("subType", item.subType ?: 0)
            put("amount", item.amountCents / 100.0)
            put("currency", item.currency.ifBlank { "CNY" })
            item.displayAsset()?.let { put("asset_name", it) }
            item.toAssetName?.let { put("to_asset_name", it) }
            item.categoryName?.let { put("category_name", it) }
            put("time", timeFmt.format(Date(item.paidAt)))
            val remark = item.note?.takeIf { it.isNotBlank() }
                ?: item.merchant?.takeIf { it.isNotBlank() }
                ?: ""
            put("remarks", remark)
            item.bookName?.let { put("book_name", it) }
        }
    }

    private fun callForObject(
        context: Context,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): JSONObject? = try {
        val bundle = context.applicationContext.contentResolver.call(pluginUri, method, arg, extras)
        val raw = bundle?.getString(CaptureContract.ACK_RESULT)?.trim()?.takeIf { it.isNotEmpty() }
        raw?.let { JSONObject(it) }
    } catch (_: Throwable) {
        null
    }
}
