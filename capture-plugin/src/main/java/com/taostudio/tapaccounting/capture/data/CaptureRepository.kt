package com.taostudio.tapaccounting.capture.data

import android.content.Context
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher

/** 一次插入的结果；inserted=false 表示命中幂等去重（id 或 source+sourceTxnId）。 */
data class InsertOutcome(
    val capture: PaymentCapture,
    val inserted: Boolean,
)

class CaptureRepository(private val dao: PaymentCaptureDao) {

    suspend fun insert(payload: CapturePayload): InsertOutcome {
        val now = System.currentTimeMillis()

        // 订单号优先去重；没有订单号时不做“金额+时间”自动合并（见需求 10.1）。
        payload.sourceTxnId?.takeIf { it.isNotBlank() }?.let { txn ->
            dao.findBySourceTxn(payload.source, txn)?.let { existing ->
                // 同一笔转账会从 WCDB message 与 AppMessage 各来一次：
                // 先到的可能没有 isSend、无法分类，或者商户名是脏的“待xxx确认收款”；后到的补全更新。
                val incomingMerchant = payload.merchant?.takeIf { it.isNotBlank() }
                val shouldUpgradeMerchant = incomingMerchant != null && (
                    existing.merchant.isNullOrBlank() ||
                        existing.merchant.startsWith("待") ||
                        existing.merchant.startsWith("wxid_")
                    )
                if ((existing.billType == null && payload.billType != null) ||
                    shouldUpgradeMerchant ||
                    (existing.paymentMethodRaw == null && payload.paymentMethodRaw != null)
                ) {
                    val merged = existing.copy(
                        amountCents = payload.amountCents ?: existing.amountCents,
                        merchant = if (shouldUpgradeMerchant) incomingMerchant else (existing.merchant ?: incomingMerchant),
                        categoryName = payload.categoryName ?: existing.categoryName,
                        paymentMethodRaw = payload.paymentMethodRaw ?: existing.paymentMethodRaw,
                        assetName = payload.assetName ?: existing.assetName,
                        toAssetName = payload.toAssetName ?: existing.toAssetName,
                        billType = payload.billType ?: existing.billType,
                        subType = payload.subType ?: existing.subType,
                        needsRefundLink = existing.needsRefundLink || payload.needsRefundLink,
                        rawJson = payload.rawJson ?: existing.rawJson,
                        ruleVersion = payload.ruleVersion ?: existing.ruleVersion,
                        updatedAt = now,
                    )
                    dao.update(merged)
                    return InsertOutcome(merged, inserted = false)
                }
                return InsertOutcome(existing, inserted = false)
            }
        }

        val entity = payload.toEntity(now)
        val rowId = dao.insert(entity)
        if (rowId == -1L) {
            val existing = dao.findById(entity.id) ?: entity
            return InsertOutcome(existing, inserted = false)
        }
        return InsertOutcome(entity, inserted = true)
    }

    suspend fun findById(id: String): PaymentCapture? = dao.findById(id)

    suspend fun list(limit: Int = 200): List<PaymentCapture> =
        dao.listRecent(limit).map { normalizeHistoricalRow(it) }

    suspend fun listPending(limit: Int = 100, onlyExpense: Boolean = false): List<PaymentCapture> {
        val rows = dao.listByStatuses(CaptureStatus.PENDING_COUNT_STATUSES.toList(), limit)
            .map { normalizeHistoricalRow(it) }
        return if (onlyExpense) {
            rows.filter {
                it.billType == null ||
                    it.billType == LocalBillType.TYPE_EXPENSE ||
                    it.subType == LocalBillType.SUBTYPE_REFUND ||
                    it.needsRefundLink
            }
        } else {
            rows
        }
    }

    private suspend fun normalizeHistoricalRow(row: PaymentCapture): PaymentCapture {
        val cleanedMerchant = row.merchant?.let { raw ->
            Regex("""^待(.+?)确认收款$""").matchEntire(raw.trim())?.groupValues?.getOrNull(1)?.trim()
                ?: raw
        }
        val isRefund = row.subType == LocalBillType.SUBTYPE_REFUND || row.needsRefundLink
        val cleanedCategory = if (isRefund && row.categoryName == "收红包") "退款" else row.categoryName
        if (cleanedMerchant != row.merchant || cleanedCategory != row.categoryName) {
            val fixed = row.copy(merchant = cleanedMerchant, categoryName = cleanedCategory)
            runCatching { dao.update(fixed) }
            return fixed
        }
        return row
    }

    /** “已不计”列表，支持在待记页底部恢复为待记。 */
    suspend fun listIgnored(limit: Int = 50): List<PaymentCapture> =
        dao.listByStatuses(listOf(CaptureStatus.IGNORED), limit).map { normalizeHistoricalRow(it) }

    suspend fun countPending(onlyExpense: Boolean = false): Int {
        if (!onlyExpense) {
            return dao.countByStatus(CaptureStatus.PENDING_COUNT_STATUSES.toList())
        }
        return listPending(200, onlyExpense = true).size
    }

    suspend fun updateStatus(id: String, status: String): Boolean =
        dao.updateStatus(id, status, System.currentTimeMillis()) > 0

    suspend fun updateNote(id: String, note: String?): Boolean =
        dao.updateNote(id, note?.takeIf { it.isNotBlank() }, System.currentTimeMillis()) > 0

    /**
     * 更新备注并立即用“备注分类规则”重新推断分类（如手写“咖啡”立即命中“餐饮 - 咖啡”）。
     *
     * 只跑 NOTE / ANY 规则，不跑商户规则。用户手动选择的分类（MANUAL）不被覆盖；
     * 其余来源在未命中时清空分类，等待智能归类。
     */
    suspend fun updateNoteAndReclassify(context: Context, id: String, note: String?): PaymentCapture? {
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val existing = dao.findById(id) ?: return null
        val now = System.currentTimeMillis()
        val keepManual = existing.categorySource == CategorySource.MANUAL
        val matchedCategory = if (keepManual) {
            null
        } else {
            CaptureRuleMatcher.resolveNoteCategory(context, cleanNote)
        }
        val updated = when {
            keepManual -> existing.copy(note = cleanNote, updatedAt = now)
            matchedCategory != null -> existing.copy(
                note = cleanNote,
                categoryName = matchedCategory,
                categorySource = CategorySource.RULE,
                updatedAt = now,
            )
            else -> existing.copy(
                note = cleanNote,
                categoryName = null,
                categorySource = null,
                updatedAt = now,
            )
        }
        dao.update(updated)
        return updated
    }

    /**
     * 更新待记草稿（备注/资产/分类），并支持顺手记住资产映射与分类规则。
     *
     * 分类写入优先级：
     * 1. 显式传入 [categoryName]（手动选择默认 MANUAL；AI 归类需传 [CategorySource.AI]）；
     * 2. 备注变化时只跑备注规则，命中写 RULE；
     * 3. 未命中且非 MANUAL 时清空分类，等待智能归类；
     * 4. 备注未变且未显式传分类时保持原分类与来源。
     */
    suspend fun updateDraft(
        context: Context,
        id: String,
        note: String?,
        assetName: String?,
        categoryName: String?,
        categorySource: String? = null,
        rememberAssetMapping: Boolean = false,
        rememberCategoryKeyword: String? = null,
    ): PaymentCapture? {
        val existing = dao.findById(id) ?: return null
        val now = System.currentTimeMillis()
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAsset = assetName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.assetName
        val explicitCategory = categoryName?.trim()?.takeIf { it.isNotEmpty() }
        val noteChanged = cleanNote != existing.note
        val keepManual = existing.categorySource == CategorySource.MANUAL &&
            categorySource != null && categorySource != CategorySource.MANUAL

        val resolvedCategory: String?
        val resolvedCategorySource: String?
        when {
            keepManual || (existing.categorySource == CategorySource.MANUAL && explicitCategory == null) -> {
                resolvedCategory = existing.categoryName
                resolvedCategorySource = CategorySource.MANUAL
            }
            explicitCategory != null -> {
                resolvedCategory = explicitCategory
                resolvedCategorySource = when {
                    categorySource != null -> categorySource
                    explicitCategory == existing.categoryName -> existing.categorySource
                    else -> CategorySource.MANUAL
                }
            }
            noteChanged -> {
                val matched = CaptureRuleMatcher.resolveNoteCategory(context, cleanNote)
                resolvedCategory = matched
                resolvedCategorySource = if (matched != null) CategorySource.RULE else null
            }
            else -> {
                resolvedCategory = existing.categoryName
                resolvedCategorySource = existing.categorySource
            }
        }

        val updated = existing.copy(
            note = cleanNote,
            assetName = cleanAsset,
            categoryName = resolvedCategory,
            categorySource = resolvedCategorySource,
            updatedAt = now,
        )
        dao.update(updated)

        val db = CaptureApp.from(context).database
        val rawChannel = existing.paymentMethodRaw?.trim().orEmpty()
        if (rememberAssetMapping && rawChannel.isNotEmpty() && !cleanAsset.isNullOrBlank()) {
            val existingRule = db.assetMappingDao().findByPattern(rawChannel)
            db.assetMappingDao().upsert(
                AssetMappingRule(
                    id = existingRule?.id ?: 0L,
                    pattern = rawChannel,
                    targetAssetName = cleanAsset,
                    isRegex = false,
                    sort = existingRule?.sort ?: 0,
                    enabled = true,
                )
            )
            // 顺手把其它同支付方式的待记条目也填上该资产
            listPending(200, onlyExpense = false)
                .filter { it.id != id && it.paymentMethodRaw?.trim() == rawChannel }
                .forEach { other ->
                    dao.updateAssetName(other.id, cleanAsset, now)
                }
        }

        val ruleKeyword = rememberCategoryKeyword?.trim().orEmpty()
        if (ruleKeyword.isNotEmpty() && !resolvedCategory.isNullOrBlank()) {
            val existingCatRule = db.categoryRuleDao()
                .findByKeywordAndField(ruleKeyword, CategoryRule.MATCH_ANY)
            db.categoryRuleDao().upsert(
                CategoryRule(
                    id = existingCatRule?.id ?: 0L,
                    keyword = ruleKeyword,
                    matchField = CategoryRule.MATCH_ANY,
                    isRegex = false,
                    targetCategoryName = resolvedCategory,
                    sort = existingCatRule?.sort ?: 0,
                    enabled = true,
                )
            )
        }

        return updated
    }

    suspend fun deleteById(id: String): Boolean = dao.deleteById(id) > 0

    private fun CapturePayload.toEntity(now: Long): PaymentCapture = PaymentCapture(
        id = captureId,
        source = source,
        sourceTxnId = sourceTxnId?.takeIf { it.isNotBlank() },
        amountCents = amountCents,
        currency = currency.ifBlank { "CNY" },
        merchant = merchant?.takeIf { it.isNotBlank() },
        categoryName = categoryName?.takeIf { it.isNotBlank() },
        paymentMethodRaw = paymentMethodRaw?.takeIf { it.isNotBlank() },
        assetName = assetName?.takeIf { it.isNotBlank() },
        toAssetName = toAssetName?.takeIf { it.isNotBlank() },
        billType = billType,
        subType = subType,
        needsRefundLink = needsRefundLink,
        paidAt = if (paidAt > 0L) paidAt else now,
        capturedAt = if (capturedAt > 0L) capturedAt else now,
        bookName = bookName?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        // hook 投递的一律先落 PENDING；状态只能由插件自己推进。
        status = status.ifBlank { CaptureStatus.PENDING },
        needsAmount = needsAmount || amountCents == null,
        rawJson = rawJson,
        ruleVersion = ruleVersion,
        sourceAppVersion = sourceAppVersion,
        createdAt = now,
        updatedAt = now,
    )
}
