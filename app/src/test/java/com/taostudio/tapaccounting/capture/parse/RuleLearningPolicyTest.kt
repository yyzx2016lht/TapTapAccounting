package com.taostudio.tapaccounting.capture.parse

import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLearningPolicyTest {

    @Test
    fun `short ai keywords use exact match`() {
        assertEquals(
            AccountingRule.MatchMode.EXACT,
            RuleLearningPolicy.preferredMatchMode("苹果"),
        )
        assertEquals(
            AccountingRule.MatchMode.SUBSTRING,
            RuleLearningPolicy.preferredMatchMode("星巴克"),
        )
    }

    @Test
    fun `user rule is protected from being overwritten by ai`() {
        val userRule = AccountingRule(
            id = 1,
            keyword = "咖啡",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "饮品 - 咖啡",
            source = AccountingRule.Source.USER,
        )
        val decision = RuleLearningPolicy.decideUnified(
            listOf(userRule),
            keyword = "咖啡",
            targetCategoryName = "餐饮 - 咖啡",
        )
        assertEquals(RuleLearningPolicy.UnifiedDecision.SkipProtected, decision)
    }

    @Test
    fun `seed and import rules are protected from being overwritten by ai`() {
        val seedRule = AccountingRule(
            id = 2,
            keyword = "地铁",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "交通 - 地铁",
            source = AccountingRule.Source.SEED,
        )
        val importRule = AccountingRule(
            id = 3,
            keyword = "打车",
            matchField = AccountingRule.MatchField.ANY,
            targetCategory = "交通 - 打车",
            source = AccountingRule.Source.IMPORT,
        )

        assertEquals(
            RuleLearningPolicy.UnifiedDecision.SkipProtected,
            RuleLearningPolicy.decideUnified(listOf(seedRule), "地铁", "其他分类")
        )
        assertEquals(
            RuleLearningPolicy.UnifiedDecision.SkipProtected,
            RuleLearningPolicy.decideUnified(listOf(importRule), "打车", "其他分类")
        )
    }

    @Test
    fun `same keyword and same category skips learning`() {
        val aiRule = AccountingRule(
            id = 4,
            keyword = "拿铁",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "餐饮 - 咖啡",
            source = AccountingRule.Source.AI,
        )
        val decision = RuleLearningPolicy.decideUnified(
            listOf(aiRule),
            keyword = "拿铁",
            targetCategoryName = "餐饮 - 咖啡",
        )
        assertEquals(RuleLearningPolicy.UnifiedDecision.SkipSameCategory, decision)
    }

    @Test
    fun `same keyword with different category updates ai rule`() {
        val aiRule = AccountingRule(
            id = 5,
            keyword = "拿铁",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "餐饮 - 咖啡",
            source = AccountingRule.Source.AI,
        )
        val decision = RuleLearningPolicy.decideUnified(
            listOf(aiRule),
            keyword = "拿铁",
            targetCategoryName = "饮品 - 奶茶",
        )
        assertTrue(decision is RuleLearningPolicy.UnifiedDecision.Update)
        val update = decision as RuleLearningPolicy.UnifiedDecision.Update
        assertEquals(5L, update.rule.id)
    }

    @Test
    fun `new keyword inserts ai rule`() {
        val decision = RuleLearningPolicy.decideUnified(
            emptyList(),
            keyword = "喜茶",
            targetCategoryName = "饮品 - 奶茶",
        )
        assertEquals(RuleLearningPolicy.UnifiedDecision.Insert, decision)
    }

    @Test
    fun `leafCategoryName extracts last segment`() {
        assertEquals("水果", RuleLearningPolicy.leafCategoryName("吃的 - 水果"))
        assertEquals("咖啡", RuleLearningPolicy.leafCategoryName("餐饮 > 饮品 / 咖啡"))
        assertEquals("其他", RuleLearningPolicy.leafCategoryName("其他"))
    }

    @Test
    fun `short note produces direct keyword without calling ai`() {
        val keyword = com.taostudio.tapaccounting.RuleKeywordPolicy.directKeywordFromNote("苹果")
        assertEquals("苹果", keyword)
        val coffee = com.taostudio.tapaccounting.RuleKeywordPolicy.directKeywordFromNote("瑞幸咖啡")
        assertEquals("瑞幸咖啡", coffee)
    }

    @Test
    fun `rule learning off mode constant check`() {
        assertEquals("OFF", com.taostudio.tapaccounting.capture.data.CapturePrefs.RULE_LEARN_OFF)
        assertEquals("AUTO", com.taostudio.tapaccounting.capture.data.CapturePrefs.RULE_LEARN_AUTO)
        assertEquals("ASK", com.taostudio.tapaccounting.capture.data.CapturePrefs.RULE_LEARN_ASK)
    }
}
