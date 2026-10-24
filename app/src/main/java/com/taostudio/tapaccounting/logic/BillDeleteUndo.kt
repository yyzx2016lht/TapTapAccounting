package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import kotlinx.coroutines.CancellationException

/**
 * 「删完可撤销」的底层能力。
 *
 * 删除本身是即时的（账单进 deleted_bills 表、余额回滚），撤销 = 把刚刚进回收站的那些行
 * 原样还原回 bills 表。为了能精确还原，删除前先取一个时间戳，删除成功后立刻把
 * deletedAt >= 该时间戳的行全捞出来（这一步能顺带带上被级联删除的关联退款），
 * 交给 UI 层挂在撤销按钮上。
 */
object BillDeleteUndo {

    /** 删除并捕获本次产生的回收站行；返回 null 表示删除失败（已提示用户）。 */
    suspend fun delete(db: AppDatabase, bills: List<Bill>): List<DeletedBill>? {
        val targets = bills.distinctBy { it.id }
        if (targets.isEmpty()) return null

        val from = System.currentTimeMillis()
        return try {
            if (targets.size == 1) {
                BillDeleteHelper.deleteBillAndRevertBalance(db, targets.first())
            } else {
                BillDeleteHelper.deleteBillsAndRevertBalance(db, targets)
            }
            db.deletedBillDao().getDeletedSince(from)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching {
                Utils.toast(TapApplication.app(), deleteFailureMessage(e))
            }
            null
        }
    }

    /** 撤销：把 [rows] 从回收站还原回账单表并重新计入余额。返回是否成功。 */
    suspend fun undo(db: AppDatabase, rows: List<DeletedBill>): Boolean {
        if (rows.isEmpty()) return false
        return try {
            BillRestoreHelper.restoreBills(db, rows)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching {
                Utils.toast(TapApplication.app(), "撤销失败，请到回收站手动恢复")
            }
            false
        }
    }
}
