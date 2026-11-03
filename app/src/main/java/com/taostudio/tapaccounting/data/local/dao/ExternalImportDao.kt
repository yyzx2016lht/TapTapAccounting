package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.ExternalImport

@Dao
interface ExternalImportDao {

    @Query("SELECT * FROM external_imports WHERE captureId = :captureId LIMIT 1")
    suspend fun getByCaptureId(captureId: String): ExternalImport?

    @Query("SELECT * FROM external_imports WHERE captureId IN (:captureIds)")
    suspend fun getByCaptureIds(captureIds: List<String>): List<ExternalImport>

    @Query("DELETE FROM external_imports WHERE captureId = :captureId")
    suspend fun deleteByCaptureId(captureId: String)

    /**
     * 写回执。返回 -1 表示该 captureId 已存在（同一事务内预检已覆盖绝大多数情况，
     * 这里兜住并发/重入），调用方据此判定重复，而不是覆盖旧回执。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: ExternalImport): Long
}
