package com.taostudio.tapaccounting.ui.main.home

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope

internal fun ResolvedLedgerViewScope.withSearchBooks(bookNames: Set<String>?): ResolvedLedgerViewScope {
    if (bookNames == null) return this
    val names = bookNames.mapTo(hashSetOf(), BookAccountManager::normalizeBookName)
    val ids = availableBooks
        .filter { BookAccountManager.normalizeBookName(it.name) in names }
        .mapTo(linkedSetOf()) { it.id }
    if (ids.isEmpty()) return this
    return copy(scope = scope.copy(books = LedgerBookSelection.Selected(ids)))
}
