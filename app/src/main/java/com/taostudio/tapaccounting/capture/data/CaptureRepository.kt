package com.taostudio.tapaccounting.capture.data

import android.content.Context
import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.dedup.DuplicateDetector
import com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher
import kotlin.math.abs

/** 一次插入的结果；inserted=false 表示命中幂等去重（id 或 source+sourceTxnId）。 */
data class InsertOutcome(
    val capture: PaymentCapture,
    val inserted: Boolean,
)

class CaptureRepository(private val dao: PaymentCaptureDao) {

    private companion object {
        /** 自动判重窗口上限：防止用户配置异常导致全表扫描。 */
        const val MAX_DEDUP_WINDOW_MILLIS: Long = 3_600_000L

        val HISTORY_STATUSES = listOf(
            CaptureStatus.RECORDED,
            CaptureStatus.IGNORED,
            CaptureStatus.DUPLICATE,
        )
    }

    suspend fun insert(
        payload: CapturePayload,
        transferIncomeDedupEnabled: Boolean = true,
        transferIncomeDedupWindowMillis: Long = DuplicateDetector.DEFAULT_WINDOW_MILLIS,
    ): InsertOutcome {
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

        // 自动去重与跨渠道合并检测（5分钟时间窗口，金额完全一致）
        val amount = entity.amountCents
        if (amount != null && amount > 0L) {
            val window = DuplicateDetector.DEFAULT_WINDOW_MILLIS
            val startTime = entity.paidAt - window
            val endTime = entity.paidAt + window
            val candidateStatuses = listOf(
                CaptureStatus.PENDING,
                CaptureStatus.AI_DRAFT,
                CaptureStatus.POSSIBLE_DUPLICATE,
                CaptureStatus.RECORDED,
                CaptureStatus.IGNORED,
            )
            val candidates = dao.findPotentialDuplicates(amount, startTime, endTime, candidateStatuses)
            val duplicateParent = DuplicateDetector.findDuplicate(entity, candidates)
            if (duplicateParent != null) {
                // 1. 保存当前 capture 为 DUPLICATE 子账单，指向主账单 groupId
                val child = entity.copy(
                    status = CaptureStatus.DUPLICATE,
                    groupId = duplicateParent.id,
                    updatedAt = now,
                )
                dao.insert(child)

                // 2. 将互补信息合并到主账单
                val mergedParent = DuplicateDetector.merge(duplicateParent, entity)
                dao.update(mergedParent)

                return InsertOutcome(mergedParent, inserted = false)
            }

            // 转账 → 到账收款：微信/支付宝先记一笔转账，收款银行随后推送“入账/收款”。
            // 两边来源和 billType 都不同，普通去重会放过；这里按用户配置的窗口归入「重复」。
            if (transferIncomeDedupEnabled) {
                val transferWindow = transferIncomeDedupWindowMillis.coerceIn(0L, MAX_DEDUP_WINDOW_MILLIS)
                val transferCandidates = dao.findPotentialDuplicates(
                    amount,
                    entity.paidAt - transferWindow,
                    entity.paidAt + transferWindow,
                    candidateStatuses,
                )
                val transferParent = transferCandidates.firstOrNull { candidate ->
                    candidate.groupId == null &&
                        candidate.status != CaptureStatus.DUPLICATE &&
                        DuplicateDetector.isTransferIncomeDuplicate(entity, candidate, transferWindow)
                }
                if (transferParent != null) {
                    val child = entity.copy(
                        status = CaptureStatus.DUPLICATE,
                        groupId = transferParent.id,
                        updatedAt = now,
                    )
                    dao.insert(child)
                    return InsertOutcome(child, inserted = false)
                }
            }
        }

        val rowId = dao.insert(entity)
        if (rowId == -1L) {
            val existing = dao.findById(entity.id) ?: entity
            return InsertOutcome(existing, inserted = false)
        }

        // 反向顺序：银行到账通知先到、微信/支付宝转账记录后到。
        if (transferIncomeDedupEnabled && entity.billType == LocalBillType.TYPE_TRANSFER) {
            markEarlierIncomeAsDuplicate(entity, now, transferIncomeDedupWindowMillis)
        }
        return InsertOutcome(entity, inserted = true)
    }

    /**
     * 反向顺序的转账判重：只处理还没入账的待记。
     * RECORDED 的收入已经写成账单，再标重复会造成“账单还在、待记没了”的漏账。
     */
    private suspend fun markEarlierIncomeAsDuplicate(
        transfer: PaymentCapture,
        now: Long,
        transferIncomeDedupWindowMillis: Long,
    ) {
        val amount = transfer.amountCents ?: return
        if (amount <= 0L) return
        val window = transferIncomeDedupWindowMillis.coerceIn(0L, MAX_DEDUP_WINDOW_MILLIS)
        val candidates = dao.findPotentialDuplicates(
            amount,
            transfer.paidAt - window,
            transfer.paidAt + window,
            CaptureStatus.PENDING_COUNT_STATUSES.toList(),
        )
        val income = candidates.firstOrNull { candidate ->
            candidate.groupId == null &&
                candidate.status != CaptureStatus.DUPLICATE &&
                DuplicateDetector.isTransferIncomeDuplicate(candidate, transfer, window)
        } ?: return
        dao.update(
            income.copy(
                status = CaptureStatus.DUPLICATE,
                groupId = transfer.id,
                updatedAt = now,
            )
        )
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

    /**
     * 历史记录：已记 / 已不计 / 重复。
     *
     * 待记、疑似重复、失败仍留在收件箱，不进入历史；历史只负责“已经处理完”的条目。
     */
    suspend fun listHistory(limit: Int = 200, offset: Int = 0): List<PaymentCapture> =
        dao.listByStatusesPage(HISTORY_STATUSES, limit, offset).map { normalizeHistoricalRow(it) }

    suspend fun countHistory(): Int = dao.countByStatus(HISTORY_STATUSES)

    /** 清理 N 天前的已完成/已不计/重复记录；返回删除条数。 */
    suspend fun deleteHistoryOlderThan(cutoff: Long): Int =
        dao.deleteByStatusesOlderThan(HISTORY_STATUSES, cutoff)

    suspend fun clearHistory(): Int = dao.deleteByStatuses(HISTORY_STATUSES)

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

    /** 只更新“拆分记账”勾选；不影响备注/分类，也不触发 AI。 */
    suspend fun updateSplitRequested(id: String, requested: Boolean): Boolean =
        dao.updateSplitRequested(id, requested, System.currentTimeMillis()) > 0

    suspend fun updateBookName(id: String, bookName: String?): Boolean =
        dao.updateBookName(id, bookName, System.currentTimeMillis()) > 0

    suspend fun updateExcludeFromStats(id: String, excludeFromStats: Boolean): Boolean =
        dao.updateExcludeFromStats(id, excludeFromStats, System.currentTimeMillis()) > 0

    /** 清除分类，重置为待分类状态。 */
    suspend fun clearCategory(id: String): PaymentCapture? {
        val existing = dao.findById(id) ?: return null
        val updated = existing.copy(
            categoryName = null,
            categorySource = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(updated)
        return updated
    }

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
        val ruleMatch = if (keepManual) {
            null
        } else {
            com.taostudio.tapaccounting.capture.rules.AccountingRuleEngine.matchNote(context, cleanNote)
        }
        val updated = when {
            keepManual -> existing.copy(note = cleanNote, updatedAt = now)
            ruleMatch != null -> existing.copy(
                note = cleanNote,
                categoryName = ruleMatch.categoryName,
                categorySource = CategorySource.RULE,
                categoryRuleId = ruleMatch.ruleId,
                updatedAt = now,
            )
            else -> existing.copy(
                note = cleanNote,
                categoryName = null,
                categorySource = null,
                categoryRuleId = null,
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
        val resolvedRuleId: Long?
        when {
            keepManual || (existing.categorySource == CategorySource.MANUAL && explicitCategory == null) -> {
                resolvedCategory = existing.categoryName
                resolvedCategorySource = CategorySource.MANUAL
                resolvedRuleId = existing.categoryRuleId
            }
            explicitCategory != null -> {
                resolvedCategory = explicitCategory
                resolvedCategorySource = when {
                    categorySource != null -> categorySource
                    explicitCategory == existing.categoryName -> existing.categorySource
                    else -> CategorySource.MANUAL
                }
                resolvedRuleId = if (resolvedCategorySource == CategorySource.RULE) existing.categoryRuleId else null
            }
            noteChanged -> {
                val matched = com.taostudio.tapaccounting.capture.rules.AccountingRuleEngine.matchNote(context, cleanNote)
                resolvedCategory = matched?.categoryName
                resolvedCategorySource = if (matched != null) CategorySource.RULE else null
                resolvedRuleId = matched?.ruleId
            }
            else -> {
                resolvedCategory = existing.categoryName
                resolvedCategorySource = existing.categorySource
                resolvedRuleId = existing.categoryRuleId
            }
        }

        val updated = existing.copy(
            note = cleanNote,
            assetName = cleanAsset,
            categoryName = resolvedCategory,
            categorySource = resolvedCategorySource,
            categoryRuleId = resolvedRuleId,
            updatedAt = now,
        )
        dao.update(updated)

        val db = com.taostudio.tapaccounting.data.local.AppDatabase.getDatabase(context)
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
                    source = CategoryRule.SOURCE_USER,
                    learnedAt = null,
                )
            )
        }

        return updated
    }

    suspend fun deleteById(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun listMergedChildren(groupId: String): List<PaymentCapture> =
        dao.listMergedChildren(groupId).map { normalizeHistoricalRow(it) }

    suspend fun unmerge(childId: String): Boolean {
        val child = dao.findById(childId) ?: return false
        val parentId = child.groupId ?: return false
        val parent = dao.findById(parentId)

        val updated = dao.unmerge(childId, CaptureStatus.PENDING, System.currentTimeMillis()) > 0
        if (updated && parent != null) {
            val remainingChildren = dao.listMergedChildren(parentId).filter { it.id != childId }
            val remainingChannels = mutableListOf<String>()
            fun addChan(ch: String?, src: String?) {
                val raw = ch?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        if (!remainingChannels.contains(it)) remainingChannels.add(it)
                    }
                } else {
                    val fallback = when (src?.trim()) {
                        "WECHAT" -> "微信支付"
                        "ALIPAY" -> "支付宝"
                        "SMS" -> "银行短信"
                        else -> src?.trim().orEmpty()
                    }
                    if (fallback.isNotEmpty() && !remainingChannels.contains(fallback)) {
                        remainingChannels.add(fallback)
                    }
                }
            }
            addChan(null, parent.source)
            for (c in remainingChildren) {
                addChan(c.channel, c.source)
            }
            val newChannel = remainingChannels.joinToString(" + ")
            dao.update(parent.copy(channel = newChannel, updatedAt = System.currentTimeMillis()))
        }
        return updated
    }

    suspend fun deduplicatePendingItems(): Int {
        val pendingList = dao.listByStatuses(CaptureStatus.PENDING_COUNT_STATUSES.toList(), limit = 500)
        if (pendingList.size < 2) return 0

        val byAmount = pendingList.groupBy { it.amountCents }.filter { (amount, list) ->
            amount != null && amount > 0L && list.size > 1
        }

        var mergedCount = 0
        val now = System.currentTimeMillis()

        for ((_, items) in byAmount) {
            val sorted = items.sortedWith(compareBy({ it.paidAt }, { it.createdAt })).toMutableList()
            val processedIds = mutableSetOf<String>()

            for (i in 0 until sorted.size) {
                val parent = sorted[i]
                if (parent.id in processedIds) continue

                for (j in i + 1 until sorted.size) {
                    val candidate = sorted[j]
                    if (candidate.id in processedIds) continue

                    if (abs(candidate.paidAt - parent.paidAt) <= DuplicateDetector.DEFAULT_WINDOW_MILLIS) {
                        if (DuplicateDetector.isDuplicate(candidate, parent)) {
                            val merged = DuplicateDetector.merge(parent, candidate)
                            dao.update(merged)
                            val child = candidate.copy(
                                status = CaptureStatus.DUPLICATE,
                                groupId = parent.id,
                                updatedAt = now,
                            )
                            dao.update(child)

                            processedIds.add(candidate.id)
                            sorted[i] = merged
                            mergedCount++
                        }
                    }
                }
            }
        }
        return mergedCount
    }

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
        excludeFromStats = excludeFromStats,
        groupId = groupId?.takeIf { it.isNotBlank() },
        channel = channel?.takeIf { it.isNotBlank() } ?: (if (source == "WECHAT") "微信支付" else if (source == "ALIPAY") "支付宝" else source),
        createdAt = now,
        updatedAt = now,
    )
}
