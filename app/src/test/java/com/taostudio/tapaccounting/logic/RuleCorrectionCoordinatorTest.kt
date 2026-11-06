package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证规则修正协调器 (RuleCorrectionCoordinator) 的核心裁决与徽标映射逻辑 (T07)。
 */
class RuleCorrectionCoordinatorTest {

    // 1. 验证 BillDetailActivity 来源徽标映射逻辑
    @Test
    fun testResolveProvenanceBadgeText_manual() {
        val prov = BillRuleProvenance(billId = 1L, source = BillRuleProvenance.Source.MANUAL)
        val badge = RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov)
        assertEquals("用户自选", badge)
    }

    @Test
    fun testResolveProvenanceBadgeText_ruleWithName() {
        val prov = BillRuleProvenance(
            billId = 2L,
            source = BillRuleProvenance.Source.RULE,
            ruleId = 100L,
            keyword = "美团外卖"
        )
        val badge = RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov, ruleName = "美团外卖")
        assertEquals("规则命中：美团外卖", badge)
    }

    @Test
    fun testResolveProvenanceBadgeText_ruleFallbackToKeyword() {
        val prov = BillRuleProvenance(
            billId = 2L,
            source = BillRuleProvenance.Source.RULE,
            keyword = "肯德基"
        )
        val badge = RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov, ruleName = null)
        assertEquals("规则命中：肯德基", badge)
    }

    @Test
    fun testResolveProvenanceBadgeText_ruleWithoutName() {
        val prov = BillRuleProvenance(
            billId = 2L,
            source = BillRuleProvenance.Source.RULE
        )
        val badge = RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov, ruleName = "")
        assertEquals("规则命中", badge)
    }

    @Test
    fun testResolveProvenanceBadgeText_ai() {
        val prov = BillRuleProvenance(billId = 3L, source = BillRuleProvenance.Source.AI)
        val badge = RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov)
        assertEquals("AI 建议", badge)
    }

    @Test
    fun testResolveProvenanceBadgeText_unknownSourceReturnsNull() {
        val prov = BillRuleProvenance(billId = 4L, source = "SHORTCUT")
        assertNull(RuleCorrectionCoordinator.resolveProvenanceBadgeText(prov))
    }

    @Test
    fun testResolveProvenanceBadgeText_nullOrEmpty() {
        assertNull(RuleCorrectionCoordinator.resolveProvenanceBadgeText(null as String?))
        assertNull(RuleCorrectionCoordinator.resolveProvenanceBadgeText(null as BillRuleProvenance?))
        assertNull(RuleCorrectionCoordinator.resolveProvenanceBadgeText(""))
        assertNull(RuleCorrectionCoordinator.resolveProvenanceBadgeText("UNKNOWN_SOURCE"))
    }

    // 2. 验证已存在 USER 规则提示覆盖逻辑
    @Test
    fun testDecideCorrection_existingUserRulePromptsOverwrite() {
        val existingUserRule = AccountingRule(
            id = 10L,
            keyword = "星巴克",
            targetCategory = "餐饮",
            source = AccountingRule.Source.USER
        )

        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "餐饮",
            newCategory = "饮料",
            candidateKeyword = "星巴克",
            provenance = BillRuleProvenance(billId = 101L, source = BillRuleProvenance.Source.RULE, ruleId = 10L),
            matchingRules = listOf(existingUserRule)
        )

        assertEquals(RuleCorrectionCoordinator.CorrectionAction.OVERWRITE_USER_RULE, decision.action)
        assertEquals("星巴克", decision.keyword)
        assertEquals("饮料", decision.targetCategory)
        assertEquals("您之前已为「星巴克」设置过分类规则，是否更新为「饮料」？", decision.promptMessage)
        assertEquals(existingUserRule, decision.existingRule)
    }

    @Test
    fun testDecideCorrection_existingUserRuleMatchedByKeyword() {
        val existingUserRule = AccountingRule(
            id = 15L,
            keyword = "沃尔玛",
            targetCategory = "购物",
            source = AccountingRule.Source.USER
        )

        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "日用",
            newCategory = "超市",
            candidateKeyword = "沃尔玛",
            provenance = null,
            matchingRules = listOf(existingUserRule)
        )

        assertEquals(RuleCorrectionCoordinator.CorrectionAction.OVERWRITE_USER_RULE, decision.action)
        assertEquals("您之前已为「沃尔玛」设置过分类规则，是否更新为「超市」？", decision.promptMessage)
    }

    // 3. 验证 AI 规则或无规则提示新建规则逻辑
    @Test
    fun testDecideCorrection_aiRulePromptsSolidify() {
        val aiRule = AccountingRule(
            id = 20L,
            keyword = "滴滴出行",
            targetCategory = "交通",
            source = AccountingRule.Source.AI
        )

        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "交通",
            newCategory = "商务差旅",
            candidateKeyword = "滴滴出行",
            provenance = BillRuleProvenance(billId = 102L, source = BillRuleProvenance.Source.AI, ruleId = 20L),
            matchingRules = listOf(aiRule)
        )

        assertEquals(RuleCorrectionCoordinator.CorrectionAction.CREATE_OR_SOLIDIFY_RULE, decision.action)
        assertEquals("滴滴出行", decision.keyword)
        assertEquals("商务差旅", decision.targetCategory)
        assertEquals("已将「滴滴出行」分类修改为「商务差旅」，是否将此规则记住，下次自动生效？", decision.promptMessage)
    }

    @Test
    fun testDecideCorrection_noRulePromptsCreateRule() {
        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "未分类",
            newCategory = "家居",
            candidateKeyword = "无印良品",
            provenance = null,
            matchingRules = emptyList()
        )

        assertEquals(RuleCorrectionCoordinator.CorrectionAction.CREATE_OR_SOLIDIFY_RULE, decision.action)
        assertEquals("无印良品", decision.keyword)
        assertEquals("家居", decision.targetCategory)
        assertEquals("已将「无印良品」分类修改为「家居」，是否将此规则记住，下次自动生效？", decision.promptMessage)
        assertNull(decision.existingRule)
    }

    // 4. 边界条件验证
    @Test
    fun testDecideCorrection_noOpWhenCategoryUnchanged() {
        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "餐饮",
            newCategory = "餐饮",
            candidateKeyword = "麦当劳"
        )
        assertEquals(RuleCorrectionCoordinator.CorrectionAction.NO_OP, decision.action)
    }

    @Test
    fun testDecideCorrection_noOpWhenCategoryBlank() {
        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "餐饮",
            newCategory = "   ",
            candidateKeyword = "麦当劳"
        )
        assertEquals(RuleCorrectionCoordinator.CorrectionAction.NO_OP, decision.action)
    }

    @Test
    fun testDecideCorrection_noOpWhenKeywordBlank() {
        val decision = RuleCorrectionCoordinator.decideCorrection(
            oldCategory = "餐饮",
            newCategory = "早餐",
            candidateKeyword = "   "
        )
        assertEquals(RuleCorrectionCoordinator.CorrectionAction.NO_OP, decision.action)
    }
}
