package com.taostudio.tapaccounting.capturebridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.ImagePickerActivity
import com.taostudio.tapaccounting.OverlayService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.capture.contract.AccountingContext
import com.taostudio.tapaccounting.capture.contract.AssetInfo
import com.taostudio.tapaccounting.capture.contract.BookInfo
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CategoryInfo
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.AssetNameResolver
import com.taostudio.tapaccounting.logic.CategoryNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
            CaptureContract.METHOD_OPEN_IMAGE_ACCOUNTING -> openImageAccounting(ctx, extras)
            CaptureContract.METHOD_IMPORT_BILLS -> importBills(ctx, arg, extras)
            CaptureContract.METHOD_IMPORT_BILL_GROUP -> importBillGroup(ctx, arg, extras)
            CaptureContract.METHOD_PREVIEW_SPLIT -> previewSplit(ctx, arg, extras)
            CaptureContract.METHOD_PREVIEW_IMAGE_SPLIT -> previewImageSplit(ctx, arg, extras)
            CaptureContract.METHOD_GET_IMPORT_STATUS -> getImportStatus(ctx, arg, extras)
            CaptureContract.METHOD_SUGGEST_RULE -> suggestRuleKeyword(ctx, arg, extras)
            CaptureContract.METHOD_GENERATE_RULE -> generateRule(ctx, arg, extras)
            CaptureContract.METHOD_CLASSIFY_CAPTURES,
            CaptureContract.METHOD_CLASSIFY_NOTES,
            CaptureContract.METHOD_START_CLASSIFY -> classifyNotes(ctx, arg, extras)
            CaptureContract.METHOD_GET_CLASSIFY_RESULT -> errorJson(
                "not_implemented",
                "$method 请直接使用 classifyNotes"
            )
            else -> errorJson("unknown_method", "未知方法：$method")
        }
    }

    // ---------------- 方法 ----------------

    private fun ping(ctx: Context): JSONObject {
        val appVersion = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val hasAiKey = runCatching { Prefs.getAiKey(ctx).isNotBlank() }.getOrDefault(false)
        return JSONObject()
            .put("ok", true)
            .put("contractVersion", CaptureContract.CONTRACT_VERSION)
            .put("appVersion", appVersion)
            .put("importEnabled", true)
            .put("aiEnabled", hasAiKey)
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
        val currentBook = BookAccountManager.resolveWritableBook(
            ctx,
            BookAccountManager.getSelectedBook(ctx, bookNames)
        )
        val defaultBook = BookAccountManager.getDefaultBook(ctx, bookNames)
        val context = AccountingContext(
            contractVersion = CaptureContract.CONTRACT_VERSION,
            defaultBookName = currentBook.ifBlank { defaultBook },
            currentBookName = currentBook.ifBlank { defaultBook },
            assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx),
            books = books,
            assets = assets,
            categories = categories
        )
        return JSONObject()
            .put("ok", true)
            .put("contractVersion", context.contractVersion)
            .put("defaultBookName", context.defaultBookName ?: JSONObject.NULL)
            .put("currentBookName", context.currentBookName ?: JSONObject.NULL)
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

    /**
     * 待记图片记账：带 capture 上下文打开图片选择器。
     *
     * 选图、AI 识别和确认面板在 [com.taostudio.tapaccounting.CaptureImageAccountingActivity]
     * 里完成，复用 App 现有图片记账链路；这里只做幂等预检和启动。
     */
    private suspend fun openImageAccounting(ctx: Context, extras: Bundle?): JSONObject {
        val captureId = extras?.getString(CaptureContract.EXTRA_CAPTURE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return errorJson("missing_captureId", "缺少 captureId")
        val payloadRaw = extras.getString(CaptureContract.EXTRA_CAPTURE_PAYLOAD)
            ?: return errorJson("missing_capture_payload", "缺少待记上下文")
        val payload = try {
            JSONObject(payloadRaw)
        } catch (t: Throwable) {
            return errorJson("invalid_capture_payload", "待记上下文不是合法 JSON：${t.message}")
        }
        if (payload.optLong("amountCents", 0L) <= 0L) {
            return errorJson("invalid_capture_amount", "待记金额缺失，请先补金额再图片记账")
        }

        // 第二道幂等：已导入过就直接回执，不再打开图片选择器。
        val db = AppDatabase.getDatabase(ctx)
        val existing = db.externalImportDao().getByCaptureId(captureId)
        if (existing != null) {
            if (db.billDao().getBillById(existing.billId) != null) {
                return JSONObject()
                    .put("ok", true)
                    .put("accepted", false)
                    .put("duplicate", true)
                    .put("captureId", captureId)
                    .put("billId", existing.billId)
            }
            // 账单已被删除：旧回执失效，允许重新入账。
            db.externalImportDao().deleteByCaptureId(captureId)
        }

        val normalizedPayload = normalizeCaptureAsset(ctx, payload).apply {
            // 资产功能关闭时不带 account，避免绑定一个用户根本看不到的资产。
            if (!Prefs.isAssetFeatureEnabled(ctx)) {
                remove("assetId")
                remove("assetName")
            }
        }
        val requestId = extras.getString(CaptureContract.EXTRA_REQUEST_ID)
        val intent = Intent(ctx, ImagePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            putExtra(CaptureContract.EXTRA_CAPTURE_PAYLOAD, normalizedPayload.toString())
            if (!requestId.isNullOrBlank()) {
                putExtra(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        return try {
            ctx.startActivity(intent)
            JSONObject()
                .put("ok", true)
                .put("accepted", true)
                .put("captureId", captureId)
                .put("requestId", requestId ?: JSONObject.NULL)
        } catch (e: Exception) {
            errorJson(
                "activity_start_blocked",
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
        return CaptureImportService.importBills(ctx, array, requestId)
    }

    /** 文字拆分：只拆条目 + 金额，不写账单、不决定是否拆分。 */
    private suspend fun previewSplit(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_SPLIT_PAYLOAD) ?: arg
            ?: return errorJson("missing_split_payload", "缺少拆分 JSON")
        val payload = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return errorJson("invalid_split_payload", "拆分 JSON 不合法：${t.message}")
        }
        // AI 内部 20 秒超时；这里再兜一层，避免 binder 被异常网络拖死。
        return withTimeoutOrNull(30_000L) {
            CaptureImportService.previewSplit(ctx, payload)
        } ?: errorJson("ai_timeout", "AI 拆分超时，请重试或改备注")
    }

    /** 图片拆分预览：后台多模态解析小票并分类。 */
    private suspend fun previewImageSplit(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_SPLIT_PAYLOAD) ?: arg
            ?: return errorJson("missing_split_payload", "缺少拆分 JSON")
        val payload = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return errorJson("invalid_split_payload", "拆分 JSON 不合法：${t.message}")
        }
        val pfd = extras?.getParcelable<ParcelFileDescriptor>(CaptureContract.EXTRA_IMAGE_PFD)
            ?: return errorJson("missing_image", "缺少图片数据，请重新选择")
        val mime = extras?.getString(CaptureContract.EXTRA_IMAGE_MIME) ?: "image/jpeg"
        return withTimeoutOrNull(45_000L) {
            CaptureImportService.previewImageSplit(ctx, pfd, mime, payload)
        } ?: errorJson("ai_timeout", "AI 识图拆分超时，请重试或改用清晰图片")
    }

    /** 拆分入账：N 条账单同一 paymentGroupId，一条 captureId 回执。 */
    private suspend fun importBillGroup(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_BILL_GROUP_PAYLOAD) ?: arg
            ?: return errorJson("missing_bill_group", "缺少拆分入账 JSON")
        val payload = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return errorJson("invalid_bill_group", "拆分入账 JSON 不合法：${t.message}")
        }
        val requestId = extras?.getString(CaptureContract.EXTRA_REQUEST_ID)
        return withTimeoutOrNull(20_000L) {
            CaptureImportService.importBillGroup(ctx, payload, requestId)
        } ?: errorJson("import_timeout", "拆分入账超时，请重试")
    }

    /**
     * 图片记账的资产名也可能是渠道原始户名；能匹配到本地资产时折成本地名字。
     * 匹配不到就保留原值，让 importBillGroup 明确报 asset_not_found，不静默丢资产。
     */
    private suspend fun normalizeCaptureAsset(ctx: Context, source: JSONObject): JSONObject {
        val out = JSONObject(source.toString())
        if (out.optLong("assetId", 0L) > 0L) return out
        val raw = out.optString("assetName", "").trim()
        if (raw.isEmpty()) return out
        val assets = AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
        AssetNameResolver.resolve(assets, raw)?.let { out.put("assetName", it.name) }
        return out
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

    /**
     * 轻量智能归类：只读取备注与 billType，把分类候选和备注交给 App 的 AI。
     * 兼容旧 `classifyCaptures`：多余字段会被忽略，响应仍是 `{ok, drafts}`。
     */
    private suspend fun classifyNotes(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_NOTES)
            ?: extras?.getString("notes")
            ?: extras?.getString("captures")
            ?: arg
            ?: return errorJson("missing_notes", "缺少 notes JSON")
        val array = try {
            if (raw.trimStart().startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONArray().put(JSONObject(raw))
            }
        } catch (t: Throwable) {
            return errorJson("invalid_notes", "notes 不是合法 JSON：${t.message}")
        }
        // AI 内部已有 15/25 秒超时；这里再兜一层，避免 binder 调用被异常网络拖死。
        return withTimeoutOrNull(30_000L) {
            CaptureImportService.classifyNotes(ctx, array)
        } ?: errorJson("ai_timeout", "智能归类超时，请重试")
    }

    /**
     * AI 规则学习：只接收备注、已确认分类与账单类型，返回 `{ok, keyword}`。
     *
     * 失败/超时返回空关键词而不是异常；学习是后台副作用，绝不能影响入账。
     */
    private suspend fun suggestRuleKeyword(
        ctx: Context,
        arg: String?,
        extras: Bundle?,
    ): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_RULE_PAYLOAD) ?: arg
            ?: return errorJson("missing_rule_payload", "缺少规则学习 JSON")
        val payload = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return errorJson("invalid_rule_payload", "规则学习 JSON 不合法：${t.message}")
        }
        val note = payload.optString("note", "").trim()
        val categoryName = payload.optString("categoryName", "").trim()
        val billType = payload.optInt("billType", -1)
        if (note.isEmpty() || categoryName.isEmpty()) {
            return JSONObject().put("ok", true).put("keyword", "")
        }
        // AI 内部 10 秒超时；这里再兜一层，避免 binder 被异常网络拖死。
        val keyword = withTimeoutOrNull(15_000L) {
            AIService.suggestRuleKeyword(
                ctx = ctx,
                note = note,
                categoryName = categoryName,
                billType = billType,
            )
        }.orEmpty()
        return JSONObject().put("ok", true).put("keyword", keyword)
    }

    /** AI 自动生成记账规则：根据样本报文分析正则与字段映射。 */
    private suspend fun generateRule(ctx: Context, arg: String?, extras: Bundle?): JSONObject {
        val raw = extras?.getString(CaptureContract.EXTRA_GENERATE_RULE_PAYLOAD) ?: arg
            ?: return errorJson("missing_payload", "缺少生成规则请求参数")
        val payload = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return errorJson("invalid_payload", "生成规则参数 JSON 不合法：${t.message}")
        }
        val app = payload.optString("app", "").trim()
        val type = payload.optString("type", "DATA").trim()
        val rawJson = payload.optString("rawJson", "").trim()
        if (rawJson.isEmpty()) {
            return errorJson("empty_raw_json", "缺少原始报文样本")
        }
        val result = AIService.generateAutoRule(ctx, app, type, rawJson)
            ?: return errorJson("ai_failed", "AI 生成规则失败或超时，请检查网络与 AI 配置")
        return JSONObject().put("ok", true).put("rule", result)
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
