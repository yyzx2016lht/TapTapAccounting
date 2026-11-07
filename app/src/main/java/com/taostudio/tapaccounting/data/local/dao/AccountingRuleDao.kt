package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taostudio.tapaccounting.data.local.entity.AccountingRule

@Dao
interface AccountingRuleDao {
    @Query("SELECT * FROM accounting_rules ORDER BY sort ASC, id ASC")
    suspend fun listAll(): List<AccountingRule>

    @Query("SELECT * FROM accounting_rules WHERE enabled = 1 ORDER BY sort ASC, id ASC")
    suspend fun listEnabled(): List<AccountingRule>

    /** 主记账提示词/覆盖专用：只取用户或 AI 学习的习惯规则，避免待记/导入/预置规则污染提示词。 */
    @Query(
        "SELECT * FROM accounting_rules WHERE enabled = 1 " +
            "AND source IN ('USER','AI') " +
            "AND matchField IN ('REMARK','USER_INPUT','ANY') " +
            "ORDER BY sort ASC, id ASC"
    )
    suspend fun listEnabledForAiAccounting(): List<AccountingRule>

    @Query("SELECT * FROM accounting_rules WHERE source = :source ORDER BY sort ASC, id ASC")
    suspend fun listBySource(source: String): List<AccountingRule>

    @Query("SELECT * FROM accounting_rules WHERE keyword = :keyword LIMIT 1")
    suspend fun findByKeyword(keyword: String): AccountingRule?

    @Query("SELECT * FROM accounting_rules WHERE keyword = :keyword COLLATE NOCASE")
    suspend fun findAllByKeyword(keyword: String): List<AccountingRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AccountingRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<AccountingRule>): List<Long>

    @Update
    suspend fun update(rule: AccountingRule)

    @Query("UPDATE accounting_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM accounting_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM accounting_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM accounting_rules")
    suspend fun count(): Int
}
