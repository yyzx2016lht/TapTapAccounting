package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.logic.BillEntryDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BillEntryDefaultsTest {

    private fun millis(y: Int, m: Int, d: Int, h: Int = 21, min: Int = 28, s: Int = 9): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, s)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun parts(ts: Long): List<Int> =
        Calendar.getInstance().apply { timeInMillis = ts }.let {
            listOf(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH) + 1,
                it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY),
                it.get(Calendar.MINUTE),
                it.get(Calendar.SECOND)
            )
        }

    @Test
    fun viewingCurrentMonthKeepsNow() {
        val now = millis(2026, 10, 1)
        assertNull(BillEntryDefaults.defaultTimeForViewedMonth(now, 2026, 10))
    }

    @Test
    fun viewingPastMonthUsesMonthEnd() {
        // 2026-10-01 深夜在 9 月列表里记一笔 → 2026-09-30（前一天），时分秒保留
        val now = millis(2026, 10, 1)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2026, 9)
        assertEquals(listOf(2026, 9, 30, 21, 28, 9), parts(ts!!))
    }

    @Test
    fun viewingPastMonthUsesMonthEndEvenMidMonth() {
        val now = millis(2026, 10, 15, 9, 5, 0)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2026, 9)
        assertEquals(listOf(2026, 9, 30, 9, 5, 0), parts(ts!!))
    }

    @Test
    fun viewingFutureMonthUsesMonthStart() {
        val now = millis(2026, 10, 15, 9, 5, 0)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2026, 11)
        assertEquals(listOf(2026, 11, 1, 9, 5, 0), parts(ts!!))
    }

    @Test
    fun viewingOtherYearSameMonthNumberIsPast() {
        val now = millis(2026, 10, 1)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2025, 10)
        assertEquals(listOf(2025, 10, 31, 21, 28, 9), parts(ts!!))
    }

    @Test
    fun pastMonthUsesNonLeapFebruaryEnd() {
        val now = millis(2027, 3, 15, 8, 0, 0)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2027, 2)
        assertEquals(listOf(2027, 2, 28, 8, 0, 0), parts(ts!!))
    }

    @Test
    fun pastMonthUsesLeapFebruaryEnd() {
        val now = millis(2028, 3, 15, 8, 0, 0)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2028, 2)
        assertEquals(listOf(2028, 2, 29, 8, 0, 0), parts(ts!!))
    }

    @Test
    fun pastThirtyDayMonthEndsAtThirty() {
        val now = millis(2026, 10, 31, 12, 0, 0)
        val ts = BillEntryDefaults.defaultTimeForViewedMonth(now, 2026, 9)
        assertEquals(listOf(2026, 9, 30, 12, 0, 0), parts(ts!!))
    }
}
