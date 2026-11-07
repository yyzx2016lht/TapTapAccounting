package com.taostudio.tapaccounting.capture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CaptureEventDao {

    /** rawHash 唯一：重复原始数据只留一条。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: CaptureEvent): Long

    @Query("""
        UPDATE capture_events
        SET matched = :matched,
            ruleName = :ruleName,
            autoType = :autoType,
            localType = :localType,
            localSubType = :localSubType,
            amountCents = :amountCents,
            merchant = :merchant,
            channel = :channel
        WHERE id = :id
    """)
    suspend fun updateMatchResult(
        id: Long,
        matched: Boolean,
        ruleName: String?,
        autoType: String?,
        localType: Int?,
        localSubType: Int?,
        amountCents: Long?,
        merchant: String?,
        channel: String?,
    )

    @Query("""
        UPDATE capture_events
        SET matched = :matched,
            ruleName = :ruleName,
            autoType = :autoType,
            localType = :localType,
            localSubType = :localSubType,
            amountCents = :amountCents,
            merchant = :merchant,
            channel = :channel
        WHERE rawHash = :rawHash
    """)
    suspend fun updateMatchResultByHash(
        rawHash: String,
        matched: Boolean,
        ruleName: String?,
        autoType: String?,
        localType: Int?,
        localSubType: Int?,
        amountCents: Long?,
        merchant: String?,
        channel: String?,
    )

    @Query("SELECT * FROM capture_events ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<CaptureEvent>

    @Query("SELECT * FROM capture_events WHERE matched = 0 ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun listUnmatched(limit: Int): List<CaptureEvent>

    @Query("SELECT COUNT(*) FROM capture_events")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM capture_events WHERE matched = 1")
    suspend fun countMatched(): Int

    /** 原始报文只用于诊断/规则调试，保留最近 N 条即可，避免 JSON 无限增长。 */
    @Query(
        "DELETE FROM capture_events WHERE id NOT IN (" +
            "SELECT id FROM capture_events ORDER BY receivedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimToLatest(keep: Int): Int

    @Query("DELETE FROM capture_events")
    suspend fun deleteAll(): Int
}
