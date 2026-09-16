package com.taostudio.tapaccounting.widget

import android.content.Context
import org.json.JSONObject

/**
 * 每个桌面小组件实例（appWidgetId）的配置持久化。
 * 与 [com.taostudio.tapaccounting.Prefs] 分开存放，避免把跟具体 widgetId 相关的
 * 动态 key 混进通用设置的 SharedPreferences。
 */
object WidgetConfigStore {
    private const val PREF_NAME = "expense_widget_configs"
    private const val KEY_PREFIX = "widget_config_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        val json = JSONObject().apply {
            put("bookName", config.bookName)
            put("period", config.period.name)
        }
        prefs(context).edit().putString(KEY_PREFIX + appWidgetId, json.toString()).apply()
    }

    fun load(context: Context, appWidgetId: Int): WidgetConfig? {
        val raw = prefs(context).getString(KEY_PREFIX + appWidgetId, null) ?: return null
        val stored = runCatching {
            val json = JSONObject(raw)
            WidgetConfig(
                bookName = json.getString("bookName"),
                period = runCatching { WidgetPeriod.valueOf(json.optString("period")) }
                    .getOrDefault(WidgetPeriod.THIS_MONTH)
            )
        }.getOrNull() ?: return null

        val availableBooks = activeBooks(context)
        val resolvedBook = stored.bookName.takeIf { it in availableBooks }
            ?: com.taostudio.tapaccounting.BookAccountManager.getSelectedBook(context)
                .takeIf { it in availableBooks }
            ?: availableBooks.first()
        return if (resolvedBook == stored.bookName) {
            stored
        } else {
            stored.copy(bookName = resolvedBook).also { save(context, appWidgetId, it) }
        }
    }

    fun activeBooks(context: Context): List<String> {
        val manager = com.taostudio.tapaccounting.BookAccountManager
        val allBooks = manager.getBookAccounts(context)
        val collapsed = manager.getCollapsedBookAccounts(context, allBooks).toSet()
        return (allBooks.filterNot { it in collapsed } + manager.ALL_BOOK).distinct()
    }

    fun delete(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(KEY_PREFIX + appWidgetId).apply()
    }

    fun deleteAll(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove(KEY_PREFIX + it) }
        editor.apply()
    }
}
