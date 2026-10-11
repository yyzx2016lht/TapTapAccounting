package com.taostudio.tapaccounting.logic

import androidx.room.withTransaction
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.data.sync.SharedMutationHooks
import com.taostudio.tapaccounting.widget.ExpenseWidgetUpdater

object BillMutationService {

    private const val REFUND_CATEGORY_PREFIX = "\u9000\u6b3e\uff1a"
    private const val REFUND_CATEGORY_PREFIX_ALT = "\u9000\u6b3e\u00b7"

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun stripRefundPrefix(categoryName: String): String {
        return CategoryNameNormalizer.stripRefundPrefix(categoryName)
    }

    private suspend fun normalizeBillCategoryName(db: AppDatabase, bill: Bill): Bill {
        val normalized = bill.copy(categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName))
        return alignCategoryLink(db, normalized)
    }

    /**
     * 给「绕过写入漏斗、自己管理资产余额影响」的路径复用（CSV 导入、回收站还原）。
     *
     * 这些路径不能整个走漏斗（漏斗会施加余额影响），但分类关联必须和漏斗完全一致：
     * 缺 `categoryId` 会让账单对分类预算隐形，展示名过期会让它在统计页上变成「幽灵分类」。
     */
    suspend fun alignCategoryLinkForDirectWrite(db: AppDatabase, bill: Bill): Bill =
        normalizeBillCategoryName(db, bill)

    /**
     * 写入漏斗统一对齐分类关联。
     *
     * `bills.categoryId` 是权威，`categoryName` 只是展示路径快照（"吃的 - 三餐"）——统计页与
     * 洞察都按这份快照聚合，从不回查 id。快照一旦和分类树不一致，同一笔支出就会在统计页上被
     * 拆成两个分类。所以这里两个方向都对齐：
     *
     * - 有 id：按分类树重算展示名（保留退款前缀，退款靠前缀单独识别）。周期模板、聊天改写、
     *   导入都可能抄来一个过期的名字，这一步统一纠正；
     * - 没 id：按名字反查 id，只认 (叶子名, 收支类型) 唯一匹配，宁可留空也不猜。
     *
     * 对齐只是锦上添花，任何异常都放行原账单——绝不能让它导致一笔正常记账失败。
     */
    private suspend fun alignCategoryLink(db: AppDatabase, bill: Bill): Bill {
        if (bill.type == Bill.TYPE_TRANSFER) return bill
        val categories = try {
            db.categoryDao().getAllCategoriesList()
        } catch (e: Exception) {
            logWarn("BILL_CATEGORY", "（警告）分类关联对齐失败，按原样写入：${e.message}")
            return bill
        }
        // 分类表为空时保守放行：可能是恢复/迁移的中间态，不要据此把 categoryId 全清掉
        if (categories.isEmpty()) return bill
        return alignAgainstTree(
            bill = bill,
            paths = CategoryRepository.displayNamesById(categories),
            uniqueByLeafAndType = UncategorizedBillRelinker.uniqueLeafTypeIndex(categories)
        )
    }

    /**
     * 对齐逻辑的纯函数形态：给定分类树，算出这笔账单该写成什么样。
     *
     * - 有 id 且能在树里找到：展示名重算为当前路径（退款前缀保留）；
     * - 有 id 但树里找不到（分类已删除）：**清空 categoryId**，对齐外键 `SET_NULL` 的语义。
     *   悬空 id 既进不了分类预算（`categoryId IN (...)` 匹配不上），又永远挂在旧名字下，
     *   比清空更糟——清空后还能被启动回填按名字重新认领；
     * - 没有 id：按名字反查，只认唯一匹配，宁可留空也不猜。
     */
    internal fun alignAgainstTree(
        bill: Bill,
        paths: Map<Long, String>,
        uniqueByLeafAndType: Map<Pair<String, Int>, Long>
    ): Bill {
        if (bill.type == Bill.TYPE_TRANSFER) return bill
        val currentId = bill.categoryId
        if (currentId != null) {
            val path = paths[currentId] ?: return bill.copy(categoryId = null)
            return if (isDisplayNameAligned(bill.categoryName, path)) {
                bill
            } else {
                bill.copy(categoryName = withRefundPrefixOf(bill.categoryName, path))
            }
        }
        if (!needsCategoryRelink(bill)) return bill
        val resolved = UncategorizedBillRelinker.resolveCategoryId(
            rawName = bill.categoryName,
            billType = bill.type,
            uniqueByLeafAndType = uniqueByLeafAndType
        )
        return if (resolved == null) bill else bill.copy(categoryId = resolved)
    }

    /**
     * 是否需要从分类名反查 categoryId：名字为空无从查，转账没有分类。
     */
    internal fun needsCategoryRelink(bill: Bill): Boolean =
        bill.categoryId == null &&
            bill.categoryName.isNotBlank() &&
            bill.type != Bill.TYPE_TRANSFER

    /**
     * 账单的展示名是否已经和分类树里的路径一致。
     *
     * 退款账单的名字带「退款：」/「退款·」前缀，比对时必须把前缀算进去，
     * 否则每次写入都会把一条本来正确的退款账单反复改写。
     */
    internal fun isDisplayNameAligned(existingName: String, path: String): Boolean =
        existingName == withRefundPrefixOf(existingName, path)

    /** 保留退款账单的展示前缀，只替换后面的分类路径。 */
    private fun withRefundPrefixOf(existingName: String, path: String): String = when {
        existingName.startsWith(REFUND_CATEGORY_PREFIX) -> REFUND_CATEGORY_PREFIX + path
        existingName.startsWith(REFUND_CATEGORY_PREFIX_ALT) -> REFUND_CATEGORY_PREFIX_ALT + path
        else -> path
    }

    private fun logFull(tag: String, message: String) {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return
        Logger.d(ctx, tag, message)
    }

    /** 数据一致性告警：普通日志模式也会保留 */
    private fun logWarn(tag: String, message: String) {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        Logger.w(ctx, tag, message)
    }

    private fun auditBill(op: String, bill: Bill) {
        logFull(
            "BILL_MUTATION",
            "AUDIT_BILL|op=$op|billId=${bill.id}|type=${bill.type}|subType=${bill.subType}|amount=${bill.amount}|asset=${bill.accountName}|toAsset=${bill.toAccountName}|currency=${bill.currency}|timeMs=${bill.time}"
        )
    }

    /** 账单写成功后通知桌面小组件；防抖合并连续多笔，避免悬浮窗/聊天连记时刷爆。 */
    private fun notifyWidgetsChanged() {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        ExpenseWidgetUpdater.refreshAllDebounced(ctx)
    }

    suspend fun insertBillAndApplyImpact(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "insert:start type=${bill.type}, amount=${bill.amount}, account=${bill.accountName}, to=${bill.toAccountName}, assetImpact=$applyAssetImpact")
        return db.withTransaction {
            val normalizedInput = normalizeBillCategoryName(db, SharedMutationHooks.prepareLocalBill(db, bill))
            if (applyAssetImpact) {
                validateRequiredRatesForBill(db, normalizedInput)
            }
            val savedBill = normalizedInput.copy(id = db.billDao().insertBill(normalizedInput))
            if (applyAssetImpact) {
                val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
                if (impacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logWarn("BILL_GUARD", "（警告）insert 已写账单但资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
                }
            }
            logFull("BILL_MUTATION", "insert:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}, accountId=${savedBill.accountId}, toAccountId=${savedBill.toAccountId}")
            auditBill("insert", savedBill)
            SharedMutationHooks.enqueueSaved(db, savedBill)
            matchRecurringQuietly(db, savedBill)
            db.billDao().getBillById(savedBill.id) ?: savedBill
        }.also { notifyWidgetsChanged() }
    }

    suspend fun insertBillWithinActiveTransaction(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "insertTx:start type=${bill.type}, amount=${bill.amount}, account=${bill.accountName}, to=${bill.toAccountName}, assetImpact=$applyAssetImpact")
        val normalizedInput = normalizeBillCategoryName(db, SharedMutationHooks.prepareLocalBill(db, bill))
        if (applyAssetImpact) {
            validateRequiredRatesForBill(db, normalizedInput)
        }
        val savedBill = normalizedInput.copy(id = db.billDao().insertBill(normalizedInput))
        if (applyAssetImpact) {
            val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
            if (impacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logWarn("BILL_GUARD", "（警告）insertTx 已写账单但资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
            }
        }
        logFull("BILL_MUTATION", "insertTx:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}")
        auditBill("insert_tx", savedBill)
        SharedMutationHooks.enqueueSaved(db, savedBill)
        matchRecurringQuietly(db, savedBill)
        notifyWidgetsChanged()
        return db.billDao().getBillById(savedBill.id) ?: savedBill
    }

    /**
     * 保存仅属于本机的系统生成账单。理财批次没有参与共享账本同步，因此其估算收益也不能
     * 进入共享队列，否则多台设备会各自结息并产生重复流水。
     */
    suspend fun insertLocalGeneratedBillWithinActiveTransaction(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        val localBill = normalizeBillCategoryName(
            db,
            bill.copy(
                sharedId = null,
                memberId = null,
                isShared = false,
                sharedRevision = 0,
                sharedDeviceId = null,
                relatedSharedId = null
            )
        )
        val savedBill = localBill.copy(id = db.billDao().insertBill(localBill))
        if (applyAssetImpact) {
            // P0-3：apply 前校验汇率；失败抛出以中止事务，禁止「落库但余额不动」
            validateRequiredRatesForBill(db, savedBill)
            BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
        }
        auditBill("insert_local_generated", savedBill)
        notifyWidgetsChanged()
        return db.billDao().getBillById(savedBill.id) ?: savedBill
    }

    suspend fun upsertBillAndApplyImpact(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        return insertBillAndApplyImpact(db, bill, applyAssetImpact)
    }

    suspend fun replaceBill(
        db: AppDatabase,
        oldBill: Bill,
        newBill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "replace:start oldId=${oldBill.id}, oldType=${oldBill.type}, oldAmount=${oldBill.amount}, newType=${newBill.type}, newAmount=${newBill.amount}, assetImpact=$applyAssetImpact")
        return db.withTransaction {
            // 事务内重读，避免并发编辑用过期快照回滚余额
            val freshOldBill = db.billDao().getBillById(oldBill.id) ?: oldBill
            SharedMutationHooks.requireOwner(db, freshOldBill)
            require(freshOldBill.subType != Bill.SUBTYPE_INVESTMENT_ESTIMATE) {
                "自动估算收益不能直接编辑，请在资产详情修改收益设置"
            }
            val normalizedBill = when {
                freshOldBill.subType == Bill.SUBTYPE_REFUND -> {
                    val fallbackCategory = stripRefundPrefix(freshOldBill.categoryName)
                    val inputCategory = stripRefundPrefix(newBill.categoryName)
                    val normalizedCategory = if (inputCategory.isNotEmpty()) inputCategory else fallbackCategory
                    newBill.copy(
                        subType = Bill.SUBTYPE_REFUND,
                        relatedBillId = freshOldBill.relatedBillId,
                        categoryName = "$REFUND_CATEGORY_PREFIX$normalizedCategory",
                        originalAmount = newBill.amount
                    )
                }

                freshOldBill.type == Bill.TYPE_EXPENSE && baseOriginalAmount(freshOldBill) > freshOldBill.amount -> {
                    // P1-1: 用户输入视为新的 original；净额 = original - 已关联退款，保持 original = net + refunds
                    val refundTotal = db.billDao().getRefundTotalBySourceId(freshOldBill.id)
                    val newOriginal = newBill.amount.coerceAtLeast(refundTotal)
                    newBill.copy(
                        amount = (newOriginal - refundTotal).coerceAtLeast(0.0),
                        originalAmount = newOriginal
                    )
                }

                else -> {
                    newBill.copy(
                        originalAmount = newBill.amount
                    )
                }
            }
            // 换账本时不复用旧 sharedId，否则同步可能把账单拽回原共享账本
            val bookChanged = normalizedBill.bookName.isNotBlank() &&
                freshOldBill.bookName.isNotBlank() &&
                normalizedBill.bookName != freshOldBill.bookName
            val sharedFields = if (bookChanged) {
                normalizedBill.copy(
                    sharedId = null,
                    memberId = null,
                    isShared = false,
                    relatedSharedId = null,
                    sharedRevision = 0,
                    sharedDeviceId = null
                )
            } else {
                normalizedBill.copy(
                    sharedId = freshOldBill.sharedId,
                    memberId = freshOldBill.memberId,
                    isShared = freshOldBill.isShared,
                    relatedSharedId = freshOldBill.relatedSharedId
                )
            }
            val normalizedBillForStorage = normalizeBillCategoryName(
                db,
                SharedMutationHooks.prepareLocalBill(
                    db,
                    sharedFields.copy(paymentGroupId = newBill.paymentGroupId ?: freshOldBill.paymentGroupId)
                )
            )

            if (freshOldBill.subType == Bill.SUBTYPE_REFUND && freshOldBill.relatedBillId != null) {
                val sourceBill = db.billDao().getBillById(freshOldBill.relatedBillId)
                if (sourceBill != null) {
                    SharedMutationHooks.requireOwner(db, sourceBill)
                    val sourceBaseOriginalAmount = baseOriginalAmount(sourceBill)
                    val delta = normalizedBillForStorage.amount - freshOldBill.amount
                    // 与 saveRefundBill 一致：超额退款直接拒绝，而不是静默截断
                    require(delta <= sourceBill.amount + 1e-9) {
                        "Refund amount exceeds remaining expense"
                    }
                    val newSourceActualAmount = (sourceBill.amount - delta).coerceIn(0.0, sourceBaseOriginalAmount)
                    val savedSource = SharedMutationHooks.prepareLocalBill(
                        db,
                        sourceBill.copy(
                            amount = newSourceActualAmount,
                            originalAmount = sourceBaseOriginalAmount
                        )
                    )
                    db.billDao().updateBill(savedSource)
                    SharedMutationHooks.enqueueSaved(db, savedSource)
                }
            }

            if (applyAssetImpact) {
                validateRequiredRatesForBill(db, normalizedBillForStorage)
            }

            val oldImpacted = BillAssetImpactService.revertBillBalanceImpact(db, freshOldBill)
            if (applyAssetImpact && oldImpacted == 0 && freshOldBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logWarn("BILL_GUARD", "（警告）replace 回滚旧账单时资产未变化，oldBillId=${freshOldBill.id}, type=${freshOldBill.type}, asset=${freshOldBill.accountName}, toAsset=${freshOldBill.toAccountName}")
            }

            // 编辑路径：使用 updateBill 而非 insertBill，避免外键级联删除/重建
            val savedBill = if (normalizedBillForStorage.id > 0L) {
                db.billDao().updateBill(normalizedBillForStorage)
                normalizedBillForStorage
            } else {
                normalizedBillForStorage.copy(id = db.billDao().insertBill(normalizedBillForStorage))
            }
            if (applyAssetImpact) {
                val newImpacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
                if (newImpacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logWarn("BILL_GUARD", "（警告）replace 写入新账单后资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
                }
            }
            InvestmentInterestService.syncLotAfterBillReplacement(db, freshOldBill, savedBill)
            logFull("BILL_MUTATION", "replace:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}, category=${savedBill.categoryName}")
            auditBill("replace", savedBill)
            SharedMutationHooks.enqueueSaved(db, savedBill)
            matchRecurringQuietly(db, savedBill)
            db.billDao().getBillById(savedBill.id) ?: savedBill
        }.also { notifyWidgetsChanged() }
    }

    suspend fun saveRefundBill(
        db: AppDatabase,
        originalBill: Bill,
        refundBill: Bill,
        previousRefundBill: Bill? = null
    ): Bill {
        logFull("BILL_MUTATION", "refund:start sourceId=${originalBill.id}, refundId=${refundBill.id}, refundAmount=${refundBill.amount}")
        return db.withTransaction {
            previousRefundBill?.let { SharedMutationHooks.requireOwner(db, it) }
            val latestOriginal = db.billDao().getBillById(originalBill.id)
                ?: error("Original bill not found")

            require(latestOriginal.type == Bill.TYPE_EXPENSE && latestOriginal.subType != Bill.SUBTYPE_REFUND) {
                "Original bill is not refundable"
            }
            SharedMutationHooks.requireOwner(db, latestOriginal)

            val existingRefund = when {
                previousRefundBill != null -> {
                    db.billDao().getBillById(previousRefundBill.id) ?: previousRefundBill
                }
                refundBill.id > 0L -> db.billDao().getBillById(refundBill.id)
                else -> null
            }

            val oldRefundAmount = existingRefund?.amount ?: 0.0
            val delta = refundBill.amount - oldRefundAmount
            require(delta <= latestOriginal.amount + 1e-9) {
                "Refund amount exceeds remaining expense"
            }

            val baseOriginalAmount = baseOriginalAmount(latestOriginal)
            val newActualExpense = (latestOriginal.amount - delta).coerceIn(0.0, baseOriginalAmount)
            val savedOriginal = SharedMutationHooks.prepareLocalBill(db,
                latestOriginal.copy(
                    amount = newActualExpense,
                    originalAmount = baseOriginalAmount
                )
            )
            db.billDao().updateBill(savedOriginal)
            SharedMutationHooks.enqueueSaved(db, savedOriginal)

            val sourceCategory = stripRefundPrefix(latestOriginal.categoryName)
            val normalizedRefundBill = refundBill.copy(
                id = existingRefund?.id ?: refundBill.id,
                type = Bill.TYPE_INCOME,
                subType = Bill.SUBTYPE_REFUND,
                categoryId = latestOriginal.categoryId,
                categoryName = "$REFUND_CATEGORY_PREFIX$sourceCategory",
                currency = latestOriginal.currency,
                exchangeRate = latestOriginal.exchangeRate,
                relatedBillId = latestOriginal.id,
                bookName = latestOriginal.bookName,
                originalAmount = refundBill.amount
            )
            val normalizedRefundForStorage = normalizeBillCategoryName(
                db,
                SharedMutationHooks.prepareLocalBill(db, normalizedRefundBill.copy(
                    sharedId = existingRefund?.sharedId,
                    memberId = existingRefund?.memberId,
                    isShared = existingRefund?.isShared ?: false,
                    relatedSharedId = latestOriginal.sharedId
                ))
            )

            // 编辑已有退款账单时用 updateBill，新建时才用 insertBill
            validateRequiredRatesForBill(db, normalizedRefundForStorage)
            existingRefund?.let {
                val impacted = BillAssetImpactService.revertBillBalanceImpact(db, it)
                if (impacted == 0 && it.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logWarn("BILL_GUARD", "（警告）refund 编辑回滚旧退款时资产未变化，billId=${it.id}, type=${it.type}, asset=${it.accountName}, toAsset=${it.toAccountName}")
                }
            }

            val savedRefundBill = if (normalizedRefundForStorage.id > 0L) {
                db.billDao().updateBill(normalizedRefundForStorage)
                normalizedRefundForStorage
            } else {
                normalizedRefundForStorage.copy(id = db.billDao().insertBill(normalizedRefundForStorage))
            }
            val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedRefundBill)
            if (impacted == 0 && savedRefundBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logWarn("BILL_GUARD", "（警告）refund 写入后资产未变化，billId=${savedRefundBill.id}, type=${savedRefundBill.type}, asset=${savedRefundBill.accountName}, toAsset=${savedRefundBill.toAccountName}")
            }
            logFull("BILL_MUTATION", "refund:done refundId=${savedRefundBill.id}, sourceId=${latestOriginal.id}, refundAmount=${savedRefundBill.amount}, sourceCurrentAmount=${newActualExpense}")
            auditBill("refund", savedRefundBill)
            SharedMutationHooks.enqueueSaved(db, savedRefundBill)
            db.billDao().getBillById(savedRefundBill.id) ?: savedRefundBill
        }.also { notifyWidgetsChanged() }
    }

    suspend fun resolveRefundSourceBill(db: AppDatabase, refundBill: Bill): Bill? {
        return db.withTransaction {
            refundBill.relatedBillId?.let { id ->
                db.billDao().getBillById(id)?.let { return@withTransaction it }
            }
            if (refundBill.subType != Bill.SUBTYPE_REFUND) return@withTransaction null
            val sourceCategory = stripRefundPrefix(refundBill.categoryName).ifBlank {
                return@withTransaction null
            }
            val source = db.billDao().findLikelyRefundSourceBill(
                bookName = refundBill.bookName,
                categoryName = sourceCategory,
                refundAmount = refundBill.amount,
                refundAccountName = refundBill.accountName,
                refundTime = refundBill.time
            ) ?: return@withTransaction null
            source
        }
    }

    suspend fun setExcludeFromStats(db: AppDatabase, bill: Bill, exclude: Boolean) {
        db.withTransaction {
            val latest = db.billDao().getBillById(bill.id) ?: return@withTransaction
            SharedMutationHooks.requireOwner(db, latest)
            val saved = SharedMutationHooks.prepareLocalBill(db, latest.copy(excludeFromStats = exclude))
            db.billDao().updateBill(saved)
            SharedMutationHooks.enqueueSaved(db, saved)
        }
        notifyWidgetsChanged()
    }

    /**
     * 批量把多笔账单设为（不）计入统计。先逐条做共享账本所有者校验，
     * 再在一个事务内写库并入队同步；任何一条越权都会整批失败，绝不部分生效。
     */
    suspend fun setExcludeFromStatsBatch(db: AppDatabase, bills: List<Bill>, exclude: Boolean) {
        val targets = bills.distinctBy { it.id }
            .filter { it.id > 0L }
            .mapNotNull { db.billDao().getBillById(it.id) }
            .filter { it.excludeFromStats != exclude }
        if (targets.isEmpty()) return
        targets.forEach { SharedMutationHooks.requireOwner(db, it) }
        db.withTransaction {
            targets.forEach { bill ->
                val saved = SharedMutationHooks.prepareLocalBill(db, bill.copy(excludeFromStats = exclude))
                db.billDao().updateBill(saved)
                SharedMutationHooks.enqueueSaved(db, saved)
            }
        }
        notifyWidgetsChanged()
    }

    /** 批量设置（[groupId] 非空）或清除（null）扣款组；与 [setExcludeFromStatsBatch] 同样整批校验所有者。 */
    suspend fun setPaymentGroupBatch(db: AppDatabase, bills: List<Bill>, groupId: String?) {
        val targets = bills.distinctBy { it.id }
            .filter { it.id > 0L }
            .mapNotNull { db.billDao().getBillById(it.id) }
            .filter { it.paymentGroupId != groupId }
        if (targets.isEmpty()) return
        targets.forEach { SharedMutationHooks.requireOwner(db, it) }
        db.withTransaction {
            targets.forEach { bill ->
                val saved = SharedMutationHooks.prepareLocalBill(db, bill.copy(paymentGroupId = groupId))
                db.billDao().updateBill(saved)
                SharedMutationHooks.enqueueSaved(db, saved)
            }
        }
    }

    suspend fun validateRequiredRatesForBill(db: AppDatabase, bill: Bill) {
        val sourceAsset = bill.accountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.accountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        val targetAsset = bill.toAccountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.toAccountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        BillAssetImpactService.ensureRatesForImpact(bill, sourceAsset, targetAsset)
    }

    private suspend fun matchRecurringQuietly(db: AppDatabase, bill: Bill) {
        try {
            RecurringBillingService(db).matchNewBill(bill)
        } catch (_: Exception) {
            // 账单保存是主流程；周期账单推进失败不能阻断入账。
        }
    }
}
