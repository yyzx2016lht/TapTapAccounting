package com.taostudio.tapaccounting.capture.parse

import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CategoryRule
import com.taostudio.tapaccounting.capture.data.CategorySource
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLearningPolicyTest {

    private fun capture(
        source: String? = CategorySource.AI,
        note: String? = "苹果",
        category: String? = "吃的 - 水果",
        billType: Int? = LocalBillType.TYPE_EXPENSE,
        subType: Int? = LocalBillType.SUBTYPE_NORMAL,
        needsRefundLink: Boolean = false,
    ) = PaymentCapture(
        id = "c1",
        source = "WECHAT",
        note = note,
        categoryName = category,
        categorySource = source,
        billType = billType,
        subType = subType,
        needsRefundLink = needsRefundLink,
        paidAt = 1L,
        capturedAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun rule(
        keyword: String,
        category: String,
        source: String,
        matchField: String = CategoryRule.MATCH_NOTE,
    ) = CategoryRule(
        keyword = keyword,
        matchField = matchField,
        targetCategoryName = category,
        source = source,
    )

    @Test
    fun `ai classified normal expense with note is eligible`() {
        assertTrue(RuleLearningPolicy.isEligible(capture()))
    }

    @Test
    fun `manual source is not eligible`() {
        assertFalse(RuleLearningPolicy.isEligible(capture(source = CategorySource.MANUAL)))
        assertFalse(RuleLearningPolicy.isEligible(capture(source = null)))
    }

    @Test
    fun `blank note or other category is not eligible`() {
        assertFalse(RuleLearningPolicy.isEligible(capture(note = null)))
        assertFalse(RuleLearningPolicy.isEligible(capture(note = "  ")))
        assertFalse(RuleLearningPolicy.isEligible(capture(category = "其他")))
        assertFalse(RuleLearningPolicy.isEligible(capture(category = "支出 - 其他")))
    }

    @Test
    fun `transfer and refund are not eligible`() {
        assertFalse(
            RuleLearningPolicy.isEligible(
                capture(billType = LocalBillType.TYPE_TRANSFER, subType = LocalBillType.SUBTYPE_NORMAL)
            )
        )
        assertFalse(
            RuleLearningPolicy.isEligible(
                capture(billType = LocalBillType.TYPE_INCOME, subType = LocalBillType.SUBTYPE_REFUND)
            )
        )
        assertFalse(RuleLearningPolicy.isEligible(capture(needsRefundLink = true)))
    }

    @Test
    fun `new keyword inserts ai rule`() {
        assertEquals(
            RuleLearningPolicy.Decision.Insert,
            RuleLearningPolicy.decide(emptyList(), "苹果", "吃的 - 水果"),
        )
    }

    @Test
    fun `existing ai rule same category is skipped`() {
        val rules = listOf(rule("苹果", "吃的 - 水果", CategoryRule.SOURCE_AI))
        assertEquals(
            RuleLearningPolicy.Decision.SkipSameCategory,
            RuleLearningPolicy.decide(rules, "苹果", "吃的 - 水果"),
        )
    }

    @Test
    fun `existing ai rule different category is updated`() {
        val rules = listOf(rule("苹果", "数码", CategoryRule.SOURCE_AI))
        val decision = RuleLearningPolicy.decide(rules, "苹果", "吃的 - 水果")
        assertTrue(decision is RuleLearningPolicy.Decision.Update)
        assertEquals("数码", (decision as RuleLearningPolicy.Decision.Update).rule.targetCategoryName)
    }

    @Test
    fun `user seed and import rules are never overwritten`() {
        listOf(
            CategoryRule.SOURCE_USER,
            CategoryRule.SOURCE_SEED,
            CategoryRule.SOURCE_IMPORT,
        ).forEach { source ->
            assertEquals(
                RuleLearningPolicy.Decision.SkipProtected,
                RuleLearningPolicy.decide(
                    listOf(rule("苹果", "数码", source)),
                    "苹果",
                    "吃的 - 水果",
                ),
            )
        }
    }

    @Test
    fun `user any rule protects too`() {
        assertEquals(
            RuleLearningPolicy.Decision.SkipProtected,
            RuleLearningPolicy.decide(
                listOf(rule("苹果", "数码", CategoryRule.SOURCE_USER, CategoryRule.MATCH_ANY)),
                "苹果",
                "吃的 - 水果",
            ),
        )
    }

    @Test
    fun `merchant rule does not block note learning`() {
        assertEquals(
            RuleLearningPolicy.Decision.Insert,
            RuleLearningPolicy.decide(
                listOf(rule("苹果", "数码", CategoryRule.SOURCE_USER, CategoryRule.MATCH_MERCHANT)),
                "苹果",
                "吃的 - 水果",
            ),
        )
    }

    @Test
    fun `leaf category and toast only show second level`() {
        assertEquals("水果", RuleLearningPolicy.leafCategoryName("吃的 - 水果"))
        assertEquals("咖啡", RuleLearningPolicy.leafCategoryName("餐饮/咖啡"))
        assertEquals("水果", RuleLearningPolicy.leafCategoryName("水果"))
        assertNull(
            RuleLearningPolicy.leafCategoryName("").takeIf { it.isNotEmpty() }
        )
        assertEquals("已记住：苹果 → 水果", RuleLearningPolicy.toastText("苹果", "吃的 - 水果"))
    }

    @Test
    fun `ai rule is sorted after manual rules`() {
        val ai = RuleLearningPolicy.newAiRule("苹果", "吃的 - 水果", 123L)
        assertEquals(CategoryRule.SOURCE_AI, ai.source)
        assertEquals(CategoryRule.MATCH_NOTE, ai.matchField)
        assertEquals(123L, ai.learnedAt)
        assertTrue(ai.sort >= 10_000)
    }
}
