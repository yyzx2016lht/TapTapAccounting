package com.taostudio.tapaccounting.capturebridge

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.room.withTransaction
import com.taostudio.tapaccounting.AiImageCompressor
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
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
import com.taostudio.tapaccounting.logic.PaymentGroups
import com.taostudio.tapaccounting.logic.SplitAmounts
import com.taostudio.tapaccounting.logic.SplitPreviewException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * 待记 → 账单的唯一写入/分类实现。
 *
 * [CaptureBridgeProvider]（插件调用）与 [OverlayPendingCaptureController]（悬浮窗批量入账）
 * 共用这一份逻辑，保证类型/退款/资产校验/幂等回执完全一致，不再各写一套。
 */
internal object CaptureImportService {

    /**
     * 批量导入；每条结果为 `{captureId, billId, status, error?}`。
     * 单条失败不影响其他条目。
     *
     * 若单条因为 `missing_category` 失败，且调用方带来了用户备注（[ImportBill.note]），
     * 只对这些条目做一次轻量智能归类，然后重试一次；归类仍失败就保留原错误。
     */
    suspend fun importBills(ctx: Context, array: JSONArray, requestId: String?): JSONObject {
        val db = AppDatabase.getDatabase(ctx)
        val bookRows = db.bookDao().getAll()
        val books = BookAccountManager.getBookAccounts(ctx, bookRows.map { it.name })
        val defaultBook = BookAccountManager.getDefaultBook(ctx, books)
        val results = JSONArray()
        var allOk = true
        val missingCategoryRetries = mutableListOf<ImportBill>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val captureId = item?.optString("captureId", "").orEmpty().trim()
            val import = try {
                requireNotNull(item) { "bills[$index] 不是对象" }
                parseImportBill(item)
            } catch (t: Throwable) {
                allOk = false
                results.put(errorResult(captureId, t.message ?: t.javaClass.simpleName))
                continue
            }

            try {
                val created = importOne(ctx, db, import, requestId, defaultBook)
                results.put(successResult(created))
                if (created.third == ImportItemStatus.ERROR) allOk = false
            } catch (t: DuplicateCaptureException) {
                results.put(duplicateResult(t))
            } catch (t: Throwable) {
                if (t.message == "missing_category" && !import.note.isNullOrBlank()) {
                    missingCategoryRetries.add(import)
                } else {
                    allOk = false
                    results.put(errorResult(import.captureId, t.message ?: t.javaClass.simpleName))
                }
            }
        }

        if (missingCategoryRetries.isNotEmpty()) {
            allOk = try {
                retryMissingCategoriesWithAi(
                    ctx = ctx,
                    db = db,
                    imports = missingCategoryRetries,
                    requestId = requestId,
                    defaultBook = defaultBook,
                    results = results,
                    allOk = allOk,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                missingCategoryRetries.forEach {
                    results.put(errorResult(it.captureId, "missing_category"))
                }
                false
            }
        }

        return JSONObject()
            .put("ok", allOk)
            .put("requestId", requestId ?: JSONObject.NULL)
            .put("results", results)
    }

    /** `missing_category` 的唯一兜底：整批备注做一次轻量归类（相同备注合并），再逐条重试一次。 */
    private suspend fun retryMissingCategoriesWithAi(
        ctx: Context,
        db: AppDatabase,
        imports: List<ImportBill>,
        requestId: String?,
        defaultBook: String,
        results: JSONArray,
        allOk: Boolean,
    ): Boolean {
        val requests = JSONArray()
        imports.forEach { import ->
            requests.put(
                JSONObject()
                    .put("captureId", import.captureId)
                    .put("note", import.note?.trim().orEmpty())
                    .put("billType", import.type)
            )
        }
        val classified = try {
            classifyNotes(ctx, requests)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        if (classified == null || !classified.optBoolean("ok", false)) {
            imports.forEach { results.put(errorResult(it.captureId, "missing_category")) }
            return false
        }

        val byCaptureId = mutableMapOf<String, String>()
        val drafts = classified.optJSONArray("drafts") ?: JSONArray()
        for (i in 0 until drafts.length()) {
            val draft = drafts.optJSONObject(i) ?: continue
            val captureId = draft.optString("captureId", "").trim()
            val categoryName = draft.optString("categoryName", "").trim()
            if (captureId.isNotEmpty() && categoryName.isNotEmpty()) {
                byCaptureId[captureId] = categoryName
            }
        }

        var ok = allOk
        imports.forEach { import ->
            val categoryName = byCaptureId[import.captureId]
            if (categoryName.isNullOrBlank()) {
                ok = false
                results.put(errorResult(import.captureId, "missing_category"))
                return@forEach
            }
            try {
                val created = importOne(
                    ctx = ctx,
                    db = db,
                    import = import.copy(categoryId = null, categoryName = categoryName),
                    requestId = requestId,
                    defaultBook = defaultBook,
                )
                results.put(successResult(created))
                if (created.third == ImportItemStatus.ERROR) ok = false
            } catch (t: DuplicateCaptureException) {
                results.put(duplicateResult(t))
            } catch (t: Throwable) {
                ok = false
                results.put(errorResult(import.captureId, t.message ?: t.javaClass.simpleName))
            }
        }
        return ok
    }

    private fun successResult(created: Triple<String, Long, String>): JSONObject = JSONObject()
        .put("captureId", created.first)
        .put("billId", created.second)
        .put("status", created.third)

    private fun duplicateResult(t: DuplicateCaptureException): JSONObject = JSONObject()
        .put("captureId", t.captureId)
        .put("billId", t.existingBillId)
        .put("status", ImportItemStatus.DUPLICATE)

    private fun errorResult(captureId: String, message: String): JSONObject = JSONObject()
        .put("captureId", captureId)
        .put("billId", JSONObject.NULL)
        .put("status", ImportItemStatus.ERROR)
        .put("error", message)

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
            tryLinkedRefund(db, import, requestId, defaultBook)?.let { return it }
            // 若原支出账单尚未入账或分类不同无法自动关联，退化为普通收入入账，绝不卡死用户。
            return importAsNormalBill(
                ctx = ctx,
                db = db,
                import = import.copy(
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_NORMAL,
                    needsRefundLink = false,
                ),
                type = Bill.TYPE_INCOME,
                subType = Bill.SUBTYPE_NORMAL,
                requestId = requestId,
                defaultBook = defaultBook,
            )
        }

        return importAsNormalBill(ctx, db, import, type, subType, requestId, defaultBook)
    }

    private suspend fun importAsNormalBill(
        ctx: Context,
        db: AppDatabase,
        import: ImportBill,
        type: Int,
        subType: Int,
        requestId: String?,
        defaultBook: String,
    ): Triple<String, Long, String> {
        val asset = resolveAsset(db, import.assetId, import.assetName)
        val toAsset = resolveAsset(db, import.toAssetId, import.toAssetName)
        if (type == Bill.TYPE_TRANSFER) {
            require(Prefs.isAssetFeatureEnabled(ctx)) { "asset_feature_disabled" }
            requireNotNull(asset) { "missing_asset" }
            requireNotNull(toAsset) { "missing_to_asset" }
            require(asset.id != toAsset.id) { "same_asset" }
        }

        val allCategories = db.categoryDao().getAllCategoriesList()
        val resolvedCategory = if (type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME)) {
            import.categoryId?.let { id -> allCategories.firstOrNull { it.id == id } }
                ?: CategoryNameResolver.resolve(allCategories, import.categoryName, type)
                ?: if (type == Bill.TYPE_INCOME) {
                    CategoryNameResolver.resolve(allCategories, "退款", Bill.TYPE_INCOME)
                        ?: CategoryNameResolver.resolve(allCategories, "其他", Bill.TYPE_INCOME)
                        ?: allCategories.firstOrNull { it.type == Bill.TYPE_INCOME }
                } else {
                    null
                }
        } else {
            null
        }
        if (type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME)) {
            // 分类对不上时宁可报错让用户改，也不写一笔“空分类”的账进统计。
            requireNotNull(resolvedCategory) {
                if (import.categoryName.isNullOrBlank()) "missing_category"
                else "category_not_found:${import.categoryName}"
            }
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
            categoryId = resolvedCategory?.id,
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
            excludeFromStats = import.excludeFromStats,
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

    private suspend fun tryLinkedRefund(
        db: AppDatabase,
        import: ImportBill,
        requestId: String?,
        defaultBook: String,
    ): Triple<String, Long, String>? {
        val resolvedAsset = runCatching { resolveAsset(db, import.assetId, import.assetName) }.getOrNull()
        val original = import.relatedBillId?.let { relatedId ->
            db.billDao().getBillById(relatedId)
                ?: throw IllegalArgumentException("refund_source_not_found")
        } ?: run {
            val probe = Bill(
                type = Bill.TYPE_EXPENSE,
                subType = Bill.SUBTYPE_REFUND,
                amount = import.amountCents / 100.0,
                originalAmount = import.amountCents / 100.0,
                categoryName = import.categoryName.orEmpty(),
                accountName = resolvedAsset?.name ?: import.assetName.orEmpty(),
                bookName = import.bookName?.takeIf { it.isNotBlank() } ?: defaultBook,
                time = normalizeMillis(import.paidAt),
            )
            BillMutationService.resolveRefundSourceBill(db, probe)
                ?: return null
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
            excludeFromStats = import.excludeFromStats,
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

    fun parseImportBill(obj: JSONObject): ImportBill {
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
            note = obj.optString("note", "").takeIf { it.isNotBlank() },
            bookName = obj.optString("bookName", "").takeIf { it.isNotBlank() },
            feeCents = obj.optLong("feeCents", 0L),
            relatedBillId = obj.longOrNull("relatedBillId"),
            excludeFromStats = obj.optBoolean("excludeFromStats", false),
        )
    }

    // ---------------- 文字拆分记账 ----------------

    /**
     * 只让 AI 按备注拆条目和金额；每个条目的分类由插件先跑本地规则、再用
     * [classifyNotes] 兜底。这里不决定“要不要拆”，也不写任何账单。
     *
     * 返回 `{ok, items:[{name, amountCents, inferred}], sumCents, diffCents,
     * totalCents, fallbackCategoryName}`；失败返回明确错误码。
     */
    suspend fun previewSplit(ctx: Context, payload: JSONObject): JSONObject {
        val note = payload.optString("note", "").trim()
        val totalCents = payload.optLong("totalCents", -1L)
        val billType = payload.optInt("billType", Bill.TYPE_EXPENSE)
        if (note.isEmpty()) {
            return errorJson("split_empty", "备注为空，请先写「苹果30 雪梨40」")
        }
        if (totalCents <= 0L) {
            return errorJson("invalid_total", "付款金额无效，无法拆分")
        }
        if (billType != Bill.TYPE_EXPENSE && billType != Bill.TYPE_INCOME) {
            return errorJson("split_unsupported_type", "转账/还款/退款不支持文字拆分")
        }

        val rawItems = try {
            AIService.splitNoteItems(
                ctx = ctx,
                note = note,
                totalCents = totalCents,
                billType = billType,
            )
        } catch (t: SplitPreviewException) {
            return errorJson(t.code, t.message.orEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return errorJson("split_ai_failed", t.message ?: "AI 拆分失败，请改备注")
        }

        return when (val outcome = SplitAmounts.validate(totalCents, rawItems)) {
            is SplitAmounts.Outcome.Ok -> {
                val items = JSONArray()
                outcome.items.forEach { item ->
                    items.put(
                        JSONObject()
                            .put("name", item.name)
                            .put("amountCents", item.amountCents)
                            .put("inferred", item.inferred)
                    )
                }
                val db = AppDatabase.getDatabase(ctx)
                val fallbackCategory = resolveFallbackCategory(db, billType)
                JSONObject()
                    .put("ok", true)
                    .put("items", items)
                    .put("sumCents", outcome.sumCents)
                    .put("diffCents", outcome.diffCents)
                    .put("totalCents", totalCents)
                    .put("fallbackCategoryName", fallbackCategory ?: JSONObject.NULL)
            }

            is SplitAmounts.Outcome.Error -> errorJson(outcome.code, outcome.message)
        }
    }

    /**
     * 图片拆分记账预览：从 PFD 读取图片，在后台调用多模态模型识图并分类。
     */
    suspend fun previewImageSplit(
        ctx: Context,
        pfd: ParcelFileDescriptor?,
        mime: String,
        payload: JSONObject,
    ): JSONObject {
        if (pfd == null) {
            return errorJson("missing_image", "缺少图片数据，请重新选择图片")
        }
        val totalCents = payload.optLong("totalCents", -1L)
        val billType = payload.optInt("billType", Bill.TYPE_EXPENSE)
        if (totalCents <= 0L) {
            return errorJson("invalid_total", "付款金额无效，无法拆分")
        }
        if (billType != Bill.TYPE_EXPENSE && billType != Bill.TYPE_INCOME) {
            return errorJson("split_unsupported_type", "转账/还款/退款不支持图片拆分")
        }

        val db = AppDatabase.getDatabase(ctx)
        val allCategories = db.categoryDao().getAllCategoriesList()

        val tempFile = File.createTempFile("split_img_pfd_", ".tmp", ctx.cacheDir)
        val imageBase64: String
        val actualMime: String
        try {
            FileInputStream(pfd.fileDescriptor).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val compressed = AiImageCompressor.compressInPlace(tempFile)
            val bytes = tempFile.readBytes()
            if (bytes.isEmpty()) {
                return errorJson("image_read_failed", "图片读取失败，请重新选择")
            }
            imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            actualMime = if (compressed) AiImageCompressor.JPEG_MIME else mime
        } catch (t: Throwable) {
            return errorJson("image_read_failed", "图片读取处理异常：${t.message}")
        } finally {
            tempFile.delete()
            runCatching { pfd.close() }
        }

        val rawItems = try {
            AIService.splitImageItems(
                ctx = ctx,
                imageBase64 = imageBase64,
                mimeType = actualMime,
                totalCents = totalCents,
                billType = billType,
                categories = allCategories,
            )
        } catch (t: SplitPreviewException) {
            return errorJson(t.code, t.message.orEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return errorJson("split_ai_failed", t.message ?: "AI 识图拆分失败，请改用清晰图片")
        }

        val splitAmountsItems = rawItems.map { SplitAmounts.RawItem(it.name, it.amountCents) }
        return when (val outcome = SplitAmounts.validate(totalCents, splitAmountsItems)) {
            is SplitAmounts.Outcome.Ok -> {
                val items = JSONArray()
                outcome.items.forEachIndexed { index, item ->
                    val resolvedCat = rawItems.getOrNull(index)?.category?.let { catName ->
                        CategoryNameResolver.resolve(allCategories, catName, billType)?.name ?: catName
                    }
                    items.put(
                        JSONObject()
                            .put("name", item.name)
                            .put("amountCents", item.amountCents)
                            .put("inferred", item.inferred)
                            .putOpt("categoryName", resolvedCat)
                    )
                }
                val fallbackCategory = resolveFallbackCategory(db, billType)
                JSONObject()
                    .put("ok", true)
                    .put("items", items)
                    .put("sumCents", outcome.sumCents)
                    .put("diffCents", outcome.diffCents)
                    .put("totalCents", totalCents)
                    .put("fallbackCategoryName", fallbackCategory ?: JSONObject.NULL)
            }

            is SplitAmounts.Outcome.Error -> errorJson(outcome.code, outcome.message)
        }
    }

    private suspend fun resolveFallbackCategory(db: AppDatabase, billType: Int): String? {
        val categories = db.categoryDao().getAllCategoriesList()
        return CategoryNameResolver.resolve(categories, "其他", billType)?.name
            ?: CategoryNameResolver.resolve(categories, "其它", billType)?.name
    }

    /** 图片记账补差额用：与文字拆分相同的“其他”兜底分类。 */
    suspend fun resolveFallbackCategoryName(ctx: Context, billType: Int): String? =
        resolveFallbackCategory(AppDatabase.getDatabase(ctx), billType)

    private data class GroupItem(
        val name: String,
        val amountCents: Long,
        val categoryName: String?,
    )

    private data class GroupImportOutcome(
        val duplicate: Boolean,
        val paymentGroupId: String?,
        val billIds: List<Long>,
    )

    /**
     * 拆分入账：N 条账单同一 paymentGroupId，一条 captureId 回执。
     *
     * 整个写入在 [androidx.room.withTransaction] 内完成；重复 captureId 直接回
     * duplicate，不重复建单。广播在事务提交后补一次，供插件加速对账。
     */
    suspend fun importBillGroup(
        ctx: Context,
        payload: JSONObject,
        requestId: String?,
    ): JSONObject {
        val captureId = payload.optString("captureId", "").trim()
        if (captureId.isEmpty()) return errorJson("missing_captureId", "缺少 captureId")

        val type = payload.optInt("type", Bill.TYPE_EXPENSE)
        val subType = payload.optInt("subType", Bill.SUBTYPE_NORMAL)
        val needsRefundLink = payload.optBoolean("needsRefundLink", false)
        if (type != Bill.TYPE_EXPENSE && type != Bill.TYPE_INCOME) {
            return errorJson("split_unsupported_type", "转账/还款/退款不支持文字拆分")
        }
        if (subType != Bill.SUBTYPE_NORMAL || needsRefundLink) {
            return errorJson("split_unsupported_type", "退款/还款账单不支持文字拆分")
        }

        val itemArray = payload.optJSONArray("items")
            ?: return errorJson("split_empty", "缺少拆分条目")
        val items = buildList {
            for (i in 0 until itemArray.length()) {
                val obj = itemArray.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val amountCents = obj.optLong("amountCents", -1L)
                val categoryName = obj.optString("categoryName", "")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                if (name.isEmpty()) return errorJson("split_item_name_missing", "有条目缺少名称")
                if (amountCents <= 0L) return errorJson("split_amount_invalid", "有条目金额无效")
                if (categoryName.isNullOrEmpty()) {
                    return errorJson("missing_category", "「$name」缺少分类")
                }
                add(GroupItem(name = name, amountCents = amountCents, categoryName = categoryName))
            }
        }
        if (items.isEmpty()) return errorJson("split_empty", "缺少拆分条目")

        val sumCents = items.sumOf { it.amountCents }
        val totalCents = payload.optLong("totalCents", sumCents)
        if (totalCents != sumCents) {
            return errorJson(
                "split_total_mismatch",
                "拆分合计与付款不一致，请重新确认",
            )
        }

        val db = AppDatabase.getDatabase(ctx)
        val bookRows = db.bookDao().getAll()
        val books = BookAccountManager.getBookAccounts(ctx, bookRows.map { it.name })
        val defaultBook = BookAccountManager.getDefaultBook(ctx, books)
        val paidAt = normalizeMillis(payload.optLong("paidAt", System.currentTimeMillis()))
        val currency = payload.optString("currency", "CNY").ifBlank { "CNY" }
        val assetId = payload.longOrNull("assetId")
        val assetName = payload.optString("assetName", "")
            .takeUnless { it.isBlank() || it == "null" }
        val bookName = payload.optString("bookName", "")
            .takeUnless { it.isBlank() || it == "null" }
            ?: defaultBook
        val excludeFromStats = payload.optBoolean("excludeFromStats", false)

        val outcome = try {
            db.withTransaction {
                val existing = db.externalImportDao().getByCaptureId(captureId)
                if (existing != null) {
                    val existingBill = db.billDao().getBillById(existing.billId)
                    if (existingBill != null) {
                        return@withTransaction GroupImportOutcome(
                            duplicate = true,
                            paymentGroupId = existing.paymentGroupId,
                            billIds = listOf(existing.billId),
                        )
                    }
                    // 首条账单已被删除：回执失效，允许这次重新入账。
                    db.externalImportDao().deleteByCaptureId(captureId)
                }

                val asset = resolveAsset(db, assetId, assetName)
                val categories = db.categoryDao().getAllCategoriesList()
                val groupId = PaymentGroups.newGroupId()
                val billIds = ArrayList<Long>(items.size)
                items.forEach { item ->
                    val category = CategoryNameResolver.resolve(categories, item.categoryName, type)
                        ?: throw IllegalArgumentException("category_not_found:${item.categoryName}")
                    val amount = item.amountCents / 100.0
                    val bill = Bill(
                        type = type,
                        subType = Bill.SUBTYPE_NORMAL,
                        amount = amount,
                        originalAmount = amount,
                        currency = currency,
                        categoryId = category.id,
                        categoryName = category.name,
                        accountId = asset?.id,
                        accountName = asset?.name.orEmpty(),
                        time = paidAt,
                        remark = item.name,
                        bookName = bookName,
                        paymentGroupId = groupId,
                        excludeFromStats = excludeFromStats,
                    )
                    val saved = BillMutationService.insertBillWithinActiveTransaction(
                        db = db,
                        bill = bill,
                        applyAssetImpact = true,
                        captureId = null,
                        requestId = requestId,
                    )
                    billIds.add(saved.id)
                }

                val inserted = db.externalImportDao().insertIgnore(
                    ExternalImport(
                        captureId = captureId,
                        billId = billIds.first(),
                        requestId = requestId,
                        paymentGroupId = groupId,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                if (inserted == -1L) {
                    val existing = db.externalImportDao().getByCaptureId(captureId)
                    throw DuplicateCaptureException(captureId, existing?.billId ?: billIds.first())
                }
                GroupImportOutcome(
                    duplicate = false,
                    paymentGroupId = groupId,
                    billIds = billIds,
                )
            }
        } catch (t: DuplicateCaptureException) {
            val existing = db.externalImportDao().getByCaptureId(t.captureId)
            return JSONObject()
                .put("ok", true)
                .put("duplicate", true)
                .put("captureId", t.captureId)
                .put("paymentGroupId", existing?.paymentGroupId ?: JSONObject.NULL)
                .put("billIds", JSONArray().put(existing?.billId ?: t.existingBillId))
                .put("count", 1)
        } catch (t: IllegalArgumentException) {
            val message = t.message.orEmpty().ifBlank { "invalid_bill_group" }
            val code = when {
                message.startsWith("category_not_found:") -> "category_not_found"
                message.startsWith("asset_not_found:") -> "asset_not_found"
                message == "missing_asset" -> "missing_asset"
                message == "missing_category" -> "missing_category"
                else -> message
            }
            return errorJson(code, message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return errorJson("import_failed", t.message ?: t.javaClass.simpleName)
        }

        if (!outcome.duplicate) {
            db.billDao().getBillById(outcome.billIds.firstOrNull() ?: -1L)?.let { bill ->
                BillMutationService.notifyBillCreated(bill, captureId)
            }
        }
        val billIds = JSONArray()
        outcome.billIds.forEach { billIds.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("duplicate", outcome.duplicate)
            .put("captureId", captureId)
            .put("paymentGroupId", outcome.paymentGroupId ?: JSONObject.NULL)
            .put("billIds", billIds)
            .put("count", outcome.billIds.size)
    }

    // ---------------- 轻量智能归类 ----------------

    private data class NoteClassifyItem(
        val captureId: String,
        val note: String,
        val billType: Int,
    )

    /**
     * 批量备注 → 批量分类。只把 `captureId / note / billType` 交给 AI 分类器，
     * 不传金额、时间、资产、商户；返回 `{ok, drafts:[{captureId, categoryName?}]}`。
     *
     * 逐条独立失败：某条没备注、AI 判断不了或返回分类不在候选内，该条 categoryName 为空，
     * 不影响其他条。请求按账单类型合并（支出/收入各至多一次），不会变成 N 次整笔记账分析。
     */
    suspend fun classifyNotes(ctx: Context, array: JSONArray): JSONObject {
        val drafts = JSONArray()
        if (array.length() == 0) {
            return JSONObject().put("ok", true).put("drafts", drafts)
        }
        if (Prefs.getAiKey(ctx).isBlank()) {
            return errorJson("ai_not_configured", "请先在敲敲记账中配置 AI API Key")
        }

        val db = AppDatabase.getDatabase(ctx)
        val allCategories = db.categoryDao().getAllCategoriesList()

        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val captureId = obj.optString("captureId", "").trim()
                if (captureId.isEmpty()) continue
                add(
                    NoteClassifyItem(
                        captureId = captureId,
                        note = obj.optString("note", "").trim(),
                        billType = obj.optInt("billType", Bill.TYPE_EXPENSE),
                    )
                )
            }
        }

        // 按账单类型分组：支出/收入各一次轻量请求；同一备注在同组内只出现一次。
        val categoryByTypeNote = mutableMapOf<Pair<Int, String>, String?>()
        items
            .filter {
                it.note.isNotEmpty() &&
                    (it.billType == Bill.TYPE_EXPENSE || it.billType == Bill.TYPE_INCOME)
            }
            .groupBy { it.billType }
            .forEach { (billType, group) ->
                val notes = group.map { it.note }.distinct()
                val resolved = try {
                    AIService.classifyCategories(
                        ctx = ctx,
                        notes = notes,
                        billType = billType,
                        categories = allCategories,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    emptyList()
                }
                notes.forEachIndexed { index, note ->
                    categoryByTypeNote[billType to note] = resolved.getOrNull(index)
                        ?.let { CategoryNameResolver.displayPath(allCategories, it) }
                }
            }

        items.forEach { item ->
            drafts.put(
                JSONObject().apply {
                    put("captureId", item.captureId)
                    putOpt("categoryName", categoryByTypeNote[item.billType to item.note])
                }
            )
        }
        return JSONObject().put("ok", true).put("drafts", drafts)
    }

    // ---------------- 工具 ----------------

    fun normalizeMillis(value: Long): Long = when {
        value <= 0L -> System.currentTimeMillis()
        value < 10_000_000_000L -> value * 1000L
        else -> value
    }

    fun errorJson(error: String, message: String?): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("message", message.orEmpty())

    private fun JSONObject.longOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }
}
