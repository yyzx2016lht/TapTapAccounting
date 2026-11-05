package com.taostudio.tapaccounting.capture.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBundleCodecTest {

    private fun ruleJson(
        name: String,
        systemRuleName: String,
        enabled: Boolean = true,
        source: String? = null,
        creator: String? = null,
    ): JSONObject = JSONObject().apply {
        put("app", "com.tencent.mm")
        put("type", "DATA")
        put("name", name)
        put("enabled", enabled)
        put("js", "var $systemRuleName={get:function(){return null}};")
        put("systemRuleName", systemRuleName)
        source?.let { put("source", it) }
        creator?.let { put("creator", it) }
    }

    private fun bundleJson(vararg rules: JSONObject, common: String = "var common={};"): String =
        JSONObject().apply {
            put("common", common)
            put("category", "")
            put("rules", JSONArray().apply { rules.forEach { put(it) } })
            put("version", 1)
            put("date", 1L)
            put("source", "builtin")
        }.toString()

    @Test
    fun `asset rules become builtin system rules`() {
        val bundle = RuleBundleCodec.fromAssetJson(bundleJson(ruleJson("内置规则", "rule_1")))
        assertEquals(1, bundle.rules.size)
        assertEquals(RuleBundleCodec.SOURCE_BUILTIN, bundle.rules[0].source)
        assertEquals(RuleBundleCodec.CREATOR_SYSTEM, bundle.rules[0].creator)
        assertEquals("内置", bundle.rules[0].sourceLabel)
        assertFalse(bundle.rules[0].isUser)
    }

    @Test
    fun `bundle round trip keeps user meta and creator`() {
        val meta = JSONObject().apply {
            put("pattern", "支付￥(\\d+\\.\\d{2})")
            put("amountField", "\$1")
        }
        val bundle = RuleBundleCodec.Bundle(
            common = "var common={};",
            category = "var category={};",
            rules = listOf(
                RuleBundleCodec.Rule(
                    app = "com.tencent.mm",
                    type = RuleBundleCodec.TYPE_DATA,
                    name = "我的规则",
                    enabled = true,
                    js = "var rule_user_1={get:function(){return null}};",
                    systemRuleName = "rule_user_1",
                    source = RuleBundleCodec.SOURCE_USER,
                    creator = RuleBundleCodec.CREATOR_USER,
                    meta = meta,
                ),
            ),
            source = RuleBundleCodec.SOURCE_LOCAL,
        )
        val decoded = RuleBundleCodec.decode(RuleBundleCodec.encode(bundle))
        assertEquals("var common={};", decoded.common)
        assertEquals("var category={};", decoded.category)
        assertEquals(1, decoded.rules.size)
        assertTrue(decoded.rules[0].isUser)
        assertEquals("我的", decoded.rules[0].sourceLabel)
        assertEquals("支付￥(\\d+\\.\\d{2})", decoded.rules[0].meta?.optString("pattern"))
        assertEquals(RuleBundleCodec.SOURCE_LOCAL, decoded.source)
    }

    @Test
    fun `decode rejects missing common`() {
        val text = JSONObject().apply {
            put("rules", JSONArray())
        }.toString()
        val error = runCatching { RuleBundleCodec.decode(text) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("common"))
    }

    @Test
    fun `decode rejects rule with bad variable name`() {
        val bad = ruleJson("坏规则", "not valid name")
        val error = runCatching { RuleBundleCodec.decode(bundleJson(bad)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("第 1 条"))
    }

    @Test
    fun `enabled accepts numeric zero from local api`() {
        val rule = RuleBundleCodec.Rule.fromJson(
            ruleJson("停用规则", "rule_2").put("enabled", 0),
        )
        assertNotNull(rule)
        assertFalse(rule!!.enabled)
    }

    @Test
    fun `engine order puts user rules before system rules`() {
        val system = RuleBundleCodec.Rule(
            app = "com.tencent.mm",
            type = "DATA",
            name = "系统",
            enabled = true,
            js = "var rule_1={};",
            systemRuleName = "rule_1",
            source = RuleBundleCodec.SOURCE_BUILTIN,
            creator = RuleBundleCodec.CREATOR_SYSTEM,
        )
        val user = system.copy(
            name = "我的",
            systemRuleName = "rule_user_1",
            source = RuleBundleCodec.SOURCE_USER,
            creator = RuleBundleCodec.CREATOR_USER,
        )
        val ordered = RuleBundleCodec.orderedForEngine(listOf(system, user))
        assertEquals(listOf("rule_user_1", "rule_1"), ordered.map { it.systemRuleName })
    }

    @Test
    fun `sync merge keeps user rules and replaces system rules`() {
        val oldUser = RuleBundleCodec.Rule(
            app = "com.tencent.mm",
            type = "DATA",
            name = "我的",
            enabled = true,
            js = "var rule_user_1={};",
            systemRuleName = "rule_user_1",
            source = RuleBundleCodec.SOURCE_USER,
            creator = RuleBundleCodec.CREATOR_USER,
        )
        val oldSystem = oldUser.copy(
            name = "旧系统",
            systemRuleName = "rule_1",
            source = RuleBundleCodec.SOURCE_BUILTIN,
            creator = RuleBundleCodec.CREATOR_SYSTEM,
        )
        val newSystem = oldSystem.copy(
            name = "新系统",
            systemRuleName = "rule_2",
            source = RuleBundleCodec.SOURCE_IMPORT,
        )
        val existing = RuleBundleCodec.Bundle(
            common = "old common",
            rules = listOf(oldUser, oldSystem),
            source = RuleBundleCodec.SOURCE_BUILTIN,
        )
        val merged = RuleBundleCodec.mergeForSync(
            existing = existing,
            syncedRules = listOf(newSystem),
            common = "new common",
            category = "new category",
        )
        assertEquals("new common", merged.common)
        assertEquals("new category", merged.category)
        assertEquals(listOf("rule_user_1", "rule_2"), merged.rules.map { it.systemRuleName })
        assertEquals(RuleBundleCodec.SOURCE_LOCAL, merged.source)
    }
}
