package com.taostudio.tapaccounting.capture.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 支付渠道名称 -> 敲敲记账资产名称映射规则（对标自动记账 AssetsMapModel）。
 */
@Entity(
    tableName = "asset_mapping_rules",
    indices = [Index(value = ["pattern"], unique = true)],
)
data class AssetMappingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Hook 抓到的原始支付方式或匹配模式，例如 "零钱"、"招商银行储蓄卡(4852)"。 */
    val pattern: String,
    /** 映射到的敲敲记账资产名称，例如 "微信"、"招商银行"。 */
    val targetAssetName: String,
    val isRegex: Boolean = false,
    val sort: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface AssetMappingDao {
    @Query("SELECT * FROM asset_mapping_rules ORDER BY sort ASC, id DESC")
    suspend fun listAll(): List<AssetMappingRule>

    @Query("SELECT * FROM asset_mapping_rules WHERE enabled = 1 ORDER BY sort ASC, id DESC")
    suspend fun listEnabled(): List<AssetMappingRule>

    @Query("SELECT * FROM asset_mapping_rules WHERE pattern = :pattern LIMIT 1")
    suspend fun findByPattern(pattern: String): AssetMappingRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AssetMappingRule): Long

    @Update
    suspend fun update(rule: AssetMappingRule): Int

    @Query("DELETE FROM asset_mapping_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM asset_mapping_rules")
    suspend fun count(): Int
}
