package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus

@Dao
interface RecurringPatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: RecurringPattern): Long

    @Update
    suspend fun update(pattern: RecurringPattern)

    @Delete
    suspend fun delete(pattern: RecurringPattern)

    @Query("DELETE FROM recurring_patterns WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM recurring_patterns WHERE status = :status ORDER BY lastSeenAt DESC")
    suspend fun getByStatus(status: RecurringStatus): List<RecurringPattern>

    @Query("SELECT * FROM recurring_patterns ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<RecurringPattern>

    @Query("SELECT * FROM recurring_patterns WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun getByMerchantKey(merchantKey: String): RecurringPattern?

    /**
     * 按签名找已有的周期模式（去重用）。
     *
     * 分类的匹配**以 `categoryId` 优先**：`categoryName` 只是分类树里的展示路径快照
     * （"吃的 - 三餐"），分类一旦改名，快照就过期了。若仍按名字匹配，改名后同一个
     * 商户+分类会匹配不上旧记录，于是被当成新周期插入一条，留下两条重复的周期模式
     * （旧那条若已 CONFIRMED 不会被清理）。只有当两边都没有 id 时（历史数据）才退回按名字比。
     */
    @Query("""
        SELECT * FROM recurring_patterns
        WHERE merchantKey = :merchantKey
          AND bookName = :bookName
          AND billType = :billType
          AND billSubType = :billSubType
          AND (
              (:categoryId IS NOT NULL AND categoryId = :categoryId)
              OR (:categoryId IS NULL AND IFNULL(categoryName, '') = IFNULL(:categoryName, ''))
          )
          AND IFNULL(accountName, '') = IFNULL(:accountName, '')
          AND IFNULL(toAccountName, '') = IFNULL(:toAccountName, '')
        LIMIT 1
    """)
    suspend fun getBySignature(
        merchantKey: String,
        bookName: String,
        categoryName: String?,
        categoryId: Long?,
        accountName: String?,
        toAccountName: String,
        billType: Int,
        billSubType: Int
    ): RecurringPattern?

    /** 分类改名/换父级后，把挂在它下面的周期模式的展示名快照重算。 */
    @Query("""
        UPDATE recurring_patterns
        SET categoryName = :newName, updatedAt = :now
        WHERE categoryId = :categoryId
    """)
    suspend fun syncCategoryNameByCategoryId(categoryId: Long, newName: String, now: Long)

    /** 合并重复分类时，把挂在被并入分类下的周期模式改挂到保留的那个。 */
    @Query("UPDATE recurring_patterns SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun migrateCategoryId(oldCategoryId: Long, newCategoryId: Long)

    /**
     * 无 categoryId 的历史周期模式：按旧叶子名就地替换。
     * 覆盖裸叶子名、路径末尾、路径开头（祖先改名）三种形态。
     *
     * 按位置精确替换而非 `REPLACE(...)`：全局替换在分类名互为子串时会误伤
     * ——「餐 - 三餐」在「餐」改名后会变成「新餐 - 三新餐」。
     * 周期模式不会是退款账单（`RecurringBillDetector` 与 `matchNewBill` 都过滤了退款），
     * 所以这里不需要「退款：」前缀分支。
     */
    @Query("""
        UPDATE recurring_patterns
        SET categoryName = CASE
            WHEN categoryName = :oldLeaf
                THEN :newLeaf
            WHEN categoryName LIKE :oldLeaf || ' - %'
                THEN :newLeaf || SUBSTR(categoryName, LENGTH(:oldLeaf) + 1)
            ELSE SUBSTR(categoryName, 1, LENGTH(categoryName) - LENGTH(:oldLeaf)) || :newLeaf
        END,
        updatedAt = :now
        WHERE categoryId IS NULL
          AND categoryName IS NOT NULL
          AND (
              categoryName = :oldLeaf
              OR categoryName LIKE '% - ' || :oldLeaf
              OR categoryName LIKE :oldLeaf || ' - %'
          )
    """)
    suspend fun syncCategoryNameByOldName(oldLeaf: String, newLeaf: String, now: Long)

    @Query("SELECT * FROM recurring_patterns WHERE status = :status AND lastSeenAt < :before")
    suspend fun getStale(status: RecurringStatus, before: Long): List<RecurringPattern>

    @Query("DELETE FROM recurring_patterns")
    suspend fun deleteAll()
}
