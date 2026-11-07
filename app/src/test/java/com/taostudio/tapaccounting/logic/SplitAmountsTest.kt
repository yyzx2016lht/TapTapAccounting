package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAmountsTest {

    @Test
    fun equalTotal_keepsItemsAndZeroDiff() {
        val outcome = SplitAmounts.validate(
            totalCents = 7000L,
            rawItems = listOf(
                SplitAmounts.RawItem("苹果", 3000L),
                SplitAmounts.RawItem("雪梨", 4000L),
            ),
        )

        assertTrue(outcome is SplitAmounts.Outcome.Ok)
        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(7000L, ok.sumCents)
        assertEquals(0L, ok.diffCents)
        assertEquals(listOf("苹果", "雪梨"), ok.items.map { it.name })
        assertEquals(listOf(3000L, 4000L), ok.items.map { it.amountCents })
        assertTrue(ok.items.none { it.inferred })
    }

    @Test
    fun lessThanTotal_reportsDifference() {
        val outcome = SplitAmounts.validate(
            totalCents = 7000L,
            rawItems = listOf(
                SplitAmounts.RawItem("苹果", 3000L),
                SplitAmounts.RawItem("雪梨", 3500L),
            ),
        )

        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(6500L, ok.sumCents)
        assertEquals(500L, ok.diffCents)
    }

    @Test
    fun oneMissingAmount_isInferredFromDifference() {
        val outcome = SplitAmounts.validate(
            totalCents = 7000L,
            rawItems = listOf(
                SplitAmounts.RawItem("苹果", 3000L),
                SplitAmounts.RawItem("雪梨", null),
            ),
        )

        val ok = outcome as SplitAmounts.Outcome.Ok
        assertEquals(0L, ok.diffCents)
        val inferred = ok.items.single { it.name == "雪梨" }
        assertEquals(4000L, inferred.amountCents)
        assertTrue(inferred.inferred)
        assertTrue(ok.items.single { it.name == "苹果" }.inferred.not())
    }

    @Test
    fun exceedsTotal_isRejected() {
        val outcome = SplitAmounts.validate(
            totalCents = 7000L,
            rawItems = listOf(
                SplitAmounts.RawItem("苹果", 5000L),
                SplitAmounts.RawItem("雪梨", 4000L),
            ),
        )

        val error = outcome as SplitAmounts.Outcome.Error
        assertEquals("split_total_exceeds", error.code)
    }

    @Test
    fun emptyItems_isRejected() {
        val outcome = SplitAmounts.validate(totalCents = 7000L, rawItems = emptyList())

        val error = outcome as SplitAmounts.Outcome.Error
        assertEquals("split_empty", error.code)
    }

    @Test
    fun multipleMissingAmounts_areRejected() {
        val outcome = SplitAmounts.validate(
            totalCents = 7000L,
            rawItems = listOf(
                SplitAmounts.RawItem("苹果", null),
                SplitAmounts.RawItem("雪梨", null),
            ),
        )

        val error = outcome as SplitAmounts.Outcome.Error
        assertEquals("split_amount_missing", error.code)
    }

    @Test
    fun differenceAdjustment_fillsGap() {
        val base = listOf(
            SplitAmounts.Item("苹果", 3000L),
            SplitAmounts.Item("雪梨", 3500L),
        )
        val adjusted = SplitAmounts.withDifferenceAdjustment(
            items = base,
            diffCents = 500L,
            categoryName = "其他",
        )

        assertEquals(3, adjusted.size)
        assertEquals("补差额调整", adjusted.last().name)
        assertEquals(500L, adjusted.last().amountCents)
        assertEquals(7000L, adjusted.sumOf { it.amountCents })
    }

    @Test
    fun yuanStyleAmounts_areNormalizedToCents() {
        val normalized = SplitAmounts.normalizeYuanAmounts(
            totalCents = 7000L,
            items = listOf(
                SplitAmounts.RawItem("苹果", 30L),
                SplitAmounts.RawItem("雪梨", 40L),
            ),
        )

        assertEquals(listOf(3000L, 4000L), normalized.map { it.amountCents })
    }

    @Test
    fun centStyleAmounts_areNotMultiplied() {
        val normalized = SplitAmounts.normalizeYuanAmounts(
            totalCents = 70L,
            items = listOf(
                SplitAmounts.RawItem("苹果", 30L),
                SplitAmounts.RawItem("雪梨", 40L),
            ),
        )

        assertEquals(listOf(30L, 40L), normalized.map { it.amountCents })
    }
}
