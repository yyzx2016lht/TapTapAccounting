package com.taostudio.tapaccounting.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.dao.AssetDao
import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.entity.Asset

class AssetRepository(
    private val assetDao: AssetDao,
    private val billDao: BillDao? = null,
    private val appDatabase: AppDatabase? = null
) {

    val allAssets: Flow<List<Asset>> = assetDao.getAllAssets()

    suspend fun getAllAssetsList(): List<Asset> {
        return assetDao.getAllAssetsList()
    }

    suspend fun addAsset(asset: Asset): Long {
        return assetDao.insertAsset(asset)
    }

    suspend fun updateAsset(asset: Asset) {
        assetDao.updateAsset(asset)
    }

    suspend fun getAssetById(id: Long): Asset? {
        return assetDao.getAssetById(id)
    }

    suspend fun updateBalance(id: Long, amount: Double) {
        assetDao.updateBalance(id, amount)
    }

    suspend fun deleteAssetWithCleanup(asset: Asset) {
        // P2-19: 缺 billDao/appDatabase 时禁止静默跳过清理
        val dao = requireNotNull(billDao) { "AssetRepository requires billDao" }
        val db = requireNotNull(appDatabase) { "AssetRepository requires appDatabase" }
        val deletedNameLabel = "${asset.name}（已删除）"

        if (db != null) {
            db.withTransaction {
                dao.backfillAssetLinksByName()
                // 删除资产时保留全部历史账单，仅解除资产关联并保留账户名快照（加“已删除”标记）。
                dao.clearAccountId(asset.id)
                dao.clearToAccountId(asset.id)
                dao.markDeletedAccountName(asset.name, deletedNameLabel)
                dao.markDeletedToAccountName(asset.name, deletedNameLabel)
                assetDao.deleteAsset(asset)
            }
            return
        }

        dao.backfillAssetLinksByName()
        // 无事务兜底路径同样不删除任何账单，仅解除关联。
        dao.clearAccountId(asset.id)
        dao.clearToAccountId(asset.id)
        dao.markDeletedAccountName(asset.name, deletedNameLabel)
        dao.markDeletedToAccountName(asset.name, deletedNameLabel)
        assetDao.deleteAsset(asset)
    }
}

