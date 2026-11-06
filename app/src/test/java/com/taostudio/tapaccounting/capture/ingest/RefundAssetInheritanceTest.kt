package com.taostudio.tapaccounting.capture.ingest

import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import org.junit.Assert.assertEquals
import org.junit.Test

class RefundAssetInheritanceTest {

    private fun capture(
        id: String,
        subType: Int = LocalBillType.SUBTYPE_REFUND,
        assetName: String? = null,
        paymentMethodRaw: String? = null,
        rawJson: String? = null,
    ): PaymentCapture = PaymentCapture(
        id = id,
        source = "WECHAT",
        amountCents = 1L,
        currency = "CNY",
        merchant = "柳桥",
        assetName = assetName,
        paymentMethodRaw = paymentMethodRaw,
        paidAt = 100000L,
        capturedAt = 100000L,
        billType = LocalBillType.TYPE_INCOME,
        subType = subType,
        needsRefundLink = subType == LocalBillType.SUBTYPE_REFUND,
        rawJson = rawJson,
        status = "PENDING",
        createdAt = 100000L,
        updatedAt = 100000L,
    )

    @Test
    fun `refund status without asset inherits original bank card`() {
        val refund = capture(
            id = "refund",
            assetName = "微信",
            paymentMethodRaw = "零钱",
            rawJson = """{"type":"transfer","content":"{\"msg\":{\"appmsg\":{\"wcpayinfo\":{\"paysubtype\":\"4\"}}}}"}""",
        )
        val original = capture(
            id = "original",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "农业银行",
            paymentMethodRaw = "农业银行储蓄卡",
        )

        val resolved = RefundAssetInheritance.inherit(refund, original)
        assertEquals("农业银行", resolved.assetName)
        assertEquals("农业银行储蓄卡", resolved.paymentMethodRaw)
    }

    @Test
    fun `explicit refund notice asset wins over original`() {
        val refund = capture(
            id = "refund",
            assetName = null,
            paymentMethodRaw = "建设银行 (3529)",
            rawJson = """{"mMap":{"description":"退款方式退回建设银行 (3529)"}}""",
        )
        val original = capture(
            id = "original",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "微信",
            paymentMethodRaw = "零钱",
        )

        val resolved = RefundAssetInheritance.inherit(refund, original)
        assertEquals(null, resolved.assetName)
        assertEquals("建设银行 (3529)", resolved.paymentMethodRaw)
    }

    @Test
    fun `explicit bank notice without mapped asset falls back to original bank asset`() {
        val refund = capture(
            id = "refund",
            assetName = null,
            paymentMethodRaw = "农业银行 (3077)",
            rawJson = """{"mMap":{"description":"退款方式退回农业银行 (3077)"}}""",
        )
        val original = capture(
            id = "original",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "农业银行",
            paymentMethodRaw = "农业银行储蓄卡",
        )

        val resolved = RefundAssetInheritance.inherit(refund, original)
        assertEquals("农业银行", resolved.assetName)
        assertEquals("农业银行 (3077)", resolved.paymentMethodRaw)
    }

    @Test
    fun `zero money original keeps zero money`() {
        val refund = capture(id = "refund", assetName = "微信", paymentMethodRaw = "零钱")
        val original = capture(
            id = "original",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "微信",
            paymentMethodRaw = "零钱",
        )

        val resolved = RefundAssetInheritance.inherit(refund, original)
        assertEquals("微信", resolved.assetName)
        assertEquals("零钱", resolved.paymentMethodRaw)
    }

    @Test
    fun `non refund is untouched`() {
        val capture = capture(
            id = "normal",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "微信",
            paymentMethodRaw = "零钱",
        )
        val original = capture(
            id = "original",
            subType = LocalBillType.SUBTYPE_NORMAL,
            assetName = "农业银行",
            paymentMethodRaw = "农业银行储蓄卡",
        )

        val resolved = RefundAssetInheritance.inherit(capture, original)
        assertEquals("微信", resolved.assetName)
        assertEquals("零钱", resolved.paymentMethodRaw)
    }
}
