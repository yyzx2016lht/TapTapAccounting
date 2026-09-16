package com.taostudio.tapaccounting.widget

import android.content.Context
import java.util.Calendar

/**
 * 桌面小组件支持的三种尺寸。每种尺寸对应一个独立的 AppWidgetProvider，
 * 这样用户在系统"添加小组件"选择器里能直接看到三个不同大小的条目。
 */
enum class WidgetSize {
    COMPACT,       // 支出金额 2x1
    STANDARD,      // 预算金额 2x1
    TODAY_BUDGET,  // 今日花费与预算剩余 2x1
    DETAILED       // 财务概览 4x2
}

/** 小组件展示的统计周期。 */
enum class WidgetPeriod {
    TODAY,
    THIS_MONTH,
    THIS_WEEK,
    LAST_7_DAYS;

    fun label(): String = when (this) {
        TODAY -> "今日"
        THIS_MONTH -> "本月"
        THIS_WEEK -> "本周"
        LAST_7_DAYS -> "最近7日"
    }

    /** 返回 [start, end] 闭区间的毫秒时间戳，与统计页的日期口径保持一致（周一为周首）。 */
    fun range(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (this) {
            TODAY -> {
                setStartOfDay(cal)
                val start = cal.timeInMillis
                setEndOfDay(cal)
                start to cal.timeInMillis
            }
            THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                setStartOfDay(cal)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            THIS_WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                setStartOfDay(cal)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 6)
                setEndOfDay(cal)
                start to cal.timeInMillis
            }
            LAST_7_DAYS -> {
                setEndOfDay(cal)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, -6)
                setStartOfDay(cal)
                cal.timeInMillis to end
            }
        }
    }

    private fun setStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun setEndOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
    }
}


/** 样式由用户添加的小组件类型决定；实例配置只负责数据范围。 */
data class WidgetConfig(
    val bookName: String,
    val period: WidgetPeriod = WidgetPeriod.THIS_MONTH
) {
    companion object {
        fun default(context: Context): WidgetConfig {
            return WidgetConfig(
                bookName = com.taostudio.tapaccounting.BookAccountManager.getSelectedBook(context)
            )
        }
    }
}
