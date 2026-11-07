package com.taostudio.tapaccounting.data.repository

import com.taostudio.tapaccounting.data.backup.DataExportManager
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import com.taostudio.tapaccounting.data.local.entity.AiRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份规则兼容性测试：
 * 1. 旧版 AiRule 格式（对象列表与 JSON）恢复转换兼容性
 * 2. 新版 AccountingRule 格式恢复与 Round-trip 完整性
 * 3. 恢复时不盲目清空现有待记规则，按 keyword + matchField + targetCategory 进行 merge/upsert
 */
class BackupRulesCompatibilityTest {

    @Test
    fun `convert legacy AiRule list to AccountingRule`() {
        val legacyRules = listOf(
            AiRule(
                id = 1,
                keyword = "工资",
                targetType = 1,
                targetCategory = "薪资",
                targetAccount1 = "招商银行",
                targetAccount2 = null,
                isEnabled = true
            ),
            AiRule(
                id = 2,
                keyword = "花呗还款",
                targetType = 0,
                targetCategory = "还款",
                targetAccount1 = "支付宝",
                targetAccount2 = "花呗",
                isEnabled = false
            )
        )

        val converted = BackupRepository.convertRulesToAccountingRules(legacyRules)
        assertEquals(2, converted.size)

        val r1 = converted[0]
        assertEquals("工资", r1.keyword)
        assertFalse(r1.isRegex)
        assertEquals(AccountingRule.MatchField.REMARK, r1.matchField)
        assertEquals(AccountingRule.MatchMode.ALL_TOKENS, r1.matchMode)
        assertEquals(1, r1.targetType)
        assertEquals("薪资", r1.targetCategory)
        assertEquals("招商银行", r1.targetAccount1)
        assertNull(r1.targetAccount2)
        assertNull(r1.targetBook)
        assertEquals(0, r1.sort)
        assertTrue(r1.enabled)
        assertEquals(AccountingRule.Source.USER, r1.source)

        val r2 = converted[1]
        assertEquals("花呗还款", r2.keyword)
        assertFalse(r2.enabled)
        assertEquals("还款", r2.targetCategory)
        assertEquals("支付宝", r2.targetAccount1)
        assertEquals("花呗", r2.targetAccount2)
        assertEquals(AccountingRule.Source.USER, r2.source)
    }

    @Test
    fun `convert legacy AiRule JSON to AccountingRule`() {
        val legacyJson = """
            [
              {
                "id": 10,
                "keyword": "美团外卖",
                "targetType": 0,
                "targetCategory": "餐饮",
                "targetAccount1": "微信零钱",
                "isEnabled": true
              }
            ]
        """.trimIndent()

        val converted = BackupRepository.convertRulesToAccountingRules(legacyJson)
        assertEquals(1, converted.size)

        val rule = converted[0]
        assertEquals("美团外卖", rule.keyword)
        assertEquals(AccountingRule.MatchField.REMARK, rule.matchField)
        assertEquals(AccountingRule.MatchMode.ALL_TOKENS, rule.matchMode)
        assertEquals(0, rule.targetType)
        assertEquals("餐饮", rule.targetCategory)
        assertEquals("微信零钱", rule.targetAccount1)
        assertTrue(rule.enabled)
        assertEquals(AccountingRule.Source.USER, rule.source)
    }

    @Test
    fun `convert new AccountingRule JSON preserving all fields`() {
        val newJson = """
            [
              {
                "id": 1,
                "keyword": "星巴克.*",
                "isRegex": true,
                "matchField": "NOTE",
                "matchMode": "SUBSTRING",
                "targetType": 0,
                "targetCategory": "咖啡饮品",
                "targetBook": "日常账本",
                "targetAccount1": "招行卡",
                "targetAccount2": null,
                "sort": 10,
                "enabled": true,
                "source": "SEED",
                "learnedAt": 1700000000,
                "createdAt": 1690000000,
                "updatedAt": 1695000000
              }
            ]
        """.trimIndent()

        val converted = BackupRepository.convertRulesToAccountingRules(newJson)
        assertEquals(1, converted.size)

        val rule = converted[0]
        assertEquals("星巴克.*", rule.keyword)
        assertTrue(rule.isRegex)
        assertEquals("NOTE", rule.matchField)
        assertEquals("SUBSTRING", rule.matchMode)
        assertEquals(0, rule.targetType)
        assertEquals("咖啡饮品", rule.targetCategory)
        assertEquals("日常账本", rule.targetBook)
        assertEquals("招行卡", rule.targetAccount1)
        assertNull(rule.targetAccount2)
        assertEquals(10, rule.sort)
        assertTrue(rule.enabled)
        assertEquals("SEED", rule.source)
        assertEquals(1700000000L, rule.learnedAt)
        assertEquals(1690000000L, rule.createdAt)
        assertEquals(1695000000L, rule.updatedAt)
    }

    @Test
    fun `DataExportManager deserializeRules handles both legacy and new json`() {
        val legacyJson = """[{"keyword":"滴滴","targetCategory":"交通","isEnabled":true}]"""
        val legacyResult = DataExportManager.deserializeRules(legacyJson)
        assertEquals(1, legacyResult.size)
        assertEquals("滴滴", legacyResult[0].keyword)
        assertEquals(AccountingRule.MatchField.REMARK, legacyResult[0].matchField)

        val newJson = """[{"keyword":"滴滴","matchField":"MERCHANT","matchMode":"SUBSTRING","targetCategory":"出行","enabled":true}]"""
        val newResult = DataExportManager.deserializeRules(newJson)
        assertEquals(1, newResult.size)
        assertEquals("滴滴", newResult[0].keyword)
        assertEquals("MERCHANT", newResult[0].matchField)
        assertEquals("出行", newResult[0].targetCategory)
    }

    @Test
    fun `round-trip serialization of AccountingRule through DataExportManager`() {
        val originalRule = AccountingRule(
            id = 5,
            keyword = "电费",
            isRegex = false,
            matchField = AccountingRule.MatchField.NOTE,
            matchMode = AccountingRule.MatchMode.SUBSTRING,
            targetType = 0,
            targetCategory = "生活缴费",
            targetBook = "家庭账本",
            targetAccount1 = "建设银行",
            targetAccount2 = null,
            sort = 3,
            enabled = true,
            source = AccountingRule.Source.IMPORT,
            learnedAt = 1710000000L,
            createdAt = 1700000000L,
            updatedAt = 1705000000L
        )

        val json = DataExportManager.serialize(listOf(originalRule))
        val restored = DataExportManager.deserializeRules(json)
        assertEquals(1, restored.size)

        val r = restored[0]
        assertEquals(originalRule.keyword, r.keyword)
        assertEquals(originalRule.isRegex, r.isRegex)
        assertEquals(originalRule.matchField, r.matchField)
        assertEquals(originalRule.matchMode, r.matchMode)
        assertEquals(originalRule.targetType, r.targetType)
        assertEquals(originalRule.targetCategory, r.targetCategory)
        assertEquals(originalRule.targetBook, r.targetBook)
        assertEquals(originalRule.targetAccount1, r.targetAccount1)
        assertEquals(originalRule.targetAccount2, r.targetAccount2)
        assertEquals(originalRule.sort, r.sort)
        assertEquals(originalRule.enabled, r.enabled)
        assertEquals(originalRule.source, r.source)
        assertEquals(originalRule.learnedAt, r.learnedAt)
        assertEquals(originalRule.createdAt, r.createdAt)
        assertEquals(originalRule.updatedAt, r.updatedAt)
    }

    @Test
    fun `rule merge dedup logic correctly matches keyword, matchField, targetCategory`() {
        // 模拟本地已有规则
        val existing = mutableListOf(
            AccountingRule(
                id = 1L,
                keyword = "肯德基",
                matchField = AccountingRule.MatchField.NOTE,
                targetCategory = "快餐",
                source = AccountingRule.Source.USER
            ),
            AccountingRule(
                id = 2L,
                keyword = "肯德基",
                matchField = AccountingRule.MatchField.REMARK,
                targetCategory = "餐饮",
                source = AccountingRule.Source.USER
            )
        )

        // 模拟旧备份恢复：包含一条匹配已存在规则（更新）和一条新规则（追加）
        val incomingLegacy = listOf(
            // 匹配 id=2: keyword="肯德基", matchField="REMARK", targetCategory="餐饮"
            AiRule(keyword = "肯德基", targetType = 0, targetCategory = "餐饮", targetAccount1 = "微信", targetAccount2 = null, isEnabled = true),
            // 新规则
            AiRule(keyword = "麦当劳", targetType = 0, targetCategory = "快餐", targetAccount1 = "支付宝", targetAccount2 = null, isEnabled = true)
        )

        val incomingConverted = BackupRepository.convertRulesToAccountingRules(incomingLegacy)

        // 验证 merge 模拟
        var updatedCount = 0
        var insertedCount = 0
        incomingConverted.forEach { incoming ->
            val existingIndex = existing.indexOfFirst {
                it.keyword == incoming.keyword &&
                    it.matchField == incoming.matchField &&
                    it.targetCategory == incoming.targetCategory
            }
            if (existingIndex >= 0) {
                // Update
                val old = existing[existingIndex]
                existing[existingIndex] = incoming.copy(id = old.id)
                updatedCount++
            } else {
                // Insert
                existing.add(incoming.copy(id = (existing.size + 1).toLong()))
                insertedCount++
            }
        }

        assertEquals(1, updatedCount)
        assertEquals(1, insertedCount)
        assertEquals(3, existing.size)

        // 验证原有的 NOTE 规则未被覆盖或删除
        val noteRule = existing.find { it.keyword == "肯德基" && it.matchField == AccountingRule.MatchField.NOTE }
        assertNotNull(noteRule)
        assertEquals("快餐", noteRule?.targetCategory)

        // 验证更新的 REMARK 规则 targetAccount1 已更新为 "微信"
        val remarkRule = existing.find { it.keyword == "肯德基" && it.matchField == AccountingRule.MatchField.REMARK }
        assertNotNull(remarkRule)
        assertEquals("微信", remarkRule?.targetAccount1)
    }
}
