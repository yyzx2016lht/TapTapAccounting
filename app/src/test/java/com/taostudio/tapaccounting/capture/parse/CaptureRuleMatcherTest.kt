package com.taostudio.tapaccounting.capture.parse

import com.taostudio.tapaccounting.capture.data.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureRuleMatcherTest {

    private fun rule(
        keyword: String,
        matchField: String,
        category: String,
        enabled: Boolean = true,
    ) = CategoryRule(
        keyword = keyword,
        matchField = matchField,
        targetCategoryName = category,
        enabled = enabled,
    )

    @Test
    fun `note rule matches note`() {
        val rules = listOf(rule("苹果", CategoryRule.MATCH_NOTE, "水果"))
        assertEquals("水果", CaptureRuleMatcher.matchNoteCategory(rules, "买了苹果"))
    }

    @Test
    fun `any rule matches note`() {
        val rules = listOf(rule("苹果", CategoryRule.MATCH_ANY, "水果"))
        assertEquals("水果", CaptureRuleMatcher.matchNoteCategory(rules, "苹果"))
    }

    @Test
    fun `merchant rule does not match note`() {
        val rules = listOf(rule("沃尔玛", CategoryRule.MATCH_MERCHANT, "超市"))
        assertNull(CaptureRuleMatcher.matchNoteCategory(rules, "沃尔玛"))
    }

    @Test
    fun `merchant rule is skipped even when an any rule follows`() {
        val rules = listOf(
            rule("沃尔玛", CategoryRule.MATCH_MERCHANT, "超市"),
            rule("苹果", CategoryRule.MATCH_ANY, "水果"),
        )
        assertEquals("水果", CaptureRuleMatcher.matchNoteCategory(rules, "沃尔玛买了苹果"))
    }

    @Test
    fun `no rule returns null`() {
        assertNull(CaptureRuleMatcher.matchNoteCategory(emptyList(), "苹果"))
        assertNull(
            CaptureRuleMatcher.matchNoteCategory(
                listOf(rule("苹果", CategoryRule.MATCH_NOTE, "水果")),
                "香蕉",
            ),
        )
    }

    @Test
    fun `blank note returns null`() {
        val rules = listOf(rule("苹果", CategoryRule.MATCH_ANY, "水果"))
        assertNull(CaptureRuleMatcher.matchNoteCategory(rules, null))
        assertNull(CaptureRuleMatcher.matchNoteCategory(rules, "   "))
    }
}
