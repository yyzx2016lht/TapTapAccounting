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

    @Query("SELECT * FROM capture_events ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<CaptureEvent>

    @Query("SELECT COUNT(*) FROM capture_events")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM capture_events WHERE matched = 1")
    suspend fun countMatched(): Int
}
