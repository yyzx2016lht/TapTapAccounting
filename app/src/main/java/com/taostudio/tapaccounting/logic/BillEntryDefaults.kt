package com.taostudio.tapaccounting.logic

import java.util.Calendar

/**
 * 「记一笔」的默认日期策略。
 *
 * 用户正在浏览某个月份时，新账单默认落进那个月，取该月中离"现在"最近的一天：
 * 浏览过去的月份取月末，浏览未来的月份取月初；时分秒保留当前值。
 * 只有浏览当前月时才用"现在"。
 *
 * 背景：此前无论如何都默认"今天"。在 9 月列表里点 + 记账后账单落进 10 月，
 * 返回列表看不到，用户会以为没记上（见 docs/ui-audit/COMFORT-REVIEW.md 第 11 条）。
 */
object BillEntryDefaults {

    /**
     * @param nowMillis   当前时间
     * @param viewedYear  正在浏览的年份
     * @param viewedMonth 正在浏览的月份（1-12）
     * @return 浏览月与当前月一致时返回 null（调用方继续用当前时间）；
     *         否则返回浏览月内的时间戳：早于当前月取该月最后一天，晚于当前月取第一天，时分秒不变
     */
    fun defaultTimeForViewedMonth(nowMillis: Long, viewedYear: Int, viewedMonth: Int): Long? {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowYear = now.get(Calendar.YEAR)
        val nowMonth = now.get(Calendar.MONTH) + 1
        if (viewedYear == nowYear && viewedMonth == nowMonth) return null

        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.YEAR, viewedYear)
            set(Calendar.MONTH, (viewedMonth - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
        }
        // 先落到 1 号再取该月天数，避免"31 日"之类的日期在 set 时被自动进位到下个月。
        val viewedIsBeforeNow = viewedYear < nowYear || (viewedYear == nowYear && viewedMonth < nowMonth)
        val day = if (viewedIsBeforeNow) target.getActualMaximum(Calendar.DAY_OF_MONTH) else 1
        target.set(Calendar.DAY_OF_MONTH, day)
        return target.timeInMillis
    }
}
