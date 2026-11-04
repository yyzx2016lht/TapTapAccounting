package com.taostudio.tapaccounting.capture.notify

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleRecordPolicyTest {

    private fun capture(
        amountCents: Long? = 300L,
        billType: Int? = LocalBillType.TYPE_EXPENSE,
        subType: Int? = LocalBillType.SUBTYPE_NORMAL,
        needsRefundLink: Boolean = false,
        assetName: String? = "微信",
        categoryName: String? = "水果",
        status: String = CaptureStatus.PENDING,
    ) = PaymentCapture(
        id = "capture-1",
        source = "WECHAT",
        amountCents = amountCents,
        categoryName = categoryName,
        assetName = assetName,
        billType = billType,
        subType = subType,
        needsRefundLink = needsRefundLink,
        status = status,
        paidAt = 1L,
        capturedAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `normal expense can be imported directly`() {
        assertNull(BubbleRecordPolicy.importBlock(capture(), bridgeAvailable = true))
        assertNull(BubbleRecordPolicy.preClassificationBlock(capture(), bridgeAvailable = true))
    }

    @Test
    fun `missing amount requires fine tune`() {
        assertEquals(
            BubbleRecordPolicy.Block.AMOUNT_MISSING,
            BubbleRecordPolicy.importBlock(capture(amountCents = null), bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.AMOUNT_MISSING,
            BubbleRecordPolicy.importBlock(capture(amountCents = 0L), bridgeAvailable = true),
        )
    }

    @Test
    fun `transfer repayment and refund require fine tune`() {
        val transfer = capture(billType = LocalBillType.TYPE_TRANSFER)
        val repayment = capture(billType = LocalBillType.TYPE_TRANSFER, subType = LocalBillType.SUBTYPE_REPAYMENT)
        val refund = capture(billType = LocalBillType.TYPE_INCOME, subType = LocalBillType.SUBTYPE_REFUND)
        val refundLink = capture(billType = LocalBillType.TYPE_INCOME, needsRefundLink = true)

        assertEquals(
            BubbleRecordPolicy.Block.UNSAFE_BILL_TYPE,
            BubbleRecordPolicy.importBlock(transfer, bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.UNSAFE_BILL_TYPE,
            BubbleRecordPolicy.importBlock(repayment, bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.UNSAFE_BILL_TYPE,
            BubbleRecordPolicy.importBlock(refund, bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.UNSAFE_BILL_TYPE,
            BubbleRecordPolicy.importBlock(refundLink, bridgeAvailable = true),
        )
    }

    @Test
    fun `unmapped asset requires fine tune`() {
        assertEquals(
            BubbleRecordPolicy.Block.ASSET_UNMAPPED,
            BubbleRecordPolicy.importBlock(capture(assetName = null), bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.ASSET_UNMAPPED,
            BubbleRecordPolicy.importBlock(capture(assetName = "  "), bridgeAvailable = true),
        )
    }

    @Test
    fun `possible duplicate requires fine tune`() {
        assertEquals(
            BubbleRecordPolicy.Block.POSSIBLE_DUPLICATE,
            BubbleRecordPolicy.importBlock(
                capture(status = CaptureStatus.POSSIBLE_DUPLICATE),
                bridgeAvailable = true,
            ),
        )
    }

    @Test
    fun `missing category blocks import but not classification`() {
        val noCategory = capture(categoryName = null)
        assertNull(BubbleRecordPolicy.preClassificationBlock(noCategory, bridgeAvailable = true))
        assertEquals(
            BubbleRecordPolicy.Block.MISSING_CATEGORY,
            BubbleRecordPolicy.importBlock(noCategory, bridgeAvailable = true),
        )
    }

    @Test
    fun `unavailable bridge blocks both stages`() {
        assertEquals(
            BubbleRecordPolicy.Block.BRIDGE_UNAVAILABLE,
            BubbleRecordPolicy.preClassificationBlock(capture(), bridgeAvailable = false),
        )
        assertEquals(
            BubbleRecordPolicy.Block.BRIDGE_UNAVAILABLE,
            BubbleRecordPolicy.importBlock(capture(), bridgeAvailable = false),
        )
    }
}
