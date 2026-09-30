package com.taostudio.tapaccounting

import java.util.Locale
import kotlin.math.abs

/**
 * 金额显示的唯一出口。
 *
 * 背景：此前项目内有 15+ 套各自实现的金额格式化方法，其中几类在同一屏内
 * 混用会导致肉眼可见的不一致：
 *
 *   AmountFormatHelper.formatAmount   "%,.2f"  -> 1420.19
 *   CalendarActivity.formatAmt        "%.2f" 后手工剥掉 ".00" -> 13（而不是 13.00）
 *
 * 也就是说日历里 9 月 15 日显示 "-13"、9 月 24 日显示 "-20"，
 * 而其余日期显示 "-89.81" —— 同一个网格内小数位不统一。
 *
 * 现在统一到本文件，并显式区分两种需求：
 *
 *   format()            固定两位小数。用于金额、合计、预算等需要精确对齐的位置。
 *   formatCompact()     仅在「确定末位为零」时去掉小数尾零。
 *                       注意这是有条件的压缩，不会把 13.5 变成 "13.5 -> 13"，
 *                       也不会造成 -13 与 -89.81 混排那种观感。
 *
 * 抹零属于显示偏好而非数值差异，若产品上需要「一律紧凑」，
 * 请显式传 compact = true，而不是引入第三种隐式行为。
 */
object AmountFormatHelper {

    private const val FORMAT_GROUPED = "%,.2f"
    private const val FORMAT_PLAIN = "%.2f"
    private const val FORMAT_COMPACT = "%.4f"

    fun isGroupingEnabledByDefault(): Boolean {
        return runCatching {
            Prefs.isAmountGroupingEnabled(TapApplication.app())
        }.getOrDefault(true)
    }

    /**
     * 标准金额：固定两位小数，可选千位分隔。
     */
    fun formatAmount(amount: Double, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        val pattern = if (useGrouping) FORMAT_GROUPED else FORMAT_PLAIN
        return String.format(Locale.getDefault(), pattern, amount)
    }

    /**
     * 紧凑金额：末位为零时省略小数部分。13.00 -> 13，13.50 -> 13.5。
     *
     * 用于列表行、图表标签等纵向空间紧张的位置；不要用于需要竖向对齐的合计。
     */
    fun formatCompact(amount: Double, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        // 千位分隔必须交给 formatter 自己做，不能手工把 "," 拼进 pattern，
        // 否则会插到小数点之后（",1420.19"）。
        val pattern = if (useGrouping) "%,.4f" else "%.4f"
        val trimmed = String.format(Locale.getDefault(), pattern, abs(amount))
            .trimEnd('0')
            .trimEnd('.')
        val sign = if (amount < 0) "-" else ""
        return sign + trimmed
    }

    /**
     * 带符号前缀，常用于「收 1,234.00」这类带标签的文本。
     */
    fun formatSigned(amount: Double, prefix: String, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        val body = formatAmount(abs(amount), useGrouping)
        val sign = if (amount < 0) "-" else prefix
        return "$sign$body"
    }

    fun formatCurrency(symbol: String, amount: Double, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        return "$symbol${formatAmount(amount, useGrouping)}"
    }

    /**
     * 比率（年化利率、收益率等）的紧凑显示，最多保留 4 位有效小数。
     *
     * 与 [formatCompact] 分开是因为比率的有效精度远高于金额：
     * 3.45% 需要保留两位，但 0.0325% 也要能如实显示；而金额永远只到分。
     * 项目内此前有 5 处各自实现了同一段 %.4f + trimEnd('0') 逻辑
     * （AccountingFormController、InvestmentLotManagerDialog、
     * InvestmentLotSplitDialog、AssetDetailActivity、AddAssetActivity）。
     */
    fun formatRate(value: Double): String {
        val body = String.format(Locale.getDefault(), "%.4f", abs(value))
            .trimEnd('0')
            .trimEnd('.')
        return if (value < 0) "-$body" else body
    }
}
