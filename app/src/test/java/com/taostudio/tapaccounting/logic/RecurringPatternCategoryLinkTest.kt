package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 周期模式的分类关联：**以 categoryId 为准，categoryName 只是展示快照**。
 *
 * 背景：分类改名 / 提升 / 降级 / 换父级后，`categoryName`（"吃的 - 三餐"）这份快照会过期。
 * 周期模式有两处按名字关联的地方，都会因此静默出错：
 *
 * 1. 去重签名（[RecurringBillingService.patternSignature] 与 `RecurringPatternDao.getBySignature`）
 *    按名字算 —— 改名后旧 pattern 与新候选对不上，会重复登记一条；已 CONFIRMED 的旧记录
 *    不在清理范围内，于是永久留下两条。
 * 2. 编辑保存时按旧名反查分类 id —— 查不到就写 null，用户只是改个金额，分类关联就丢了。
 */
class RecurringPatternCategoryLinkTest {

    private fun signature(categoryId: Long?, categoryName: String?) =
        RecurringBillingService.patternSignature(
            merchantKey = "星巴克",
            bookName = "默认账本",
            categoryId = categoryId,
            categoryName = categoryName,
            accountName = "微信",
            toAccountName = "",
            billType = Bill.TYPE_EXPENSE,
            billSubType = Bill.SUBTYPE_NORMAL
        )

    // ---------- 去重签名 ----------

    @Test
    fun `renaming the category keeps the signature stable`() {
        // 分类 11 从「吃的 - 三餐」改名为「吃的 - 早餐」：签名必须不变，
        // 否则同一个周期会被重复登记成两条
        assertEquals(signature(11L, "吃的 - 三餐"), signature(11L, "吃的 - 早餐"))
    }

    @Test
    fun `an id is enough even when the name snapshot is gone`() {
        assertEquals(signature(11L, null), signature(11L, "吃的 - 三餐"))
    }

    @Test
    fun `different categories stay apart`() {
        assertNotEquals(signature(11L, "吃的 - 三餐"), signature(22L, "吃的 - 三餐"))
    }

    @Test
    fun `legacy patterns without an id still fall back to the name`() {
        // 历史数据没有 categoryId，只能按名字比——此时改名确实会分叉，属于已知的降级行为
        assertEquals(signature(null, "吃的 - 三餐"), signature(null, "吃的 - 三餐"))
        assertNotEquals(signature(null, "吃的 - 三餐"), signature(null, "吃的 - 早餐"))
    }

    @Test
    fun `an identified pattern never collides with an id-less one`() {
        assertNotEquals(signature(11L, "吃的 - 三餐"), signature(null, "吃的 - 三餐"))
    }

    @Test
    fun `the other dimensions still participate`() {
        val base = signature(11L, "吃的 - 三餐")
        val otherBook = RecurringBillingService.patternSignature(
            merchantKey = "星巴克",
            bookName = "旅行账本",
            categoryId = 11L,
            categoryName = "吃的 - 三餐",
            accountName = "微信",
            toAccountName = "",
            billType = Bill.TYPE_EXPENSE,
            billSubType = Bill.SUBTYPE_NORMAL
        )
        val otherMerchant = RecurringBillingService.patternSignature(
            merchantKey = "瑞幸",
            bookName = "默认账本",
            categoryId = 11L,
            categoryName = "吃的 - 三餐",
            accountName = "微信",
            toAccountName = "",
            billType = Bill.TYPE_EXPENSE,
            billSubType = Bill.SUBTYPE_NORMAL
        )
        val otherType = RecurringBillingService.patternSignature(
            merchantKey = "星巴克",
            bookName = "默认账本",
            categoryId = 11L,
            categoryName = "吃的 - 三餐",
            accountName = "微信",
            toAccountName = "",
            billType = Bill.TYPE_INCOME,
            billSubType = Bill.SUBTYPE_NORMAL
        )
        assertNotEquals(base, otherBook)
        assertNotEquals(base, otherMerchant)
        assertNotEquals(base, otherType)
    }

    // ---------- 编辑保存时的分类解析 ----------

    @Test
    fun `an existing link is kept instead of being re-looked-up by a stale name`() = runBlocking {
        var lookedUp = false
        val resolved = RecurringBillingService.resolvePatternCategoryId(
            keptCategoryId = 11L,
            selectedCategoryName = "吃的 - 三餐",
            billType = Bill.TYPE_EXPENSE
        ) {
            lookedUp = true
            99L
        }
        assertEquals(11L, resolved)
        // 关键：名字已过期，绝不能拿它去反查——反查失败就会把关联清成 null
        assertFalse(lookedUp)
    }

    @Test
    fun `a re-picked category is resolved by name`() = runBlocking {
        val resolved = RecurringBillingService.resolvePatternCategoryId(
            keptCategoryId = null,
            selectedCategoryName = "工资",
            billType = Bill.TYPE_INCOME
        ) { name -> if (name == "工资") 22L else null }
        assertEquals(22L, resolved)
    }

    @Test
    fun `an unresolvable name yields no link`() = runBlocking {
        assertNull(
            RecurringBillingService.resolvePatternCategoryId(
                keptCategoryId = null,
                selectedCategoryName = "已经改掉的旧名",
                billType = Bill.TYPE_EXPENSE
            ) { null }
        )
    }

    @Test
    fun `an empty name yields no link`() = runBlocking {
        assertNull(
            RecurringBillingService.resolvePatternCategoryId(
                keptCategoryId = null,
                selectedCategoryName = "",
                billType = Bill.TYPE_EXPENSE
            ) { 11L }
        )
        assertNull(
            RecurringBillingService.resolvePatternCategoryId(
                keptCategoryId = null,
                selectedCategoryName = null,
                billType = Bill.TYPE_EXPENSE
            ) { 11L }
        )
    }

    @Test
    fun `transfers never carry a category`() = runBlocking {
        // 转账/还款在 UI 上会把分类栏填成「转账」「还款」字样，
        // 若按名字反查，很可能误挂到一个真的叫「转账」的分类上
        assertNull(
            RecurringBillingService.resolvePatternCategoryId(
                keptCategoryId = 11L,
                selectedCategoryName = "转账",
                billType = Bill.TYPE_TRANSFER
            ) { 99L }
        )
    }
}
