package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并重复分类的决策规则。
 *
 * 现实背景：`category.json`（内置分类库）里「其他」和「其它」是同一个图标的两个条目，
 * 而 `default_category.json`（初始种子）只给「其它」。手动加过「其他」的账本会同时拥有两个，
 * 各自挂着一批账单，在统计页和分类预算里看起来像两个分类。
 *
 * 执行部分（改挂账单、删分类）需要 Room，无法在 JVM 单测里跑；这里锁住的是**决策**：
 * 合并哪些、保留哪个、什么情况下必须放弃。
 */
class DuplicateCategoryMergerTest {

    private fun cat(id: Long, name: String, type: Int, parentId: Long? = null) =
        Category(id = id, name = name, type = type, parentId = parentId)

    @Test
    fun `both spellings on both types produce one merge per type`() {
        // 真机上的形态：「其它」(29,43) 与「其他」(63,67)，支出收入各一对
        val plan = DuplicateCategoryMerger.plan(
            listOf(
                cat(29, "其它", 0), cat(43, "其它", 1),
                cat(63, "其他", 0), cat(67, "其他", 1),
            )
        )

        assertEquals(2, plan.actions.size)
        assertTrue(plan.skips.isEmpty())

        val expense = plan.actions.single { it.type == 0 }
        assertEquals(29L, expense.sourceId)
        assertEquals("其它", expense.sourceName)
        assertEquals(63L, expense.targetId)
        assertEquals("其他", expense.targetName)

        val income = plan.actions.single { it.type == 1 }
        assertEquals(43L, income.sourceId)
        assertEquals(67L, income.targetId)
    }

    @Test
    fun `a single spelling is left alone`() {
        // 绝大多数账本只有种子给的那个，这里必须是空操作
        assertTrue(DuplicateCategoryMerger.plan(listOf(cat(43, "其它", 1))).actions.isEmpty())
        assertTrue(DuplicateCategoryMerger.plan(listOf(cat(63, "其他", 0))).actions.isEmpty())
    }

    @Test
    fun `an empty category list is a no-op`() {
        val plan = DuplicateCategoryMerger.plan(emptyList())
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.skips.isEmpty())
    }

    @Test
    fun `duplicates of different types are never merged`() {
        // 支出「其他」与收入「其它」——类型不同就是两个不同的分类，合并会改变账单的类型语义
        val plan = DuplicateCategoryMerger.plan(listOf(cat(63, "其他", 0), cat(43, "其它", 1)))
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `a source with children is skipped and reported`() {
        // 删掉「其它」会让它的子分类 parentId 被外键置空、账单名「其它 - 杂项」失去父级，
        // 属于用户自己的结构，不能替他决定——跳过并留痕
        val plan = DuplicateCategoryMerger.plan(
            listOf(
                cat(29, "其它", 0), cat(63, "其他", 0),
                cat(30, "其它杂项", 0, parentId = 29),
            )
        )

        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.skips.size)
        val skip = plan.skips.single()
        assertEquals("其它", skip.name)
        assertEquals(0, skip.type)
    }

    @Test
    fun `a target with children is still merged`() {
        // 保留的那个有子分类不影响合并：只是把账单挂过去，不删它
        val plan = DuplicateCategoryMerger.plan(
            listOf(
                cat(29, "其它", 0), cat(63, "其他", 0),
                cat(64, "其他杂项", 0, parentId = 63),
            )
        )

        assertEquals(1, plan.actions.size)
        assertEquals(63L, plan.actions.single().targetId)
    }

    @Test
    fun `sub-categories are not treated as duplicates`() {
        // 「其它」只作为子分类存在时，账单名是「杂项 - 其它」，按名字迁移的语义完全不同
        val plan = DuplicateCategoryMerger.plan(
            listOf(
                cat(1, "杂项", 0),
                cat(29, "其它", 0, parentId = 1),
                cat(63, "其他", 0),
            )
        )
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `only the declared alias pair is considered`() {
        // 同义但不在别名表里的分类不动——合并是破坏性操作，范围必须明确
        val plan = DuplicateCategoryMerger.plan(listOf(cat(1, "餐饮", 0), cat(2, "饮食", 0)))
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `the target is the first spelling declared in the alias group`() {
        // 方向固定为保留「其他」（现代汉语规范写法，也是 category.json 里的正式条目）
        val plan = DuplicateCategoryMerger.plan(
            listOf(
                cat(29, "其它", 0), cat(63, "其他", 0),
            )
        )
        assertEquals("其他", plan.actions.single().targetName)
        assertEquals("其它", plan.actions.single().sourceName)
    }
}
