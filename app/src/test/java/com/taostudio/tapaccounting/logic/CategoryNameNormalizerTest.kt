package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryNameNormalizerTest {

    @Test
    fun `leafName keeps the last segment of a stored display path`() {
        assertEquals("三餐", CategoryNameNormalizer.leafName("吃的 - 三餐"))
        assertEquals("水果", CategoryNameNormalizer.leafName("吃的 - 水果"))
    }

    @Test
    fun `leafName returns a plain name unchanged`() {
        assertEquals("日用品", CategoryNameNormalizer.leafName("日用品"))
        assertEquals("", CategoryNameNormalizer.leafName(""))
    }

    @Test
    fun `leafName takes only the deepest segment`() {
        assertEquals("拿铁", CategoryNameNormalizer.leafName("吃的 - 喝的 - 拿铁"))
    }

    @Test
    fun `leafName still reads the legacy separator archives used`() {
        // Legacy archives stored "父>子"; normalizeForStorage does not rewrite it, so the restore
        // path splits on the legacy separator explicitly.
        assertEquals("父>子", CategoryNameNormalizer.leafName("父>子"))
    }

    @Test
    fun `normalizeForStorage unifies the separators it knows about`() {
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的>三餐"))
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的/三餐"))
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的 - 三餐"))
    }

    @Test
    fun `normalizeForStorage heals the legacy picker separator with spaces`() {
        // 分类选择器曾经输出「父 > 子」（带空格），每个调用方都得自己转成 " - "。
        // 这里锁住归一化能力，选择器改为直接输出规范格式后，历史数据仍能靠它治愈。
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的 > 三餐"))
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的/::/三餐"))
        assertEquals("吃的 - 三餐", CategoryNameNormalizer.normalizeForStorage("吃的 · 三餐"))
    }

    @Test
    fun `stripRefundPrefix removes every stacked refund prefix`() {
        assertEquals("吃的 - 水果", CategoryNameNormalizer.stripRefundPrefix("退款：吃的 - 水果"))
        assertEquals("吃的 - 水果", CategoryNameNormalizer.stripRefundPrefix("退款：退款：吃的 - 水果"))
        assertEquals("吃的 - 水果", CategoryNameNormalizer.stripRefundPrefix("吃的 - 水果"))
    }
}
