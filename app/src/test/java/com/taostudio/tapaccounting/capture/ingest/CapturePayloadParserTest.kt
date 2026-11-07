package com.taostudio.tapaccounting.capture.ingest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePayloadParserTest {

    @Test
    fun `parses camelCase and snake_case aliases`() {
        val payload = CapturePayloadParser.parse(
            JSONObject()
                .put("capture_id", "c-1")
                .put("source", "WECHAT")
                .put("raw_type", "data")
                .put("amount", 12.34)
                .put("paid_at", 1_700_000_000_000L)
                .put("shop_name", "测试商户")
                .put("payment_method_raw", "零钱")
                .put("orderId", "txn-1")
                .toString(),
        )

        assertEquals("c-1", payload.captureId)
        assertEquals("WECHAT", payload.source)
        assertEquals("DATA", payload.rawType)
        assertEquals(1234L, payload.amountCents)
        assertEquals(1_700_000_000_000L, payload.paidAt)
        assertEquals("测试商户", payload.merchant)
        assertEquals("零钱", payload.paymentMethodRaw)
        assertEquals("txn-1", payload.sourceTxnId)
        assertTrue(payload.rawJson?.contains("capture_id") == true)
    }

    @Test
    fun `falls back to now and raw json when optional fields are missing`() {
        val before = System.currentTimeMillis()
        val payload = CapturePayloadParser.parse(
            JSONObject()
                .put("captureId", "c-2")
                .put("source", "ALIPAY")
                .toString(),
        )
        val after = System.currentTimeMillis()

        assertNull(payload.amountCents)
        assertTrue(payload.paidAt in before..after)
        assertTrue(payload.capturedAt in before..after)
        assertTrue(payload.needsAmount)
        assertTrue(payload.rawJson?.contains("c-2") == true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative amount`() {
        CapturePayloadParser.parse(
            JSONObject()
                .put("captureId", "c-3")
                .put("source", "WECHAT")
                .put("amountCents", -1L)
                .toString(),
        )
    }
}
