package com.taostudio.tapaccounting

import android.content.Context
import org.json.JSONArray
import java.io.File

object BookAccountManager {
    const val DEFAULT_BOOK = "\u65E5\u5E38\u8D26\u672C"
    const val ALL_BOOK = "\u5168\u90E8\u8D26\u672C"
    const val COLLAPSED_BOOK_GROUP = "\u6536\u7EB3\u8D26\u672C"
    private const val LEGACY_DEFAULT_BOOK = "\u699B\u6A3F\uE17B\u7490\uFE3D\u6E70"

    private const val PREF_NAME = "flip_prefs"
    private const val KEY_BOOK_ACCOUNTS = "book_accounts_v1"
    private const val KEY_COLLAPSED_BOOK_ACCOUNTS = "collapsed_book_accounts_v1"
    private const val KEY_SELECTED_BOOK = "selected_book_name_v1"
    private const val KEY_DEFAULT_BOOK = "default_book_name_v1"

    // 每个账本对应的封面图路径前缀（key = "book_banner_" + 账本名）
    private const val KEY_BOOK_BANNER_PREFIX = "book_banner_"
    // 每个账本对应的主题色前缀（key = "book_color_" + 账本名，存 ARGB int）
    private const val KEY_BOOK_COLOR_PREFIX = "book_color_"

    /**
     * 预置的账本颜色池，按账本顺序循环取用（共 15 色）。
     *
     * 刻意不收高饱和的黄绿（#6BBF40）：它紧挨着收入语义色 income_color #4CAF50，
     * 两个绿同屏时会被读成「收入绿」，而大面积高饱和底色压白字也糊得厉害。
     * 绿色系已有 绿 / 深绿 两档，第四档本就冗余。
     *
     * 注意：手动选色器（HomeBannerController 里的 colorOptions）是另一份列表，
     * 增删颜色时两边要同步。
     */
    val BOOK_COLOR_PALETTE = intArrayOf(
        0xFF4080FF.toInt(), // 蓝
        0xFF2FA36B.toInt(), // 绿
        0xFFE05A5A.toInt(), // 红
        0xFF8A4FD1.toInt(), // 紫
        0xFFE07A30.toInt(), // 橙
        0xFF29A8A8.toInt(), // 青
        0xFF1A56CC.toInt(), // 深蓝
        0xFF29A8E0.toInt(), // 天蓝
        0xFF1E7A50.toInt(), // 深绿
        0xFFC0392B.toInt(), // 深红
        0xFFE0609A.toInt(), // 粉色
        0xFF5E3596.toInt(), // 深紫
        0xFF8D5524.toInt(), // 棕色
        0xFF555555.toInt(), // 深灰
        0xFF222222.toInt(), // 炭黑
    )

    /** 获取账本封面图路径，null 表示未设置 */
    fun getBookBannerPath(context: Context, bookName: String): String? {
        val key = KEY_BOOK_BANNER_PREFIX + normalizeBookName(bookName)
        return prefs(context).getString(key, null)?.takeIf { it.isNotEmpty() }
    }

    /** 设置账本封面图路径，传 null 或空串表示清除 */
    fun setBookBannerPath(context: Context, bookName: String, path: String?) {
        val key = KEY_BOOK_BANNER_PREFIX + normalizeBookName(bookName)
        prefs(context).edit().apply {
            if (path.isNullOrEmpty()) remove(key) else putString(key, path)
            apply()
        }
    }

    /**
     * 获取账本主题色（ARGB int）。
     * 若未手动设置，则根据账本在列表中的顺序从 BOOK_COLOR_PALETTE 中分配并**持久化**，
     * 保证下次颜色不会因账本列表顺序变化而改变。
     */
    fun getBookColor(context: Context, bookName: String): Int {
        val key = KEY_BOOK_COLOR_PREFIX + normalizeBookName(bookName)
        val saved = prefs(context).getInt(key, Int.MIN_VALUE)
        if (saved != Int.MIN_VALUE) return saved
        // 首次分配：按当前顺序取色，立刻持久化
        val books = getBookAccounts(context)
        val index = books.indexOfFirst { it == normalizeBookName(bookName) }.coerceAtLeast(0)
        val color = BOOK_COLOR_PALETTE[index % BOOK_COLOR_PALETTE.size]
        prefs(context).edit().putInt(key, color).apply()
        return color
    }

    /** 手动设置账本主题色 */
    fun setBookColor(context: Context, bookName: String, color: Int) {
        val key = KEY_BOOK_COLOR_PREFIX + normalizeBookName(bookName)
        prefs(context).edit().putInt(key, color).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun normalizeBookName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isBlank() -> DEFAULT_BOOK
            value == LEGACY_DEFAULT_BOOK -> DEFAULT_BOOK
            else -> value
        }
    }

    fun getDefaultBook(context: Context, availableBooks: List<String>? = null): String {
        val stored = prefs(context).getString(KEY_DEFAULT_BOOK, null)?.trim().orEmpty()
        val normalizedStored = normalizeBookName(stored).takeIf { it.isNotBlank() && it != ALL_BOOK }
        val normalizedAvailable = (availableBooks ?: emptyList())
            .map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK }
            .distinct()
        val resolved = when {
            normalizedStored != null && (normalizedAvailable.isEmpty() || normalizedAvailable.contains(normalizedStored)) -> normalizedStored
            normalizedAvailable.isNotEmpty() -> normalizedAvailable.first()
            else -> DEFAULT_BOOK
        }
        if (normalizedStored != resolved) {
            prefs(context).edit().putString(KEY_DEFAULT_BOOK, resolved).apply()
        }
        return resolved
    }

    fun setDefaultBook(context: Context, bookName: String) {
        val normalized = normalizeBookName(bookName)
        if (normalized.isBlank() || normalized == ALL_BOOK) return
        prefs(context).edit().putString(KEY_DEFAULT_BOOK, normalized).apply()
    }

    /**
     * 返回可写入账单的账本名称。
     * 「全部账本」是聚合视图，不允许作为真实落库 bookName。
     */
    fun resolveWritableBook(bookName: String?): String {
        val normalized = normalizeBookName(bookName)
        return if (normalized == ALL_BOOK) DEFAULT_BOOK else normalized
    }

    fun resolveWritableBook(context: Context, bookName: String?): String {
        val normalized = normalizeBookName(bookName)
        return if (normalized == ALL_BOOK) getDefaultBook(context) else normalized
    }

    fun rawAliases(bookName: String): List<String> {
        val normalized = normalizeBookName(bookName)
        return when (normalized) {
            ALL_BOOK -> emptyList()
            COLLAPSED_BOOK_GROUP -> emptyList()
            DEFAULT_BOOK -> listOf(DEFAULT_BOOK, LEGACY_DEFAULT_BOOK, "")
            else -> listOf(normalized)
        }
    }

    fun isBillInBook(billBookName: String?, selectedBookName: String): Boolean {
        if (normalizeBookName(selectedBookName) == ALL_BOOK) return true
        if (normalizeBookName(selectedBookName) == COLLAPSED_BOOK_GROUP) return false
        return normalizeBookName(billBookName) == normalizeBookName(selectedBookName)
    }

    fun withAllBookOption(books: List<String>, defaultBookName: String = DEFAULT_BOOK): List<String> {
        val defaultBook = normalizeBookName(defaultBookName)
        val result = linkedSetOf(defaultBook)
        books.map(::normalizeBookName)
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP }
            .forEach { result.add(it) }
        if (result.size > 1) {
            result.add(ALL_BOOK)
        }
        return result.toList()
    }

    fun getSelectedBook(context: Context, availableBooks: List<String>? = null): String {
        val normalizedAvailable = (availableBooks ?: getBookAccounts(context)).map { normalizeBookName(it) }
        val selected = normalizeBookName(prefs(context).getString(KEY_SELECTED_BOOK, null))
        return when {
            selected == ALL_BOOK -> ALL_BOOK
            normalizedAvailable.isEmpty() -> DEFAULT_BOOK
            normalizedAvailable.contains(selected) -> selected
            else -> normalizedAvailable.first().also { setSelectedBook(context, it) }
        }
    }

    fun setSelectedBook(context: Context, bookName: String) {
        prefs(context).edit().putString(KEY_SELECTED_BOOK, normalizeBookName(bookName)).apply()
    }

    fun getCollapsedBookAccounts(context: Context, availableBooks: List<String> = getBookAccounts(context)): List<String> {
        val available = availableBooks
            .map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP }
            .toSet()
        return readCollapsedAccounts(context)
            .map { normalizeBookName(it) }
            .filter { it in available && it != getDefaultBook(context, availableBooks) }
            .distinct()
    }

    fun isBookCollapsed(context: Context, bookName: String): Boolean {
        val normalized = normalizeBookName(bookName)
        if (normalized == ALL_BOOK || normalized == COLLAPSED_BOOK_GROUP) return false
        return getCollapsedBookAccounts(context).contains(normalized)
    }

    fun setBookCollapsed(context: Context, bookName: String, collapsed: Boolean) {
        val normalized = normalizeBookName(bookName)
        if (normalized.isBlank() || normalized == ALL_BOOK || normalized == COLLAPSED_BOOK_GROUP) return
        if (normalized == getDefaultBook(context)) return
        val current = readCollapsedAccounts(context).map { normalizeBookName(it) }.toMutableList()
        current.removeAll { it == normalized || it == ALL_BOOK || it == COLLAPSED_BOOK_GROUP }
        if (collapsed) current.add(normalized)
        saveCollapsedAccounts(context, current)

        val selected = getSelectedBook(context)
        if (collapsed && selected == normalized) {
            setSelectedBook(context, getDefaultBook(context))
        }
    }

    fun getDisplayBookAccounts(
        context: Context,
        books: List<String>,
        includeAllBook: Boolean,
        collapsedGroupExpanded: Boolean,
        defaultBookName: String = getDefaultBook(context, books)
    ): List<String> {
        val defaultBook = normalizeBookName(defaultBookName)
        val normalizedBooks = books
            .map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP }
            .distinct()
        val collapsed = getCollapsedBookAccounts(context, normalizedBooks).toSet()
        val result = linkedSetOf<String>()
        result.add(defaultBook)
        normalizedBooks
            .filter { it != defaultBook && it !in collapsed }
            .forEach { result.add(it) }
        if (collapsed.isNotEmpty()) {
            result.add(COLLAPSED_BOOK_GROUP)
            if (collapsedGroupExpanded) {
                normalizedBooks
                    .filter { it in collapsed }
                    .forEach { result.add(it) }
            }
        }
        if (includeAllBook && result.any { it != defaultBook && it != COLLAPSED_BOOK_GROUP }) {
            result.add(ALL_BOOK)
        }
        return result.toList()
    }

    fun getBookAccounts(context: Context, databaseBooks: List<String> = emptyList()): List<String> {
        val merged = linkedSetOf<String>()
        readSavedAccounts(context).forEach { merged.add(normalizeBookName(it)) }
        databaseBooks.forEach { merged.add(normalizeBookName(it)) }
        val names = merged.filter { it.isNotBlank() && it != ALL_BOOK }
        val defaultBook = getDefaultBook(context, names)
        val accounts = linkedSetOf<String>().apply {
            add(defaultBook)
            names.forEach { add(it) }
        }.toList()
        saveAccounts(context, accounts)

        val selected = normalizeBookName(prefs(context).getString(KEY_SELECTED_BOOK, null))
        if (selected != ALL_BOOK && !accounts.contains(selected)) {
            setSelectedBook(context, accounts.firstOrNull() ?: ALL_BOOK)
        }
        return accounts
    }

    fun addBookAccount(context: Context, bookName: String): Boolean {
        val target = normalizeBookName(bookName)
        if (target.isBlank()) return false
        val current = getBookAccounts(context).toMutableList()
        if (current.contains(target)) return false
        current.add(target)
        saveAccounts(context, current)
        setSelectedBook(context, target)
        return true
    }

    fun renameBookAccount(context: Context, oldName: String, newName: String): Boolean {
        val oldNorm = normalizeBookName(oldName)
        val newNorm = normalizeBookName(newName)
        if (newNorm.isBlank()) return false
        if (oldNorm == newNorm) return true

        val current = getBookAccounts(context).toMutableList()
        if (current.contains(newNorm)) return false
        val index = current.indexOf(oldNorm)
        if (index < 0) return false
        val wasCollapsed = isBookCollapsed(context, oldNorm)
        current[index] = newNorm
        saveAccounts(context, current)
        if (wasCollapsed) {
            setBookCollapsed(context, oldNorm, collapsed = false)
            setBookCollapsed(context, newNorm, collapsed = true)
        }
        if (getDefaultBook(context, current) == oldNorm) {
            setDefaultBook(context, newNorm)
        }
        if (getSelectedBook(context, current) == oldNorm) {
            setSelectedBook(context, newNorm)
        }
        return true
    }

    fun removeBookAccount(context: Context, bookName: String, fallbackBook: String? = null): Boolean {
        val target = normalizeBookName(bookName)
        val fallback = fallbackBook?.let { normalizeBookName(it) }
        val current = getBookAccounts(context).toMutableList()
        if (!current.remove(target)) return false
        saveAccounts(context, current)
        setBookCollapsed(context, target, collapsed = false)
        val prefs = prefs(context)
        val targetKeys = buildBookKeyCandidates(target)
        val targetBannerPaths = targetKeys
            .mapNotNull { key -> prefs.getString(KEY_BOOK_BANNER_PREFIX + key, null) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val retainedBannerPaths = current
            .flatMap { buildBookKeyCandidates(it) }
            .mapNotNull { key -> prefs.getString(KEY_BOOK_BANNER_PREFIX + key, null) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val edit = prefs.edit()
        targetKeys.forEach { key ->
            edit.remove(KEY_BOOK_BANNER_PREFIX + key)
            edit.remove(KEY_BOOK_COLOR_PREFIX + key)
        }
        edit.apply()

        cleanupBannerFiles(context, targetKeys, targetBannerPaths - retainedBannerPaths)
        if (getDefaultBook(context, current) == target) {
            val nextDefault = when {
                fallback != null && current.contains(fallback) -> fallback
                current.isNotEmpty() -> current.first()
                else -> DEFAULT_BOOK
            }
            setDefaultBook(context, nextDefault)
        }
        if (getSelectedBook(context, current) == target) {
            val nextSelected = when {
                fallback != null && current.contains(fallback) -> fallback
                current.isNotEmpty() -> current.first()
                else -> ALL_BOOK
            }
            setSelectedBook(context, nextSelected)
        }
        return true
    }

    private fun buildBookKeyCandidates(bookName: String): List<String> {
        val keys = linkedSetOf<String>()
        fun add(value: String?) {
            val raw = value ?: return
            keys.add(raw)
            keys.add(raw.trim())
            keys.add(normalizeBookName(raw))
        }
        add(bookName)
        rawAliases(bookName).forEach(::add)
        return keys.filter { it != ALL_BOOK }
    }

    private fun cleanupBannerFiles(context: Context, targetKeys: List<String>, removablePaths: Set<String>) {
        removablePaths.forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && file.isFile) file.delete()
            }
        }
        val bannerDir = File(context.filesDir, "banners")
        if (!bannerDir.isDirectory) return
        targetKeys.forEach { key ->
            val hashedFile = File(bannerDir, "banner_${key.hashCode()}.jpg")
            if (hashedFile.exists() && hashedFile.isFile) {
                runCatching { hashedFile.delete() }
            }
        }
    }

    private fun readSavedAccounts(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_BOOK_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readCollapsedAccounts(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_COLLAPSED_BOOK_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCollapsedAccounts(context: Context, names: List<String>) {
        val normalized = linkedSetOf<String>()
        names.map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP }
            .forEach { normalized.add(it) }

        val arr = JSONArray()
        normalized.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_COLLAPSED_BOOK_ACCOUNTS, arr.toString()).apply()
    }

    private fun saveAccounts(context: Context, names: List<String>) {
        val normalized = linkedSetOf<String>()
        names.map { normalizeBookName(it) }
            .filter { it.isNotBlank() }
            .forEach { normalized.add(it) }

        val arr = JSONArray()
        normalized.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_BOOK_ACCOUNTS, arr.toString()).apply()
    }

    /**
     * 将账本列表序列化为 JSON 数组字符串，供 Prefs.serializeSettings() 备份使用。
     */
    fun serializeBookAccounts(context: Context): String {
        val accounts = getBookAccounts(context)
        val arr = JSONArray()
        accounts.forEach { arr.put(it) }
        return arr.toString()
    }

    fun serializeCollapsedBookAccounts(context: Context): String {
        val accounts = getCollapsedBookAccounts(context)
        val arr = JSONArray()
        accounts.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * 按拖拽后的新顺序重新保存账本列表。
     * [newOrder] 来自 BookOverviewAdapter.onDragEnd()，已包含所有账本名。
     */
    fun reorderBookAccounts(context: Context, newOrder: List<String>) {
        // ALL_BOOK 不参与持久化排序，始终在运行时动态置顶
        val defaultBook = getDefaultBook(context)
        val normalized = newOrder.map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP && it != defaultBook }
        val ordered = linkedSetOf<String>()
        ordered.add(defaultBook)
        normalized.forEach { ordered.add(it) }
        getBookAccounts(context)
            .map { normalizeBookName(it) }
            .filter { it.isNotBlank() && it != ALL_BOOK && it != COLLAPSED_BOOK_GROUP && it != defaultBook }
            .forEach { ordered.add(it) }
        saveAccounts(context, ordered.toList())
    }
}

