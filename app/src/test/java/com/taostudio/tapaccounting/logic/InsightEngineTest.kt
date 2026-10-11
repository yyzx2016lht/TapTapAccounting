package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.insight.InsightAction
import com.taostudio.tapaccounting.logic.insight.InsightEngine
import com.taostudio.tapaccounting.logic.insight.InsightReport
import com.taostudio.tapaccounting.logic.insight.InsightSeverity
import com.taostudio.tapaccounting.logic.insight.InsightType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InsightEngineTest {

    private val march = monthStart(2024, Calendar.MARCH)
    private val afterMarch = at(2024, Calendar.APRIL, 5)

    @Test
    fun finishedMonth_spendDrop_isPositiveHeadline() {
        val current = listOf(
            expense(100.0, "餐饮", Calendar.MARCH, 3),
            expense(120.0, "交通", Calendar.MARCH, 8),
            expense(80.0, "日用", Calendar.MARCH, 12)
        )
        val previous = listOf(
            expense(300.0, "餐饮", Calendar.FEBRUARY, 3),
            expense(250.0, "交通", Calendar.FEBRUARY, 8),
            expense(250.0, "日用", Calendar.FEBRUARY, 12)
        )

        val report = generate(current, previous, now = afterMarch)

        val headline = report.cards.first()
        assertEquals(InsightType.MONTH_TOTAL_DELTA, headline.type)
        assertEquals(InsightSeverity.POSITIVE, headline.severity)
        assertEquals("比上月少花 ¥500", headline.title)
        assertEquals("对比上月", report.comparisonLabel)
    }

    @Test
    fun inProgressMonth_comparesAgainstSamePeriodOfLastMonth() {
        // 3 月 10 日：本月 1–10 日花了 300，上月 1–10 日也花了 300，但上月全月花了 3000。
        val current = listOf(
            expense(100.0, "餐饮", Calendar.MARCH, 2),
            expense(100.0, "餐饮", Calendar.MARCH, 5),
            expense(100.0, "餐饮", Calendar.MARCH, 9)
        )
        val previous = listOf(
            expense(150.0, "餐饮", Calendar.FEBRUARY, 3),
            expense(150.0, "餐饮", Calendar.FEBRUARY, 8),
            expense(2700.0, "餐饮", Calendar.FEBRUARY, 20)
        )

        val report = generate(current, previous, now = at(2024, Calendar.MARCH, 10))

        val headline = report.cards.first { it.type == InsightType.MONTH_TOTAL_DELTA }
        assertEquals("支出与上月同期基本持平", headline.title)
        assertEquals("对比上月 1–10 日", report.comparisonLabel)
    }

    @Test
    fun unlinkedRefund_reducesSpendLikeStatsPage() {
        val current = listOf(
            expense(500.0, "购物", Calendar.MARCH, 3),
            expense(100.0, "餐饮", Calendar.MARCH, 4),
            expense(100.0, "餐饮", Calendar.MARCH, 5),
            refund(400.0, "退款：购物", Calendar.MARCH, 6)
        )
        val previous = listOf(
            expense(300.0, "购物", Calendar.FEBRUARY, 3),
            expense(100.0, "餐饮", Calendar.FEBRUARY, 4)
        )

        val report = generate(current, previous, now = afterMarch)

        val headline = report.cards.first()
        assertTrue(headline.body.startsWith("本月 ¥300"))
    }

    @Test
    fun categoryChanges_groupByProvidedTopLevelCategory() {
        val current = listOf(
            expense(200.0, "餐饮>午餐", Calendar.MARCH, 3),
            expense(200.0, "餐饮>晚餐", Calendar.MARCH, 4),
            expense(100.0, "交通", Calendar.MARCH, 5)
        )
        val previous = listOf(
            expense(100.0, "餐饮>午餐", Calendar.FEBRUARY, 3),
            expense(100.0, "交通", Calendar.FEBRUARY, 5)
        )

        val report = generate(current, previous, now = afterMarch, categoryOf = { it.categoryName.substringBefore('>') })

        val card = report.cards.first { it.type == InsightType.MONTH_CATEGORY_DELTA }
        assertEquals("餐饮比上月多花 ¥300", card.title)
        assertEquals(InsightAction.OpenCategory("餐饮"), card.action)
    }

    @Test
    fun largeExpense_detectedAnywhereInMonth() {
        val current = listOf(
            expense(20.0, "餐饮", Calendar.MARCH, 1),
            expense(30.0, "餐饮", Calendar.MARCH, 2),
            expense(25.0, "餐饮", Calendar.MARCH, 3),
            expense(35.0, "餐饮", Calendar.MARCH, 4),
            expense(800.0, "购物", Calendar.MARCH, 5, id = 88)
        )

        val report = generate(current, emptyList(), now = afterMarch)

        val card = report.cards.firstOrNull { it.type == InsightType.LARGE_EXPENSE }
        assertNotNull(card)
        assertEquals(InsightAction.OpenBill(88), card!!.action)
    }

    @Test
    fun noPreviousData_inProgress_showsProjectionWithoutExtrapolatingOneOff() {
        val current = listOf(
            expense(3000.0, "住房", Calendar.MARCH, 1),
            expense(50.0, "餐饮", Calendar.MARCH, 2),
            expense(50.0, "餐饮", Calendar.MARCH, 4),
            expense(50.0, "餐饮", Calendar.MARCH, 6),
            expense(50.0, "餐饮", Calendar.MARCH, 9)
        )

        val report = generate(current, emptyList(), now = at(2024, Calendar.MARCH, 10))

        val headline = report.cards.first()
        assertEquals(InsightType.MONTH_PROJECTION, headline.type)
        // 3000 房租不外推：3000 + 200 / 10 * 31 = 3620
        assertEquals("按此节奏，本月预计支出 ¥3,620", headline.title)
    }

    @Test
    fun futureMonth_returnsEmptyReport() {
        val report = generate(emptyList(), emptyList(), now = at(2024, Calendar.FEBRUARY, 10))
        assertEquals(InsightReport.EMPTY, report)
    }

    @Test
    fun earlyInMonth_skipsComparisons() {
        val current = listOf(expense(500.0, "餐饮", Calendar.MARCH, 1))
        val previous = listOf(expense(50.0, "餐饮", Calendar.FEBRUARY, 1))

        val report = generate(current, previous, now = at(2024, Calendar.MARCH, 2))

        assertFalse(report.cards.any { it.type == InsightType.MONTH_TOTAL_DELTA })
    }

    @Test
    fun sameInput_producesStableIds() {
        val current = listOf(
            expense(100.0, "餐饮", Calendar.MARCH, 1),
            expense(200.0, "餐饮", Calendar.MARCH, 2),
            expense(300.0, "交通", Calendar.MARCH, 3)
        )
        val previous = listOf(
            expense(60.0, "餐饮", Calendar.FEBRUARY, 1),
            expense(80.0, "餐饮", Calendar.FEBRUARY, 2),
            expense(100.0, "交通", Calendar.FEBRUARY, 3)
        )

        val first = generate(current, previous, now = afterMarch).cards.map { it.id }
        val second = generate(current, previous, now = afterMarch).cards.map { it.id }

        assertEquals(first, second)
    }

    private fun generate(
        current: List<Bill>,
        previous: List<Bill>,
        now: Long,
        categoryOf: (Bill) -> String = { it.categoryName.removePrefix("退款：") }
    ) = InsightEngine.generate(
        currentBills = current,
        previousBills = previous,
        monthStart = march,
        now = now,
        amountOf = { it.amount },
        categoryOf = categoryOf
    )

    private fun monthStart(year: Int, month: Int) = Calendar.getInstance().apply {
        clear()
        set(year, month, 1, 0, 0, 0)
    }.timeInMillis

    private fun at(year: Int, month: Int, day: Int) = Calendar.getInstance().apply {
        clear()
        set(year, month, day, 12, 0, 0)
    }.timeInMillis

    private fun expense(amount: Double, category: String, month: Int, day: Int, id: Long = month * 100L + day) =
        Bill(id = id, type = Bill.TYPE_EXPENSE, amount = amount, categoryName = category, time = at(2024, month, day))

    private fun refund(amount: Double, category: String, month: Int, day: Int) =
        Bill(
            id = 9_000L + day,
            type = Bill.TYPE_EXPENSE,
            subType = Bill.SUBTYPE_REFUND,
            amount = amount,
            categoryName = category,
            time = at(2024, month, day)
        )
}
