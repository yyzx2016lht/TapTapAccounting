package com.taostudio.tapaccounting.capture.rules

import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一分类与记账规则引擎。
 *
 * 统一处理待记备注匹配与 AI 记账规则覆盖。
 */
interface AccountingRuleEngine {
    suspend fun matchNote(context: Context, note: String?, merchant: String? = null): RuleMatch?
    suspend fun matchAiBill(context: Context, userInput: String?, remarks: String?): RuleMatch?
    suspend fun matchAllNote(context: Context, note: String?, merchant: String? = null): List<RuleMatch>
    fun matchPromptRules(rules: List<AccountingRule>, text: String?): List<AccountingRule>
    fun invalidateCache() {}

    companion object : AccountingRuleEngine by DefaultAccountingRuleEngine {
        val INSTANCE: AccountingRuleEngine get() = DefaultAccountingRuleEngine
    }
}

object DefaultAccountingRuleEngine : AccountingRuleEngine {

    @Volatile
    private var cachedRules: List<AccountingRule>? = null

    override fun invalidateCache() {
        cachedRules = null
    }

    override suspend fun matchNote(context: Context, note: String?, merchant: String?): RuleMatch? {
        val rules = loadRules(context)
        return matchNoteWithRules(rules, note, merchant)
    }

    override suspend fun matchAiBill(context: Context, userInput: String?, remarks: String?): RuleMatch? {
        val rules = loadRules(context)
        return matchAiBillWithRules(rules, userInput, remarks)
    }

    override suspend fun matchAllNote(context: Context, note: String?, merchant: String?): List<RuleMatch> {
        val rules = loadRules(context)
        return matchAllNoteWithRules(rules, note, merchant)
    }

    private suspend fun loadRules(context: Context): List<AccountingRule> = withContext(Dispatchers.IO) {
        cachedRules ?: AppDatabase.getDatabase(context).accountingRuleDao().listEnabled().also {
            cachedRules = it
        }
    }

    /**
     * 规则优先级比较器：
     * sort ASC -> source (USER > IMPORT/SEED > AI) -> id ASC
     */
    val ruleComparator = Comparator<AccountingRule> { a, b ->
        var cmp = a.sort.compareTo(b.sort)
        if (cmp != 0) return@Comparator cmp
        cmp = sourcePriority(a.source).compareTo(sourcePriority(b.source))
        if (cmp != 0) return@Comparator cmp
        a.id.compareTo(b.id)
    }

    /**
     * 实际命中排序：保留用户 sort 优先级，再让“完全一致 > 长关键词 > 短关键词”胜出。
     * 不改变规则列表页的 USER > SEED/IMPORT > AI 展示顺序。
     */
    private val matchRuleComparator = Comparator<AccountingRule> { a, b ->
        var cmp = a.sort.compareTo(b.sort)
        if (cmp != 0) return@Comparator cmp
        cmp = matchModePriority(a.matchMode).compareTo(matchModePriority(b.matchMode))
        if (cmp != 0) return@Comparator cmp
        cmp = b.keyword.trim().length.compareTo(a.keyword.trim().length)
        if (cmp != 0) return@Comparator cmp
        cmp = sourcePriority(a.source).compareTo(sourcePriority(b.source))
        if (cmp != 0) return@Comparator cmp
        a.id.compareTo(b.id)
    }

    private fun matchModePriority(mode: String?): Int = when (mode) {
        AccountingRule.MatchMode.EXACT -> 0
        AccountingRule.MatchMode.ALL_TOKENS -> 1
        else -> 2
    }

    fun sourcePriority(source: String): Int = when (source) {
        AccountingRule.Source.USER -> 1
        AccountingRule.Source.IMPORT, AccountingRule.Source.SEED -> 2
        AccountingRule.Source.AI -> 3
        else -> 4
    }

    /**
     * 文本匹配判定。
     * - isRegex == true: 正则表达式匹配（忽略大小写）
     * - matchMode == ALL_TOKENS: 空格分隔的所有 token 必须全部出现在文本中（忽略大小写）
     * - matchMode == SUBSTRING: 子串包含（忽略大小写）
     */
    fun matchesText(rule: AccountingRule, text: String): Boolean {
        if (text.isEmpty() || rule.keyword.isBlank()) return false
        return if (rule.isRegex) {
            runCatching {
                Regex(rule.keyword, RegexOption.IGNORE_CASE).containsMatchIn(text)
            }.getOrDefault(false)
        } else {
            when (rule.matchMode) {
                AccountingRule.MatchMode.EXACT -> {
                    text.trim().equals(rule.keyword.trim(), ignoreCase = true)
                }
                AccountingRule.MatchMode.ALL_TOKENS -> {
                    val tokens = rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.isEmpty()) false
                    else tokens.all { token -> text.contains(token, ignoreCase = true) }
                }
                else -> {
                    text.contains(rule.keyword, ignoreCase = true)
                }
            }
        }
    }

    /**
     * 待记/收件箱备注规则单条匹配。
     */
    fun matchNoteWithRules(rules: List<AccountingRule>, note: String?, merchant: String? = null): RuleMatch? {
        return matchAllNoteWithRules(rules, note, merchant).firstOrNull()
    }

    /**
     * 待记/收件箱备注匹配（返回全部命中规则，按优先级排序）。
     *
     * 匹配字段：
     * 1. 优先使用 note 匹配 matchField 在 NOTE、ANY、REMARK 的规则。
     * 2. 若提供了 merchant，且商户规则匹配 matchField 在 MERCHANT、ANY 的规则，追加在后（若 note 未命中则商户规则成为第一命中）。
     */
    fun matchAllNoteWithRules(rules: List<AccountingRule>, note: String?, merchant: String? = null): List<RuleMatch> {
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val cleanMerchant = merchant?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanNote == null && cleanMerchant == null) return emptyList()

        val sortedRules = rules.filter { it.enabled }.sortedWith(matchRuleComparator)
        val result = mutableListOf<RuleMatch>()
        val matchedRuleIds = mutableSetOf<Long>()

        // 1. 优先按备注匹配
        if (cleanNote != null) {
            for (rule in sortedRules) {
                if (rule.matchField == AccountingRule.MatchField.NOTE ||
                    rule.matchField == AccountingRule.MatchField.ANY
                ) {
                    if (matchesText(rule, cleanNote)) {
                        result.add(
                            RuleMatch(
                                ruleId = rule.id,
                                keyword = rule.keyword,
                                categoryName = rule.targetCategory,
                                targetType = rule.targetType,
                                targetAccount1 = rule.targetAccount1,
                                targetAccount2 = rule.targetAccount2,
                                source = rule.source,
                                matchedText = cleanNote,
                            )
                        )
                        matchedRuleIds.add(rule.id)
                    }
                }
            }
        }

        // 2. 若提供商户，匹配商户规则
        if (cleanMerchant != null) {
            for (rule in sortedRules) {
                if (rule.id in matchedRuleIds) continue
                if (rule.matchField == AccountingRule.MatchField.MERCHANT ||
                    rule.matchField == AccountingRule.MatchField.ANY
                ) {
                    if (matchesText(rule, cleanMerchant)) {
                        result.add(
                            RuleMatch(
                                ruleId = rule.id,
                                keyword = rule.keyword,
                                categoryName = rule.targetCategory,
                                targetType = rule.targetType,
                                targetAccount1 = rule.targetAccount1,
                                targetAccount2 = rule.targetAccount2,
                                source = rule.source,
                                matchedText = cleanMerchant,
                            )
                        )
                        matchedRuleIds.add(rule.id)
                    }
                }
            }
        }

        return result
    }

    /**
     * AI 记账结果覆盖规则匹配。
     *
     * 匹配字段在 REMARK、USER_INPUT、ANY、NOTE。
     * remarks 优先匹配；若未命中再匹配 userInput。
     */
    /**
     * 主记账提示词专用：只返回用户/AI 学习、且适合主记账的规则。
     * 和 [matchAiBillWithRules] 使用同一套 matchField/matchMode/isRegex，避免提示词与覆盖不一致。
     */
    override fun matchPromptRules(rules: List<AccountingRule>, text: String?): List<AccountingRule> {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        return rules.filter { rule ->
            rule.enabled &&
                (rule.source == AccountingRule.Source.USER || rule.source == AccountingRule.Source.AI) &&
                (rule.matchField == AccountingRule.MatchField.REMARK ||
                    rule.matchField == AccountingRule.MatchField.USER_INPUT ||
                    rule.matchField == AccountingRule.MatchField.ANY) &&
                matchesText(rule, clean)
        }
    }

    fun matchAiBillWithRules(rules: List<AccountingRule>, userInput: String?, remarks: String?): RuleMatch? {
        val cleanRemarks = remarks?.trim()?.takeIf { it.isNotEmpty() }
        val cleanUserInput = userInput?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanRemarks == null && cleanUserInput == null) return null

        // 主记账只吃用户/AI 学习的习惯规则；待记 NOTE、导入/预置规则不参与，避免改变原记账行为。
        val sortedRules = rules.filter {
            it.enabled && (it.source == AccountingRule.Source.USER || it.source == AccountingRule.Source.AI)
        }.sortedWith(ruleComparator)

        // 1. remarks 优先：匹配 REMARK, ANY
        if (cleanRemarks != null) {
            for (rule in sortedRules) {
                if (rule.matchField == AccountingRule.MatchField.REMARK ||
                    rule.matchField == AccountingRule.MatchField.ANY
                ) {
                    if (matchesText(rule, cleanRemarks)) {
                        return RuleMatch(
                            ruleId = rule.id,
                            keyword = rule.keyword,
                            categoryName = rule.targetCategory,
                            targetType = rule.targetType,
                            targetAccount1 = rule.targetAccount1,
                            targetAccount2 = rule.targetAccount2,
                            source = rule.source,
                            matchedText = cleanRemarks,
                        )
                    }
                }
            }
        }

        // 2. userInput 兜底：匹配 USER_INPUT, ANY, REMARK
        if (cleanUserInput != null) {
            for (rule in sortedRules) {
                if (rule.matchField == AccountingRule.MatchField.USER_INPUT ||
                    rule.matchField == AccountingRule.MatchField.ANY ||
                    rule.matchField == AccountingRule.MatchField.REMARK
                ) {
                    if (matchesText(rule, cleanUserInput)) {
                        return RuleMatch(
                            ruleId = rule.id,
                            keyword = rule.keyword,
                            categoryName = rule.targetCategory,
                            targetType = rule.targetType,
                            targetAccount1 = rule.targetAccount1,
                            targetAccount2 = rule.targetAccount2,
                            source = rule.source,
                            matchedText = cleanUserInput,
                        )
                    }
                }
            }
        }

        return null
    }
}
