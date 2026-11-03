package com.taostudio.tapaccounting.capture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PaymentCaptureDao {

    /** 幂等插入：id 已存在时返回 -1，不覆盖已有记录。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: PaymentCapture): Long

    @Query("SELECT * FROM payment_captures ORDER BY paidAt DESC, createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<PaymentCapture>

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
}
