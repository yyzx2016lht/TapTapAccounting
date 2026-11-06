package com.taostudio.tapaccounting.capturebridge

import com.taostudio.tapaccounting.logic.SplitAmounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 拆分记账单元测试：
 * 1. 3 条明细（可乐 3 元、西瓜 15 元、兰州拉面 22 元，总计 40 元）正常拆解；
 * 2. 合计不等阻断校验；
 * 3. 只有 1 条且金额相等时平滑降级判断；
 * 4. 只有 1 条但金额不相等时差额提示；
 * 5. 差额自动调整填补。
 */
class SplitBillTest {

    @Test
    fun `three items normal split exactly matches total`() {
        val totalCents = 4000L // 40 元
        val rawItems = listOf(
            SplitAmounts.RawItem("可乐", 300L),       // 3 元
            SplitAmounts.RawItem("西瓜", 1500L),      // 15 元
            SplitAmounts.RawItem("兰州拉面", 2200L),   // 22 元
        )

        val outcome = SplitAmounts.validate(totalCents, rawItems)
        assertTrue(outcome is SplitAmounts.Outcome.Ok)
        val ok = outcome as SplitAmounts.Outcome.Ok

        assertEquals(4000L, ok.sumCents)
        assertEquals(0L, ok.diffCents)
        assertEquals(3, ok.items.size)
        assertEquals(listOf("可乐", "西瓜", "兰州拉面"), ok.items.map { it.name })
        assertEquals(listOf(300L, 1500L, 2200L), ok.items.map { it.amountCents })

        // 无法降级为单笔
        val canDegrade = ok.items.size == 1 && ok.items[0].amountCents == totalCents
        assertFalse(canDegrade)
    }

    @Test
    fun `total exceeds rejects split`() {
        val totalCents = 4000L // 40 元
        val rawItems = listOf(
            SplitAmounts.RawItem("可乐", 300L),
            SplitAmounts.RawItem("西瓜", 1500L),
            SplitAmounts.RawItem("兰州拉面", 2500L), // 总计 4300L > 4000L
        )

        val outcome = SplitAmounts.validate(totalCents, rawItems)
        assertTrue(outcome is SplitAmounts.Outcome.Error)
        val err = outcome as SplitAmounts.Outcome.Error
        assertEquals("split_total_exceeds", err.code)
    }

    @Test
    fun `total less than sum reports gap difference`() {
        val totalCents = 4000L // 40 元
        val rawItems = listOf(
            SplitAmounts.RawItem("可乐", 300L),
            SplitAmounts.RawItem("西瓜", 1500L),
            // 总计 1800L < 4000L，缺少 2200L
        )

        val outcome = SplitAmounts.validate(totalCents, rawItems)
        assertTrue(outcome is SplitAmounts.Outcome.Ok)
        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(1800L, ok.sumCents)
        assertEquals(2200L, ok.diffCents)
    }

    @Test
    fun `single item with equal amount gracefully degrades to single bill`() {
        val totalCents = 4000L
        val rawItems = listOf(
            SplitAmounts.RawItem("兰州拉面套餐", 4000L)
        )

        val outcome = SplitAmounts.validate(totalCents, rawItems)
        assertTrue(outcome is SplitAmounts.Outcome.Ok)
        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(4000L, ok.sumCents)
        assertEquals(0L, ok.diffCents)

        val canDegrade = ok.items.size == 1 && ok.items[0].amountCents == totalCents
        assertTrue("单条且金额相等时应平滑降级为单笔记账", canDegrade)
    }

    @Test
    fun `single item with less amount cannot degrade and reports difference`() {
        val totalCents = 4000L
        val rawItems = listOf(
            SplitAmounts.RawItem("可乐", 300L)
        )

        val outcome = SplitAmounts.validate(totalCents, rawItems)
        assertTrue(outcome is SplitAmounts.Outcome.Ok)
        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(300L, ok.sumCents)
        assertEquals(3700L, ok.diffCents)

        val canDegrade = ok.items.size == 1 && ok.items[0].amountCents == totalCents
        assertFalse(canDegrade)
    }

    @Test
    fun `differenceAdjustment fills remaining gap`() {
        val totalCents = 4000L
        val baseItems = listOf(
            SplitAmounts.Item("可乐", 300L),
            SplitAmounts.Item("西瓜", 1500L),
        )
        val diffCents = totalCents - baseItems.sumOf { it.amountCents } // 2200L

        val adjusted = SplitAmounts.withDifferenceAdjustment(
            items = baseItems,
            diffCents = diffCents,
            categoryName = "其他",
        )
        assertEquals(3, adjusted.size)
        assertEquals("补差额调整", adjusted.last().name)
        assertEquals(2200L, adjusted.last().amountCents)
        assertEquals(totalCents, adjusted.sumOf { it.amountCents })
    }
}
