package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputEditorTest {
    @Test
    fun insert_replacesSingleLeadingZero() {
        val result = AmountInputEditor.insert("0", 1, 1, "5")

        assertEquals(AmountInputEdit("5", 1), result)
    }

    @Test
    fun insertDot_startsEmptyAmountWithZero() {
        val result = AmountInputEditor.insert("", 0, 0, ".")

        assertEquals(AmountInputEdit("0.", 2), result)
    }

    @Test
    fun insert_placesDigitAtCursorInsteadOfAppendingToCents() {
        val result = AmountInputEditor.insert("123.45", 1, 1, "9")

        assertEquals(AmountInputEdit("1923.45", 2), result)
    }

    @Test
    fun insert_replacesSelectedDigits() {
        val result = AmountInputEditor.insert("123.45", 0, 3, "9")

        assertEquals(AmountInputEdit("9.45", 1), result)
    }

    @Test
    fun delete_removesCharacterBeforeCursor() {
        val result = AmountInputEditor.delete("123.45", 2, 2)

        assertEquals(AmountInputEdit("13.45", 1), result)
    }

    @Test
    fun delete_removesSelection() {
        val result = AmountInputEditor.delete("123.45", 0, 3)

        assertEquals(AmountInputEdit(".45", 0), result)
    }

    @Test
    fun insertDot_addsLeadingZeroAtStartOfExpressionSegment() {
        val result = AmountInputEditor.insert("12+34", 3, 3, ".")

        assertEquals(AmountInputEdit("12+0.34", 5), result)
    }

    @Test
    fun insertDot_isIgnoredWhenCurrentSegmentAlreadyHasDecimalPoint() {
        val result = AmountInputEditor.insert("12.34+5", 2, 2, ".")

        assertEquals(AmountInputEdit("12.34+5", 2), result)
    }

    @Test
    fun insertOperator_splitsNumberAtCursor() {
        val result = AmountInputEditor.insertOperator("123.45", 3, 3, "+")

        assertEquals(AmountInputEdit("123+.45", 4), result)
    }

    @Test
    fun insertOperator_replacesOperatorImmediatelyBeforeCursor() {
        val result = AmountInputEditor.insertOperator("12+", 3, 3, "×")

        assertEquals(AmountInputEdit("12×", 3), result)
    }

    @Test
    fun insert_capsFractionAtTwoDigits() {
        val result = AmountInputEditor.insert("12.34", 5, 5, "5")

        assertEquals(AmountInputEdit("12.34", 5), result)
    }

    @Test
    fun insert_fillsFractionUpToTheCap() {
        val result = AmountInputEditor.insert("12.3", 4, 4, "4")

        assertEquals(AmountInputEdit("12.34", 5), result)
    }

    @Test
    fun insert_capOnlyAppliesWhenCursorIsAfterTheDecimalPoint() {
        // 光标在整数位时仍可插入，不受小数位上限影响
        val result = AmountInputEditor.insert("12.34", 1, 1, "9")

        assertEquals(AmountInputEdit("192.34", 2), result)
    }

    @Test
    fun insert_respectsConfiguredMaxDecimals() {
        val result = AmountInputEditor.insert("12.34", 5, 5, "5", maxDecimals = 3)

        assertEquals(AmountInputEdit("12.345", 6), result)
    }

    @Test
    fun insert_capIsPerExpressionSegment() {
        val result = AmountInputEditor.insert("12.34+5", 7, 7, "6")

        assertEquals(AmountInputEdit("12.34+56", 8), result)
    }
}
