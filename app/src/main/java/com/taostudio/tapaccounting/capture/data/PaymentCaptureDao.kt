package com.taostudio.tapaccounting.capture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PaymentCaptureDao {

    /** 幂等插入：id 已存在时返回 -1，不覆盖已有记录。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: PaymentCapture): Long

    @Update
    suspend fun update(capture: PaymentCapture): Int

    @Query("SELECT * FROM payment_captures ORDER BY paidAt DESC, createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<PaymentCapture>

    @Query("SELECT * FROM payment_captures WHERE status IN (:statuses) ORDER BY paidAt DESC, createdAt DESC LIMIT :limit")
    suspend fun listByStatuses(statuses: List<String>, limit: Int): List<PaymentCapture>

    @Query("SELECT * FROM payment_captures WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PaymentCapture?

    @Query("SELECT * FROM payment_captures WHERE source = :source AND sourceTxnId = :sourceTxnId LIMIT 1")
    suspend fun findBySourceTxn(source: String, sourceTxnId: String): PaymentCapture?

    @Query("SELECT COUNT(*) FROM payment_captures WHERE status IN (:statuses)")
    suspend fun countByStatus(statuses: List<String>): Int

    @Query("UPDATE payment_captures SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: String, note: String?, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET assetName = :assetName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAssetName(id: String, assetName: String?, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET splitRequested = :splitRequested, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSplitRequested(id: String, splitRequested: Boolean, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET bookName = :bookName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBookName(id: String, bookName: String?, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET excludeFromStats = :excludeFromStats, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateExcludeFromStats(id: String, excludeFromStats: Boolean, updatedAt: Long): Int

    @Query("UPDATE payment_captures SET categoryName = :categoryName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCategoryName(id: String, categoryName: String?, updatedAt: Long): Int

    @Query(
        "UPDATE payment_captures SET note = :note, assetName = :assetName, " +
            "categoryName = :categoryName, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateDraftFields(
        id: String,
        note: String?,
        assetName: String?,
        categoryName: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM payment_captures
        WHERE amountCents = :amountCents
          AND paidAt BETWEEN :startTime AND :endTime
          AND status IN (:statuses)
        ORDER BY paidAt ASC, createdAt ASC
        """
    )
    suspend fun findPotentialDuplicates(
        amountCents: Long,
        startTime: Long,
        endTime: Long,
        statuses: List<String>,
    ): List<PaymentCapture>

    @Query("SELECT * FROM payment_captures WHERE groupId = :groupId ORDER BY createdAt ASC")
    suspend fun listMergedChildren(groupId: String): List<PaymentCapture>

    @Query("UPDATE payment_captures SET status = :status, groupId = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun unmerge(id: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM payment_captures WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
