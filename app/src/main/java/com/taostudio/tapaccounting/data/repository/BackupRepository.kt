package com.taostudio.tapaccounting.data.repository

import android.util.Log
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.ChatBillMessageParser
import com.taostudio.tapaccounting.data.backup.SharedRestoreData
import com.taostudio.tapaccounting.data.backup.SharedRestoreMode
import com.taostudio.tapaccounting.data.backup.buildSharedRestoreData
import com.taostudio.tapaccounting.data.backup.materializePendingOperation
import com.taostudio.tapaccounting.data.backup.requireSharedRestoreGuard
import com.taostudio.tapaccounting.data.backup.sanitizeBillForRestore
import com.taostudio.tapaccounting.data.backup.sanitizeBudgetForRestore
import com.taostudio.tapaccounting.data.backup.validateSharedReconnect
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Book
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.SharedLedger
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer

internal data class RestoredBudgetCategory(
    val categoryId: Long?,
    val categoryName: String?
)

internal fun normalizeBudgetBookName(bookName: String): String {
    return when {
        bookName.isBlank() || bookName == BookAccountManager.ALL_BOOK -> ""
        else -> BookAccountManager.normalizeBookName(bookName)
    }
}

internal fun normalizeInvestmentLotForRestore(lot: InvestmentLot): InvestmentLot {
    return lot.copy(
        settlementCycle = lot.settlementCycle.takeIf {
            it in InvestmentLot.CYCLE_DAILY..InvestmentLot.CYCLE_YEARLY
        } ?: InvestmentLot.CYCLE_DAILY,
        settlementInterval = lot.settlementInterval.coerceAtLeast(1),
        interestCarry = lot.interestCarry.takeIf { it.isFinite() } ?: 0.0,
        status = lot.status.takeIf {
            it in InvestmentLot.STATUS_ACTIVE..InvestmentLot.STATUS_CLOSED
        } ?: InvestmentLot.STATUS_ACTIVE
    )
}

internal fun resolveBudgetCategoryForRestore(
    budget: Budget,
    categoryIdMap: Map<Long, Long>,
    currentCategories: List<Category>
): RestoredBudgetCategory? {
    val oldCategoryId = budget.categoryId
        ?: return RestoredBudgetCategory(categoryId = null, categoryName = null)
    val expenseCategories = currentCategories.filter { it.type == 0 }
    val byId = expenseCategories.associateBy { it.id }
    val displayNames = CategoryRepository.displayNamesById(expenseCategories)

    categoryIdMap[oldCategoryId]?.let { mappedId ->
        val mapped = byId[mappedId] ?: return@let
        return RestoredBudgetCategory(mapped.id, displayNames[mapped.id] ?: mapped.name)
    }

    val backupName = budget.categoryName?.trim().orEmpty()
    if (backupName.isEmpty()) return null

    byId[oldCategoryId]?.let { sameId ->
        val currentName = displayNames[sameId.id] ?: sameId.name
        if (backupName == currentName || backupName == sameId.name) {
            return RestoredBudgetCategory(sameId.id, currentName)
        }
    }

    val nameMatches = expenseCategories.filter { category ->
        val currentName = displayNames[category.id] ?: category.name
        backupName == currentName || backupName == category.name
    }
    if (nameMatches.size != 1) return null
    val matched = nameMatches.single()
    return RestoredBudgetCategory(matched.id, displayNames[matched.id] ?: matched.name)
}

/**
 * 从 Room 读出业务数据供 `.bak` 导出，或把 `.bak` 写回数据库。
 *
 * 恢复时会重建 ID 映射（分类/资产/账单外键），与 [AppDatabase] Migration 无关。
 * 新增 [com.taostudio.tapaccounting.data.local.entity] 后请同步 [getFullData] 与 [restoreFullData]。
 */
class BackupRepository(private val db: AppDatabase) {
    companion object {
        /**
         * Merge-restore dedup key: time + amount + type + account + bookName.
         * Without bookName two books can collide and remap IDs across ledgers.
         */
        fun isSameBillForMerge(existing: Bill, incoming: Bill): Boolean =
            existing.time == incoming.time &&
                existing.amount == incoming.amount &&
                existing.type == incoming.type &&
                existing.accountName == incoming.accountName &&
                existing.bookName == incoming.bookName
    }


    suspend fun getFullData(): Map<String, Any> {
        return db.withTransaction {
            val books = db.bookDao().getAll()
            val sharedData = buildSharedRestoreData(
                books = books,
                ledgers = db.sharedLedgerDao().getAll(),
                members = db.sharedMemberDao().getAll(),
                queue = db.syncQueueDao().getAll(),
                pendingOperations = db.syncOperationDao().getPending()
            )
            mapOf(
                "assets" to db.assetDao().getAllAssetsList(),
                "bills" to db.billDao().getAllBillsList(),
                "deleted_bills" to db.deletedBillDao().getAllDeletedBills(),
                "investment_lots" to db.investmentLotDao().getAllLots(),
                "categories" to db.categoryDao().getAllCategoriesList(),
                "rules" to db.aiRuleDao().getAllRulesList(),
                "chat_messages" to db.chatMessageDao().getAll(),
                "budgets" to db.budgetDao().getAll(),
                "recurring_patterns" to db.recurringPatternDao().getAll(),
                "books" to books,
                "shared_ledgers" to sharedData.ledgers,
                "shared_members" to sharedData.members,
                "sync_queue" to sharedData.pendingQueue,
                "sync_operations" to sharedData.pendingOperations
            )
        }
    }

    suspend fun restoreFullData(
        assets: List<Asset>?,
        bills: List<Bill>?,
        deletedBills: List<DeletedBill>?,
        investmentLots: List<InvestmentLot>?,
        categories: List<Category>?,
        rules: List<AiRule>?,
        chatMessages: List<ChatMessage>?,
        budgets: List<Budget>?,
        recurringPatterns: List<RecurringPattern>?,
        books: List<Book>? = null,
        sharedRestoreData: SharedRestoreData? = null,
        sharedRestoreMode: SharedRestoreMode = SharedRestoreMode.LOCAL_COPY,
        newDeviceId: String? = null,
        beforeCommit: suspend () -> Unit = {}
    ) {
        db.withTransaction {
            requireOverwriteRestoreDependenciesAreComplete(
                assets = assets,
                bills = bills,
                deletedBills = deletedBills,
                investmentLots = investmentLots,
                categories = categories,
                chatMessages = chatMessages,
                budgets = budgets,
                recurringPatterns = recurringPatterns
            )
            requireInvestmentRestoreIsComplete(assets, investmentLots)
            validateRestoreBeforeMutation(
                hasSelectedDatabaseModule = listOf(
                    assets, bills, deletedBills, investmentLots, categories, rules, chatMessages,
                    budgets, recurringPatterns, books, sharedRestoreData
                ).any { it != null },
                bills = bills,
                budgets = budgets,
                sharedRestoreData = sharedRestoreData,
                sharedRestoreMode = sharedRestoreMode,
                newDeviceId = newDeviceId
            )
            val categoryIdMap = mutableMapOf<Long, Long>()
            val assetIdMap = mutableMapOf<Long, Long>()
            val billIdMap = mutableMapOf<Long, Long>()

            books.orEmpty().forEach { book ->
                if (book.name.isNotBlank()) db.bookDao().resolveOrCreateId(book.name)
            }

            if (categories != null) {
                Log.d("BackupRepo", "开始恢复分类，共 ${categories.size} 条")
                db.categoryDao().deleteAll()
                val roots = categories.filter { it.parentId == null }
                val children = categories.filter { it.parentId != null }
                roots.forEach { cat ->
                    val newId = db.categoryDao().insertCategory(cat.copy(id = 0))
                    categoryIdMap[cat.id] = newId
                }
                children.forEach { cat ->
                    val newParentId = cat.parentId?.let { categoryIdMap[it] }
                    val newId = db.categoryDao().insertCategory(cat.copy(id = 0, parentId = newParentId))
                    categoryIdMap[cat.id] = newId
                }
            }

            if (assets != null) {
                db.assetDao().deleteAll()
                assets.forEach { asset ->
                    val newId = db.assetDao().insertAsset(asset.copy(id = 0))
                    assetIdMap[asset.id] = newId
                }
            }

            if (bills != null) {
                db.billDao().deleteAll()
                db.deletedBillDao().deleteAll()
                db.investmentLotDao().deleteAll()
                val existingCategoriesByName = db.categoryDao().getAllCategoriesList().associateBy { it.name }
                val existingAssetsByName = db.assetDao().getAllAssetsList().associateBy { it.name }
                val pendingRelated = mutableListOf<Pair<Long, Long>>()

                bills.forEach { bill ->
                    val restoredBill = sanitizeBillForRestore(bill, sharedRestoreMode)
                    val remappedCategoryId = resolveCategoryId(bill, categoryIdMap, existingCategoriesByName)
                    val remappedAccountId = resolveAssetId(bill.accountId, bill.accountName, assetIdMap, existingAssetsByName)
                    val remappedToAccountId = resolveAssetId(bill.toAccountId, bill.toAccountName, assetIdMap, existingAssetsByName)

                    val insertedId = try {
                        db.billDao().insertBill(
                            restoredBill.copy(
                                id = 0,
                                categoryId = remappedCategoryId,
                                accountId = remappedAccountId,
                                toAccountId = remappedToAccountId,
                                categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName),
                                relatedBillId = null
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(
                            "BackupRepo",
                            "恢复账单失败 oldBillId=${bill.id}, type=${bill.type}, amount=${bill.amount}, book=${bill.bookName}",
                            e
                        )
                        throw e
                    }
                    billIdMap[bill.id] = insertedId
                    bill.relatedBillId?.let { pendingRelated.add(insertedId to it) }
                }

                pendingRelated.forEach { (newBillId, oldRelatedId) ->
                    val newRelatedId = billIdMap[oldRelatedId] ?: return@forEach
                    val current = db.billDao().getBillById(newBillId) ?: return@forEach
                    db.billDao().updateBill(current.copy(relatedBillId = newRelatedId))
                }

                val deletedOriginalIds = deletedBills.orEmpty()
                    .mapTo(hashSetOf(), DeletedBill::originalBillId)
                deletedBills.orEmpty().forEach { deletedBill ->
                    db.deletedBillDao().insert(
                        prepareDeletedBillForRestore(
                            deletedBill,
                            categoryIdMap,
                            existingCategoriesByName,
                            assetIdMap,
                            existingAssetsByName,
                            billIdMap,
                            deletedOriginalIds
                        )
                    )
                }

                investmentLots.orEmpty().forEach { lot ->
                    val newAssetId = assetIdMap[lot.assetId] ?: lot.assetId
                    db.investmentLotDao().insertLot(
                        normalizeInvestmentLotForRestore(lot).copy(
                            id = 0L,
                            assetId = newAssetId,
                            sourceBillId = lot.sourceBillId?.let { billIdMap[it] }
                        )
                    )
                }
            }

            if (rules != null) {
                db.aiRuleDao().deleteAll()
                rules.forEach { db.aiRuleDao().insertRule(it.copy(id = 0)) }
            }

            if (chatMessages != null) {
                val existingIds = db.chatMessageDao().getAll().map { it.id }
                if (existingIds.isNotEmpty()) {
                    db.chatMessageDao().deleteByIds(existingIds)
                }
                chatMessages.forEach { msg ->
                    db.chatMessageDao().insert(remapChatBillReferences(msg, billIdMap).copy(id = 0))
                }
            }

            if (budgets != null) {
                db.budgetDao().deleteAll()
                val currentCategories = db.categoryDao().getAllCategoriesList()
                budgets.forEach { budget ->
                    prepareBudgetForRestore(budget, categoryIdMap, currentCategories, sharedRestoreMode)
                        ?.let { db.budgetDao().saveForSlot(it) }
                }
            }

            if (recurringPatterns != null) {
                db.recurringPatternDao().deleteAll()
                val currentCategories = db.categoryDao().getAllCategoriesList()
                recurringPatterns.forEach { pattern ->
                    db.recurringPatternDao().insert(
                        prepareRecurringPatternForRestore(pattern, categoryIdMap, currentCategories)
                    )
                }
            }

            if (sharedRestoreMode == SharedRestoreMode.RECONNECT && sharedRestoreData != null) {
                restoreSharedSnapshot(sharedRestoreData, requireNotNull(newDeviceId))
            }
            beforeCommit()
        }
    }

    /**
     * 合并恢复：只补充备份中有、本地没有的数据，不删除现有数据，不回退资产余额。
     * - 资产/分类：按名称去重，已存在则跳过
     * - 账单：按 时间+金额+类型+账户名 去重，已存在则跳过
     * - 设置：覆盖写入（设置本身就是替换语义）
     */
    suspend fun mergeRestoreFullData(
        assets: List<Asset>?,
        bills: List<Bill>?,
        deletedBills: List<DeletedBill>? = null,
        investmentLots: List<InvestmentLot>?,
        categories: List<Category>?,
        rules: List<AiRule>?,
        chatMessages: List<ChatMessage>?,
        budgets: List<Budget>?,
        recurringPatterns: List<RecurringPattern>?,
        books: List<Book>? = null,
        sharedRestoreData: SharedRestoreData? = null,
        sharedRestoreMode: SharedRestoreMode = SharedRestoreMode.LOCAL_COPY,
        newDeviceId: String? = null,
        beforeCommit: suspend () -> Unit = {}
    ): MergeRestoreResult {
        var insertedAssets = 0
        var skippedAssets = 0
        var insertedCategories = 0
        var skippedCategories = 0
        var insertedBills = 0
        var skippedBills = 0
        var insertedInvestmentLots = 0
        var skippedInvestmentLots = 0
        var insertedRules = 0
        var insertedChatMessages = 0
        var insertedBudgets = 0
        var insertedRecurringPatterns = 0

        db.withTransaction {
            requireInvestmentRestoreIsComplete(assets, investmentLots)
            validateRestoreBeforeMutation(
                hasSelectedDatabaseModule = listOf(
                    assets, bills, deletedBills, investmentLots, categories, rules, chatMessages, budgets,
                    recurringPatterns, books, sharedRestoreData
                ).any { it != null },
                bills = bills,
                budgets = budgets,
                sharedRestoreData = sharedRestoreData,
                sharedRestoreMode = sharedRestoreMode,
                newDeviceId = newDeviceId
            )
            val categoryIdMap = mutableMapOf<Long, Long>()
            val assetIdMap = mutableMapOf<Long, Long>()
            val billIdMap = mutableMapOf<Long, Long>()

            books.orEmpty().forEach { book ->
                if (book.name.isNotBlank()) db.bookDao().resolveOrCreateId(book.name)
            }

            // ── 分类：按名称去重 ──
            if (categories != null) {
                val existingByName = db.categoryDao().getAllCategoriesList().associateBy { it.name }
                val roots = categories.filter { it.parentId == null }
                val children = categories.filter { it.parentId != null }

                roots.forEach { cat ->
                    val existing = existingByName[cat.name]
                    if (existing != null) {
                        categoryIdMap[cat.id] = existing.id
                        skippedCategories++
                    } else {
                        val newId = db.categoryDao().insertCategory(cat.copy(id = 0))
                        categoryIdMap[cat.id] = newId
                        insertedCategories++
                    }
                }
                children.forEach { cat ->
                    val existing = existingByName[cat.name]
                    if (existing != null) {
                        categoryIdMap[cat.id] = existing.id
                        skippedCategories++
                    } else {
                        val newParentId = cat.parentId?.let { categoryIdMap[it] }
                        val newId = db.categoryDao().insertCategory(cat.copy(id = 0, parentId = newParentId))
                        categoryIdMap[cat.id] = newId
                        insertedCategories++
                    }
                }
            }

            // ── 资产：按名称去重 ──
            if (assets != null) {
                val existingByName = db.assetDao().getAllAssetsList().associateBy { it.name }
                assets.forEach { asset ->
                    val existing = existingByName[asset.name]
                    if (existing != null) {
                        assetIdMap[asset.id] = existing.id
                        skippedAssets++
                    } else {
                        val newId = db.assetDao().insertAsset(asset.copy(id = 0))
                        assetIdMap[asset.id] = newId
                        insertedAssets++
                    }
                }
            }

            // ── 账单：按 时间+金额+类型+账户名 去重，不改资产余额 ──
            if (bills != null) {
                val existingCategoriesByName = db.categoryDao().getAllCategoriesList().associateBy { it.name }
                val existingAssetsByName = db.assetDao().getAllAssetsList().associateBy { it.name }
                val pendingRelated = mutableListOf<Pair<Long, Long>>()

                bills.forEach { bill ->
                    val restoredBill = sanitizeBillForRestore(bill, sharedRestoreMode)
                    val isReconnectingSharedBill =
                        sharedRestoreMode == SharedRestoreMode.RECONNECT && restoredBill.isShared
                    val existingBill = if (isReconnectingSharedBill) {
                        restoredBill.sharedId?.let { db.billDao().getBySharedId(it) }
                    } else {
                        db.billDao().getBillsBetweenTimesList(bill.time, bill.time).firstOrNull {
                            isSameBillForMerge(it, bill)
                        }
                    }

                    if (existingBill != null) {
                        check(existingBill.bookName == restoredBill.bookName) {
                            "合并恢复去重命中跨账本账单 old=${bill.id} book=${bill.bookName} existingBook=${existingBill.bookName}"
                        }
                        billIdMap[bill.id] = existingBill.id
                        skippedBills++
                        return@forEach
                    }

                    val remappedCategoryId = resolveCategoryId(bill, categoryIdMap, existingCategoriesByName)
                    val remappedAccountId = resolveAssetId(bill.accountId, bill.accountName, assetIdMap, existingAssetsByName)
                    val remappedToAccountId = resolveAssetId(bill.toAccountId, bill.toAccountName, assetIdMap, existingAssetsByName)

                    val insertedId = db.billDao().insertBill(
                        restoredBill.copy(
                            id = 0,
                            categoryId = remappedCategoryId,
                            accountId = remappedAccountId,
                            toAccountId = remappedToAccountId,
                            categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName),
                            relatedBillId = null
                        )
                    )
                    insertedBills++
                    billIdMap[bill.id] = insertedId
                    bill.relatedBillId?.let { pendingRelated.add(insertedId to it) }
                }

                // 修复退款关联
                pendingRelated.forEach { (newBillId, oldRelatedId) ->
                    // 在备份账单中找 oldRelatedId 对应的新 ID
                    val oldRelatedBill = bills.find { it.id == oldRelatedId } ?: return@forEach
                    val match = db.billDao().countDuplicateBills(
                        time = oldRelatedBill.time,
                        amount = oldRelatedBill.amount,
                        type = oldRelatedBill.type,
                        accountName = oldRelatedBill.accountName
                    )
                    // 如果关联账单已存在（之前插入的或本地原有的），尝试匹配
                    val candidates = db.billDao().getBillsBetweenTimesList(oldRelatedBill.time, oldRelatedBill.time)
                    val relatedBill = candidates.find {
                        it.amount == oldRelatedBill.amount &&
                            it.type == oldRelatedBill.type &&
                            it.accountName == oldRelatedBill.accountName
                    }
                    if (relatedBill != null) {
                        val current = db.billDao().getBillById(newBillId) ?: return@forEach
                        db.billDao().updateBill(current.copy(relatedBillId = relatedBill.id))
                    }
                }
            }

            if (investmentLots != null) {
                investmentLots.forEach { lot ->
                    val newAssetId = assetIdMap[lot.assetId] ?: return@forEach
                    val mappedSourceBillId = lot.sourceBillId?.let { billIdMap[it] }
                    if (mappedSourceBillId != null &&
                        db.investmentLotDao().getLotBySourceBillId(mappedSourceBillId) != null
                    ) {
                        skippedInvestmentLots++
                        return@forEach
                    }
                    db.investmentLotDao().insertLot(
                        normalizeInvestmentLotForRestore(lot).copy(
                            id = 0L,
                            assetId = newAssetId,
                            sourceBillId = mappedSourceBillId
                        )
                    )
                    insertedInvestmentLots++
                }
            }

            if (deletedBills != null) {
                val existingSignatures = db.deletedBillDao().getAllDeletedBills()
                    .mapTo(hashSetOf(), ::deletedBillSignature)
                val currentCategories = db.categoryDao().getAllCategoriesList().associateBy(Category::name)
                val currentAssets = db.assetDao().getAllAssetsList().associateBy(Asset::name)
                val deletedOriginalIds = deletedBills.mapTo(hashSetOf(), DeletedBill::originalBillId)
                deletedBills.forEach { deletedBill ->
                    if (deletedBillSignature(deletedBill) in existingSignatures) return@forEach
                    val restored = prepareDeletedBillForRestore(
                        deletedBill,
                        categoryIdMap,
                        currentCategories,
                        assetIdMap,
                        currentAssets,
                        billIdMap,
                        deletedOriginalIds
                    )
                    db.deletedBillDao().insert(restored)
                    existingSignatures += deletedBillSignature(deletedBill)
                }
            }

            // ── 规则：追加 ──
            if (rules != null) {
                rules.forEach {
                    db.aiRuleDao().insertRule(it.copy(id = 0))
                    insertedRules++
                }
            }

            // ── 聊天记录：追加 ──
            if (chatMessages != null) {
                chatMessages.forEach { msg ->
                    db.chatMessageDao().insert(remapChatBillReferences(msg, billIdMap).copy(id = 0))
                    insertedChatMessages++
                }
            }

            // ── 预算：覆盖（按账本+月份+分类去重） ──
            if (budgets != null) {
                val currentCategories = db.categoryDao().getAllCategoriesList()
                budgets.forEach { budget ->
                    val restored = prepareBudgetForRestore(
                        budget,
                        categoryIdMap,
                        currentCategories,
                        sharedRestoreMode
                    )
                    if (restored != null) {
                        db.budgetDao().saveForSlot(restored)
                        insertedBudgets++
                    }
                }
            }

            // ── 周期记账模式：追加 ──
            if (recurringPatterns != null) {
                val currentCategories = db.categoryDao().getAllCategoriesList()
                recurringPatterns.forEach { pattern ->
                    db.recurringPatternDao().insert(
                        prepareRecurringPatternForRestore(pattern, categoryIdMap, currentCategories)
                    )
                    insertedRecurringPatterns++
                }
            }

            if (sharedRestoreMode == SharedRestoreMode.RECONNECT && sharedRestoreData != null) {
                restoreSharedSnapshot(sharedRestoreData, requireNotNull(newDeviceId))
            }
            beforeCommit()
        }

        return MergeRestoreResult(
            insertedAssets = insertedAssets, skippedAssets = skippedAssets,
            insertedCategories = insertedCategories, skippedCategories = skippedCategories,
            insertedBills = insertedBills, skippedBills = skippedBills,
            insertedInvestmentLots = insertedInvestmentLots,
            skippedInvestmentLots = skippedInvestmentLots,
            insertedRules = insertedRules, insertedChatMessages = insertedChatMessages,
            insertedBudgets = insertedBudgets, insertedRecurringPatterns = insertedRecurringPatterns
        )
    }

    private suspend fun validateRestoreBeforeMutation(
        hasSelectedDatabaseModule: Boolean,
        bills: List<Bill>?,
        budgets: List<Budget>?,
        sharedRestoreData: SharedRestoreData?,
        sharedRestoreMode: SharedRestoreMode,
        newDeviceId: String?
    ) {
        requireSharedRestoreGuard(
            hasActiveSharedLedgers = db.sharedLedgerDao().getAll().isNotEmpty(),
            hasSelectedDatabaseModule = hasSelectedDatabaseModule
        )
        if (!hasSelectedDatabaseModule || sharedRestoreMode == SharedRestoreMode.LOCAL_COPY) return

        val reconnectData = requireNotNull(sharedRestoreData) {
            "重新连接共享账本需要完整的共享恢复数据"
        }
        val deviceId = requireNotNull(newDeviceId) {
            "重新连接共享账本需要由上层生成新的设备 ID"
        }
        val sharedBookNames = mutableSetOf<String>()
        val ledgersByBook = reconnectData.ledgers.associateBy { it.bookName }
        val membersByLedger = reconnectData.members.groupBy { it.ledgerUuid }

        bills.orEmpty().forEach { bill ->
            val hasSharedMarker = bill.isShared || bill.sharedId != null || bill.memberId != null ||
                bill.sharedRevision != 0L || bill.sharedDeviceId != null || bill.relatedSharedId != null
            if (!hasSharedMarker) return@forEach
            require(bill.isShared && Operation.UUID_PATTERN.matches(bill.sharedId.orEmpty())) {
                "共享账单缺少有效的共享 ID"
            }
            require(Operation.UUID_PATTERN.matches(bill.memberId.orEmpty()) && bill.sharedRevision > 0) {
                "共享账单缺少有效的成员或版本信息"
            }
            bill.relatedSharedId?.let { relatedId ->
                require(Operation.UUID_PATTERN.matches(relatedId)) { "共享账单关联 ID 无效" }
            }
            val ledger = requireNotNull(ledgersByBook[bill.bookName]) {
                "共享账单 ${bill.sharedId} 缺少对应共享账本"
            }
            require(membersByLedger[ledger.uuid].orEmpty().any { it.memberId == bill.memberId }) {
                "共享账单 ${bill.sharedId} 的成员不在共享账本中"
            }
            sharedBookNames += bill.bookName
        }
        budgets.orEmpty().forEach { budget ->
            val hasSharedMarker = budget.isShared || budget.sharedId != null || budget.revision != 0L ||
                budget.sharedDeviceId != null || budget.memberBudgetAllocations != null
            if (!hasSharedMarker) return@forEach
            require(budget.isShared && Operation.UUID_PATTERN.matches(budget.sharedId.orEmpty()) && budget.revision > 0) {
                "共享预算缺少有效的共享 ID 或版本信息"
            }
            require(budget.bookName in ledgersByBook) {
                "共享预算 ${budget.sharedId} 缺少对应共享账本"
            }
            sharedBookNames += budget.bookName
        }

        validateSharedReconnect(reconnectData, deviceId, sharedBookNames)
    }

    private fun requireInvestmentRestoreIsComplete(
        assets: List<Asset>?,
        investmentLots: List<InvestmentLot>?
    ) {
        if (investmentLots == null) return
        val backupAssetIds = requireNotNull(assets) {
            "恢复投资批次时必须同时恢复对应资产"
        }.mapTo(hashSetOf(), Asset::id)
        require(investmentLots.all { it.assetId in backupAssetIds }) {
            "投资批次缺少对应的备份资产"
        }
    }

    private suspend fun restoreSharedSnapshot(data: SharedRestoreData, newDeviceId: String) {
        val ledgerIdByUuid = mutableMapOf<String, Long>()
        data.ledgers.forEach { restored ->
            val bookId = db.bookDao().resolveOrCreateId(restored.bookName)
            val ledgerId = db.sharedLedgerDao().insert(
                SharedLedger(
                    uuid = restored.uuid,
                    bookId = bookId,
                    name = restored.name,
                    webdavUrl = restored.webdavUrl,
                    webdavUser = restored.webdavUser,
                    remotePath = restored.remotePath,
                    localMemberId = restored.localMemberId,
                    createdAt = restored.createdAt
                )
            )
            ledgerIdByUuid[restored.uuid] = ledgerId
        }

        val restoredMembers = data.members.map { restored ->
            SharedMember(
                ledgerId = ledgerIdByUuid.getValue(restored.ledgerUuid),
                memberId = restored.memberId,
                displayName = restored.displayName,
                joinOrder = restored.joinOrder,
                isLocal = restored.isLocal
            )
        }
        if (restoredMembers.isNotEmpty()) db.sharedMemberDao().insertAll(restoredMembers)

        val operationById = data.pendingOperations.associateBy { it.operationId }
        data.pendingQueue.sortedBy { it.createdAt }.forEach { queued ->
            val operation = operationById.getValue(queued.operationId)
            val ledger = data.ledgers.single { it.uuid == queued.ledgerUuid }
            val (restoredOperation, restoredQueue) = materializePendingOperation(
                operation = operation,
                queue = queued,
                ledgerId = ledgerIdByUuid.getValue(queued.ledgerUuid),
                ledgerRemotePath = ledger.remotePath,
                newDeviceId = newDeviceId
            )
            require(db.syncOperationDao().insertIgnore(restoredOperation) != -1L) {
                "待上传操作 ${queued.operationId} 已存在"
            }
            require(db.syncQueueDao().insertIgnore(restoredQueue) != -1L) {
                "待上传队列 ${queued.operationId} 已存在"
            }
        }
    }

    private suspend fun prepareBudgetForRestore(
        budget: Budget,
        categoryIdMap: Map<Long, Long>,
        currentCategories: List<Category>,
        sharedRestoreMode: SharedRestoreMode
    ): Budget? {
        val category = resolveBudgetCategoryForRestore(budget, categoryIdMap, currentCategories)
            ?: return null
        val bookName = normalizeBudgetBookName(budget.bookName)
        return sanitizeBudgetForRestore(budget, sharedRestoreMode).copy(
            id = 0,
            bookId = db.bookDao().resolveOrCreateId(bookName),
            bookName = bookName,
            categoryId = category.categoryId,
            categoryKey = category.categoryId ?: Budget.TOTAL_CATEGORY_KEY,
            categoryName = category.categoryName
        )
    }

    private fun requireOverwriteRestoreDependenciesAreComplete(
        assets: List<Asset>?,
        bills: List<Bill>?,
        deletedBills: List<DeletedBill>?,
        investmentLots: List<InvestmentLot>?,
        categories: List<Category>?,
        chatMessages: List<ChatMessage>?,
        budgets: List<Budget>?,
        recurringPatterns: List<RecurringPattern>?
    ) {
        val modules = listOf(
            assets,
            bills,
            deletedBills,
            investmentLots,
            categories,
            chatMessages,
            budgets,
            recurringPatterns
        )
        require(modules.none { it != null } || modules.all { it != null }) {
            "覆盖恢复必须整组恢复资产、分类、账单及其关联数据"
        }
    }

    private fun prepareRecurringPatternForRestore(
        pattern: RecurringPattern,
        categoryIdMap: Map<Long, Long>,
        currentCategories: List<Category>
    ): RecurringPattern {
        val categoriesByName = currentCategories.associateBy(Category::name)
        val mappedCategoryId = pattern.categoryId?.let { oldId ->
            categoryIdMap[oldId] ?: pattern.categoryName
                ?.let(::categoryNameCandidates)
                ?.firstNotNullOfOrNull { categoriesByName[it]?.id }
        }
        return pattern.copy(
            id = 0,
            categoryId = mappedCategoryId,
            categoryName = pattern.categoryName?.let(CategoryNameNormalizer::normalizeForStorage)
        )
    }

    private fun prepareDeletedBillForRestore(
        deletedBill: DeletedBill,
        categoryIdMap: Map<Long, Long>,
        categoriesByName: Map<String, Category>,
        assetIdMap: Map<Long, Long>,
        assetsByName: Map<String, Asset>,
        liveBillIdMap: Map<Long, Long>,
        deletedOriginalIds: Set<Long>
    ): DeletedBill {
        val categoryId = deletedBill.categoryId?.let { oldId ->
            categoryIdMap[oldId] ?: categoryNameCandidates(deletedBill.categoryName)
                .firstNotNullOfOrNull { categoriesByName[it]?.id }
        }
        val relatedId = deletedBill.relatedBillId?.let { oldRelatedId ->
            liveBillIdMap[oldRelatedId] ?: oldRelatedId.takeIf { it in deletedOriginalIds }
        }
        return deletedBill.copy(
            id = 0L,
            originalBillId = deletedBill.originalBillId,
            categoryId = categoryId,
            accountId = resolveAssetId(
                deletedBill.accountId, deletedBill.accountName, assetIdMap, assetsByName
            ),
            toAccountId = resolveAssetId(
                deletedBill.toAccountId, deletedBill.toAccountName, assetIdMap, assetsByName
            ),
            categoryName = CategoryNameNormalizer.normalizeForStorage(deletedBill.categoryName),
            relatedBillId = relatedId
        )
    }

    private fun deletedBillSignature(bill: DeletedBill): String =
        listOf(
            bill.originalBillId,
            bill.type,
            bill.subType,
            bill.amount.toBits(),
            bill.time,
            bill.bookName,
            bill.deletedAt
        ).joinToString("\u0000")

    private fun remapChatBillReferences(msg: ChatMessage, billIdMap: Map<Long, Long>): ChatMessage {
        if (msg.msgType != 4) return msg

        val oldBillIds = ChatBillMessageParser.parseBillIds(msg.billIds)
        val newBillIds = oldBillIds.mapNotNull(billIdMap::get)
        val baseBillIdsJson = JSONArray(newBillIds.map { it.toString() }).toString()
        val newBillIdsText = if (ChatBillMessageParser.isDeprecatedBillMessage(msg.billIds)) {
            ChatBillMessageParser.markBillIdsAsDeprecated(baseBillIdsJson)
        } else {
            baseBillIdsJson
        }

        return msg.copy(
            billIds = newBillIdsText,
            content = remapChatBillContentIds(msg.content, billIdMap)
        )
    }

    private fun remapChatBillContentIds(content: String, billIdMap: Map<Long, Long>): String {
        if (content.isBlank()) return content
        return runCatching {
            val root = JSONObject(content)
            root.optJSONArray("bills")?.let { bills ->
                for (i in 0 until bills.length()) {
                    val billJson = bills.optJSONObject(i) ?: continue
                    val oldId = billJson.optLong("id", 0L)
                    billIdMap[oldId]?.let { mapped ->
                        billJson.put("id", mapped)
                    } ?: billJson.remove("id")
                }
            }
            remapIdArray(root, "deprecatedBillIds", billIdMap)
            remapIdArray(root, "editedBillIds", billIdMap)
            root.toString()
        }.getOrElse { content }
    }

    private fun remapIdArray(root: JSONObject, key: String, billIdMap: Map<Long, Long>) {
        val source = root.optJSONArray(key) ?: return
        val remapped = JSONArray()
        for (i in 0 until source.length()) {
            val oldId = source.optLong(i, 0L)
            billIdMap[oldId]?.let(remapped::put)
        }
        root.put(key, remapped)
    }

    private fun resolveCategoryId(
        bill: Bill,
        categoryIdMap: Map<Long, Long>,
        existingCategoriesByName: Map<String, Category>
    ): Long? {
        val oldCategoryId = bill.categoryId ?: return null
        categoryIdMap[oldCategoryId]?.let { return it }
        for (name in categoryNameCandidates(bill.categoryName)) {
            existingCategoriesByName[name]?.id?.let { return it }
        }
        return null
    }

    private fun resolveAssetId(
        oldAssetId: Long?,
        assetName: String,
        assetIdMap: Map<Long, Long>,
        existingAssetsByName: Map<String, Asset>
    ): Long? {
        oldAssetId?.let { assetIdMap[it]?.let { mapped -> return mapped } }
        val key = assetName.trim()
        if (key.isBlank()) return null
        return existingAssetsByName[key]?.id
    }

    private fun categoryNameCandidates(raw: String): List<String> {
        val s = CategoryNameNormalizer.normalizeForStorage(raw)
        if (s.isBlank()) return emptyList()
        val set = linkedSetOf<String>()
        set.add(s)
        val gt = s.substringAfterLast('>', "").trim()
        if (gt.isNotBlank()) set.add(gt)
        return set.toList()
    }
}

data class MergeRestoreResult(
    val insertedAssets: Int = 0,
    val skippedAssets: Int = 0,
    val insertedCategories: Int = 0,
    val skippedCategories: Int = 0,
    val insertedBills: Int = 0,
    val skippedBills: Int = 0,
    val insertedInvestmentLots: Int = 0,
    val skippedInvestmentLots: Int = 0,
    val insertedRules: Int = 0,
    val insertedChatMessages: Int = 0,
    val insertedBudgets: Int = 0,
    val insertedRecurringPatterns: Int = 0
)
