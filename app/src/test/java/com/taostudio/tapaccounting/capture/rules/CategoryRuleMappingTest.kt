package com.taostudio.tapaccounting.capture.rules

import com.taostudio.tapaccounting.capture.CaptureInboxEvents
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRuleMappingTest {

    @Test
    fun testRuleSortingByPriorityOrder() {
        val userRule = AccountingRule(
            id = 100,
            keyword = "打车",
            targetCategory = "交通",
            sort = 0,
            source = AccountingRule.Source.USER
        )
        val seedRule = AccountingRule(
            id = 50,
            keyword = "滴滴",
            targetCategory = "交通",
            sort = 0,
            source = AccountingRule.Source.SEED
        )
        val importRule = AccountingRule(
            id = 60,
            keyword = "高德",
            targetCategory = "交通",
            sort = 0,
            source = AccountingRule.Source.IMPORT
        )
        val aiRule = AccountingRule(
            id = 10,
            keyword = "T3出行",
            targetCategory = "交通",
            sort = 0,
            source = AccountingRule.Source.AI
        )

        val list = listOf(aiRule, importRule, userRule, seedRule)
        val sorted = list.sortedWith(DefaultAccountingRuleEngine.ruleComparator)

        // USER (source priority 1) -> SEED/IMPORT (source priority 2) -> AI (source priority 3)
        assertEquals(userRule.id, sorted[0].id)
        assertEquals(AccountingRule.Source.USER, sorted[0].source)

        assertTrue(sorted[1].source == AccountingRule.Source.SEED || sorted[1].source == AccountingRule.Source.IMPORT)
        assertTrue(sorted[2].source == AccountingRule.Source.SEED || sorted[2].source == AccountingRule.Source.IMPORT)

        assertEquals(aiRule.id, sorted[3].id)
        assertEquals(AccountingRule.Source.AI, sorted[3].source)
    }

    @Test
    fun testRuleFilteringBySource() {
        val rules = listOf(
            AccountingRule(id = 1, keyword = "咖啡", targetCategory = "餐饮", source = AccountingRule.Source.USER),
            AccountingRule(id = 2, keyword = "星巴克", targetCategory = "餐饮", source = AccountingRule.Source.SEED),
            AccountingRule(id = 3, keyword = "瑞幸", targetCategory = "餐饮", source = AccountingRule.Source.IMPORT),
            AccountingRule(id = 4, keyword = "麦当劳", targetCategory = "餐饮", source = AccountingRule.Source.AI),
        )

        val userFiltered = rules.filter { it.source == AccountingRule.Source.USER }
        assertEquals(1, userFiltered.size)
        assertEquals("咖啡", userFiltered.first().keyword)

        val seedFiltered = rules.filter {
            it.source == AccountingRule.Source.SEED || it.source == AccountingRule.Source.IMPORT
        }
        assertEquals(2, seedFiltered.size)

        val aiFiltered = rules.filter { it.source == AccountingRule.Source.AI }
        assertEquals(1, aiFiltered.size)
        assertEquals("麦当劳", aiFiltered.first().keyword)
    }

    @Test
    fun testRuleFilteringByKeywordAndCategory() {
        val rules = listOf(
            AccountingRule(id = 1, keyword = "咖啡", targetCategory = "餐饮 - 咖啡", source = AccountingRule.Source.USER),
            AccountingRule(id = 2, keyword = "滴滴", targetCategory = "交通出行 - 打车", source = AccountingRule.Source.SEED),
            AccountingRule(id = 3, keyword = "房租", targetCategory = "居住 - 房租", source = AccountingRule.Source.USER),
        )

        // Filter by keyword substring
        val q1 = "啡"
        val matchedByKeyword = rules.filter {
            it.keyword.contains(q1, ignoreCase = true) || (it.targetCategory?.contains(q1, ignoreCase = true) == true)
        }
        assertEquals(1, matchedByKeyword.size)
        assertEquals("咖啡", matchedByKeyword.first().keyword)

        // Filter by target category substring
        val q2 = "交通"
        val matchedByCategory = rules.filter {
            it.keyword.contains(q2, ignoreCase = true) || (it.targetCategory?.contains(q2, ignoreCase = true) == true)
        }
        assertEquals(1, matchedByCategory.size)
        assertEquals("滴滴", matchedByCategory.first().keyword)
    }

    @Test
    fun testRuleToggleInPlace() {
        val rule = AccountingRule(
            id = 1,
            keyword = "咖啡",
            targetCategory = "餐饮",
            enabled = true,
            source = AccountingRule.Source.USER
        )

        val toggled = rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis())
        assertFalse(toggled.enabled)

        val toggledBack = toggled.copy(enabled = !toggled.enabled, updatedAt = System.currentTimeMillis())
        assertTrue(toggledBack.enabled)
    }

    @Test
    fun testEngineCacheInvalidation() {
        DefaultAccountingRuleEngine.invalidateCache()
        // verify invalidateCache runs without error
        AccountingRuleEngine.INSTANCE.invalidateCache()
    }

    @Test
    fun testCaptureInboxEventsNotify() = runBlocking {
        var notified = false
        val job = launch {
            CaptureInboxEvents.changes.first()
            notified = true
        }
        CaptureInboxEvents.notifyChanged()
        job.join()
        assertTrue(notified)
    }
}
