package com.taostudio.tapaccounting.capture.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.CaptureReconciler
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.notify.CaptureNotifier
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件统一 Provider：
 * 1. `ingest`：供 adb 调试 / 同包调用；
 * 2. `getPendingCount` / `listPendingCaptures` / `updateCaptureDraft` / `ignoreCapture` / `markCapturesRecorded`：
 *    供敲敲记账主程序在敲击弹出悬浮窗时零延迟读取与同步待记数据。
 */
class CaptureIngestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return bundleOf(error("provider not attached"))
        if (method == CaptureContract.INGEST_METHOD) {
            val token = extras?.getString(CaptureContract.EXTRA_TOKEN) ?: arg
            val payloadJson = extras?.getString(CaptureContract.EXTRA_PAYLOAD)
            return bundleOf(CaptureIngestHandler.ingest(ctx, token, payloadJson))
        }

        if (!isAuthorizedAppCaller(ctx, extras, arg)) {
            return bundleOf(error("unauthorized caller"))
        }

        val response = try {
            runBlocking(Dispatchers.IO) {
                dispatchAppMethod(ctx, method, arg, extras)
            }
        } catch (t: Throwable) {
            error(t.message ?: t.javaClass.simpleName)
        }
        return bundleOf(response)
    }

    private fun isAuthorizedAppCaller(ctx: Context, extras: Bundle?, arg: String?): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid() || callingUid == Process.ROOT_UID || callingUid == Process.SHELL_UID) {
            return true
        }
        // 同签名校验：敲敲记账与插件共用同一 keystore，防止第三方冒充包名读取/修改待记。
        if (ctx.checkCallingPermission(CaptureContract.BRIDGE_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        if (ctx.packageManager.checkSignatures(Process.myUid(), callingUid) ==
            android.content.pm.PackageManager.SIGNATURE_MATCH
        ) {
            return true
        }
        val token = extras?.getString(CaptureContract.EXTRA_TOKEN) ?: arg
        return !token.isNullOrBlank() && token == BuildConfig.CAPTURE_INGEST_TOKEN
    }

    private suspend fun dispatchAppMethod(
        ctx: Context,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): String {
        val app = ctx.applicationContext as? CaptureApp ?: return error("app not attached")
        val repo = app.repository
        val onlyExpense = CapturePrefs.isOnlyExpense(app)

        return when (method) {
            CaptureContract.METHOD_GET_PENDING_COUNT -> {
                val count = repo.countPending(onlyExpense = onlyExpense)
                JSONObject()
                    .put("ok", true)
                    .put("count", count)
                    .put("updatedAt", System.currentTimeMillis())
                    .toString()
            }

            CaptureContract.METHOD_LIST_PENDING_CAPTURES -> {
                val limit = extras?.getInt("limit", 50)?.coerceIn(1, 200) ?: 50
                val rawItems = repo.listPending(limit, onlyExpense = onlyExpense)
                // 对账一次：如果某笔已经在 App 里入账，及时推进状态
                val changed = runCatching {
                    CaptureReconciler.reconcile(repo, CaptureBridgeClient(app), rawItems)
                }.getOrDefault(0)
                val items = if (changed > 0) repo.listPending(limit, onlyExpense = onlyExpense) else rawItems
                if (changed > 0) {
                    CaptureNotifier.refreshWithItems(app, items)
                }

                val array = JSONArray()
                for (item in items) {
                    val resolvedAsset = item.assetName
                        ?: CaptureRuleMatcher.resolveAssetName(app, item.paymentMethodRaw)
                    val resolvedCat = item.categoryName
                        ?: CaptureRuleMatcher.resolveCategoryName(
                            context = app,
                            merchant = item.merchant,
                            shopItem = null,
                            note = item.note,
                            fallbackCategory = null,
                        )
                    array.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("source", item.source)
                            put("amountCents", item.amountCents ?: 0L)
                            put("currency", item.currency)
                            putOpt("merchant", item.merchant)
                            putOpt("categoryName", resolvedCat)
                            putOpt("paymentMethodRaw", item.paymentMethodRaw)
                            putOpt("assetName", resolvedAsset)
                            putOpt("toAssetName", item.toAssetName)
                            put("paidAt", item.paidAt)
                            putOpt("bookName", item.bookName)
                            putOpt("note", item.note)
                            putOpt("billType", item.billType)
                            putOpt("subType", item.subType)
                            put("needsRefundLink", item.needsRefundLink)
                            put("status", item.status)
                        }
                    )
                }
                JSONObject()
                    .put("ok", true)
                    .put("count", items.size)
                    .put("items", array)
                    .toString()
            }

            CaptureContract.METHOD_UPDATE_CAPTURE_DRAFT -> {
                val id = (extras?.getString(CaptureContract.EXTRA_CAPTURE_ID) ?: arg)?.trim().orEmpty()
                if (id.isEmpty()) return error("missing captureId")
                val note = extras?.getString("note")
                val assetName = extras?.getString("assetName")
                val categoryName = extras?.getString("categoryName")
                val rememberAsset = extras?.getBoolean("rememberAsset", false) ?: false
                val rememberCategoryKeyword = extras?.getString("rememberCategoryKeyword")
                val updated = repo.updateDraft(
                    context = app,
                    id = id,
                    note = note,
                    assetName = assetName,
                    categoryName = categoryName,
                    rememberAssetMapping = rememberAsset,
                    rememberCategoryKeyword = rememberCategoryKeyword,
                )
                CaptureNotifier.refreshAll(app)
                JSONObject()
                    .put("ok", updated != null)
                    .putOpt("categoryName", updated?.categoryName)
                    .putOpt("assetName", updated?.assetName)
                    .putOpt("note", updated?.note)
                    .toString()
            }

            CaptureContract.METHOD_IGNORE_CAPTURE -> {
                val id = (extras?.getString(CaptureContract.EXTRA_CAPTURE_ID) ?: arg)?.trim().orEmpty()
                if (id.isEmpty()) return error("missing captureId")
                val ok = repo.updateStatus(id, CaptureStatus.IGNORED)
                CaptureNotifier.refreshAll(app)
                JSONObject()
                    .put("ok", ok)
                    .toString()
            }

            CaptureContract.METHOD_MARK_CAPTURES_RECORDED -> {
                val rawIds = extras?.getString(CaptureContract.EXTRA_CAPTURE_IDS) ?: arg.orEmpty()
                val ids = runCatching {
                    val arr = JSONArray(rawIds)
                    buildList {
                        for (i in 0 until arr.length()) {
                            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }
                }.getOrElse {
                    rawIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                var updatedCount = 0
                ids.forEach { id ->
                    if (repo.updateStatus(id, CaptureStatus.RECORDED)) {
                        updatedCount++
                    }
                }
                CaptureNotifier.refreshAll(app)
                JSONObject()
                    .put("ok", true)
                    .put("updatedCount", updatedCount)
                    .toString()
            }

            else -> error("unknown method: $method")
        }
    }

    private fun error(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("contractVersion", CaptureContract.VERSION)
            .put("error", message)
            .toString()

    private fun bundleOf(json: String): Bundle = Bundle().apply {
        putString(CaptureContract.ACK_RESULT, json)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("ingest only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ingest only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ingest only")
}
