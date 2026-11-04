package com.taostudio.tapaccounting.capture.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * 商户 / 商品 / 备注 -> 敲敲记账分类规则（对标自动记账 CategoryRule）。
 */
@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["keyword", "matchField"], unique = true)],
)
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 匹配关键词或正则表达式，如 "美宜佳"、"青菜"、"淘宝闪购"。 */
    val keyword: String,
    /** 匹配范围：ANY（商户/商品/备注任一）、MERCHANT（仅商户）、NOTE（仅备注）。 */
    val matchField: String = MATCH_ANY,
    val isRegex: Boolean = false,
    /** 目标分类名称（与敲敲记账一致，如 "吃的 - 买菜"、"喝的 - 饮料"）。 */
    val targetCategoryName: String,
    val targetBookName: String? = null,
    val sort: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MATCH_ANY = "ANY"
        const val MATCH_MERCHANT = "MERCHANT"
        const val MATCH_NOTE = "NOTE"
    }
}

@Dao
interface CategoryRuleDao {
    @Query("SELECT * FROM category_rules ORDER BY sort ASC, id DESC")
    suspend fun listAll(): List<CategoryRule>

    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY sort ASC, id DESC")
    suspend fun listEnabled(): List<CategoryRule>

    @Query("SELECT * FROM category_rules WHERE keyword = :keyword AND matchField = :matchField LIMIT 1")
    suspend fun findByKeywordAndField(keyword: String, matchField: String): CategoryRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategoryRule): Long

    @Update
    suspend fun update(rule: CategoryRule): Int

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM category_rules")
    suspend fun count(): Int
}
