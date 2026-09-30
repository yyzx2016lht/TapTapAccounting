package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写入漏斗的分类关联对齐规则。
 *
 * `bills.categoryId` 是权威、`categoryName` 只是展示路径快照，两者必须一致：
 * 缺 id 会让这笔支出在**分类预算**里隐形，快照过期会让它在**统计页**上被拆成两个分类。
 * 这段判定守住两个方向的边界——缺 id 才反查（只认唯一匹配，宁可留空也不猜），
 * 有 id 就按分类树重算展示名（退款前缀必须保留），转账一律跳过（没有分类）。
 */
class BillMutationServiceRelinkTest {

    private fun bill(
        type: Int,
        categoryName: String,
        categoryId: Long? = null
    ) = Bill(
        type = type,
        amount = 10.0,
        time = 0L,
        categoryName = categoryName,
        categoryId = categoryId
    )

    @Test
    fun `expense with only a name needs relink`() {
        assertTrue(BillMutationService.needsCategoryRelink(bill(Bill.TYPE_EXPENSE, "吃的 - 三餐")))
    }

    @Test
    fun `income with only a name needs relink`() {
        assertTrue(BillMutationService.needsCategoryRelink(bill(Bill.TYPE_INCOME, "工资")))
    }

    @Test
    fun `an already linked bill is left alone`() {
        assertFalse(
            BillMutationService.needsCategoryRelink(
                bill(Bill.TYPE_EXPENSE, "吃的 - 三餐", categoryId = 11L)
            )
        )
    }

    @Test
    fun `transfers are skipped because they carry no category`() {
        assertFalse(BillMutationService.needsCategoryRelink(bill(Bill.TYPE_TRANSFER, "转账到招行")))
    }

    @Test
    fun `blank names are skipped`() {
        assertFalse(BillMutationService.needsCategoryRelink(bill(Bill.TYPE_EXPENSE, "")))
        assertFalse(BillMutationService.needsCategoryRelink(bill(Bill.TYPE_EXPENSE, "   ")))
    }

    @Test
    fun `a refund bill with only a name needs relink`() {
        // 退款账单 type=TYPE_INCOME，categoryId 继承自原支出。原支出若也是 NULL，
        // 退款就得靠名字补回来——否则它同样对分类预算隐形。
        val refund = bill(Bill.TYPE_INCOME, "退款：吃的 - 三餐").copy(subType = Bill.SUBTYPE_REFUND)
        assertTrue(BillMutationService.needsCategoryRelink(refund))
    }

    // --- 有 categoryId 时：展示名必须以分类树为准，快照过期就要重算 ---
    //
    // 统计页与洞察都按 bills.categoryName 聚合，从不回查 categoryId。快照一旦过期，
    // 同一笔支出会在统计页上被拆成两个分类（「幽灵分类」）。

    @Test
    fun `a bill whose stored path matches the tree is already aligned`() {
        assertTrue(BillMutationService.isDisplayNameAligned("吃的 - 三餐", "吃的 - 三餐"))
    }

    @Test
    fun `a refund bill keeps its prefix when compared against the tree path`() {
        // 「退款：吃的 - 三餐」对应的路径就是「吃的 - 三餐」——已经一致，不该被反复改写
        assertTrue(BillMutationService.isDisplayNameAligned("退款：吃的 - 三餐", "吃的 - 三餐"))
        assertTrue(BillMutationService.isDisplayNameAligned("退款·吃的 - 三餐", "吃的 - 三餐"))
    }

    @Test
    fun `a stale path after a rename is detected as misaligned`() {
        assertFalse(BillMutationService.isDisplayNameAligned("吃的 - 三餐", "餐饮 - 三餐"))
        assertFalse(BillMutationService.isDisplayNameAligned("退款：吃的 - 三餐", "餐饮 - 三餐"))
    }

    @Test
    fun `a bare leaf name is realigned to the full display path`() {
        // 聊天改写/导入可能只写了叶子名「三餐」，而分类树里是「吃的 - 三餐」
        assertFalse(BillMutationService.isDisplayNameAligned("三餐", "吃的 - 三餐"))
    }

    @Test
    fun `an empty stored name is realigned`() {
        assertFalse(BillMutationService.isDisplayNameAligned("", "吃的 - 三餐"))
    }

    // --- 纯函数形态的完整对齐（alignAgainstTree）---
    //
    // 写入漏斗、CSV 导入、回收站还原共用同一份规则，所以判定被抽成纯函数，
    // 直接喂一棵分类树即可断言。

    private val treePaths = mapOf(11L to "吃的 - 三餐", 22L to "工资")
    private val treeIndex = mapOf(
        ("三餐" to Bill.TYPE_EXPENSE) to 11L,
        ("工资" to Bill.TYPE_INCOME) to 22L
    )

    @Test
    fun `a linked bill gets its display name refreshed from the tree`() {
        val aligned = BillMutationService.alignAgainstTree(
            bill(Bill.TYPE_EXPENSE, "吃的 - 早餐", categoryId = 11L), treePaths, treeIndex
        )
        assertEquals("吃的 - 三餐", aligned.categoryName)
        assertEquals(11L, aligned.categoryId)
    }

    @Test
    fun `a linked refund bill keeps its prefix when refreshed`() {
        val aligned = BillMutationService.alignAgainstTree(
            bill(Bill.TYPE_INCOME, "退款：吃的 - 早餐", categoryId = 11L), treePaths, treeIndex
        )
        assertEquals("退款：吃的 - 三餐", aligned.categoryName)
    }

    @Test
    fun `an already aligned bill is returned untouched`() {
        val original = bill(Bill.TYPE_EXPENSE, "吃的 - 三餐", categoryId = 11L)
        assertSame(original, BillMutationService.alignAgainstTree(original, treePaths, treeIndex))
    }

    @Test
    fun `a dangling category id is cleared the way the foreign key would`() {
        // 分类被删掉后，从回收站还原的账单可能带着一个悬空 id。
        // 留着它，账单既进不了分类预算（IN 匹配不上），又永远挂在旧名字下。
        val aligned = BillMutationService.alignAgainstTree(
            bill(Bill.TYPE_EXPENSE, "吃的 - 三餐", categoryId = 999L), treePaths, treeIndex
        )
        assertNull(aligned.categoryId)
        assertEquals("吃的 - 三餐", aligned.categoryName)
    }

    @Test
    fun `an unlinked bill is linked through its unique leaf name`() {
        val aligned = BillMutationService.alignAgainstTree(
            bill(Bill.TYPE_EXPENSE, "吃的 - 三餐"), treePaths, treeIndex
        )
        assertEquals(11L, aligned.categoryId)
    }

    @Test
    fun `an unlinked ambiguous name is left alone`() {
        val aligned = BillMutationService.alignAgainstTree(
            bill(Bill.TYPE_EXPENSE, "吃的 - 衣服"), treePaths, treeIndex
        )
        assertNull(aligned.categoryId)
    }

    @Test
    fun `transfers are never touched`() {
        val original = bill(Bill.TYPE_TRANSFER, "转账到招行")
        assertSame(original, BillMutationService.alignAgainstTree(original, treePaths, treeIndex))
    }
}
