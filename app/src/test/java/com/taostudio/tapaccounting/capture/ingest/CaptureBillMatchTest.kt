package com.taostudio.tapaccounting.capture.ingest

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBillMatchTest {

    private fun expense(
        amount: Double,
        groupId: String? = null,
        subType: Int = Bill.SUBTYPE_NORMAL,
        type: Int = Bill.TYPE_EXPENSE,
    ): Bill = Bill(
        type = type,
        subType = subType,
        amount = amount,
        time = 1793973710000L,
        paymentGroupId = groupId,
    )

    @Test
    fun `single bill with exact amount matches`() {
        assertTrue(CaptureBillMatch.matches(listOf(expense(37.77)), 3777L))
    }

    @Test
    fun `split bills sum matches original payment`() {
        val bills = listOf(
            expense(1.51, "g1"),
            expense(5.58, "g1"),
            expense(2.40, "g1"),
            expense(9.75, "g1"),
            expense(5.51, "g1"),
            expense(9.97, "g1"),
            expense(2.55, "g1"),
            expense(0.50, "g1"),
        )
        assertTrue(CaptureBillMatch.matches(bills, 3777L))
    }

    @Test
    fun `split bills from different groups do not combine`() {
        val bills = listOf(
            expense(20.00, "g1"),
            expense(17.77, "g2"),
        )
        assertFalse(CaptureBillMatch.matches(bills, 3777L))
    }

    @Test
    fun `refund bills do not count as already recorded expense`() {
        val bills = listOf(
            expense(37.77, "g1", subType = Bill.SUBTYPE_REFUND),
        )
        assertFalse(CaptureBillMatch.matches(bills, 3777L))
    }

    @Test
    fun `income bills do not count`() {
        val bills = listOf(
            expense(37.77, "g1", type = Bill.TYPE_INCOME),
        )
        assertFalse(CaptureBillMatch.matches(bills, 3777L))
    }
}
