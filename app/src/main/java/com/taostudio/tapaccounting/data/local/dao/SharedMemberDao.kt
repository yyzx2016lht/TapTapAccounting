package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SharedMember

@Dao
interface SharedMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(value: SharedMember): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<SharedMember>)
    @Query("SELECT * FROM shared_member ORDER BY ledgerId, joinOrder") suspend fun getAll(): List<SharedMember>
    @Query("SELECT * FROM shared_member WHERE ledgerId=:ledgerId ORDER BY joinOrder") suspend fun getByLedgerId(ledgerId: Long): List<SharedMember>
    @Query("SELECT * FROM shared_member WHERE ledgerId=:ledgerId AND isLocal=1 LIMIT 1") suspend fun getLocalMember(ledgerId: Long): SharedMember?
    @Query("SELECT * FROM shared_member WHERE ledgerId=:ledgerId AND memberId=:memberId LIMIT 1") suspend fun get(ledgerId: Long, memberId: String): SharedMember?
    @Query("UPDATE shared_member SET displayName=:displayName WHERE ledgerId=:ledgerId AND memberId=:memberId")
    suspend fun updateDisplayName(ledgerId: Long, memberId: String, displayName: String)
    @Query("DELETE FROM shared_member WHERE ledgerId=:ledgerId AND memberId=:memberId")
    suspend fun delete(ledgerId: Long, memberId: String)

    /** Used when a restore replaces the roster with the live remote member list. */
    @Query("DELETE FROM shared_member WHERE ledgerId=:ledgerId")
    suspend fun deleteByLedgerId(ledgerId: Long)
}
