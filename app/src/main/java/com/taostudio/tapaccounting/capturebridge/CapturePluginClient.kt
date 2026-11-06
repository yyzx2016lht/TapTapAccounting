package com.taostudio.tapaccounting.capturebridge

import android.content.Context
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.AssetNameResolver
import com.taostudio.tapaccounting.logic.CategoryNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 悬浮窗「待记细条 / 待记速清」的数据入口。
 *
 * P2 起不再跨进程读插件 Provider，直接读写主应用 Room；类名保留是为了让
 * [OverlayPendingCaptureController] 的改动面最小。
 */
object CapturePluginClient {

    data class PendingCaptureItem(
        val id: String,
        val source: String,
        val amountCents: Long,
        val currency: String,
        val merchant: String?,
        var categoryName: String?,
        var categorySource: String? = null,
        var categoryRuleId: Long? = null,
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

    /** 旧插件安装或已完成旧数据迁移时，悬浮窗才需要显示待记细条。 */
    fun isPluginInstalled(context: Context): Boolean {
        if (CapturePrefs.isLegacyImported(context)) return true
        return runCatching {
            context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0) != null
        }.getOrDefault(false)
    }

    suspend fun listPendingCaptures(context: Context, limit: Int = 30): List<PendingCaptureItem> =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val repo = CaptureRepository(db.paymentCaptureDao())
            val onlyExpense = CapturePrefs.isOnlyExpense(context)
            val items = repo.listPending(limit, onlyExpense = onlyExpense)
            if (items.isEmpty()) return@withContext emptyList()

            val localAssets = runCatching { db.assetDao().getAllAssetsList() }.getOrDefault(emptyList())
            val localCategories = runCatching { db.categoryDao().getAllCategoriesList() }
                .getOrDefault(emptyList())

            items.map { item ->
                val rawAsset = item.assetName ?: item.paymentMethodRaw
                val resolvedAsset = if (!rawAsset.isNullOrBlank()) {
                    AssetNameResolver.resolve(localAssets, rawAsset)?.name ?: rawAsset
                } else {
                    null
                }
                val billType = item.billType ?: 0
                val resolvedCat = item.categoryName?.takeIf { it.isNotBlank() }?.let { rawCat ->
                    (CategoryNameResolver.resolve(localCategories, rawCat, billType.coerceIn(0, 1))
                        ?: CategoryNameResolver.resolve(localCategories, rawCat, 0))
                        ?.let { CategoryNameResolver.displayPath(localCategories, it) }
                        ?: rawCat
                }
                PendingCaptureItem(
                    id = item.id,
                    source = item.source,
                    amountCents = item.amountCents ?: 0L,
                    currency = item.currency,
                    merchant = item.merchant?.takeIf { it.isNotBlank() },
                    categoryName = resolvedCat,
                    categorySource = item.categorySource,
                    categoryRuleId = item.categoryRuleId,
                    paymentMethodRaw = item.paymentMethodRaw?.takeIf { it.isNotBlank() },
                    assetName = resolvedAsset,
                    toAssetName = item.toAssetName,
                    paidAt = item.paidAt,
                    bookName = item.bookName,
                    note = item.note,
                    billType = item.billType,
                    subType = item.subType ?: 0,
                    needsRefundLink = item.needsRefundLink,
                    status = item.status,
                )
            }
        }

    data class DraftUpdateResult(
        val ok: Boolean,
        val categoryName: String? = null,
        val categorySource: String? = null,
        val categoryRuleId: Long? = null,
        val assetName: String? = null,
        val note: String? = null,
    )

    suspend fun updateCaptureDraft(
        context: Context,
        captureId: String,
        note: String? = null,
        assetName: String? = null,
        categoryName: String? = null,
        categorySource: String? = null,
        rememberAsset: Boolean = false,
        rememberCategoryKeyword: String? = null,
    ): DraftUpdateResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val repo = CaptureRepository(db.paymentCaptureDao())
        val updated = repo.updateDraft(
            context = context,
            id = captureId,
            note = note,
            assetName = assetName,
            categoryName = categoryName,
            categorySource = categorySource,
            rememberAssetMapping = rememberAsset,
            rememberCategoryKeyword = rememberCategoryKeyword,
        ) ?: return@withContext DraftUpdateResult(ok = false)

        val localCategories = runCatching { db.categoryDao().getAllCategoriesList() }
            .getOrDefault(emptyList())
        val resolvedCat = updated.categoryName?.takeIf { it.isNotBlank() }?.let { rawCat ->
            (CategoryNameResolver.resolve(localCategories, rawCat, 0)
                ?: CategoryNameResolver.resolve(localCategories, rawCat, 1))
                ?.let { CategoryNameResolver.displayPath(localCategories, it) }
                ?: rawCat
        }
        DraftUpdateResult(
            ok = true,
            categoryName = resolvedCat,
            categorySource = updated.categorySource,
            categoryRuleId = updated.categoryRuleId,
            assetName = updated.assetName,
            note = updated.note,
        )
    }

    suspend fun ignoreCapture(context: Context, captureId: String): Boolean =
        withContext(Dispatchers.IO) {
            CaptureRepository(AppDatabase.getDatabase(context).paymentCaptureDao())
                .updateStatus(captureId, CaptureStatus.IGNORED)
        }

    suspend fun markCapturesRecorded(context: Context, captureIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (captureIds.isEmpty()) return@withContext false
            val repo = CaptureRepository(AppDatabase.getDatabase(context).paymentCaptureDao())
            var ok = true
            captureIds.forEach { id ->
                if (!repo.updateStatus(id, CaptureStatus.RECORDED)) ok = false
            }
            ok
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
            item.categorySource?.let { put("category_source", it) }
            item.categoryRuleId?.let { put("category_rule_id", it) }
            put("time", timeFmt.format(Date(item.paidAt)))
            val remark = item.note?.takeIf { it.isNotBlank() }
                ?: item.merchant?.takeIf { it.isNotBlank() }
                ?: ""
            put("remarks", remark)
            item.bookName?.let { put("book_name", it) }
        }
    }
}
