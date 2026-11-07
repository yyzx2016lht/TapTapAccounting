package com.taostudio.tapaccounting.capture.rules

data class RuleMatch(
    val ruleId: Long,
    val keyword: String,
    val categoryName: String?,
    val targetType: Int?,
    val targetAccount1: String?,
    val targetAccount2: String?,
    val source: String,
    val matchedText: String,
)
