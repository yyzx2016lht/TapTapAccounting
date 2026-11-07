package com.taostudio.tapaccounting.capture.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawContentFingerprintTest {

    private val refundA =
        """{"mMap":{"description":"退款金额￥0.01\n退款方式退回建设银行 (3529) \n退款原因吴希城6.4退还了你的转账\n到账时间2026-11-06 20:50:21\n备注退款资金已到账","title":"转账退款到账通知","display_name":"微信支付","source":"微信支付","t":1793969433540}}"""

    private val refundB =
        """{"mMap":{"description":"退款金额￥0.01\n退款方式退回建设银行 (3529) \n退款原因吴希城6.4退还了你的转账\n到账时间2026-11-06 20:50:21\n备注退款资金已到账","title":"转账退款到账通知","display_name":"微信支付","source":"微信支付","t":1793969433744}}"""

    private val relativeCardA =
        """{"mMap":{"description":"通过农业银行储蓄卡(3077)扣款￥37.77\n支付场景美团平台商户","title":"亲属卡使用通知","display_name":"亲属卡","source":"微信支付","t":1793973714262}}"""

    private val relativeCardB =
        """{"mMap":{"description":"通过农业银行储蓄卡(3077)扣款￥37.77\n支付场景美团平台商户","title":"亲属卡使用通知","display_name":"亲属卡","source":"微信支付","t":1793973714396}}"""

    @Test
    fun refundNotice_ignoresCaptureTime() {
        assertEquals(
            RawContentFingerprint.of("WECHAT", "DATA", refundA),
            RawContentFingerprint.of("WECHAT", "DATA", refundB),
        )
    }

    @Test
    fun relativeCardNotice_ignoresCaptureTime() {
        assertEquals(
            RawContentFingerprint.of("WECHAT", "DATA", relativeCardA),
            RawContentFingerprint.of("WECHAT", "DATA", relativeCardB),
        )
    }

    @Test
    fun cacheFieldsAreNotIgnored() {
        val withCache = relativeCardA.replace(
            "\"t\":1793973714262",
            "\"t\":1793973714262,\"cachedPayTools\":\"农业银行储蓄卡\"",
        )
        assertNotEquals(
            RawContentFingerprint.of("WECHAT", "DATA", relativeCardA),
            RawContentFingerprint.of("WECHAT", "DATA", withCache),
        )
    }

    @Test
    fun differentSourceOrRawTypeDoesNotMerge() {
        assertNotEquals(
            RawContentFingerprint.of("WECHAT", "DATA", refundA),
            RawContentFingerprint.of("ALIPAY", "DATA", refundA),
        )
        assertNotEquals(
            RawContentFingerprint.of("WECHAT", "DATA", refundA),
            RawContentFingerprint.of("WECHAT", "NOTICE", refundA),
        )
    }

    @Test
    fun jsonKeyOrderDoesNotMatter() {
        val reordered =
            """{"mMap":{"source":"微信支付","display_name":"微信支付","title":"转账退款到账通知","description":"退款金额￥0.01\n退款方式退回建设银行 (3529) \n退款原因吴希城6.4退还了你的转账\n到账时间2026-11-06 20:50:21\n备注退款资金已到账","t":1793969433540}}"""
        assertEquals(
            RawContentFingerprint.of("WECHAT", "DATA", refundA),
            RawContentFingerprint.of("WECHAT", "DATA", reordered),
        )
    }

    @Test
    fun captureTimeIgnoredButAmountStillMatters() {
        val laterTransfer = relativeCardA.replace("t\":1793973714262", "t\":1793973720000")
        assertEquals(
            RawContentFingerprint.of("WECHAT", "DATA", relativeCardA),
            RawContentFingerprint.of("WECHAT", "DATA", laterTransfer),
        )

        val differentTransfer = relativeCardA.replace("37.77", "37.78")
        assertNotEquals(
            RawContentFingerprint.of("WECHAT", "DATA", relativeCardA),
            RawContentFingerprint.of("WECHAT", "DATA", differentTransfer),
        )
    }

    @Test
    fun plainTextCollapsesWhitespace() {
        assertEquals(
            RawContentFingerprint.of("SMS", "NOTICE", "  尾号3077 收入 37.77 元 "),
            RawContentFingerprint.of("SMS", "NOTICE", "尾号3077  收入   37.77 元"),
        )
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(RawContentFingerprint.of("WECHAT", "DATA", ""))
        assertNull(RawContentFingerprint.of("WECHAT", "DATA", "   "))
        assertNull(RawContentFingerprint.of("WECHAT", "DATA", null))
    }
}
