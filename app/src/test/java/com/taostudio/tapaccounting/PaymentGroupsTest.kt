package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.PaymentGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentGroupsTest {

    private fun bill(
        id: Long,
        groupId: String? = null,
        type: Int = Bill.TYPE_EXPENSE,
        subType: Int = Bill.SUBTYPE_NORMAL,
        accountId: Long? = ASSET_ID
    ): Bill = Bill(
        id = id,
        type = type,
        subType = subType,
        amount = 10.0,
        time = 0L,
        accountId = accountId,
        paymentGroupId = groupId
    )

    @Test
    fun groupMembersCollapseAtFirstMemberPosition() {
        val items = PaymentGroups.arrange(
            listOf(bill(1), bill(2, "g"), bill(3), bill(4, "g"))
        )
        assertEquals(3, items.size)
        assertEquals(1L, (items[0] as PaymentGroups.Item.Single).bill.id)
        val group = items[1] as PaymentGroups.Item.Group
        assertEquals(listOf(2L, 4L), group.bills.map { it.id })
        assertEquals(3L, (items[2] as PaymentGroups.Item.Single).bill.id)
    }

    @Test
    fun loneVisibleMemberShowsAsNormalBill() {
        val items = PaymentGroups.arrange(listOf(bill(1, "g"), bill(2)))
        assertEquals(listOf(1L, 2L), items.map { (it as PaymentGroups.Item.Single).bill.id })
    }

    @Test
    fun transferAndRefundNeverJoinGroupDisplay() {
        val items = PaymentGroups.arrange(
            listOf(
                bill(1, "g"),
                bill(2, "g", type = Bill.TYPE_TRANSFER),
                bill(3, "g", type = Bill.TYPE_INCOME, subType = Bill.SUBTYPE_REFUND)
            )
        )
        assertEquals(3, items.count { it is PaymentGroups.Item.Single })
    }

    @Test
    fun selectionInsideOneGroupSplitsOtherwiseMerges() {
        assertEquals(PaymentGroups.Action.SPLIT, PaymentGroups.resolveAction(listOf(bill(1, "g"), bill(2, "g"))))
        assertEquals(PaymentGroups.Action.MERGE, PaymentGroups.resolveAction(listOf(bill(1, "g"), bill(2))))
        assertEquals(PaymentGroups.Action.MERGE, PaymentGroups.resolveAction(listOf(bill(1, "a"), bill(2, "b"))))
        assertEquals(PaymentGroups.Action.MERGE, PaymentGroups.resolveAction(emptyList()))
    }

    @Test
    fun mergeRequiresTwoNormalBillsPaidFromThisAsset() {
        assertEquals(PaymentGroups.MergeBlock.TOO_FEW, PaymentGroups.mergeBlock(listOf(bill(1)), ASSET_ID))
        assertEquals(
            PaymentGroups.MergeBlock.NOT_INCOME_EXPENSE,
            PaymentGroups.mergeBlock(listOf(bill(1), bill(2, type = Bill.TYPE_TRANSFER)), ASSET_ID)
        )
        assertEquals(
            PaymentGroups.MergeBlock.OTHER_ASSET,
            PaymentGroups.mergeBlock(listOf(bill(1), bill(2, accountId = 99L)), ASSET_ID)
        )
        assertNull(PaymentGroups.mergeBlock(listOf(bill(1), bill(2, type = Bill.TYPE_INCOME)), ASSET_ID))
    }

    private companion object {
        const val ASSET_ID = 7L
    }
}
