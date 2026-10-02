package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountExpressionTest {

    @Test
    fun evaluate_plainNumber() {
        assertEquals(12.0, AmountExpression.evaluate("12")!!, 0.0)
    }

    @Test
    fun evaluate_addition() {
        assertEquals(15.0, AmountExpression.evaluate("12+3")!!, 0.0)
    }

    @Test
    fun evaluate_respectsPrecedence() {
        assertEquals(18.0, AmountExpression.evaluate("12+3×2")!!, 0.0)
    }

    @Test
    fun evaluate_rejectsDivisionByZero() {
        assertNull(AmountExpression.evaluate("12÷0"))
    }

    @Test
    fun evaluate_rejectsTrailingOperator() {
        assertNull(AmountExpression.evaluate("12+"))
    }

    @Test
    fun evaluate_rejectsEmptyOrBareOperator() {
        assertNull(AmountExpression.evaluate(""))
        assertNull(AmountExpression.evaluate("×"))
    }

    @Test
    fun format_isAlwaysTwoDecimalsWithDotSeparator() {
        assertEquals("12.50", AmountExpression.format(12.5))
        assertEquals("12.00", AmountExpression.format(12.0))
    }

    @Test
    fun compact_dropsTrailingZeros() {
        assertEquals("12", AmountExpression.compact(12.0))
        assertEquals("12.5", AmountExpression.compact(12.5))
        assertEquals("0", AmountExpression.compact(0.0))
    }

    @Test
    fun containsOperator_onlyForExpressions() {
        assertTrue(AmountExpression.containsOperator("12+3"))
        assertFalse(AmountExpression.containsOperator("12.34"))
    }

    @Test
    fun normalizeForEditing_stripsCommittedTailZeros() {
        assertEquals("12", AmountExpression.normalizeForEditing("12.00"))
        assertEquals("12.5", AmountExpression.normalizeForEditing("12.50"))
        assertEquals("12", AmountExpression.normalizeForEditing("12."))
    }

    @Test
    fun normalizeForEditing_keepsMeaningfulDecimals() {
        assertEquals("12.05", AmountExpression.normalizeForEditing("12.05"))
        assertEquals("12", AmountExpression.normalizeForEditing("12"))
    }

    @Test
    fun normalizeForEditing_leavesExpressionsUntouched() {
        assertEquals("12+3", AmountExpression.normalizeForEditing("12+3"))
        assertEquals("", AmountExpression.normalizeForEditing(""))
    }
}
