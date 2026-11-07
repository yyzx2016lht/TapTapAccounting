package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bill_rule_provenance", indices = [Index("billId", unique = true), Index("captureId")])
data class BillRuleProvenance(
    @PrimaryKey val billId: Long,
    val captureId: String? = null,
    val source: String,          // MANUAL / RULE / AI
    val ruleId: Long? = null,
    val keyword: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    object Source {
        const val MANUAL = "MANUAL"
        const val RULE = "RULE"
        const val AI = "AI"
    }
}
