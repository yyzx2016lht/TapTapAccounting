package com.taostudio.tapaccounting

import android.content.Context
import com.google.gson.Gson
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.AiRule as DbAiRule

internal fun buildAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false
): String {
    // System prompt 尽量静态（规则文本），动态数据通过 user message 表达 → 提升缓存命中率
    var prompt = accountingBasePrompt(promptContext.assetFeatureEnabled)
    val hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)

    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildCategoryRulesCompact(hasSecondLevel)
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAccountingDateRule()

    prompt += if (!promptContext.assetFeatureEnabled) {
        AIPrompts.buildNoAssetAccountingRule()
    } else {
        AIPrompts.buildExecutionModeRule()
    }

    prompt += AIPrompts.buildAmountSplitAndMergeRule()
    prompt += AIPrompts.buildOutputJsonRuleWithTargetFields()

    return prompt
}

internal fun buildScreenAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false,
    quickScreenMode: Boolean = false
): String {
    // 统一使用同一个图片记账 prompt，输出格式差异由 taskInstruction / user message 控制
    // System prompt 完全静态（不含任何动态数据），数据通过 user message 注入
    var prompt = AIPrompts.IMAGE_ACCOUNTING_PROMPT
    if (quickScreenMode) {
        prompt += AIPrompts.SCREEN_CAPTURE_DIRECT_PROMPT_ADDON
    }

    // 同名商品必须合并累加，不能只保留其中一行（订单详情页常见同款连排多行）
    prompt += AIPrompts.buildSameItemMergeRule()

    // 动态规则对两个场景通用，且不与基础 prompt 冲突
    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    val hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    prompt += AIPrompts.buildCategoryRulesCompact(hasSecondLevel)
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildAccountingDateRule()
    prompt += AIPrompts.buildVisualPaymentMethodRule(
        promptContext.assetFeatureEnabled,
        promptContext.assetNames
    )

    // 信用卡还款补充（动态）
    val creditCardNames = creditCardNames(promptContext)
    if (promptContext.assetFeatureEnabled && creditCardNames.isNotEmpty()) {
        prompt += AIPrompts.buildRepaymentRule(creditCardNames, true)
    }

    return prompt
}

private fun accountingBasePrompt(assetFeatureEnabled: Boolean): String =
    if (assetFeatureEnabled) {
        AIService.MULTI_BILL_PROMPT_DEFAULT
    } else {
        AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT
    }

internal fun buildPromptCorrectionBlock(
    matchedRules: List<DbAiRule>,
    includeCategory: Boolean = true,
    includeAccount: Boolean = true
): String {
    if (matchedRules.isEmpty()) return ""
    return buildString {
        appendLine("\n【本地记账习惯修正规则（高优先）】以下规则来自用户自定义，命中后优先遵守：")
        matchedRules.forEachIndexed { index, rule ->
            append("${index + 1}. 关键词：${rule.keyword}")
            rule.targetType?.let { append("；type=$it") }
            if (includeCategory) rule.targetCategory?.takeIf { it.isNotBlank() }?.let {
                append("；目标分类名称=$it（从候选中选择对应 category_id）")
            }
            if (includeAccount) {
                rule.targetAccount1?.takeIf { it.isNotBlank() }?.let { append("；asset_name=$it") }
                rule.targetAccount2?.takeIf { it.isNotBlank() }?.let { append("；to_asset_name=$it") }
            }
            appendLine()
        }
        if (includeAccount) {
            appendLine("命中规则时：优先按规则纠正类型、分类、账户；若规则未覆盖的字段拿不准，可留空，不要猜。")
        } else {
            appendLine("命中规则时：优先按规则纠正类型、分类；当前为无资产模式，禁止要求用户补充账户。")
        }
    }
}

internal fun buildCategoryHierarchyHint(candidates: List<String>): String {
    val parents = candidates.filterNot { it.contains("/::/") || it.contains(" - ") || it.contains(" > ") }
    val children = candidates.filter { it.contains("/::/") || it.contains(" - ") || it.contains(" > ") }
    return buildString {
        if (parents.isNotEmpty()) append("一级分类：${parents.joinToString("、")}。")
        if (children.isNotEmpty()) append("可选二级分类：${children.joinToString("、")}。")
    }
}

internal fun hasSecondLevelCategories(
    expenseCats: List<String>,
    incomeCats: List<String>
): Boolean {
    // Category options may be rendered in different separators:
    // - Prompt candidates are currently built as "一级 - 二级"
    // - Some older paths use "/::/" as an internal separator
    // - Some UI/export paths may use " > "
    // We treat any visible hierarchy separator as second-level existence.
    fun hasHierarchy(list: List<String>): Boolean =
        list.any { it.contains(" - ") || it.contains("/::/") || it.contains(" > ") }
    return hasHierarchy(expenseCats) || hasHierarchy(incomeCats)
}

private fun creditCardNames(promptContext: AIAccountingPromptContext): List<String> =
    promptContext.dbAssets
        .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
        .map { it.name }

/**
 * 构建动态数据块，注入到 user message 开头。
 * 包含资产、分类、账本、币种、时间等每次请求可能变化的数据。
 */
internal fun buildDataBlock(promptContext: AIAccountingPromptContext): String = buildString {
    appendLine("【数据上下文】")
    if (promptContext.assetFeatureEnabled && promptContext.assetInfoList.isNotEmpty()) {
        appendLine("资产库：${Gson().toJson(promptContext.assetInfoList)}")
    }
    appendLine("支出分类候选：${Gson().toJson(buildPromptCategoryOptions(promptContext.expenseCats, EXPENSE_CATEGORY_ID_PREFIX))}")
    appendLine("收入分类候选：${Gson().toJson(buildPromptCategoryOptions(promptContext.incomeCats, INCOME_CATEGORY_ID_PREFIX))}")
    if (promptContext.availableBooks.isNotEmpty()) {
        appendLine("账本候选：${Gson().toJson(buildPromptBookOptions(promptContext.availableBooks))}")
    }
    appendLine("币种列表：${Gson().toJson(promptContext.currencies)}")
    appendLine("当前时间：${promptContext.currentTimeStr}")
}

internal const val EXPENSE_CATEGORY_ID_PREFIX = "e"
internal const val INCOME_CATEGORY_ID_PREFIX = "i"
internal const val BOOK_ID_PREFIX = "b"

internal data class PromptCategoryOption(
    val id: String,
    val name: String
)

internal data class PromptBookOption(
    val id: String,
    val name: String
)

internal fun buildPromptCategoryOptions(categories: List<String>, prefix: String): List<PromptCategoryOption> =
    categories.mapIndexed { index, name -> PromptCategoryOption(id = "$prefix$index", name = name) }

internal fun buildPromptBookOptions(books: List<String>): List<PromptBookOption> =
    books.mapIndexed { index, name -> PromptBookOption(id = "$BOOK_ID_PREFIX$index", name = name) }

internal fun buildAccountingUserPrompt(
    userInput: String,
    promptContext: AIAccountingPromptContext,
    matchedPromptRules: List<DbAiRule>,
    assetFeatureEnabled: Boolean,
    isFromChat: Boolean = false,
    aiName: String = ""
): String = buildString {
    // 数据上下文注入到 user message 开头，system prompt 保持静态 → 缓存友好
    append(buildDataBlock(promptContext))
    appendLine()
    // Chat 场景的差异通过 user message 表达，不污染 system prompt → 缓存互通
    if (isFromChat) {
        appendLine("【场景】对话记账模式。你需要理解对话上下文中的指代（如「同上」「刚才那笔」「再来一笔」），并在成功记账后输出 assistant_reply 字段作为对用户的自然语言回复。纯闲聊、追问、寒暄返回 no_bill + reply。")
        if (aiName.isNotBlank()) {
            appendLine("【你的名字】$aiName")
        }
        appendLine("【对话记账输出格式】成功记账：{\"bills\":[...], \"assistant_reply\":\"一句自然的中文回复\"}；非记账/纯闲聊：{\"no_bill\":true, \"reply\":\"...\"}。assistant_reply 必须是直接对用户说的话，不要输出场景标签、英文状态词、JSON 或内部指令。")
        appendLine("【历史约束·必须遵守】历史里以「过去已入账」「已记账」开头的内容只是过去轮次摘要，不是当前任务结果。只要当前【用户输入】包含新的消费/收入/票据/金额信息，就必须重新提取并输出 bills；禁止只回复「已经记账/已记好」且 no_bill=true，也禁止把历史 JSON 原文抄进 reply。")
        appendLine("【历史金额·禁止当锚点】历史摘要里出现过的金额、单价只允许用于理解指代（「同上」「刚才那笔」「再来一笔一样的」）。严禁拿它推断本轮未明确给出的金额、单价，或据此对用户未拆分的组合总价做分摊。本轮金额只能来自【用户输入】中明确写出的数字。")
    } else {
        appendLine("【场景】独立记账模式。直接输出账单 JSON，不需要 assistant_reply。")
    }
    if (matchedPromptRules.isNotEmpty()) {
        append(
            buildPromptCorrectionBlock(
                matchedPromptRules,
                includeCategory = true,
                includeAccount = assetFeatureEnabled
            )
        )
    }
    appendLine("【用户输入】")
    append(userInput)
}

internal fun buildScreenAccountingUserText(
    promptContext: AIAccountingPromptContext,
    taskInstruction: String,
    matchedPromptRules: List<DbAiRule> = emptyList()
): String = buildString {
    // 数据上下文注入到 user message 开头
    append(buildDataBlock(promptContext))
    if (matchedPromptRules.isNotEmpty()) {
        append(buildPromptCorrectionBlock(
            matchedPromptRules,
            includeCategory = true,
            includeAccount = promptContext.assetFeatureEnabled
        ))
    }
    appendLine()
    append(taskInstruction)
}
