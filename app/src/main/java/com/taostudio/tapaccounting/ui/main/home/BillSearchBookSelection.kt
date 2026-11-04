package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.viewscope.ViewBookOption

internal class BillSearchBookSelection(
    books: List<ViewBookOption>,
    initialSelection: Set<String>
) {
    private val activeNames = books.filterNot { it.isCollapsed }
        .map { BookAccountManager.normalizeBookName(it.name) }
    val collapsedNames = books.filter { it.isCollapsed }
        .map { BookAccountManager.normalizeBookName(it.name) }
    private val draft = initialSelection.mapTo(linkedSetOf(), BookAccountManager::normalizeBookName)

    var collapsedExpanded = false
        private set

    val visibleNames: List<String>
        get() = if (collapsedExpanded) activeNames + collapsedNames else activeNames

    val rowNames: List<String>
        get() = buildList {
            addAll(activeNames)
            if (collapsedNames.isNotEmpty()) {
                add(BookAccountManager.COLLAPSED_BOOK_GROUP)
                if (collapsedExpanded) addAll(collapsedNames)
            }
        }

    // 与账本范围选择器一致：收起的账本不参与数量、全选或确认，草稿保留以供再次展开。
    val selectedNames: Set<String>
        get() = draft.intersect(visibleNames.toSet())

    val allVisibleSelected: Boolean
        get() = visibleNames.isNotEmpty() && draft.containsAll(visibleNames)

    fun toggleCollapsed() {
        collapsedExpanded = !collapsedExpanded
    }

    fun toggleBook(name: String) {
        if (name !in visibleNames) return
        if (!draft.add(name)) draft.remove(name)
    }

    fun toggleAll() {
        val clear = allVisibleSelected
        draft.clear()
        if (!clear) draft.addAll(visibleNames)
    }
}
