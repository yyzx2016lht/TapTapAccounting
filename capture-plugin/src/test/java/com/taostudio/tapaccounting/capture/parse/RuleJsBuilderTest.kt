package com.taostudio.tapaccounting.capture.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleJsBuilderTest {

    private fun form() = RuleJsBuilder.RuleForm(
        app = "com.tencent.mm",
        type = "DATA",
        name = "测试规则",
        enabled = true,
        pattern = "支付￥(\\d+\\.\\d{2}).*?商户：(.*?)，",
        billType = RuleJsBuilder.BILL_EXPEND,
        amountField = "\$1",
        merchantField = "\$2",
        itemField = "固定商品",
        accountFromField = "微信零钱",
        accountToField = "",
        feeField = "",
        currencyField = "CNY",
        timeField = "\$3",
    )

    @Test
    fun `build js uses existing rule shape and var name`() {
        val js = RuleJsBuilder.buildJs(form(), "rule_user_123")
        assertTrue(js.startsWith("var rule_user_123 = {"))
        assertTrue(js.contains("get: function (data)"))
        assertTrue(js.contains("new RegExp(\"支付￥(\\\\d+\\\\.\\\\d{2}).*?商户：(.*?)，\", \"i\")"))
        assertTrue(js.contains("money: num(\"\$1\")"))
        assertTrue(js.contains("shopName: String(pick(\"\$2\"))"))
        assertTrue(js.contains("type: \"Expend\""))
        assertTrue(js.contains("channel: \"测试规则\""))
        assertTrue(js.trimEnd().endsWith("};"))
    }

    @Test
    fun `form meta round trip keeps editable fields`() {
        val meta = form().toMeta()
        val restored = RuleJsBuilder.RuleForm.fromMeta(meta)
        assertNotNull(restored)
        assertEquals("com.tencent.mm", restored!!.app)
        assertEquals("\$1", restored.amountField)
        assertEquals("\$2", restored.merchantField)
        assertEquals("固定商品", restored.itemField)
        assertEquals(RuleJsBuilder.BILL_EXPEND, restored.billType)
        assertTrue(restored.ignoreCase)
    }

    @Test
    fun `meta without pattern is not safe for form editing`() {
        assertNull(RuleJsBuilder.RuleForm.fromMeta(null))
        assertNull(RuleJsBuilder.RuleForm.fromMeta(org.json.JSONObject("{\"app\":\"com.tencent.mm\"}")))
    }

    @Test
    fun `copying a rule renames its js variable`() {
        val js = "var rule_5=function(t,r){t.get=function(){return 1};return t}({},common);"
        val renamed = RuleJsBuilder.renameRuleVariable(js, "rule_5", "rule_user_9")
        assertTrue(renamed.startsWith("var rule_user_9=function"))
        assertFalse(renamed.contains("rule_5"))
        assertEquals(js, RuleJsBuilder.renameRuleVariable(js, "rule_5", "rule_5"))
    }

    @Test
    fun `bill type and data type are normalized`() {
        assertEquals(RuleJsBuilder.BILL_INCOME, RuleJsBuilder.normalizeBillType("Income"))
        assertEquals(RuleJsBuilder.BILL_EXPEND, RuleJsBuilder.normalizeBillType("unknown"))
        assertEquals("收入", RuleJsBuilder.billTypeLabel("Income"))
        assertEquals("NOTICE", RuleJsBuilder.normalizeType("notice"))
        assertEquals("DATA", RuleJsBuilder.normalizeType("bad"))
    }
}
