package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 四种固定样式的小组件共享同一套刷新逻辑，只是各自的 [size] 和布局不同。
 * 系统在添加/删除/调整大小时都会走到这里；渲染涉及数据库 IO，用 goAsync()
 * 延长广播处理时限，避免 ANR。
 */
abstract class BaseExpenseWidgetProvider(private val size: WidgetSize) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId -> refreshWidget(context, appWidgetManager, appWidgetId) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        refreshWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetConfigStore.deleteAll(context, appWidgetIds)
    }

    private fun refreshWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = WidgetConfigStore.load(context, appWidgetId) ?: WidgetConfig.default(context)
                val snapshot = ExpenseWidgetRenderer.buildSnapshot(context, config, size)
                ExpenseWidgetRenderer.render(context, appWidgetManager, appWidgetId, size, snapshot)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class CompactExpenseWidgetProvider : BaseExpenseWidgetProvider(WidgetSize.COMPACT)
class StandardExpenseWidgetProvider : BaseExpenseWidgetProvider(WidgetSize.STANDARD)
class TodayBudgetWidgetProvider : BaseExpenseWidgetProvider(WidgetSize.TODAY_BUDGET)
class DetailedExpenseWidgetProvider : BaseExpenseWidgetProvider(WidgetSize.DETAILED)
