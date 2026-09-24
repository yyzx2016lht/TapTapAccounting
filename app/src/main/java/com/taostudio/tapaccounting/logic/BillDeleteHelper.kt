package com.taostudio.tapaccounting.logic

import androidx.room.withTransaction
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.data.sync.SharedMutationHooks
import com.taostudio.tapaccounting.widget.ExpenseWidgetUpdater

object BillDeleteHelper {
    private fun logFull(tag: String, message: String) {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return
        Logger.d(ctx, tag, message)
    }

    private fun notifyWidgetsChanged() {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        ExpenseWidgetUpdater.refreshAllDebounced(ctx)
    }

    private fun billToDeletedBill(bill: Bill): DeletedBill {
        return DeletedBill(
            originalBillId = bill.id,
            type = bill.type,
            subType = bill.subType,
            amount = bill.amount,
            originalAmount = bill.originalAmount,
            currency = bill.currency,
            exchangeRate = bill.exchangeRate,
            categoryId = bill.categoryId,
            accountId = bill.accountId,
            toAccountId = bill.toAccountId,
            categoryName = bill.categoryName,
            accountName = bill.accountName,
            toAccountName = bill.toAccountName,
            time = bill.time,
            remark = bill.remark,
            fee = bill.fee,
            bookName = bill.bookName,
            relatedBillId = bill.relatedBillId,
            excludeFromStats = bill.excludeFromStats,
            deletedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteBillAndRevertBalance(db: AppDatabase, bill: Bill) {
        deleteBillAndRevertBalanceInternal(
            db = db,
            bill = bill,
            backfillLinks = true,
            scopeBillIds = null
        )
        notifyWidgetsChanged()
    }

    suspend fun deleteBillsAndRevertBalance(db: AppDatabase, bills: List<Bill>) {
        deleteBillsAndRevertBalanceInternal(db, bills, scopeBillIds = null)
        notifyWidgetsChanged()
    }

    suspend fun deleteBillsAndRevertBalanceScoped(
        db: AppDatabase,
        bills: List<Bill>,
        scopeBillIds: Set<Long>
    ) {
        deleteBillsAndRevertBalanceInternal(
            db = db,
            bills = bills,
            scopeBillIds = scopeBillIds.filter { it > 0L }.toSet()
        )
        notifyWidgetsChanged()
    }

    private suspend fun deleteBillsAndRevertBalanceInternal(
        db: AppDatabase,
        bills: List<Bill>,
        scopeBillIds: Set<Long>?
    ) {
        if (bills.isEmpty()) return
        val uniqueBills = bills.distinctBy {
            if (it.id > 0L) {
                "id:${it.id}"
            } else {
                "tmp:${it.time}:${it.type}:${it.subType}:${it.amount}:${it.accountId}:${it.toAccountId}"
            }
        }
        if (uniqueBills.isEmpty()) return

        db.billDao().backfillAssetLinksByName()
        val latestBills = uniqueBills.map { bill ->
            if (bill.id > 0L) db.billDao().getBillById(bill.id) ?: bill else bill
        }
        latestBills.forEach { SharedMutationHooks.requireOwner(db, it) }
        db.withTransaction {
            latestBills.forEach { bill ->
                deleteBillAndRevertBalanceInternal(
                    db = db,
                    bill = bill,
                    backfillLinks = false,
                    scopeBillIds = scopeBillIds
                )
            }
        }
    }

    private suspend fun deleteBillAndRevertBalanceInternal(
        db: AppDatabase,
        bill: Bill,
        backfillLinks: Boolean,
        scopeBillIds: Set<Long>?
    ) {
        db.withTransaction {
            val billDao = db.billDao()
            val deletedBillDao = db.deletedBillDao()
            if (backfillLinks) {
                billDao.backfillAssetLinksByName()
            }
            // 已被级联删除的账单直接跳过，避免批量删除时二次回滚
            val latestBill = if (bill.id > 0L) {
                billDao.getBillById(bill.id) ?: return@withTransaction
            } else {
                bill
            }
            SharedMutationHooks.requireOwner(db, latestBill)
            SharedMutationHooks.enqueueDelete(db, latestBill)

            // 先保存到 deleted_bills 表
            deletedBillDao.insert(billToDeletedBill(latestBill))

            // 转入理财产生的本金批次与来源账单同生共死，避免删账单后继续虚构收益。
            if (latestBill.id > 0L) {
                db.investmentLotDao().deleteBySourceBillId(latestBill.id)
            }
            InvestmentInterestService.applyEstimateBillLotImpact(
                db = db,
                bill = latestBill,
                restoring = false
            )

            when {
                latestBill.subType == Bill.SUBTYPE_REFUND -> {
                    val sourceId = latestBill.relatedBillId
                    if (sourceId != null) {
                        val original = billDao.getBillById(sourceId)
                        if (original != null) {
                            val baseOriginal = if (original.originalAmount > 0.0) {
                                kotlin.math.max(original.originalAmount, original.amount)
                            } else {
                                original.amount
                            }
                            val restored = (original.amount + latestBill.amount).coerceAtMost(baseOriginal)
                            val savedOriginal = SharedMutationHooks.prepareLocalBill(db, original.copy(amount = restored, originalAmount = baseOriginal))
                            billDao.updateBill(savedOriginal)
                            SharedMutationHooks.enqueueSaved(db, savedOriginal)
                        }
                    }
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_refund 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                // 兼容旧平账记录：旧 subtype 的删除逻辑保持原样
                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_adjust 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_EXPENSE -> {
                    // P1-2: 删除支出时级联删除全部关联退款，再按全额 original 回滚，避免部分退款残留导致账实不符
                    val refunds = billDao.getRefundBillsBySourceId(latestBill.id)
                    if (refunds.isNotEmpty()) {
                        refunds.forEach { refund ->
                            SharedMutationHooks.requireOwner(db, refund)
                            SharedMutationHooks.enqueueDelete(db, refund)
                            deletedBillDao.insert(billToDeletedBill(refund))
                            val impacted = BillAssetImpactService.revertBillBalanceImpact(db, refund)
                            if (impacted == 0) {
                                logFull("BILL_GUARD", "（警告）delete_refund_linked 删除关联退款时资产未变化，billId=${refund.id}, asset=${refund.accountName}, toAsset=${refund.toAccountName}")
                            }
                        }
                        billDao.delete(refunds)
                    }
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_expense 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_INCOME -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_income 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_TRANSFER -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_transfer 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                else -> billDao.delete(latestBill)
            }
        }
    }
}
