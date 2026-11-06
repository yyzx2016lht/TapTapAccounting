package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounting_rules",
    indices = [
        Index("keyword"),
        Index("matchField"),
        Index("enabled")
    ]
)
data class AccountingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val isRegex: Boolean = false,
    val matchField: String = MatchField.REMARK,
    val matchMode: String = MatchMode.SUBSTRING,
    val targetType: Int? = null,
    val targetCategory: String? = null,
    val targetBook: String? = null,
    val targetAccount1: String? = null,
    val targetAccount2: String? = null,
    val sort: Int = 0,
    val enabled: Boolean = true,
    val source: String = Source.USER,
    val learnedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    object MatchField {
        const val ANY = "ANY"
        const val NOTE = "NOTE"
        const val MERCHANT = "MERCHANT"
        const val REMARK = "REMARK"
        const val USER_INPUT = "USER_INPUT"
    }
    object MatchMode {
        const val SUBSTRING = "SUBSTRING"
        const val ALL_TOKENS = "ALL_TOKENS"
    }
    object Source {
        const val USER = "USER"
        const val IMPORT = "IMPORT"
        const val SEED = "SEED"
        const val AI = "AI"
    }
}
