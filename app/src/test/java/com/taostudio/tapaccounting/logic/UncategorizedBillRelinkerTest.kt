package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「把只有名字的账单挂回分类」的匹配规则。
 *
 * 这些规则决定分类预算能不能算上历史账单——填错会把支出记到别的分类，填漏只是少算，
 * 所以宁可漏不可错，歧义一律返回 null。
 */
class UncategorizedBillRelinkerTest {

    private val mealId = 11L
    private val salaryId = 22L
    private val clothingExpenseId = 33L

    /** 只包含「全表唯一」的组合，与 [UncategorizedBillRelinker.relink] 的入参一致。 */
    private val unique = mapOf(
        ("三餐" to Bill.TYPE_EXPENSE) to mealId,
        ("工资" to Bill.TYPE_INCOME) to salaryId,
        ("衣服" to Bill.TYPE_EXPENSE) to clothingExpenseId
    )

    @Test
    fun `display path resolves to its leaf category`() {
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("吃的 - 三餐", Bill.TYPE_EXPENSE, unique)
        )
    }

    @Test
    fun `bare leaf name resolves as-is`() {
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("三餐", Bill.TYPE_EXPENSE, unique)
        )
    }

    @Test
    fun `legacy separators still resolve after normalization`() {
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("吃的 > 三餐", Bill.TYPE_EXPENSE, unique)
        )
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("吃的/::/三餐", Bill.TYPE_EXPENSE, unique)
        )
    }

    @Test
    fun `refund names look up the expense category despite being income bills`() {
        // 退款账单 type=TYPE_INCOME，但 categoryId 继承原支出分类（saveRefundBill）。
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("退款：吃的 - 三餐", Bill.TYPE_INCOME, unique)
        )
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("退款·吃的 - 三餐", Bill.TYPE_INCOME, unique)
        )
    }

    @Test
    fun `refund of a root category resolves without any path separator`() {
        // 真机回归：退款挂在**根分类**上时名字里没有 " - "，leafName 切不出东西，
        // 若不在取叶子名前剥掉「退款：」前缀，得到的叶子名是 "退款：三餐" 而永远匹配不到分类。
        // （旧用例都用带路径的名字，恰好绕过了这个缺口。）
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("退款：三餐", Bill.TYPE_INCOME, unique)
        )
        assertEquals(
            mealId,
            UncategorizedBillRelinker.resolveCategoryId("退款·三餐", Bill.TYPE_INCOME, unique)
        )
        // 退款挂支出分类：即便支出侧唯一，也必须按支出类型查
        assertEquals(
            clothingExpenseId,
            UncategorizedBillRelinker.resolveCategoryId("退款：衣服", Bill.TYPE_INCOME, unique)
        )
    }

    @Test
    fun `refund whose stripped leaf no longer exists resolves to null`() {
        assertNull(
            UncategorizedBillRelinker.resolveCategoryId("退款：已删除分类", Bill.TYPE_INCOME, unique)
        )
    }

    @Test
    fun `a leaf that is not unique is skipped instead of guessed`() {
        // 「衣服」在收入侧也存在同名分类 → 该组合不在 unique 表里 → 必须跳过
        assertNull(
            UncategorizedBillRelinker.resolveCategoryId("衣服", Bill.TYPE_INCOME, unique)
        )
    }

    @Test
    fun `type mismatch and unknown leaf both resolve to null`() {
        // 名字对得上但收支类型不符（支出分类不会挂在收入账单上）
        assertNull(
            UncategorizedBillRelinker.resolveCategoryId("三餐", Bill.TYPE_INCOME, unique)
        )
        // 分类已被删除
        assertNull(
            UncategorizedBillRelinker.resolveCategoryId("吃的 - 已删除分类", Bill.TYPE_EXPENSE, unique)
        )
    }

    @Test
    fun `blank names resolve to null`() {
        assertNull(UncategorizedBillRelinker.resolveCategoryId("", Bill.TYPE_EXPENSE, unique))
        assertNull(UncategorizedBillRelinker.resolveCategoryId("   ", Bill.TYPE_EXPENSE, unique))
        assertNull(UncategorizedBillRelinker.resolveCategoryId(" - ", Bill.TYPE_EXPENSE, unique))
    }

    @Test
    fun `index keeps a name that appears once per type`() {
        // 跨类型同名（支出一个「衣服」、收入一个「衣服」）不算歧义，类型本身就能区分。
        val index = UncategorizedBillRelinker.uniqueLeafTypeIndex(
            listOf(
                Category(id = 47, name = "衣服", type = 0),
                Category(id = 89, name = "衣服", type = 1)
            )
        )
        assertEquals(47L, index["衣服" to 0])
        assertEquals(89L, index["衣服" to 1])
    }

    @Test
    fun `index drops a name duplicated within the same type`() {
        // 同类型重名无法判断归属，必须整组丢弃，否则会把支出记到另一个分类上。
        val index = UncategorizedBillRelinker.uniqueLeafTypeIndex(
            listOf(
                Category(id = 47, name = "衣服", type = 0),
                Category(id = 89, name = "衣服", type = 0)
            )
        )
        assertNull(index["衣服" to 0])
        assertTrue(index.isEmpty())
    }

    @Test
    fun `index reflects the duplicate pairs seen on a real device`() {
        // 真机上出现过 5 对同名分类，这里各取一种走向验证。
        val index = UncategorizedBillRelinker.uniqueLeafTypeIndex(
            listOf(
                Category(id = 60, name = "理财产品", type = 0),
                Category(id = 69, name = "理财产品", type = 0), // 同类型重名 → 跳过
                Category(id = 29, name = "其它", type = 0),
                Category(id = 43, name = "其它", type = 1) // 跨类型 → 可判断
            )
        )
        assertNull(index["理财产品" to 0])
        assertEquals(29L, index["其它" to 0])
        assertEquals(43L, index["其它" to 1])
    }
}
