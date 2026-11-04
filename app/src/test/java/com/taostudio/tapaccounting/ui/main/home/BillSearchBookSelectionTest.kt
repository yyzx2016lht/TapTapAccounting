package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.viewscope.ViewBookOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSearchBookSelectionTest {
    private val books = listOf(
        ViewBookOption(1L, "日常账本", isShared = false),
        ViewBookOption(2L, "旧账本", isShared = false, isCollapsed = true),
        ViewBookOption(3L, "家庭账本", isShared = true)
    )
    private val active = setOf("日常账本", "家庭账本")
    private val all = active + "旧账本"

    @Test
    fun `collapsed books start behind a non-selectable group row`() {
        val selection = BillSearchBookSelection(books, active)

        assertFalse(selection.collapsedExpanded)
        assertEquals(listOf("日常账本", "家庭账本", BookAccountManager.COLLAPSED_BOOK_GROUP), selection.rowNames)
        assertEquals(active, selection.visibleNames.toSet())
        selection.toggleBook(BookAccountManager.COLLAPSED_BOOK_GROUP)
        assertEquals(active, selection.selectedNames)
    }

    @Test
    fun `collapsed books cannot be selected before expansion`() {
        val selection = BillSearchBookSelection(books, emptySet())

        selection.toggleBook("旧账本")

        assertTrue(selection.selectedNames.isEmpty())
        selection.toggleCollapsed()
        assertTrue(selection.selectedNames.isEmpty())
    }

    @Test
    fun `select all while collapsed does not silently include collapsed books`() {
        val selection = BillSearchBookSelection(books, emptySet())

        selection.toggleAll()

        assertEquals(active, selection.selectedNames)
        assertTrue(selection.allVisibleSelected)
        selection.toggleCollapsed()
        assertEquals(active, selection.selectedNames)
        assertFalse(selection.allVisibleSelected)
    }

    @Test
    fun `expansion makes collapsed books visible and selectable`() {
        val selection = BillSearchBookSelection(books, active)

        selection.toggleCollapsed()
        selection.toggleBook("旧账本")

        assertEquals(listOf("日常账本", "家庭账本", BookAccountManager.COLLAPSED_BOOK_GROUP, "旧账本"), selection.rowNames)
        assertEquals(all, selection.visibleNames.toSet())
        assertEquals(all, selection.selectedNames)
        assertTrue(selection.allVisibleSelected)
    }

    @Test
    fun `select all after expansion includes collapsed books and toggles to clear`() {
        val selection = BillSearchBookSelection(books, active)
        selection.toggleCollapsed()

        selection.toggleAll()
        assertEquals(all, selection.selectedNames)
        selection.toggleAll()
        assertTrue(selection.selectedNames.isEmpty())
        assertFalse(selection.allVisibleSelected)
    }

    @Test
    fun `collapse excludes hidden selections from confirmation but preserves draft for expansion`() {
        val selection = BillSearchBookSelection(books, all)
        selection.toggleCollapsed()
        assertEquals(all, selection.selectedNames)

        selection.toggleCollapsed()
        assertEquals(active, selection.selectedNames)
        assertEquals(2, selection.visibleNames.size)
        selection.toggleCollapsed()
        assertEquals(all, selection.selectedNames)
    }

    @Test
    fun `previously selected collapsed book still requires expansion`() {
        val selection = BillSearchBookSelection(books, setOf("旧账本"))

        assertTrue(selection.selectedNames.isEmpty())
        selection.toggleCollapsed()
        assertEquals(setOf("旧账本"), selection.selectedNames)
    }

    @Test
    fun `select all while collapsed replaces hidden drafts like the existing scope picker`() {
        val selection = BillSearchBookSelection(books, setOf("旧账本"))

        selection.toggleAll()
        selection.toggleCollapsed()

        assertEquals(active, selection.selectedNames)
    }

    @Test
    fun `without collapsed books no group row is added`() {
        val selection = BillSearchBookSelection(books.filterNot { it.isCollapsed }, emptySet())

        assertEquals(listOf("日常账本", "家庭账本"), selection.rowNames)
        selection.toggleAll()
        assertEquals(active, selection.selectedNames)
        selection.toggleAll()
        assertTrue(selection.selectedNames.isEmpty())
    }

    @Test
    fun `no books leaves no confirmable selection`() {
        val selection = BillSearchBookSelection(emptyList(), setOf("已删除账本"))

        selection.toggleAll()

        assertTrue(selection.rowNames.isEmpty())
        assertTrue(selection.selectedNames.isEmpty())
        assertFalse(selection.allVisibleSelected)
    }
}
