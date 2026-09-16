package com.taostudio.tapaccounting

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AIAccountingSupportTest {
    private val expenseCats = listOf(
        "吃的",
        "吃的/::/水果",
        "吃的/::/买菜",
        "喝的",
        "喝的/::/饮料",
        "其他"
    )

    @Test
    fun findBestMatchMapsSlashSeparatedChildPath() {
        val matched = findBestMatch("吃的/水果", expenseCats)

        assertEquals("吃的/::/水果", matched)
    }

    @Test
    fun findBestMatchMapsLeafNameToFullChildPath() {
        val matched = findBestMatch("水果", expenseCats)

        assertEquals("吃的/::/水果", matched)
    }

    @Test
    fun normalizeCategoryPathSupportsCommonSeparators() {
        val normalized = normalizeCategoryPath(" 吃的 -> 水果 ")

        assertEquals("吃的/::/水果", normalized)
    }

    @Test
    fun resolvePromptCategoryIdMapsToRealExpenseCategory() {
        val resolved = resolvePromptCategoryId(
            categoryId = "e1",
            type = 0,
            expenseCats = listOf("网费", "居家", "其它"),
            incomeCats = listOf("工资", "其它")
        )

        assertEquals("居家", resolved)
    }

    @Test
    fun resolvePromptCategoryIdRejectsUnknownCategoryId() {
        val resolved = resolvePromptCategoryId(
            categoryId = "e99",
            type = 0,
            expenseCats = listOf("网费", "居家", "其它"),
            incomeCats = listOf("工资", "其它")
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolvePromptCategoryIdRejectsWrongTypePrefix() {
        val resolved = resolvePromptCategoryId(
            categoryId = "i0",
            type = 0,
            expenseCats = listOf("餐饮"),
            incomeCats = listOf("工资")
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolvePromptBookIdMapsToRealBook() {
        val resolved = resolvePromptBookId(
            bookId = "b1",
            availableBooks = listOf("默认账本", "伙食账本", "日用账本")
        )

        assertEquals("伙食账本", resolved)
    }

    @Test
    fun resolveAccountingBookSelectionPrefersCandidateIdOverLegacyName() {
        val resolved = resolveAccountingBookSelection(
            bookId = "b2",
            bookName = "伙食账本",
            availableBooks = listOf("默认账本", "伙食账本", "日用账本")
        )

        assertEquals("日用账本", resolved)
    }

    @Test
    fun resolveAccountingBookSelectionAcceptsKnownLegacyName() {
        val resolved = resolveAccountingBookSelection(
            bookId = "",
            bookName = "  伙食账本  ",
            availableBooks = listOf("默认账本", "伙食账本")
        )

        assertEquals("伙食账本", resolved)
    }

    @Test
    fun resolveAccountingBookSelectionRejectsInventedBook() {
        val resolved = resolveAccountingBookSelection(
            bookId = "b99",
            bookName = "AI 自造账本",
            availableBooks = listOf("默认账本", "伙食账本")
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveAccountingBookForSaveUsesPerBillThenBatchThenFallback() {
        val availableBooks = listOf("默认账本", "伙食账本", "日用账本")

        assertEquals(
            "日用账本",
            resolveAccountingBookForSave("日用账本", "伙食账本", availableBooks, "默认账本")
        )
        assertEquals(
            "伙食账本",
            resolveAccountingBookForSave("", "伙食账本", availableBooks, "默认账本")
        )
        assertEquals(
            "默认账本",
            resolveAccountingBookForSave("", "", availableBooks, "默认账本")
        )
    }

    @Test
    fun cleanJsonStringStripsAlreadyBookedPrefix() {
        val cleaned = cleanJsonString(
            """已记账：{"bills":[{"amount":5.99,"type":0,"time":"2026-09-08 12:00:00","remarks":"牛奶","currency":"CNY"}]}"""
        )
        assertTrue(cleaned.startsWith("{"))
        assertTrue(JSONObject(cleaned).has("bills"))
    }

    @Test
    fun normalizeAccountingResultUsesNowWhenTimeMissing() {
        val now = 1_725_768_000_000L // fixed anchor
        val root = JSONObject(
            """{"bills":[{"amount":5.99,"type":0,"time":"","remarks":"牛奶","currency":"CNY","category_id":"e0"}]}"""
        )
        normalizeAccountingResult(
            root = root,
            expenseCats = expenseCats,
            incomeCats = emptyList(),
            assetNames = emptyList(),
            assetFeatureEnabled = false,
            referenceText = "买了牛奶5.99",
            nowMillis = now
        )
        val time = root.getJSONArray("bills").getJSONObject(0).getString("time")
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        assertEquals(expected, time)
    }

}

