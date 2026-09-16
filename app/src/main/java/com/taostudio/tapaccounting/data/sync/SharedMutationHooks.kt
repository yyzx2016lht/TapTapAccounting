package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.taostudio.tapaccounting.DeviceIdManager
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.SharedLedger
import com.taostudio.tapaccounting.widget.ExpenseWidgetUpdater
import java.util.UUID
import androidx.room.withTransaction

object SharedMutationHooks {
    private val gson = Gson()

    suspend fun prepareLocalBill(db: AppDatabase, bill: Bill): Bill {
        if (!SharedLedgerService.run { bill.isShareable() }) return bill
        val book = db.bookDao().getByName(bill.bookName) ?: return bill
        val ledger = db.sharedLedgerDao().getByBookId(book.id) ?: return bill
        val entityId = bill.sharedId ?: UUID.randomUUID().toString()
        val revision = db.syncOperationDao().maxRevision(ledger.id, "bill", entityId) + 1
        val icon = bill.cateIcon ?: com.taostudio.tapaccounting.CategoryIconHelper
            .findCategoryIcon(TapApplication.app(), bill.categoryName, bill.type)
            .takeIf { it.isNotBlank() }
        return bill.copy(sharedId = entityId, memberId = ledger.localMemberId, isShared = true,
            sharedRevision = revision, sharedDeviceId = DeviceIdManager.getDeviceId(TapApplication.app()),
            relatedSharedId = bill.relatedBillId?.let { db.billDao().getBillById(it)?.sharedId },
            cateIcon = icon)
    }

    suspend fun requireOwner(db: AppDatabase, bill: Bill) {
        if (!bill.isShared) return
        val local = db.sharedLedgerDao().getByBookName(bill.bookName)?.localMemberId ?: return
        require(bill.memberId == local) { "只能编辑自己创建的共享账单" }
    }

    suspend fun enqueueSaved(db: AppDatabase, bill: Bill, action: String = "update") {
        if (!bill.isShared || bill.sharedId == null || bill.memberId == null) return
        val book = db.bookDao().getByName(bill.bookName) ?: return
        val ledger = db.sharedLedgerDao().getByBookId(book.id) ?: return
        val payload = gson.toJsonTree(bill.copy(id = 0, accountId = null, toAccountId = null, accountName = "", toAccountName = "", accountBalanceAfter = null, toAccountBalanceAfter = null)).asJsonObject
        SharedLedgerService(TapApplication.app(), db).enqueue(ledger.id, ledger.uuid, "bill", bill.sharedId, if (bill.sharedRevision == 1L) "create" else action, bill.sharedRevision, bill.memberId, payload)
        SharedSyncScheduler.enqueueNow(TapApplication.app())
    }

    suspend fun enqueueDelete(db: AppDatabase, bill: Bill) {
        if (!bill.isShared || bill.sharedId == null || bill.memberId == null) return
        require(bill.memberId == db.sharedLedgerDao().getByBookName(bill.bookName)?.localMemberId) { "只能删除自己创建的共享账单" }
        val ledger = db.sharedLedgerDao().getByBookName(bill.bookName) ?: return
        enqueueDeleteFromLedger(db, ledger, bill)
        SharedSyncScheduler.enqueueNow(TapApplication.app())
    }

    /**
     * 备份页的“永久清理”不进入回收站、也不回退私人资产余额，但共享账单仍必须先写
     * tombstone。整批先做所有者校验，再在一个事务内提交，避免清理到一半才失败。
     */
    suspend fun deleteBillsPermanently(db: AppDatabase, bills: List<Bill>) {
        val latest = bills.distinctBy { it.id }.mapNotNull { bill ->
            if (bill.id > 0L) db.billDao().getBillById(bill.id) else null
        }
        val sharedTargets = latest.filter { it.isShared }.map { bill ->
            val ledger = db.sharedLedgerDao().getByBookName(bill.bookName)
                ?: error("该账单的共享来源已失效，请先退出或修复共享账本")
            require(bill.memberId == ledger.localMemberId) { "只能清理自己创建的共享账单" }
            ledger to bill
        }
        db.withTransaction {
            sharedTargets.forEach { (ledger, bill) -> enqueueDeleteFromLedger(db, ledger, bill) }
            if (latest.isNotEmpty()) db.billDao().delete(latest)
        }
        if (sharedTargets.isNotEmpty()) SharedSyncScheduler.enqueueNow(TapApplication.app())
        runCatching { ExpenseWidgetUpdater.refreshAllDebounced(TapApplication.app()) }
    }

    private suspend fun enqueueDeleteFromLedger(db: AppDatabase, ledger: SharedLedger, bill: Bill) {
        val sharedId = bill.sharedId ?: return
        val memberId = bill.memberId ?: return
        val revision = db.syncOperationDao().maxRevision(ledger.id, "bill", sharedId) + 1
        SharedLedgerService(TapApplication.app(), db).enqueue(ledger.id, ledger.uuid, "bill", sharedId, "delete", revision, memberId, null)
    }

    suspend fun moveBills(db: AppDatabase, bills: List<Bill>, targetBook: String) {
        val latest = bills.mapNotNull { db.billDao().getBillById(it.id) }
        latest.forEach { bill ->
            if (!bill.isShared) return@forEach
            val source = db.sharedLedgerDao().getByBookName(bill.bookName)
                ?: error("该账单的共享来源已失效，请重新打开应用后再试")
            require(bill.memberId == source.localMemberId) { "只能移动自己创建的共享账单" }
        }
        db.withTransaction {
            latest.forEach { bill ->
                if (bill.isShared) enqueueDelete(db, bill)
                val local = bill.copy(
                    bookName = targetBook, sharedId = null, memberId = null, isShared = false,
                    sharedRevision = 0, sharedDeviceId = null, relatedSharedId = null
                )
                val moved = prepareLocalBill(db, local)
                db.billDao().updateBill(moved)
                enqueueSaved(db, moved)
            }
        }
        runCatching { ExpenseWidgetUpdater.refreshAllDebounced(TapApplication.app()) }
    }

    suspend fun repairMovedSharedBills(db: AppDatabase) {
        val ledgers = db.sharedLedgerDao().getAll()
        if (ledgers.isEmpty()) return
        val sharedBooks = ledgers.mapNotNull { db.bookDao().getById(it.bookId)?.name }.toSet()
        val detached = db.billDao().getAllBillsList().filter { it.isShared && it.bookName !in sharedBooks }
        if (detached.isEmpty()) return
        db.withTransaction {
            detached.forEach { bill ->
                ledgers.firstOrNull { it.localMemberId == bill.memberId }
                    ?.let { enqueueDeleteFromLedger(db, it, bill) }
                db.billDao().updateBill(bill.copy(
                    sharedId = null, memberId = null, isShared = false, sharedRevision = 0,
                    sharedDeviceId = null, relatedSharedId = null
                ))
            }
        }
        SharedSyncScheduler.enqueueNow(TapApplication.app())
    }
}
