package com.taostudio.tapaccounting.capture.rules

import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingRuleEngineTest {

    private val engine = DefaultAccountingRuleEngine

    @Test
    fun `priority USER over SEED and IMPORT and AI`() {
        val userRule = AccountingRule(
            id = 10,
            keyword = "咖啡",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetCategory = "用户咖啡",
            sort = 0,
            source = AccountingRule.Source.USER
        )
        val seedRule = AccountingRule(
            id = 5,
            keyword = "咖啡",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetCategory = "预置咖啡",
            sort = 0,
            source = AccountingRule.Source.SEED
        )
        val aiRule = AccountingRule(
            id = 1,
            keyword = "咖啡",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetCategory = "AI咖啡",
            sort = 0,
            source = AccountingRule.Source.AI
        )

        // All three present: USER wins
        val match1 = engine.matchNoteWithRules(listOf(aiRule, seedRule, userRule), "喝咖啡")
        assertNotNull(match1)
        assertEquals(userRule.id, match1?.ruleId)
        assertEquals("用户咖啡", match1?.categoryName)

        // USER absent: SEED wins over AI
        val match2 = engine.matchNoteWithRules(listOf(aiRule, seedRule), "喝咖啡")
        assertNotNull(match2)
        assertEquals(seedRule.id, match2?.ruleId)
        assertEquals("预置咖啡", match2?.categoryName)

        // Only AI: AI matches
        val match3 = engine.matchNoteWithRules(listOf(aiRule), "喝咖啡")
        assertNotNull(match3)
        assertEquals(aiRule.id, match3?.ruleId)
        assertEquals("AI咖啡", match3?.categoryName)
    }

    @Test
    fun `priority sort takes precedence before source`() {
        val highPriorityAi = AccountingRule(
            id = 1,
            keyword = "外卖",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "优先AI外卖",
            sort = 0,
            source = AccountingRule.Source.AI
        )
        val lowerPriorityUser = AccountingRule(
            id = 2,
            keyword = "外卖",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "次优用户外卖",
            sort = 10,
            source = AccountingRule.Source.USER
        )

        val match = engine.matchNoteWithRules(listOf(lowerPriorityUser, highPriorityAi), "点外卖")
        assertNotNull(match)
        assertEquals(highPriorityAi.id, match?.ruleId)
        assertEquals("优先AI外卖", match?.categoryName)
    }

    @Test
    fun `priority id tie breaker`() {
        val rule1 = AccountingRule(
            id = 5,
            keyword = "打车",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "交通A",
            sort = 0,
            source = AccountingRule.Source.USER
        )
        val rule2 = AccountingRule(
            id = 10,
            keyword = "打车",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "交通B",
            sort = 0,
            source = AccountingRule.Source.USER
        )

        val match = engine.matchNoteWithRules(listOf(rule2, rule1), "今天打车")
        assertNotNull(match)
        assertEquals(5L, match?.ruleId)
        assertEquals("交通A", match?.categoryName)
    }

    @Test
    fun `substring matching mode`() {
        val rule = AccountingRule(
            id = 1,
            keyword = "Starbucks",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            isRegex = false
        )

        assertTrue(engine.matchesText(rule, "bought a STARBUCKS latte"))
        assertTrue(engine.matchesText(rule, "星巴克starbucks饮料"))
        assertFalse(engine.matchesText(rule, "star bucks"))
        assertFalse(engine.matchesText(rule, ""))
    }

    @Test
    fun `exact mode does not match compound words`() {
        val rule = AccountingRule(
            id = 1,
            keyword = "苹果",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.EXACT,
            isRegex = false,
        )

        assertTrue(engine.matchesText(rule, "苹果"))
        assertTrue(engine.matchesText(rule, "  苹果  "))
        assertFalse(engine.matchesText(rule, "苹果电脑"))
        assertFalse(engine.matchesText(rule, "买了苹果"))
    }

    @Test
    fun `longer keyword wins over shorter substring at same sort`() {
        val shortRule = AccountingRule(
            id = 1,
            keyword = "苹果",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetCategory = "水果",
            sort = 0,
            source = AccountingRule.Source.AI,
        )
        val longRule = AccountingRule(
            id = 2,
            keyword = "苹果电脑",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetCategory = "数码",
            sort = 0,
            source = AccountingRule.Source.AI,
        )

        val match = engine.matchNoteWithRules(listOf(shortRule, longRule), "买了苹果电脑")
        assertNotNull(match)
        assertEquals("数码", match?.categoryName)
    }

    @Test
    fun `all tokens matching mode`() {
        val rule = AccountingRule(
            id = 1,
            keyword = "瑞幸  拿铁   生椰",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.ALL_TOKENS,
            isRegex = false
        )

        // Tokens appear out of order: should match
        assertTrue(engine.matchesText(rule, "今天在瑞幸点了杯生椰拿铁"))
        // Case-insensitive token match
        val englishRule = AccountingRule(
            id = 2,
            keyword = "apple pay store",
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.ALL_TOKENS,
            isRegex = false
        )
        assertTrue(engine.matchesText(englishRule, "APPLE online STORE purchase with PAY"))
        // Missing token: should not match
        assertFalse(engine.matchesText(englishRule, "apple store purchase"))
    }

    @Test
    fun `regex matching mode`() {
        val rule = AccountingRule(
            id = 1,
            keyword = "^(滴滴|曹操|高德).*打车",
            matchField = AccountingRule.MatchField.NOTE,
            isRegex = true
        )

        assertTrue(engine.matchesText(rule, "滴滴快车打车出行"))
        assertTrue(engine.matchesText(rule, "高德地图打车"))
        assertFalse(engine.matchesText(rule, "我用滴滴快车打车")) // '^' requires start of string

        // Broken regex does not crash
        val brokenRegexRule = AccountingRule(
            id = 2,
            keyword = "[invalid(regex",
            matchField = AccountingRule.MatchField.NOTE,
            isRegex = true
        )
        assertFalse(engine.matchesText(brokenRegexRule, "anything"))
    }

    @Test
    fun `matchField routing for note vs merchant vs any`() {
        val noteRule = AccountingRule(
            id = 1,
            keyword = "早饭",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "餐饮-早餐",
            source = AccountingRule.Source.USER
        )
        val merchantRule = AccountingRule(
            id = 2,
            keyword = "麦当劳",
            matchField = AccountingRule.MatchField.MERCHANT,
            targetCategory = "餐饮-快餐",
            source = AccountingRule.Source.USER
        )
        val anyRule = AccountingRule(
            id = 3,
            keyword = "咖啡",
            matchField = AccountingRule.MatchField.ANY,
            targetCategory = "饮品",
            source = AccountingRule.Source.USER
        )

        val rules = listOf(noteRule, merchantRule, anyRule)

        // Note rule only matches note
        assertEquals("餐饮-早餐", engine.matchNoteWithRules(rules, note = "买早饭", merchant = null)?.categoryName)
        assertNull(engine.matchNoteWithRules(rules, note = null, merchant = "早饭店"))

        // Merchant rule only matches merchant
        assertEquals("餐饮-快餐", engine.matchNoteWithRules(rules, note = null, merchant = "麦当劳得来速")?.categoryName)
        assertNull(engine.matchNoteWithRules(rules, note = "去麦当劳", merchant = null))

        // Note matches first before merchant
        val bothMatch = engine.matchNoteWithRules(rules, note = "早饭", merchant = "麦当劳")
        assertEquals(1L, bothMatch?.ruleId)
        assertEquals("餐饮-早餐", bothMatch?.categoryName)
        assertEquals("早饭", bothMatch?.matchedText)

        // Note unmatched, falls back to merchant
        val merchantFallback = engine.matchNoteWithRules(rules, note = "日常开销", merchant = "麦当劳")
        assertEquals(2L, merchantFallback?.ruleId)
        assertEquals("餐饮-快餐", merchantFallback?.categoryName)
        assertEquals("麦当劳", merchantFallback?.matchedText)

        // ANY matches either note or merchant
        assertEquals("饮品", engine.matchNoteWithRules(rules, note = "喝咖啡", merchant = null)?.categoryName)
        assertEquals("饮品", engine.matchNoteWithRules(rules, note = null, merchant = "星巴克咖啡")?.categoryName)
    }

    @Test
    fun `matchAiBill routing remarks precedence and userInput fallback`() {
        val remarkRule = AccountingRule(
            id = 1,
            keyword = "房租",
            matchField = AccountingRule.MatchField.REMARK,
            targetCategory = "住房",
            targetType = 0,
            targetAccount1 = "工商银行",
            source = AccountingRule.Source.USER
        )
        val userInputRule = AccountingRule(
            id = 2,
            keyword = "发工资",
            matchField = AccountingRule.MatchField.USER_INPUT,
            targetCategory = "工资收入",
            targetType = 1,
            targetAccount1 = "招商银行",
            source = AccountingRule.Source.USER
        )

        val rules = listOf(remarkRule, userInputRule)

        // Remarks matches: takes precedence
        val match1 = engine.matchAiBillWithRules(rules, userInput = "发工资了交房租", remarks = "交房租")
        assertNotNull(match1)
        assertEquals(1L, match1?.ruleId)
        assertEquals("住房", match1?.categoryName)
        assertEquals("交房租", match1?.matchedText)

        // Remarks doesn't match: userInput matches
        val match2 = engine.matchAiBillWithRules(rules, userInput = "公司今天发工资啦", remarks = "日常")
        assertNotNull(match2)
        assertEquals(2L, match2?.ruleId)
        assertEquals("工资收入", match2?.categoryName)
        assertEquals("公司今天发工资啦", match2?.matchedText)

        // Neither matches
        val match3 = engine.matchAiBillWithRules(rules, userInput = "买文具", remarks = "文具")
        assertNull(match3)
    }

    @Test
    fun `conflict handling returns multiple matches in priority order`() {
        val userRule = AccountingRule(
            id = 1,
            keyword = "苹果",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "数码",
            sort = 0,
            source = AccountingRule.Source.USER
        )
        val aiRule = AccountingRule(
            id = 2,
            keyword = "苹果",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "水果",
            sort = 0,
            source = AccountingRule.Source.AI
        )
        val merchantRule = AccountingRule(
            id = 3,
            keyword = "直营店",
            matchField = AccountingRule.MatchField.MERCHANT,
            targetCategory = "数码配件",
            sort = 0,
            source = AccountingRule.Source.USER
        )

        val rules = listOf(aiRule, merchantRule, userRule)
        val matches = engine.matchAllNoteWithRules(rules, note = "买苹果", merchant = "苹果直营店")

        assertEquals(3, matches.size)
        // 1st: note match USER
        assertEquals(1L, matches[0].ruleId)
        assertEquals("数码", matches[0].categoryName)
        // 2nd: note match AI
        assertEquals(2L, matches[1].ruleId)
        assertEquals("水果", matches[1].categoryName)
        // 3rd: merchant match USER
        assertEquals(3L, matches[2].ruleId)
        assertEquals("数码配件", matches[2].categoryName)
    }

    @Test
    fun `disabled rules are ignored`() {
        val disabledRule = AccountingRule(
            id = 1,
            keyword = "超市",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "百货",
            enabled = false,
            source = AccountingRule.Source.USER
        )
        val enabledRule = AccountingRule(
            id = 2,
            keyword = "超市",
            matchField = AccountingRule.MatchField.NOTE,
            targetCategory = "日用",
            enabled = true,
            source = AccountingRule.Source.AI
        )

        val match = engine.matchNoteWithRules(listOf(disabledRule, enabledRule), "去超市")
        assertNotNull(match)
        assertEquals(2L, match?.ruleId)
        assertEquals("日用", match?.categoryName)
    }
}
