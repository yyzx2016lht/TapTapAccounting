package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPromptsTest {
    @Test
    fun intentRouterPromptContainsKeyElements() {
        val prompt = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT

        assertTrue(prompt.contains("BOOKKEEPING"))
        assertTrue(prompt.contains("GENERAL_CHAT"))
        assertFalse(prompt.contains("ACCOUNTING_CREATE"))
        assertFalse(prompt.contains("UNSUPPORTED_WRITE"))
        assertTrue(prompt.contains("intent"))
    }

    @Test
    fun categoryRulesRequireCandidateIdsWithoutInventedCategoryExamples() {
        val prompt = AIPrompts.buildCategoryRulesCompact(hasSecondLevel = false)

        assertTrue(prompt.contains("category_id"))
        assertTrue(prompt.contains("禁止自造 id 或分类名"))
        assertFalse(prompt.contains("酒店→住宿"))
        assertFalse(prompt.contains("软件/服务"))
    }

    @Test
    fun accountingDataBlockAddsRequestLocalCategoryIds() {
        val dataBlock = buildDataBlock(promptContext())

        assertTrue(dataBlock.contains("""{"id":"e0","name":"网费"}"""))
        assertTrue(dataBlock.contains("""{"id":"e1","name":"其它"}"""))
        assertTrue(dataBlock.contains("""{"id":"i0","name":"工资"}"""))
        assertTrue(dataBlock.contains("""{"id":"b0","name":"默认账本"}"""))
        assertTrue(dataBlock.contains("""{"id":"b1","name":"伙食账本"}"""))
    }

    @Test
    fun chatAccountingPromptIncludesCategoryIds() {
        val prompt = buildAccountingUserPrompt(
            userInput = "充话费50",
            promptContext = promptContext(),
            matchedPromptRules = emptyList(),
            assetFeatureEnabled = false,
            isFromChat = true
        )

        assertTrue(prompt.contains("对话记账模式"))
        assertTrue(prompt.contains("""{"id":"e0","name":"网费"}"""))
    }

    @Test
    fun screenAccountingPromptIncludesCategoryIds() {
        val prompt = buildScreenAccountingUserText(
            promptContext = promptContext(),
            taskInstruction = "识别图片中的账单"
        )

        assertTrue(prompt.contains("""{"id":"e0","name":"网费"}"""))
        assertTrue(prompt.contains("""{"id":"b0","name":"默认账本"}"""))
        assertTrue(prompt.contains("识别图片中的账单"))
    }

    @Test
    fun bookRuleRequiresCandidateIdOnEachExplicitlyTargetedBill() {
        val rule = AIPrompts.buildBookFieldRule(listOf("默认账本", "伙食账本"))

        assertTrue(rule.contains("`book_id`"))
        assertTrue(rule.contains("每条 bill"))
        assertTrue(rule.contains("未明确指定账本时"))
        assertTrue(rule.contains("禁止输出 `book_name`"))
        assertFalse(rule.contains("默认账本、伙食账本"))
    }

    @Test
    fun visionPromptsMergeDuplicateRowsAndSumTheirAmounts() {
        // 回归：订单详情页常把同款商品拆成多行（光明青柠棒冰 2.30 连排三行）。
        // 旧提示词写「不要合并同名商品」，模型执行成了「同名只留一行」——15 行只记了 12 行，少记 6.10 元。
        // 正确行为是合并成一条并累加金额，因此这里锁死两件事：不再出现旧口径、新规则必须要求累加。
        val visionPrompts = listOf(
            AIPrompts.IMAGE_ACCOUNTING_PROMPT,
            AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT,
            AIPrompts.receiptVisionUserInstruction(1),
            AIPrompts.receiptVisionUserInstruction(3)
        )
        visionPrompts.forEach { prompt ->
            assertFalse(
                "视觉提示词不能再要求保留同名多行，否则模型会丢掉重复行",
                prompt.contains("不要合并同名商品")
            )
        }

        val rule = AIPrompts.buildSameItemMergeRule()
        assertTrue(rule.contains("amount = 各行金额之和"))
        assertTrue(rule.contains("xN"))
        assertTrue(rule.contains("光明青柠棒冰 x3"))
        // 同价才合并、不同商品不合并，避免把无关行凑成一条
        assertTrue(rule.contains("单价不同"))
        assertTrue(rule.contains("商品名不同则绝不合并"))
    }

    private fun promptContext() = AIAccountingPromptContext(
        dbAssets = emptyList(),
        assetInfoList = emptyList(),
        assetNames = emptyList(),
        assetCurrencyMap = emptyMap(),
        expenseCats = listOf("网费", "其它"),
        incomeCats = listOf("工资"),
        currencies = listOf("CNY"),
        currentTimeStr = "2026-07-20 10:55:00",
        assetFeatureEnabled = false,
        availableBooks = listOf("默认账本", "伙食账本")
    )
}
