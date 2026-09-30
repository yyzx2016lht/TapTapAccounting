package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 金额格式化是纯函数，不依赖 Android 上下文（全部显式传 useGrouping）。
 */
class AmountFormatHelperTest {

    @Test
    fun formatAmount_keeps_twoDecimals() {
        assertEquals("13.00", AmountFormatHelper.formatAmount(13.0, false))
        assertEquals("89.81", AmountFormatHelper.formatAmount(89.81, false))
        assertEquals("13.50", AmountFormatHelper.formatAmount(13.5, false))
    }

    @Test
    fun formatAmount_supportsGrouping() {
        assertEquals("1,420.19", AmountFormatHelper.formatAmount(1420.19, true))
        assertEquals("1420.19", AmountFormatHelper.formatAmount(1420.19, false))
    }

    /**
     * 本次修复的核心：日历里 13.00 曾显示为 "13"，而 89.81 显示 "89.81"，
     * 同一网格内小数位不一致。compact 模式是显式、可预期的。
     */
    @Test
    fun formatCompact_dropsTrailingZerosOnly() {
        assertEquals("13", AmountFormatHelper.formatCompact(13.0, false))
        assertEquals("89.81", AmountFormatHelper.formatCompact(89.81, false))
        assertEquals("13.5", AmountFormatHelper.formatCompact(13.5, false))
        assertEquals("0.01", AmountFormatHelper.formatCompact(0.01, false))
        assertEquals("0", AmountFormatHelper.formatCompact(0.0, false))
    }

    /** 负号必须保留：日历支出显示为 -13 而不是 13。 */
    @Test
    fun formatCompact_preservesSign() {
        assertEquals("-13", AmountFormatHelper.formatCompact(-13.0, false))
        assertEquals("-89.81", AmountFormatHelper.formatCompact(-89.81, false))
    }

    @Test
    fun formatCompact_supportsGrouping() {
        assertEquals("1,420", AmountFormatHelper.formatCompact(1420.0, true))
        assertEquals("1,420.19", AmountFormatHelper.formatCompact(1420.19, true))
    }

    /**
     * 抹零不得吞掉有效小数位。旧实现用 endsWith(".00") 判断，
     * 对 0.10 这类值是安全的，但下面这些边界必须成立。
     */
    @Test
    fun formatCompact_keepsSignificantPrecision() {
        assertEquals("0.1", AmountFormatHelper.formatCompact(0.1, false))
        assertEquals("1.005", AmountFormatHelper.formatCompact(1.005, false))
        assertEquals("100.01", AmountFormatHelper.formatCompact(100.01, false))
    }

    @Test
    fun formatSigned_prefixesPositiveOnly() {
        assertEquals("收100.00", AmountFormatHelper.formatSigned(100.0, "收", false))
        assertEquals("-100.00", AmountFormatHelper.formatSigned(-100.0, "收", false))
    }

    @Test
    fun formatCurrency_appendsSymbol() {
        assertEquals("¥13.00", AmountFormatHelper.formatCurrency("¥", 13.0, false))
        assertEquals("¥-13.00", AmountFormatHelper.formatCurrency("¥", -13.0, false))
    }

    /**
     * compact 与 standard 只应在「末位为零」时不同；
     * 末位非零时两者必须完全一致，否则列表内会出现 -13 与 -89.81 那种混排。
     */
    @Test
    fun compactAndStandard_agreeWhenTrailingDigitsAreSignificant() {
        val samples = listOf(89.81, 0.01, 1420.19, 100.01, 6.34, 20.17)
        for (v in samples) {
            assertEquals(
                "mismatch for $v",
                AmountFormatHelper.formatAmount(v, false),
                AmountFormatHelper.formatCompact(v, false)
            )
        }
    }

    /** 末位为零时 compact 才允许更短，这是它唯一的差异来源。 */
    @Test
    fun compactDiffersFromStandardOnlyForTrailingZero() {
        assertEquals("13", AmountFormatHelper.formatCompact(13.0, false))
        assertEquals("13.00", AmountFormatHelper.formatAmount(13.0, false))
        assertEquals("13.5", AmountFormatHelper.formatCompact(13.5, false))
        assertEquals("13.50", AmountFormatHelper.formatAmount(13.5, false))
    }

    @Test
    fun largeValues_doNotOverflow() {
        val big = 123456789.12
        assertTrue(AmountFormatHelper.formatAmount(big, false).isNotEmpty())
        assertTrue(AmountFormatHelper.formatCompact(big, false).isNotEmpty())
    }
}
