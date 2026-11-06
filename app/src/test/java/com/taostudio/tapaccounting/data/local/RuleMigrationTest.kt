package com.taostudio.tapaccounting.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 测试从 v42 到 v43 的 SQLite 规则数据迁移逻辑：
 * 1. category_rules 和 ai_rule 两套规则均被复制到 accounting_rules
 * 2. 同关键词但不同分类的规则同时保留
 * 3. 完全重复的规则（keyword + matchField + targetCategory + source 相同）自动去重
 * 4. bill_rule_provenance 表正确创建
 * 5. payment_captures 表正确添加 categoryRuleId 列
 * 6. 原有的 category_rules 与 ai_rule 表未被删除
 */
class RuleMigrationTest {

    @Test
    fun testMigrationFromV42ToV43() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                // 1. 初始化 v42 基础表结构
                CAPTURE_MIGRATION_41_42_SQL.forEach { stmt.execute(it) }

                stmt.execute(
                    """CREATE TABLE IF NOT EXISTS `ai_rule` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `targetType` INTEGER,
                        `targetCategory` TEXT,
                        `targetAccount1` TEXT,
                        `targetAccount2` TEXT,
                        `isEnabled` INTEGER NOT NULL
                    )"""
                )

                // 2. 插入 v42 测试数据
                // (a) category_rules 数据
                stmt.execute(
                    """INSERT INTO `category_rules`
                    (keyword, matchField, isRegex, targetCategoryName, targetBookName, sort, enabled, createdAt, source, learnedAt)
                    VALUES
                    ('星巴克', 'NOTE', 0, '咖啡', '日常账本', 1, 1, 1000, 'USER', NULL),
                    ('肯德基', 'NOTE', 0, '快餐', NULL, 2, 1, 1001, 'USER', NULL),
                    ('滴滴出行', 'REMARK', 0, '打车', NULL, 3, 1, 1002, 'USER', NULL),
                    ('重复规则', 'REMARK', 0, '生活缴费', NULL, 4, 1, 1003, 'USER', NULL)
                    """
                )

                // (b) ai_rule 数据
                stmt.execute(
                    """INSERT INTO `ai_rule`
                    (keyword, targetType, targetCategory, targetAccount1, targetAccount2, isEnabled)
                    VALUES
                    ('工资', 1, '薪资', '招商银行', NULL, 1),
                    ('肯德基', 0, '餐饮', '微信', NULL, 1), -- 同关键词不同分类（肯德基：快餐 vs 餐饮）
                    ('重复规则', 0, '生活缴费', NULL, NULL, 1)  -- 完全重复规则（与上面的重复规则 keyword+REMARK+生活缴费+USER 相同）
                    """
                )

                // (c) payment_captures 插入一条待记数据
                stmt.execute(
                    """INSERT INTO `payment_captures`
                    (id, source, currency, paidAt, capturedAt, status, needsRefundLink, splitRequested, excludeFromStats, needsAmount, createdAt, updatedAt)
                    VALUES
                    ('cap-1', 'ALIPAY', 'CNY', 1700000000, 1700000001, 'PENDING', 0, 0, 0, 0, 1700000000, 1700000000)
                    """
                )

                // 3. 执行 v42 -> v43 迁移
                CAPTURE_MIGRATION_42_43_SQL.forEach { stmt.execute(it) }

                // 4. 验证 category_rules 和 ai_rule 原表仍然存在
                stmt.executeQuery("SELECT count(*) FROM category_rules").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(4, rs.getInt(1))
                }
                stmt.executeQuery("SELECT count(*) FROM ai_rule").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(3, rs.getInt(1))
                }

                // 5. 验证 bill_rule_provenance 表创建成功
                stmt.executeQuery("SELECT count(*) FROM bill_rule_provenance").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }

                // 6. 验证 payment_captures.categoryRuleId 列已添加并可写入
                stmt.execute("UPDATE payment_captures SET categoryRuleId = 123 WHERE id = 'cap-1'")
                stmt.executeQuery("SELECT categoryRuleId FROM payment_captures WHERE id = 'cap-1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(123L, rs.getLong(1))
                }

                // 7. 验证 accounting_rules 内容
                // 预期总数：
                // category_rules 4 条 + ai_rule 3 条 = 7 条
                // 其中 '重复规则' (matchField=REMARK, targetCategory=生活缴费, source=USER) 出现 2 次，去重后留 1 次
                // 因此应有 6 条
                stmt.executeQuery("SELECT count(*) FROM accounting_rules").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(6, rs.getInt(1))
                }

                // 验证 '星巴克' 来自 category_rules
                stmt.executeQuery("SELECT * FROM accounting_rules WHERE keyword = '星巴克'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("NOTE", rs.getString("matchField"))
                    assertEquals("SUBSTRING", rs.getString("matchMode"))
                    assertEquals("咖啡", rs.getString("targetCategory"))
                    assertEquals("日常账本", rs.getString("targetBook"))
                    assertEquals("USER", rs.getString("source"))
                    assertEquals(1, rs.getInt("sort"))
                    assertEquals(1, rs.getInt("enabled"))
                }

                // 验证 '工资' 来自 ai_rule
                stmt.executeQuery("SELECT * FROM accounting_rules WHERE keyword = '工资'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("REMARK", rs.getString("matchField"))
                    assertEquals("ALL_TOKENS", rs.getString("matchMode"))
                    assertEquals(1, rs.getInt("targetType"))
                    assertEquals("薪资", rs.getString("targetCategory"))
                    assertEquals("招商银行", rs.getString("targetAccount1"))
                    assertEquals("USER", rs.getString("source"))
                    assertEquals(0, rs.getInt("sort"))
                    assertEquals(1, rs.getInt("enabled"))
                }

                // 验证同关键词不同分类同时保留（'肯德基'）
                stmt.executeQuery("SELECT targetCategory, matchField FROM accounting_rules WHERE keyword = '肯德基' ORDER BY targetCategory").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("快餐", rs.getString(1))
                    assertEquals("NOTE", rs.getString(2))
                    assertTrue(rs.next())
                    assertEquals("餐饮", rs.getString(1))
                    assertEquals("REMARK", rs.getString(2))
                }

                // 验证完全重复项仅保留一条（'重复规则'）
                stmt.executeQuery("SELECT count(*) FROM accounting_rules WHERE keyword = '重复规则'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }
}
