package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance

@Dao
interface BillRuleProvenanceDao {
    @Query("SELECT * FROM bill_rule_provenance WHERE billId = :billId LIMIT 1")
    suspend fun getByBillId(billId: Long): BillRuleProvenance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provenance: BillRuleProvenance)

    @Query("DELETE FROM bill_rule_provenance WHERE billId = :billId")
    suspend fun deleteByBillId(billId: Long)

    @Query("DELETE FROM bill_rule_provenance WHERE captureId = :captureId")
    suspend fun deleteByCaptureId(captureId: String)
}
