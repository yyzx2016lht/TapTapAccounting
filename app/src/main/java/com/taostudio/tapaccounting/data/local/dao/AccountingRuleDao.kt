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

    @Query("DELETE FROM accounting_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM accounting_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM accounting_rules")
    suspend fun count(): Int
}
