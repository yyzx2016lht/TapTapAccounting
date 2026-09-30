package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.DeletedBill

@Dao
interface DeletedBillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deletedBill: DeletedBill): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(deletedBills: List<DeletedBill>)

    @Delete
    suspend fun delete(deletedBill: DeletedBill)

    @Delete
    suspend fun delete(deletedBills: List<DeletedBill>)

    @Query("SELECT * FROM deleted_bills ORDER BY deletedAt DESC")
    suspend fun getAllDeletedBills(): List<DeletedBill>

    @Query("SELECT * FROM deleted_bills WHERE id = :id LIMIT 1")
    suspend fun getDeletedBillById(id: Long): DeletedBill?

    @Query("DELETE FROM deleted_bills WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM deleted_bills WHERE originalBillId IN (:ids) ORDER BY deletedAt DESC")
    suspend fun getByOriginalBillIds(ids: List<Long>): List<DeletedBill>

    @Query("DELETE FROM deleted_bills")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM deleted_bills")
    suspend fun getCount(): Int

    /**
     * 合并重复分类时，把回收站里挂在被并入分类下的账单改挂到保留的那个。
     *
     * `deleted_bills.categoryId` **没有外键**，所以不跟着改的话会留下悬空 id：
     * 还原时既进不了分类预算，又永远挂在已经不存在的分类名下。
     */
    @Query("UPDATE deleted_bills SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun migrateCategoryId(oldCategoryId: Long, newCategoryId: Long)
}

