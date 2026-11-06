package com.taostudio.tapaccounting.capture.rules

import android.content.Context

interface AccountingRuleEngine {
    suspend fun matchNote(context: Context, note: String?, merchant: String? = null): RuleMatch?
    suspend fun matchAiBill(context: Context, userInput: String?, remarks: String?): RuleMatch?
    suspend fun matchAllNote(context: Context, note: String?, merchant: String? = null): List<RuleMatch>
}
