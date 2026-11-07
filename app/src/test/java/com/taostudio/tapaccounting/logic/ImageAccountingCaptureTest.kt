package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAccountingCaptureTest {

    private fun capture(
        totalCents: Long = 7000L,
        type: Int = 0,
        subType: Int = 0,
        needsRefundLink: Boolean = false,
    ): ImageAccountingCapture.Capture = ImageAccountingCapture.Capture(
        captureId = "capture-1",
        amountCents = totalCents,
        currency = "CNY",
        paidAt = 1_700_000_000_000L,
        type = type,
        subType = subType,
        needsRefundLink = needsRefundLink,
        assetId = 9L,
        assetName = "微信",
        bookName = "日常",
    )

    private fun bill(name: String, amountCents: Long?, category: String? = "餐饮") =
        ImageAccountingCapture.BillInput(name = name, amountCents = amountCents, categoryName = category)

    @Test
    fun `exact sum keeps two bills without adjustment`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 3000L), bill("雪梨", 4000L)),
            fallbackCategoryName = "其他",
        )

        assertTrue(outcome is ImageAccountingCapture.Outcome.Ok)
        val ok = outcome as ImageAccountingCapture.Outcome.Ok
        assertEquals(2, ok.items.size)
        assertEquals(0L, ok.adjustmentCents)
        assertEquals(7000L, ok.items.sumOf { it.amountCents })
    }

    @Test
    fun `short sum appends difference adjustment using fallback category`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 3000L), bill("雪梨", 3500L)),
            fallbackCategoryName = "其他",
        )

        val ok = outcome as ImageAccountingCapture.Outcome.Ok
        assertEquals(3, ok.items.size)
        assertEquals(500L, ok.adjustmentCents)
        assertEquals("补差额调整", ok.items.last().name)
        assertEquals(500L, ok.items.last().amountCents)
        assertEquals("其他", ok.items.last().categoryName)
        assertEquals(7000L, ok.items.sumOf { it.amountCents })
    }

    @Test
    fun `sum exceeding payment is rejected like F06`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 4000L), bill("雪梨", 4000L)),
            fallbackCategoryName = "其他",
        )

        val error = outcome as ImageAccountingCapture.Outcome.Error
        assertEquals("split_total_exceeds", error.code)
    }

    @Test
    fun `short sum without fallback category is rejected`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 3000L), bill("雪梨", 3500L)),
            fallbackCategoryName = null,
        )

        val error = outcome as ImageAccountingCapture.Outcome.Error
        assertEquals("missing_fallback_category", error.code)
    }

    @Test
    fun `missing category is rejected`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 3000L, category = null), bill("雪梨", 4000L)),
            fallbackCategoryName = "其他",
        )

        val error = outcome as ImageAccountingCapture.Outcome.Error
        assertEquals("missing_category", error.code)
    }

    @Test
    fun `single missing amount is inferred from payment difference`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(7000L),
            bills = listOf(bill("苹果", 3000L), bill("雪梨", null)),
            fallbackCategoryName = "其他",
        )

        val ok = outcome as ImageAccountingCapture.Outcome.Ok
        assertEquals(2, ok.items.size)
        assertEquals(4000L, ok.items[1].amountCents)
        assertTrue(ok.items[1].inferred)
    }

    @Test
    fun `non expense income type is rejected`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(type = 2),
            bills = listOf(bill("苹果", 7000L)),
            fallbackCategoryName = "其他",
        )

        val error = outcome as ImageAccountingCapture.Outcome.Error
        assertEquals("split_unsupported_type", error.code)
    }

    @Test
    fun `empty recognition is rejected`() {
        val outcome = ImageAccountingCapture.plan(
            capture = capture(),
            bills = emptyList(),
            fallbackCategoryName = "其他",
        )

        val error = outcome as ImageAccountingCapture.Outcome.Error
        assertEquals("image_empty", error.code)
    }

    @Test
    fun `import payload always uses capture asset time book and total`() {
        val payload = ImageAccountingCapture.buildImportPayload(
            capture = capture(7000L),
            items = listOf(
                ImageAccountingCapture.Item("苹果", 3000L, "餐饮"),
                ImageAccountingCapture.Item("雪梨", 4000L, "水果"),
            ),
        )

        assertEquals("capture-1", payload.getString("captureId"))
        assertEquals(7000L, payload.getLong("totalCents"))
        assertEquals(9L, payload.getLong("assetId"))
        assertEquals("微信", payload.getString("assetName"))
        assertEquals("日常", payload.getString("bookName"))
        assertEquals(1_700_000_000_000L, payload.getLong("paidAt"))
        assertEquals("CNY", payload.getString("currency"))
        assertEquals(0, payload.getInt("type"))
        assertEquals(0, payload.getInt("subType"))
        assertFalse(payload.getBoolean("needsRefundLink"))

        val items = payload.getJSONArray("items")
        assertEquals(2, items.length())
        val first = items.getJSONObject(0)
        assertEquals("苹果", first.getString("name"))
        assertEquals(3000L, first.getLong("amountCents"))
        assertEquals("餐饮", first.getString("categoryName"))
        assertFalse(first.has("assetId"))
        assertFalse(first.has("paidAt"))
        assertFalse(first.has("bookName"))
    }
}
