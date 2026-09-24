package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * Legacy helpers for optional DB columns [Bill.accountBalanceAfter] / [Bill.toAccountBalanceAfter].
 * Asset detail UI uses [AssetBillBalanceHistory.computeBalanceAfterByBillId] instead.
 */
object BillBalanceSnapshotService {

    suspend fun rebuildAllAssetSnapshots(db: AppDatabase) {
        db.assetDao().getAllAssetsList().forEach { asset ->
            rebuildSnapshotsForAsset(db, asset.id)
        }
    }

    suspend fun rebuildSnapshotsForAsset(db: AppDatabase, assetId: Long) {
        val asset = db.assetDao().getAssetById(assetId) ?: return
        val bills = db.billDao().getBillsByAssetIdOrNameList(asset.id, asset.name)
        if (bills.isEmpty()) return

        val balanceAfterByBillId = AssetBillBalanceHistory.computeBalanceAfterByBillId(
            bills = bills,
            assetId = asset.id,
            assetName = asset.name,
            assetCurrency = asset.currency,
            currentBalance = asset.balance
        )

        val toUpdate = bills.mapNotNull { bill ->
            val balanceAfter = balanceAfterByBillId[bill.id] ?: return@mapNotNull null
            val patched = applySnapshotForAsset(bill, asset.id, asset.name, asset.currency, balanceAfter)
            if (patched != bill) patched else null
        }
        if (toUpdate.isNotEmpty()) {
            db.billDao().updateBills(toUpdate)
        }
    }

    fun balanceAfterForAsset(bill: Bill, assetId: Long, assetName: String): Double? {
        return when {
            AssetBillBalanceHistory.matchesSource(bill, assetId, assetName) -> bill.accountBalanceAfter
            AssetBillBalanceHistory.matchesTarget(bill, assetId, assetName) -> bill.toAccountBalanceAfter
            else -> null
        }
    }

    private fun applySnapshotForAsset(
        bill: Bill,
        assetId: Long,
        assetName: String,
        assetCurrency: String,
        balanceAfter: Double
    ): Bill {
        // P1-4: 按资产币种舍入，避免零小数货币写脏
        val rounded = BillAssetImpactService.roundMoneyForCurrency(balanceAfter, assetCurrency)
        return when {
            AssetBillBalanceHistory.matchesSource(bill, assetId, assetName) ->
                bill.copy(accountBalanceAfter = rounded)
            AssetBillBalanceHistory.matchesTarget(bill, assetId, assetName) ->
                bill.copy(toAccountBalanceAfter = rounded)
            else -> bill
        }
    }
}
