package com.taostudio.tapaccounting.capturebridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.OverlayService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.AssetInfo
import com.taostudio.tapaccounting.capture.contract.BookInfo
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CategoryInfo
import com.taostudio.tapaccounting.capture.contract.ImportBill
import com.taostudio.tapaccounting.capture.contract.ImportItemStatus
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ExternalImport
import com.taostudio.tapaccounting.logic.AssetNameResolver
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CategoryNameResolver
import com.taostudio.tapaccounting.logic.DuplicateCaptureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 支付捕获插件 → 敲敲记账的唯一写入/查询桥（P0）。
 *
 * 只实现 [call]；query/insert/update/delete 一律拒绝，避免把 App 数据库暴露成通用 CRUD。
 * 权限由 manifest 的 `signature` 级 [CaptureContract.BRIDGE_PERMISSION] 保护，只有同签名
 * 的插件（debug 与 App debug 共用同一 debug keystore）能调用。
 *
 * 所有金额用分（Long）、时间用毫秒（Long）。任何异常都转成 `{ok:false}` 的 JSON，
 * 绝不把异常抛过 binder（插件侧不会因此崩溃/收到 DeadObjectException）。
 */
class CaptureBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val json = try {
            runBlocking(Dispatchers.IO) { dispatch(method, arg, extras) }
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "internal_error")
                .put("message", t.message ?: t.javaClass.simpleName)
        }
        return Bundle().apply {
            putString(CaptureContract.ACK_RESULT, json.toString())
        }
    }

    private suspend fun dispatch(method: String, arg: String?, extras: Bundle?): Any {
        val ctx = context ?: throw IllegalStateException("provider context unavailable")
        return when (method) {
            CaptureContract.METHOD_PING -> ping(ctx)
            CaptureContract.METHOD_GET_ACCOUNTING_CONTEXT -> getAccountingContext(ctx)
            CaptureContract.METHOD_GET_RECENT_BILLS -> getRecentBills(ctx, extras)
            CaptureContract.METHOD_OPEN_QUICK_RECORD -> openQuickRecord(ctx, extras)
            CaptureContract.METHOD_IMPORT_BILLS -> importBills(ctx, arg, extras)
            CaptureContract.METHOD_GET_IMPORT_STATUS -> getImportStatus(ctx, arg, extras)
            // P2 未实现：明确报错，不静默返回空数据。
            CaptureContract.METHOD_START_CLASSIFY,
            CaptureContract.METHOD_GET_CLASSIFY_RESULT -> errorJson(
                "not_implemented",
                "$method 属于 P2，当前未开放"
            )
            else -> errorJson("unknown_method", "未知方法：$method")
        }
    }

    // ---------------- 方法 ----------------

    private fun ping(ctx: Context): JSONObject {
        val appVersion = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        return JSONObject()
            .put("ok", true)
            .put("contractVersion", CaptureContract.CONTRACT_VERSION)
            .put("appVersion", appVersion)
            .put("importEnabled", true)
            // AI 代理是 P2，插件据此走本地草稿/自己记。
            .put("aiEnabled", false)
    }

    private suspend fun getAccountingContext(ctx: Context): JSONObject {
        val db = AppDatabase.getDatabase(ctx)
        val bookRows = db.bookDao().getAll()
        val bookNames = BookAccountManager.getBookAccounts(ctx, bookRows.map { it.name })
        val books = bookNames.mapIndexed { index, name ->
            BookInfo(
                id = bookRows.firstOrNull { it.name == name }?.id ?: (index + 1L),
                name = name
            )
        }
        val assets = db.assetDao().getAllAssetsList()
            .asSequence()
            .filterNot { it.isArchived }
            .map {
                AssetInfo(
                    id = it.id,
                    name = it.name,
                    type = it.type,
                    assetCategory = it.assetCategory,
                    currency = it.currency
                )
            }
            .toList()
        val categories = db.categoryDao().getAllCategoriesList().map {
            CategoryInfo(id = it.id, name = it.name, type = it.type, parentId = it.parentId)
        }
        val context = AccountingContext(
            contractVersion = CaptureContract.CONTRACT_VERSION,
            defaultBookName = BookAccountManager.getDefaultBook(ctx, bookNames),
            assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx),
            books = books,
            assets = assets,
            categories = categories
        )
        return JSONObject()
            .put("ok", true)
            .put("contractVersion", context.contractVersion)
            .put("defaultBookName", context.defaultBookName ?: JSONObject.NULL)
            .put("assetFeatureEnabled", context.assetFeatureEnabled)
            .put("books", JSONArray().apply {
                context.books.forEach { book ->
                    put(JSONObject().put("id", book.id).put("name", book.name))
                }
            })
            .put("assets", JSONArray().apply {
                context.assets.forEach { asset ->
                    put(
                        JSONObject()
                            .put("id", asset.id)
                            .put("name", asset.name)
                            .put("type", asset.type)
                            .put("assetCategory", asset.assetCategory)
                            .put("currency", asset.currency)
                    )
                }
            })
            .put("categories", JSONArray().apply {
                context.categories.forEach { category ->
                    put(
                        JSONObject()
                            .put("id", category.id)
                            .put("name", category.name)
                            .put("type", category.type)
                            .put("parentId", category.parentId ?: JSONObject.NULL)
                    )
                }
            })
    }

    private suspend fun getRecentBills(ctx: Context, extras: Bundle?): JSONArray {
        val paidAt = normalizeMillis(extras.longOr(CaptureContract.EXTRA_PAID_AT, System.currentTimeMillis()))
        val windowMinutes = extras.intOr("windowMinutes", 30).coerceIn(1, 24 * 60)
        val limit = extras.intOr("limit", 50).coerceIn(1, 200)
        val windowMs = windowMinutes * 60_000L

        val bills = AppDatabase.getDatabase(ctx).billDao()
            .getBillsBetweenTimesList(paidAt - windowMs, paidAt + windowMs)
            .asSequence()
            .filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }
            .sortedByDescending { it.time }
            .take(limit)
            .toList()

        return JSONArray().apply {
            bills.forEach { bill ->
                put(
                    JSONObject()
                        .put("billId", bill.id)
                        .put("amountCents", Math.round(bill.amount * 100.0))
                        .put("paidAt", bill.time)
                        .put("accountId", bill.accountId ?: JSONObject.NULL)
                        .put("accountName", bill.accountName)
                        .put("type", bill.type)
                )
            }
        }
    }

    private suspend fun openQuickRecord(ctx: Context, extras: Bundle?): JSONObject {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(ctx)
        ) {
            return errorJson(
                "overlay_permission_denied",
                "敲敲记账没有悬浮窗权限，无法打开记账表单"
            )
        }

        val captureId = extras?.getString(CaptureContract.EXTRA_CAPTURE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val requestId = extras?.getString(CaptureContract.EXTRA_REQUEST_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val prefillRaw = extras?.getString(CaptureContract.EXTRA_PREFILL)
        val prefill = if (prefillRaw.isNullOrBlank()) {
            null
        } else {
            runCatching { JSONObject(prefillRaw) }.getOrElse {
                return errorJson("invalid_prefill", "prefill 不是合法 JSON：${it.message}")
            }
        }
        val normalizedPrefill = prefill?.let { normalizePrefillAssets(ctx, it) }

        // 第一道幂等：已导入过就直接回执，不再开悬浮窗。
        if (captureId != null) {
            val existing = AppDatabase.getDatabase(ctx).externalImportDao().getByCaptureId(captureId)
            if (existing != null) {
                return JSONObject()
                    .put("ok", true)
                    .put("accepted", false)
                    .put("duplicate", true)
                    .put("captureId", captureId)
                    .put("billId", existing.billId)
            }
        }

        val intent = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            normalizedPrefill?.let { putExtra(OverlayService.EXTRA_PREFILL, it.toString()) }
            captureId?.let { putExtra(OverlayService.EXTRA_CAPTURE_ID, it) }
            requestId?.let { putExtra(OverlayService.EXTRA_REQUEST_ID, it) }
        }
        return try {
            OverlayService.startCompat(ctx, intent)
            JSONObject()
                .put("ok", true)
                .put("accepted", true)
                .put("captureId", captureId ?: JSONObject.NULL)
                .put("requestId", requestId ?: JSONObject.NULL)
        } catch (e: Exception) {
            // Android 12+ 后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException；
            // 纯后台被系统拒绝是预期情况，交给插件提示“点开 App 再试”。
            errorJson(
                "foreground_service_not_allowed",
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private suspend fun importBills(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString("bills")
            ?: extras?.getString("billsJson")
            ?: arg
            ?: return errorJson("missing_bills", "缺少 bills JSON")
        val requestId = extras?.getString(CaptureContract.EXTRA_REQUEST_ID)

        val array = try {
            if (raw.trimStart().startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONArray().put(JSONObject(raw))
            }
        } catch (t: Throwable) {
            return errorJson("invalid_bills", "bills 不是合法 JSON：${t.message}")
        }

        val db = AppDatabase.getDatabase(ctx)
        val bookRows = db.bookDao().getAll()
        val books = BookAccountManager.getBookAccounts(ctx, bookRows.map { it.name })
        val defaultBook = BookAccountManager.getDefaultBook(ctx, books)
        val results = JSONArray()
        var allOk = true

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val captureId = item?.optString("captureId", "").orEmpty().trim()
            try {
                requireNotNull(item) { "bills[$index] 不是对象" }
                val import = parseImportBill(item)
                val created = importOne(ctx, db, import, requestId, defaultBook)
                results.put(
                    JSONObject()
                        .put("captureId", created.first)
                        .put("billId", created.second)
                        .put("status", created.third)
                )
                if (created.third == ImportItemStatus.ERROR) allOk = false
            } catch (t: DuplicateCaptureException) {
                results.put(
                    JSONObject()
                        .put("captureId", t.captureId)
                        .put("billId", t.existingBillId)
                        .put("status", ImportItemStatus.DUPLICATE)
                )
            } catch (t: Throwable) {
                allOk = false
                results.put(
                    JSONObject()
                        .put("captureId", captureId)
                        .put("billId", JSONObject.NULL)
                        .put("status", ImportItemStatus.ERROR)
                        .put("error", t.message ?: t.javaClass.simpleName)
                )
            }
        }

        return JSONObject()
            .put("ok", allOk)
            .put("requestId", requestId ?: JSONObject.NULL)
            .put("results", results)
    }

    /** 返回 captureId / billId / status。 */
    private suspend fun importOne(
        ctx: Context,
        db: AppDatabase,
        import: ImportBill,
        requestId: String?,
        defaultBook: String
    ): Triple<String, Long, String> {
        val existing = db.externalImportDao().getByCaptureId(import.captureId)
        if (existing != null && db.billDao().getBillById(existing.billId) != null) {
            return Triple(import.captureId, existing.billId, ImportItemStatus.DUPLICATE)
        }
        if (existing != null) {
            // 账单已被删除：回执失效，允许重新导入。
            db.externalImportDao().deleteByCaptureId(import.captureId)
        }

        val type = when (import.type) {
            Bill.TYPE_REPAYMENT -> Bill.TYPE_TRANSFER
            else -> import.type
        }
        val subType = when {
            import.type == Bill.TYPE_REPAYMENT -> Bill.SUBTYPE_REPAYMENT
            import.subType == Bill.SUBTYPE_REFUND -> Bill.SUBTYPE_REFUND
            else -> import.subType
        }
        require(type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
            "unsupported bill type: ${import.type}"
        }

        if (subType == Bill.SUBTYPE_REFUND || import.needsRefundLink) {
            return importRefund(db, import, requestId)
        }

        val asset = resolveAsset(db, import.assetId, import.assetName)
        val toAsset = resolveAsset(db, import.toAssetId, import.toAssetName)
        if (type == Bill.TYPE_TRANSFER) {
            require(Prefs.isAssetFeatureEnabled(ctx)) { "asset_feature_disabled" }
            requireNotNull(asset) { "missing_asset" }
            requireNotNull(toAsset) { "missing_to_asset" }
            require(asset.id != toAsset.id) { "same_asset" }
        }

        val resolvedCategory = if (type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME)) {
            CategoryNameResolver.resolve(
                db.categoryDao().getAllCategoriesList(),
                import.categoryName,
                type,
            )
        } else {
            null
        }
        val categoryName = when {
            type == Bill.TYPE_TRANSFER && subType == Bill.SUBTYPE_REPAYMENT ->
                "还款到${toAsset?.name.orEmpty()}"
            type == Bill.TYPE_TRANSFER -> "转账到${toAsset?.name.orEmpty()}"
            else -> resolvedCategory?.name.orEmpty()
        }

        val bill = Bill(
            type = type,
            subType = subType,
            amount = import.amountCents / 100.0,
            originalAmount = import.amountCents / 100.0,
            currency = import.currency.ifBlank { "CNY" },
            categoryId = import.categoryId ?: resolvedCategory?.id,
            categoryName = categoryName,
            accountId = asset?.id,
            accountName = asset?.name.orEmpty(),
            toAccountId = toAsset?.id,
            toAccountName = toAsset?.name.orEmpty(),
            time = normalizeMillis(import.paidAt),
            remark = import.remarks,
            fee = import.feeCents / 100.0,
            bookName = import.bookName?.takeIf { it.isNotBlank() } ?: defaultBook,
            relatedBillId = import.relatedBillId,
        )

        val saved = BillMutationService.insertBillAndApplyImpact(
            db = db,
            bill = bill,
            applyAssetImpact = true,
            captureId = import.captureId,
            requestId = requestId,
        )
        return Triple(import.captureId, saved.id, ImportItemStatus.CREATED)
    }

    private suspend fun importRefund(
        db: AppDatabase,
        import: ImportBill,
        requestId: String?
    ): Triple<String, Long, String> {
        val original = import.relatedBillId?.let { relatedId ->
            db.billDao().getBillById(relatedId)
                ?: throw IllegalArgumentException("refund_source_not_found")
        } ?: run {
            // 没有显式关联时，按分类/金额/时间找唯一候选；找不到就留在“待关联”。
            val probe = Bill(
                type = Bill.TYPE_EXPENSE,
                subType = Bill.SUBTYPE_REFUND,
                amount = import.amountCents / 100.0,
                originalAmount = import.amountCents / 100.0,
                categoryName = import.categoryName.orEmpty(),
                time = normalizeMillis(import.paidAt),
            )
            BillMutationService.resolveRefundSourceBill(db, probe)
                ?: throw IllegalArgumentException("needs_refund_link")
        }
        require(original.type == Bill.TYPE_EXPENSE && original.subType != Bill.SUBTYPE_REFUND) {
            "refund_source_not_expense"
        }

        val amount = import.amountCents / 100.0
        val refund = Bill(
            type = Bill.TYPE_INCOME,
            subType = Bill.SUBTYPE_REFUND,
            amount = amount,
            originalAmount = amount,
            currency = original.currency,
            exchangeRate = original.exchangeRate,
            categoryId = original.categoryId,
            categoryName = "退款：${original.categoryName}",
            accountId = original.accountId,
            accountName = original.accountName,
            time = normalizeMillis(import.paidAt),
            remark = import.remarks,
            bookName = original.bookName,
            relatedBillId = original.id,
        )
        val saved = BillMutationService.saveRefundBill(db, original, refund)

        val inserted = db.externalImportDao().insertIgnore(
            ExternalImport(
                captureId = import.captureId,
                billId = saved.id,
                requestId = requestId,
                createdAt = System.currentTimeMillis(),
            )
        )
        if (inserted == -1L) {
            val existing = db.externalImportDao().getByCaptureId(import.captureId)
            if (existing != null) {
                return Triple(import.captureId, existing.billId, ImportItemStatus.DUPLICATE)
            }
        }
        return Triple(import.captureId, saved.id, ImportItemStatus.CREATED)
    }

    private suspend fun resolveAsset(
        db: AppDatabase,
        id: Long?,
        name: String?
    ): Asset? {
        if (id != null && id > 0L) {
            return db.assetDao().getAssetById(id)
                ?: throw IllegalArgumentException("asset_not_found:$id")
        }
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return AssetNameResolver.resolve(db.assetDao().getAllAssetsList(), trimmed)
            ?: throw IllegalArgumentException("asset_not_found:$trimmed")
    }

    /**
     * 悬浮窗预填还是渠道原始户名（零钱/招商银行储蓄卡(4852)）；
     * 在 App 边界折成本地资产名，避免编辑器里选中一个不存在的资产。
     */
    private suspend fun normalizePrefillAssets(ctx: Context, source: JSONObject): JSONObject {
        val db = AppDatabase.getDatabase(ctx)
        val assets = db.assetDao().getAllAssetsList()
        val categories = db.categoryDao().getAllCategoriesList()
        val out = JSONObject(source.toString())
        for (key in listOf("asset_name", "to_asset_name")) {
            val raw = out.optString(key, "").trim()
            if (raw.isEmpty()) continue
            AssetNameResolver.resolve(assets, raw)?.let { out.put(key, it.name) }
        }
        val rawCategory = out.optString("category_name", "").trim()
        if (rawCategory.isNotEmpty()) {
            val type = out.optInt("type", 0).coerceIn(0, 1)
            val category = CategoryNameResolver.resolve(categories, rawCategory, type)
            if (category != null) {
                out.put("category_name", category.name)
            } else {
                // 陌生分类不硬塞进本地分类；让用户在悬浮窗自己选。
                out.remove("category_name")
            }
        }
        return out
    }

    private fun parseImportBill(obj: JSONObject): ImportBill {
        val captureId = obj.optString("captureId", "").trim()
        require(captureId.isNotEmpty()) { "missing_captureId" }
        val amountCents = obj.optLong("amountCents", -1L)
        require(amountCents > 0L) { "invalid_amountCents" }
        val type = obj.optInt("type", Bill.TYPE_EXPENSE)
        val subType = if (obj.has("subType")) {
            obj.optInt("subType", Bill.SUBTYPE_NORMAL)
        } else if (type == Bill.TYPE_REPAYMENT) {
            Bill.SUBTYPE_REPAYMENT
        } else {
            Bill.SUBTYPE_NORMAL
        }
        return ImportBill(
            captureId = captureId,
            type = type,
            subType = subType,
            needsRefundLink = obj.optBoolean(
                "needsRefundLink",
                subType == Bill.SUBTYPE_REFUND
            ),
            amountCents = amountCents,
            currency = obj.optString("currency", "CNY"),
            paidAt = normalizeMillis(obj.optLong("paidAt", System.currentTimeMillis())),
            assetId = obj.longOrNull("assetId"),
            assetName = obj.optString("assetName", "").takeIf { it.isNotBlank() },
            toAssetId = obj.longOrNull("toAssetId"),
            toAssetName = obj.optString("toAssetName", "").takeIf { it.isNotBlank() },
            categoryId = obj.longOrNull("categoryId"),
            categoryName = obj.optString("categoryName", "").takeIf { it.isNotBlank() },
            remarks = obj.optString("remarks", ""),
            bookName = obj.optString("bookName", "").takeIf { it.isNotBlank() },
            feeCents = obj.optLong("feeCents", 0L),
            relatedBillId = obj.longOrNull("relatedBillId"),
        )
    }

    private fun JSONObject.longOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private suspend fun getImportStatus(ctx: Context, arg: String?, extras: Bundle?): JSONArray {
        val raw = extras?.getString(CaptureContract.EXTRA_CAPTURE_IDS) ?: arg
        val ids = parseCaptureIds(raw)
        val rows = if (ids.isEmpty()) {
            emptyList()
        } else {
            AppDatabase.getDatabase(ctx).externalImportDao().getByCaptureIds(ids)
        }
        val byId = rows.associateBy { it.captureId }
        return JSONArray().apply {
            ids.forEach { id ->
                val row = byId[id]
                put(
                    JSONObject().apply {
                        put("captureId", id)
                        if (row != null) {
                            put("status", "RECORDED")
                            put("billId", row.billId)
                            put("importedAt", row.createdAt)
                        } else {
                            // App 侧只有“已写入”的回执；IMPORTING 是插件本地状态。
                            put("status", "UNKNOWN")
                            put("billId", JSONObject.NULL)
                            put("importedAt", JSONObject.NULL)
                        }
                    }
                )
            }
        }
    }

    // ---------------- 工具 ----------------

    private fun parseCaptureIds(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val parsed = if (text.startsWith("[")) {
            runCatching {
                val array = JSONArray(text)
                buildList {
                    for (i in 0 until array.length()) {
                        array.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }.getOrDefault(emptyList())
        } else {
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return parsed.distinct().take(200)
    }

    private fun normalizeMillis(value: Long): Long = when {
        value <= 0L -> System.currentTimeMillis()
        value < 10_000_000_000L -> value * 1000L
        else -> value
    }

    private fun errorJson(error: String, message: String?): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("message", message.orEmpty())

    private fun Bundle?.longOr(key: String, fallback: Long): Long {
        val value = this?.get(key) ?: return fallback
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun Bundle?.intOr(key: String, fallback: Int): Int {
        val value = this?.get(key) ?: return fallback
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    // ---------------- 明确拒绝通用 CRUD ----------------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("CaptureBridgeProvider 只支持 call()")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("CaptureBridgeProvider 只支持 call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("CaptureBridgeProvider 只支持 call()")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("CaptureBridgeProvider 只支持 call()")
}
