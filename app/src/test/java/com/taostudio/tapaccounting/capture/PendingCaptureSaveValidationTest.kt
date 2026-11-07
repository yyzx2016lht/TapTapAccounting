package com.taostudio.tapaccounting.capture

import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capturebridge.CapturePluginClient
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCaptureSaveValidationTest {

    private fun isSaveBlockedByPendingRule(
        captureId: String?,
        type: Int,
        isRepayment: Boolean,
        categoryName: String?,
        remark: String?
    ): Boolean {
        if (captureId == null) return false
        if (isRepayment || (type != 0 && type != 1)) return false
        val hasCat = !categoryName.isNullOrBlank()
        val hasRemark = !remark?.trim().isNullOrEmpty()
        return !hasCat && !hasRemark
    }

    @Test
    fun testPendingSaveValidation_blockedWhenBothEmpty() {
        // Pending mode, expense, both category and remark empty
        assertTrue(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 0,
                isRepayment = false,
                categoryName = "",
                remark = ""
            )
        )
        // Income, both empty
        assertTrue(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 1,
                isRepayment = false,
                categoryName = null,
                remark = "   "
            )
        )
    }

    @Test
    fun testPendingSaveValidation_allowedWhenCategoryPresent() {
        // Category present, remark empty -> allowed
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 0,
                isRepayment = false,
                categoryName = "餐饮 - 午餐",
                remark = ""
            )
        )
    }

    @Test
    fun testPendingSaveValidation_allowedWhenRemarkPresent() {
        // Remark present, category empty -> allowed (will resolve via local rule or AI)
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 0,
                isRepayment = false,
                categoryName = "",
                remark = "星巴克咖啡"
            )
        )
    }

    @Test
    fun testPendingSaveValidation_allowedWhenBothPresent() {
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 0,
                isRepayment = false,
                categoryName = "餐饮",
                remark = "麦当劳"
            )
        )
    }

    @Test
    fun testPendingSaveValidation_nonPendingModeNeverBlocked() {
        // Non pending mode (captureId == null) preserves existing behavior
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = null,
                type = 0,
                isRepayment = false,
                categoryName = "",
                remark = ""
            )
        )
    }

    @Test
    fun testPendingSaveValidation_transferAndRepaymentNotBlocked() {
        // Transfer
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 2,
                isRepayment = false,
                categoryName = "",
                remark = ""
            )
        )
        // Repayment
        assertFalse(
            isSaveBlockedByPendingRule(
                captureId = "cap-123",
                type = 2,
                isRepayment = true,
                categoryName = "",
                remark = ""
            )
        )
    }

    @Test
    fun testToPrefillJson_includesCategorySourceAndRuleId() {
        val item = CapturePluginClient.PendingCaptureItem(
            id = "cap-999",
            source = CaptureSource.ALIPAY,
            amountCents = 2500L,
            currency = "CNY",
            merchant = "罗森便利店",
            categoryName = "餐饮 - 零食",
            categorySource = BillRuleProvenance.Source.RULE,
            categoryRuleId = 42L,
            paymentMethodRaw = "支付宝",
            assetName = "支付宝余额",
            toAssetName = null,
            paidAt = 1700000000000L,
            bookName = "日常账本",
            note = "便利店面包",
            billType = 0,
            subType = 0,
            needsRefundLink = false,
            status = "PENDING"
        )
        val json = CapturePluginClient.toPrefillJson(item)
        assertEquals("cap-999", item.id)
        assertEquals(0, json.getInt("type"))
        assertEquals(25.0, json.getDouble("amount"), 0.001)
        assertEquals("餐饮 - 零食", json.getString("category_name"))
        assertEquals("RULE", json.getString("category_source"))
        assertEquals(42L, json.getLong("category_rule_id"))
        assertEquals("便利店面包", json.getString("remarks"))
    }

    @Test
    fun testDraftUpdateResult_fields() {
        val res = CapturePluginClient.DraftUpdateResult(
            ok = true,
            categoryName = "餐饮",
            categorySource = "MANUAL",
            categoryRuleId = null,
            assetName = "招行卡",
            note = "午饭"
        )
        assertTrue(res.ok)
        assertEquals("餐饮", res.categoryName)
        assertEquals("MANUAL", res.categorySource)
        assertNull(res.categoryRuleId)
    }

    @Test
    fun testCapturePrefsConstants() {
        assertEquals("CONFIRM", CapturePrefs.AI_CATEGORY_CONFIRM)
        assertEquals("DIRECT", CapturePrefs.AI_CATEGORY_DIRECT)
    }
}
