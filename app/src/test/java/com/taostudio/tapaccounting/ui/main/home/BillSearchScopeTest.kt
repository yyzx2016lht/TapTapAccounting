package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import com.taostudio.tapaccounting.viewscope.SharedBookMemberContext
import com.taostudio.tapaccounting.viewscope.ViewBookOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSearchScopeTest {
    private val books = listOf(
        ViewBookOption(1L, "日常账本", isShared = false),
        ViewBookOption(2L, "家庭账本", isShared = true),
        ViewBookOption(3L, "旅行账本", isShared = true),
        ViewBookOption(4L, "旧账本", isShared = false, isCollapsed = true)
    )
    private val members = mapOf(
        "家庭账本" to SharedBookMemberContext(
            localMemberId = "family-me",
            memberNames = mapOf("family-me" to "我", "family-other" to "小明")
        ),
        "旅行账本" to SharedBookMemberContext(
            localMemberId = "trip-me",
            memberNames = mapOf("trip-me" to "我", "trip-other" to "小红")
        )
    )

    @Test
    fun `null inherits All without including collapsed books`() {
        val base = resolved(LedgerBookSelection.All)

        val result = base.withSearchBooks(null)

        assertSame(base, result)
        assertSame(LedgerBookSelection.All, result.scope.books)
        assertEquals(setOf("日常账本", "家庭账本", "旅行账本"), result.selectedBookNames)
        assertTrue(result.includes(bill("日常账本")))
        assertFalse(result.includes(bill("旧账本")))
    }

    @Test
    fun `null retains the saved single book scope`() {
        val base = resolved()

        val result = base.withSearchBooks(null)

        assertSame(base, result)
        assertEquals(LedgerBookSelection.Selected(setOf(1L)), result.scope.books)
        assertFalse(result.includes(bill("家庭账本")))
    }

    @Test
    fun `explicit other book replaces rather than intersects saved selection`() {
        val base = resolved()
        assertFalse(base.includes(bill("家庭账本")))

        val result = base.withSearchBooks(setOf("家庭账本"))

        assertEquals(LedgerBookSelection.Selected(setOf(2L)), result.scope.books)
        assertTrue(result.includes(bill("家庭账本")))
        assertFalse(result.includes(bill("日常账本")))
    }

    @Test
    fun `explicit multiple books expand beyond saved single book`() {
        val base = resolved()

        val result = base.withSearchBooks(setOf("日常账本", "家庭账本", "旅行账本"))

        assertEquals(LedgerBookSelection.Selected(setOf(1L, 2L, 3L)), result.scope.books)
        assertTrue(result.includes(bill("日常账本")))
        assertTrue(result.includes(bill("家庭账本")))
        assertTrue(result.includes(bill("旅行账本")))
        assertFalse(result.includes(bill("旧账本")))
    }

    @Test
    fun `explicit all books includes collapsed books instead of using All`() {
        val result = resolved().withSearchBooks(books.mapTo(linkedSetOf()) { it.name })

        assertEquals(LedgerBookSelection.Selected(setOf(1L, 2L, 3L, 4L)), result.scope.books)
        assertTrue(result.coversAllAvailableBooks)
        assertTrue(result.includesCollapsedBooks)
        books.forEach { assertTrue(result.includes(bill(it.name))) }
    }

    @Test
    fun `expanded search retains MINE and each shared book member context`() {
        val base = resolved(memberScope = LedgerMemberScope.MINE)

        val result = base.withSearchBooks(setOf("日常账本", "家庭账本", "旅行账本", "旧账本"))

        assertEquals(LedgerMemberScope.MINE, result.scope.members)
        assertSame(base.availableBooks, result.availableBooks)
        assertSame(base.memberContextsByBookName, result.memberContextsByBookName)
        assertTrue(result.includes(bill("日常账本")))
        assertTrue(result.includes(bill("旧账本")))
        assertTrue(result.includes(bill("家庭账本")))
        assertTrue(result.includes(bill("家庭账本", isShared = true, memberId = "family-me")))
        assertFalse(result.includes(bill("家庭账本", isShared = true, memberId = "family-other")))
        assertTrue(result.includes(bill("旅行账本", isShared = true, memberId = "trip-me")))
        assertFalse(result.includes(bill("旅行账本", isShared = true, memberId = "trip-other")))
        assertFalse(result.includes(bill("旅行账本", isShared = true, memberId = "family-me")))
        assertEquals("小明", result.memberNameFor(bill("家庭账本", true, "family-other")))
        assertEquals("小红", result.memberNameFor(bill("旅行账本", true, "trip-other")))
    }

    @Test
    fun `invalid or deleted names fall back to the original scope`() {
        val base = resolved(memberScope = LedgerMemberScope.MINE)

        val result = base.withSearchBooks(setOf("不存在的账本", "已删除账本"))

        assertSame(base, result)
        assertTrue(result.includes(bill("日常账本")))
        assertFalse(result.includes(bill("家庭账本")))
        assertFalse(result.includes(bill("旧账本")))
    }

    @Test
    fun `empty explicit selection falls back to the original scope`() {
        val base = resolved()

        assertSame(base, base.withSearchBooks(emptySet()))
    }

    @Test
    fun `unmatched names do not discard valid explicit matches`() {
        val result = resolved().withSearchBooks(setOf("家庭账本", "已删除账本"))

        assertEquals(LedgerBookSelection.Selected(setOf(2L)), result.scope.books)
        assertTrue(result.includes(bill("家庭账本")))
        assertFalse(result.includes(bill("日常账本")))
    }

    @Test
    fun `matching normalizes input and available names including blank default alias`() {
        val base = resolved().copy(
            availableBooks = books.map {
                if (it.id == 2L) it.copy(name = " 家庭账本 ") else it
            }
        )

        val result = base.withSearchBooks(setOf("\t家庭账本\n", "  "))

        assertEquals(LedgerBookSelection.Selected(setOf(1L, 2L)), result.scope.books)
        assertTrue(result.includes(bill("日常账本")))
        assertTrue(result.includes(bill("家庭账本")))
    }

    @Test
    fun `overriding search books leaves the base object unchanged`() {
        val base = resolved(memberScope = LedgerMemberScope.MINE)
        val originalScope = base.scope
        val originalSelectedBooks = base.selectedBooks
        val names = linkedSetOf("家庭账本", "旧账本")

        val result = base.withSearchBooks(names)

        assertNotSame(base, result)
        assertSame(originalScope, base.scope)
        assertSame(originalSelectedBooks, base.selectedBooks)
        assertSame(books, base.availableBooks)
        assertSame(members, base.memberContextsByBookName)
        assertEquals(LedgerBookSelection.Selected(setOf(1L)), base.scope.books)
        assertEquals(LedgerMemberScope.MINE, base.scope.members)
        assertEquals(setOf("日常账本"), base.selectedBookNames)
        assertFalse(base.includes(bill("家庭账本")))
        assertFalse(base.includes(bill("旧账本")))
        assertEquals(LedgerBookSelection.Selected(setOf(2L, 4L)), result.scope.books)
        assertEquals(setOf("家庭账本", "旧账本"), names)
    }

    private fun resolved(
        booksSelection: LedgerBookSelection = LedgerBookSelection.Selected(setOf(1L)),
        memberScope: LedgerMemberScope = LedgerMemberScope.EVERYONE
    ) = ResolvedLedgerViewScope(
        LedgerViewScope(booksSelection, memberScope),
        books,
        members
    )

    private fun bill(bookName: String, isShared: Boolean = false, memberId: String? = null) = Bill(
        type = Bill.TYPE_EXPENSE,
        amount = 10.0,
        time = 1L,
        bookName = bookName,
        isShared = isShared,
        memberId = memberId
    )
}
