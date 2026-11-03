package com.taostudio.tapaccounting.capture.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表驱动测试：规则名全部是手写假数据，不包含任何真实个人账单。
 *
 * 口径为组合 B：支出、收入、退款、转账、还款都要进账本，不再以“只记支出”为前提。
 */
class BillTypeLocalizerTest {

    private data class Case(
        val label: String,
        val autoType: String,
        val ruleName: String?,
        val expectedType: Int?,
        val expectedSubType: Int = LocalBillType.SUBTYPE_NORMAL,
        val expectedNeedsRefundLink: Boolean = false,
    )

    @Test
    fun `table maps auto accounting rules to local bill types`() {
        val cases = listOf(
            // ---- 支出 ----
            Case("普通支出", "Expend", "微信支付在线支付", LocalBillType.TYPE_EXPENSE),
            Case("支付宝消费", "Expend", "支付宝消费", LocalBillType.TYPE_EXPENSE),
            Case("支付宝账单列表", "Expend", "支付宝账单列表", LocalBillType.TYPE_EXPENSE),
            Case("出账转账仍按支出", "Expend", "微信转账", LocalBillType.TYPE_EXPENSE),
            Case("群收款是出账，不按收入关键词改判", "Expend", "微信群收款", LocalBillType.TYPE_EXPENSE),
            Case("支出规则名含收款时按收入兜底", "Expend", "微信支付经营收款", LocalBillType.TYPE_INCOME),
            Case("支出规则名含收入时按收入兜底", "Expend", "某平台收入", LocalBillType.TYPE_INCOME),
            Case("支出规则名含收益时按收入兜底", "Expend", "零钱通收益", LocalBillType.TYPE_INCOME),
            Case("支出规则名含打款时按收入兜底", "Expend", "微信支付商家打款", LocalBillType.TYPE_INCOME),

            // ---- 还款：规范输出 2/1，不输出旧值 3 ----
            Case(
                "支出 + 还款",
                "Expend",
                "信用卡还款",
                LocalBillType.TYPE_TRANSFER,
                LocalBillType.SUBTYPE_REPAYMENT,
            ),
            Case(
                "转账 + 主动还款",
                "Transfer",
                "支付宝花呗主动还款",
                LocalBillType.TYPE_TRANSFER,
                LocalBillType.SUBTYPE_REPAYMENT,
            ),
            Case(
                "转账 + 自动还款",
                "Transfer",
                "支付宝花呗自动还款",
                LocalBillType.TYPE_TRANSFER,
                LocalBillType.SUBTYPE_REPAYMENT,
            ),
            // 组合 B：别人还款给我按普通收入，不能返回 null。
            Case("收入 + 还款按普通收入", "Income", "某还款到账", LocalBillType.TYPE_INCOME),
            Case("收入 + 信用卡还款按普通收入", "Income", "信用卡还款入账", LocalBillType.TYPE_INCOME),

            // ---- 转账 ----
            Case("普通转账", "Transfer", "支付宝转账到银行卡", LocalBillType.TYPE_TRANSFER),
            Case(
                "转账 + 收款到余额宝仍是账户间调拨",
                "Transfer",
                "支付宝转账收款到余额宝",
                LocalBillType.TYPE_TRANSFER,
            ),
            Case("提现是账户间调拨", "Transfer", "微信支付提现", LocalBillType.TYPE_TRANSFER),
            Case("空白规则名的转账仍按转账", "Transfer", "   ", LocalBillType.TYPE_TRANSFER),

            // ---- 收入 / 退款 ----
            Case(
                "收入 + 退款",
                "Income",
                "微信支付商家退款",
                LocalBillType.TYPE_INCOME,
                LocalBillType.SUBTYPE_REFUND,
                expectedNeedsRefundLink = true,
            ),
            Case(
                "收入 + 转账红包退款",
                "Income",
                "微信支付转账（红包）退款",
                LocalBillType.TYPE_INCOME,
                LocalBillType.SUBTYPE_REFUND,
                expectedNeedsRefundLink = true,
            ),
            Case("收入 + 其他", "Income", "微信支付零钱通收益", LocalBillType.TYPE_INCOME),
            Case("收入 + 转账（按 AA 方向）", "Income", "微信转账", LocalBillType.TYPE_INCOME),
            Case("null 规则名的收入仍按收入", "Income", null, LocalBillType.TYPE_INCOME),

            // ---- 无法判断：不猜 ----
            Case("支出 + 退款方向矛盾", "Expend", "某笔退款", null),
            Case("转账 + 退款方向矛盾", "Transfer", "某转账退款", null),
            Case("未知类型", "Refund", "微信支付商家退款", null),
            Case("空类型", "", "微信支付在线支付", null),
            Case("空白规则名的支出仍按支出", "Expend", "   ", LocalBillType.TYPE_EXPENSE),
        )

        cases.forEach { case ->
            val expected = case.expectedType?.let {
                LocalBillTypeMapping(
                    type = it,
                    subType = case.expectedSubType,
                    needsRefundLink = case.expectedNeedsRefundLink,
                )
            }
            assertEquals(
                "case=${case.label}",
                expected,
                BillTypeLocalizer.localize(case.autoType, case.ruleName),
            )
        }
    }

    @Test
    fun `income repayment is ordinary income under option b`() {
        val mapping = BillTypeLocalizer.localize("Income", "某还款到账")

        assertEquals(LocalBillType.TYPE_INCOME, mapping?.type)
        assertEquals(LocalBillType.SUBTYPE_NORMAL, mapping?.subType)
        assertFalse(mapping?.needsRefundLink == true)
    }

    @Test
    fun `refund always requires source bill link`() {
        val mapping = BillTypeLocalizer.localize("Income", "微信支付商家退款")

        assertEquals(LocalBillType.TYPE_INCOME, mapping?.type)
        assertEquals(LocalBillType.SUBTYPE_REFUND, mapping?.subType)
        assertTrue(mapping?.needsRefundLink == true)
    }

    @Test
    fun `repayment uses canonical transfer subtype instead of legacy type 3`() {
        val mapping = BillTypeLocalizer.localize("Transfer", "支付宝花呗主动还款")

        assertEquals(LocalBillType.TYPE_TRANSFER, mapping?.type)
        assertEquals(LocalBillType.SUBTYPE_REPAYMENT, mapping?.subType)
        assertFalse(mapping?.type == LocalBillType.TYPE_REPAYMENT)
    }

    @Test
    fun `unknown auto type never guesses from rule name`() {
        assertNull(BillTypeLocalizer.localize("Other", "微信支付商家退款"))
        assertNull(BillTypeLocalizer.localize("", "支付宝花呗主动还款"))
    }

    @Test
    fun `ordinary expense predicate only matches 0 slash 0`() {
        assertTrue(BillTypeLocalizer.localize("Expend", "微信转账")!!.isExpense)
        assertFalse(BillTypeLocalizer.localize("Expend", "信用卡还款")!!.isExpense)
        assertFalse(BillTypeLocalizer.localize("Income", "微信支付商家退款")!!.isExpense)
        assertFalse(BillTypeLocalizer.localize("Transfer", "支付宝转账到银行卡")!!.isExpense)
    }

    @Test
    fun `local type constants stay aligned with app bill contract`() {
        // 与 app 的 Bill 常量对齐；改这里之前先确认 AppDatabase migration。
        assertEquals(0, LocalBillType.TYPE_EXPENSE)
        assertEquals(1, LocalBillType.TYPE_INCOME)
        assertEquals(2, LocalBillType.TYPE_TRANSFER)
        assertEquals(3, LocalBillType.TYPE_REPAYMENT)
        assertEquals(0, LocalBillType.SUBTYPE_NORMAL)
        assertEquals(1, LocalBillType.SUBTYPE_REPAYMENT)
        assertEquals(2, LocalBillType.SUBTYPE_REFUND)
    }
}
