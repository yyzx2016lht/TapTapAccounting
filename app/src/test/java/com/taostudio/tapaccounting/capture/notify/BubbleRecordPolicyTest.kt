package com.taostudio.tapaccounting.capture.notify

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡准入规则：转账不需要分类，支出/收入仍要分类。
 *
 * 手写假数据，不包含真实账单。
 */
class BubbleRecordPolicyTest {

    private fun capture(
        billType: Int? = LocalBillType.TYPE_EXPENSE,
        subType: Int? = LocalBillType.SUBTYPE_NORMAL,
        amountCents: Long? = 1000L,
        assetName: String? = "微信零钱",
        toAssetName: String? = null,
        categoryName: String? = "餐饮",
        status: String = CaptureStatus.PENDING,
        needsRefundLink: Boolean = false,
    ): PaymentCapture = PaymentCapture(
        id = "capture-1",
        source = "WECHAT",
        sourceTxnId = null,
        amountCents = amountCents,
        currency = "CNY",
        merchant = "瑞幸咖啡",
        categoryName = categoryName,
        paymentMethodRaw = null,
        assetName = assetName,
        toAssetName = toAssetName,
        billType = billType,
        subType = subType,
        needsRefundLink = needsRefundLink,
        paidAt = 100000L,
        capturedAt = 100000L,
        bookName = "日常账本",
        note = null,
        status = status,
        needsAmount = false,
        rawJson = null,
        ruleVersion = null,
        sourceAppVersion = null,
        excludeFromStats = false,
        groupId = null,
        channel = "微信支付",
        createdAt = 100000L,
        updatedAt = 100000L,
    )

    @Test
    fun `transfer is recognized for legacy and canonical types`() {
        assertTrue(BubbleRecordPolicy.isTransfer(capture(billType = LocalBillType.TYPE_TRANSFER)))
        assertTrue(BubbleRecordPolicy.isTransfer(capture(billType = LocalBillType.TYPE_REPAYMENT)))
        assertTrue(
            BubbleRecordPolicy.isTransfer(
                capture(billType = LocalBillType.TYPE_TRANSFER, subType = LocalBillType.SUBTYPE_REPAYMENT)
            )
        )
        assertFalse(BubbleRecordPolicy.isTransfer(capture(billType = LocalBillType.TYPE_EXPENSE)))
        assertFalse(BubbleRecordPolicy.isTransfer(capture(billType = LocalBillType.TYPE_INCOME)))
    }

    @Test
    fun `transfer records directly without category`() {
        val transfer = capture(
            billType = LocalBillType.TYPE_TRANSFER,
            toAssetName = "招商银行",
            categoryName = null,
        )

        assertNull(BubbleRecordPolicy.importBlock(transfer, bridgeAvailable = true))
        assertNull(BubbleRecordPolicy.preClassificationBlock(transfer, bridgeAvailable = true))
    }

    @Test
    fun `transfer needs both assets before direct recording`() {
        val noFrom = capture(
            billType = LocalBillType.TYPE_TRANSFER,
            toAssetName = "招商银行",
            assetName = null,
            categoryName = null,
        )
        val noTo = capture(
            billType = LocalBillType.TYPE_TRANSFER,
            toAssetName = null,
            categoryName = null,
        )

        assertEquals(
            BubbleRecordPolicy.Block.ASSET_UNMAPPED,
            BubbleRecordPolicy.importBlock(noFrom, bridgeAvailable = true),
        )
        assertEquals(
            BubbleRecordPolicy.Block.MISSING_TO_ASSET,
            BubbleRecordPolicy.importBlock(noTo, bridgeAvailable = true),
        )
    }

    @Test
    fun `expense still requires category`() {
        val expense = capture(billType = LocalBillType.TYPE_EXPENSE, categoryName = null)

        assertEquals(
            BubbleRecordPolicy.Block.MISSING_CATEGORY,
            BubbleRecordPolicy.importBlock(expense, bridgeAvailable = true),
        )
        // 归类前的硬性拦截不要求分类，否则 AI 归类永远等不到机会。
        assertNull(BubbleRecordPolicy.preClassificationBlock(expense, bridgeAvailable = true))
    }

    @Test
    fun `refund still needs the original bill in the floating window`() {
        val refund = capture(
            billType = LocalBillType.TYPE_INCOME,
            subType = LocalBillType.SUBTYPE_REFUND,
            needsRefundLink = true,
        )

        assertEquals(
            BubbleRecordPolicy.Block.UNSAFE_BILL_TYPE,
            BubbleRecordPolicy.importBlock(refund, bridgeAvailable = true),
        )
    }

    @Test
    fun `missing amount and bridge block everything first`() {
        val noAmount = capture(billType = LocalBillType.TYPE_TRANSFER, toAssetName = "招商银行", amountCents = null)

        assertEquals(
            BubbleRecordPolicy.Block.BRIDGE_UNAVAILABLE,
            BubbleRecordPolicy.importBlock(noAmount, bridgeAvailable = false),
        )
        assertEquals(
            BubbleRecordPolicy.Block.AMOUNT_MISSING,
            BubbleRecordPolicy.importBlock(noAmount, bridgeAvailable = true),
        )
    }
}
