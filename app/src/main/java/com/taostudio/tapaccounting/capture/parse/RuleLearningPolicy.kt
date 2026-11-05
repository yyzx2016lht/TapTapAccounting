package com.taostudio.tapaccounting.capture.parse

import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CategoryRule
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * AI 规则学习的纯逻辑：判断是否该学、写到哪条规则、toast 用哪个二级分类名。
 *
 * 这里不碰数据库和网络，方便单测；实际入队/调用 AI/写库见 `bridge.RuleLearningManager`。
 */
internal object RuleLearningPolicy {

    /** 首次失败后最多再重试 2 次：总共 3 次尝试。 */
    const val MAX_FAILED_ATTEMPTS = 3

    /**
     * AI 学习的规则排在人工/内置/导入规则之后（listEnabled 按 sort ASC 排序），
     * 避免自动规则抢在用户手写规则前面命中。
     */
    const val AI_RULE_SORT = 10_000

    private val CATEGORY_PATH_SEPARATOR = Regex("""\s*(?:-|/|>|›|→)\s*""")
    private const val OTHER_CATEGORY = "其他"

    /** 只显示二级分类：完整路径取最后一段，"吃的 - 水果" -> "水果"。 */
    fun leafCategoryName(fullCategoryName: String): String {
        val cleaned = fullCategoryName.trim()
        if (cleaned.isEmpty()) return ""
        return cleaned.split(CATEGORY_PATH_SEPARATOR)
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: cleaned
    }

    /**
     * 触发条件：AI / MANUAL 分类 + 备注非空 + 分类非“其他” + 普通收支 + 非退款。
     *
     * 转账没有二级分类，退款/退款关联账单也不学习；重复入账由调用方过滤，不进入这里。
     */
    fun isEligible(capture: PaymentCapture): Boolean {
        if (capture.categorySource != CategorySource.AI && capture.categorySource != CategorySource.MANUAL) return false
        if (capture.note.isNullOrBlank()) return false
        val category = capture.categoryName?.trim().orEmpty()
        if (category.isEmpty()) return false
        if (leafCategoryName(category) == OTHER_CATEGORY) return false
        val type = capture.billType ?: return false
        if (type != LocalBillType.TYPE_EXPENSE && type != LocalBillType.TYPE_INCOME) return false
        if (capture.subType == LocalBillType.SUBTYPE_REFUND || capture.needsRefundLink) return false
        return true
    }

    sealed interface Decision {
        /** 已有 USER / SEED / IMPORT（或未知来源）规则：绝不覆盖。 */
        data object SkipProtected : Decision

        /** 已有同关键词的 AI 规则且分类相同：不重复写。 */
        data object SkipSameCategory : Decision

        /** 没有同名备注规则：新增 source=AI 的 NOTE 规则。 */
        data object Insert : Decision

        /** 已有同关键词 AI 规则但分类不同：更新目标分类。 */
        data class Update(val rule: CategoryRule) : Decision
    }

    /**
     * 规则写入优先级：
     * 1. 同名 USER/SEED/IMPORT 规则存在 → 跳过；
     * 2. 同名 AI 规则存在 → 同分类跳过，不同分类更新；
     * 3. 没有任何同名备注规则 → 新增 AI 规则。
     */
    fun decide(
        existingRules: List<CategoryRule>,
        keyword: String,
        targetCategoryName: String,
    ): Decision {
        val sameKeyword = existingRules.filter { it.keyword.equals(keyword, ignoreCase = true) }
        val noteRules = sameKeyword.filter {
            it.matchField == CategoryRule.MATCH_NOTE || it.matchField == CategoryRule.MATCH_ANY
        }
        if (noteRules.isEmpty()) return Decision.Insert
        if (noteRules.any { it.source != CategoryRule.SOURCE_AI }) return Decision.SkipProtected

        val aiRule = noteRules.first()
        return if (aiRule.targetCategoryName.trim() == targetCategoryName.trim()) {
            Decision.SkipSameCategory
        } else {
            Decision.Update(aiRule)
        }
    }

    fun newAiRule(
        keyword: String,
        targetCategoryName: String,
        learnedAt: Long,
    ): CategoryRule = CategoryRule(
        keyword = keyword,
        matchField = CategoryRule.MATCH_NOTE,
        isRegex = false,
        targetCategoryName = targetCategoryName,
        sort = AI_RULE_SORT,
        enabled = true,
        source = CategoryRule.SOURCE_AI,
        learnedAt = learnedAt,
    )

    fun toastText(keyword: String, fullCategoryName: String): String {
        val leaf = leafCategoryName(fullCategoryName).ifBlank { fullCategoryName.trim() }
        return "已记住：$keyword → $leaf"
    }
}
