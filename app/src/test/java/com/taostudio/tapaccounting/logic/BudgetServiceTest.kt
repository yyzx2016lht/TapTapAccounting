package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BudgetServiceTest {

    @Test
    fun buildProgress_calculatesRemainingDaysAndDailyAllowance() {
        val progress = BudgetService.buildProgress(
            budgetAmount = 3100.0,
            usedAmount = 500.0,
            alertThreshold = 0.8,
            yearMonth = "2026-07",
            nowMillis = millis(2026, 7, 6)
        )

        assertEquals(31, progress.daysInMonth)
        assertEquals(6, progress.elapsedDays)
        assertEquals(26, progress.remainingDays)
        assertEquals(100.0, progress.dailyRemainingAllowance, 0.0001)
        assertEquals(6.0 / 31.0, progress.timeProgress, 0.0001)
    }

    @Test
    fun buildProgress_warnsWhenSpendingIsFastForCalendarProgress() {
        val progress = BudgetService.buildProgress(
            budgetAmount = 1000.0,
            usedAmount = 700.0,
            alertThreshold = 0.8,
            yearMonth = "2026-07",
            nowMillis = millis(2026, 7, 5)
        )

        assertEquals(BudgetService.BudgetStatus.WARNING, progress.status)
        assertEquals(BudgetService.BudgetPace.AHEAD, progress.pace)
        assertEquals(BudgetService.BudgetRiskReason.SPENDING_FAST, progress.riskReason)
    }

    @Test
    fun buildProgress_keepsSamePercentNormalNearMonthEnd() {
        val progress = BudgetService.buildProgress(
            budgetAmount = 1000.0,
            usedAmount = 700.0,
            alertThreshold = 0.8,
            yearMonth = "2026-07",
            nowMillis = millis(2026, 7, 30)
        )

        assertEquals(BudgetService.BudgetStatus.NORMAL, progress.status)
        assertEquals(BudgetService.BudgetPace.HAS_ROOM, progress.pace)
        assertEquals(BudgetService.BudgetRiskReason.NONE, progress.riskReason)
    }

    @Test
    fun buildProgress_marksExceededAndNoDailyAllowanceWhenOverspent() {
        val progress = BudgetService.buildProgress(
            budgetAmount = 1000.0,
            usedAmount = 1200.0,
            alertThreshold = 0.8,
            yearMonth = "2026-07",
            nowMillis = millis(2026, 7, 20)
        )

        assertEquals(BudgetService.BudgetStatus.EXCEEDED, progress.status)
        assertEquals(-200.0, progress.remaining, 0.0001)
        assertEquals(0.0, progress.dailyRemainingAllowance, 0.0001)
        assertEquals(BudgetService.BudgetRiskReason.EXCEEDED, progress.riskReason)
    }

    @Test
    fun buildProgress_marksPastMonthClosedAndKeepsRemaining() {
        // 10 月查看 9 月预算：月份已结束、还有剩余。
        // 此时每日额度恒为 0，界面据 isMonthClosed 换成"本月已结束"，不再显示"每天 ¥0"。
        val progress = BudgetService.buildProgress(
            budgetAmount = 1500.0,
            usedAmount = 1420.19,
            alertThreshold = 0.8,
            yearMonth = "2026-09",
            nowMillis = millis(2026, 10, 10)
        )

        assertEquals(0, progress.remainingDays)
        assertTrue(progress.isMonthClosed)
        assertEquals(79.81, progress.remaining, 0.0001)
        assertEquals(0.0, progress.dailyRemainingAllowance, 0.0001)
    }

    @Test
    fun buildProgress_currentAndFutureMonthsAreNotClosed() {
        val current = BudgetService.buildProgress(
            budgetAmount = 1000.0,
            usedAmount = 200.0,
            alertThreshold = 0.8,
            yearMonth = "2026-10",
            nowMillis = millis(2026, 10, 10)
        )
        val future = BudgetService.buildProgress(
            budgetAmount = 1000.0,
            usedAmount = 0.0,
            alertThreshold = 0.8,
            yearMonth = "2026-11",
            nowMillis = millis(2026, 10, 10)
        )

        assertFalse(current.isMonthClosed)
        assertFalse(future.isMonthClosed)
        assertTrue(current.dailyRemainingAllowance > 0.0)
        assertTrue(future.dailyRemainingAllowance > 0.0)
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
