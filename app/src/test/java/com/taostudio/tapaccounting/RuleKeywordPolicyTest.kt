package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleKeywordPolicyTest {

    @Test
    fun `generic words are rejected`() {
        RuleKeywordPolicy.GENERIC_KEYWORDS.forEach { word ->
            assertEquals("", RuleKeywordPolicy.sanitize(word))
        }
    }

    @Test
    fun `recipient style keyword is rejected`() {
        assertEquals("", RuleKeywordPolicy.sanitize("给妈妈"))
        assertEquals("", RuleKeywordPolicy.sanitize("给某某"))
    }

    @Test
    fun `quoted keyword is cleaned`() {
        assertEquals("苹果", RuleKeywordPolicy.sanitize("  “苹果”。  "))
        assertEquals("瑞幸咖啡", RuleKeywordPolicy.sanitize("关键词：瑞幸咖啡"))
    }

    @Test
    fun `keyword longer than eight chars is rejected`() {
        assertEquals("", RuleKeywordPolicy.sanitize("这是一个特别长的关键词"))
        assertEquals("苹果手机壳", RuleKeywordPolicy.sanitize("苹果手机壳"))
    }

    @Test
    fun `short standalone note is used directly`() {
        assertEquals("苹果", RuleKeywordPolicy.directKeywordFromNote("苹果"))
        assertEquals("星巴克", RuleKeywordPolicy.directKeywordFromNote(" 星巴克 "))
        assertEquals("打车", RuleKeywordPolicy.directKeywordFromNote("打车"))
    }

    @Test
    fun `sentence note falls back to ai`() {
        assertNull(RuleKeywordPolicy.directKeywordFromNote("买了苹果"))
        assertNull(RuleKeywordPolicy.directKeywordFromNote("苹果15"))
        assertNull(RuleKeywordPolicy.directKeywordFromNote("楼下超市"))
        assertNull(RuleKeywordPolicy.directKeywordFromNote("午饭"))
        assertNull(RuleKeywordPolicy.directKeywordFromNote(""))
    }
}
