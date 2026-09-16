package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一的小组件刷新入口。
 *
 * 记账/预算写操作入口很多（悬浮窗、聊天、编辑页、共享同步……），不能只依赖
 * [com.taostudio.tapaccounting.MainActivity.onPause]。策略：
 * - [refreshAllDebounced]：账单写成功后调用，短防抖合并连续多笔，覆盖不离开桌面也能立刻看到更新；
 * - [refreshAll]：MainActivity 退出时立即刷一次（无防抖，保证回桌面即可见）；
 * - updatePeriodMillis（见 res/xml 下的 provider info）作为跨天/跨月兜底轮询。
 */
object ExpenseWidgetUpdater {

    private const val REFRESH_DEBOUNCE_MS = 800L

    private val providerClasses = listOf(
        WidgetSize.COMPACT to CompactExpenseWidgetProvider::class.java,
        WidgetSize.STANDARD to StandardExpenseWidgetProvider::class.java,
        WidgetSize.TODAY_BUDGET to TodayBudgetWidgetProvider::class.java,
        WidgetSize.DETAILED to DetailedExpenseWidgetProvider::class.java
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debouncedJob: Job? = null

    /** 账单/预算写成功后调用；合并短时间内的多次写入，只渲染最后一次。 */
    fun refreshAllDebounced(context: Context) {
        val appContext = context.applicationContext
        debouncedJob?.cancel()
        debouncedJob = scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            refreshAllInternal(appContext)
        }
    }

    /** 遍历四种样式下已放置在桌面的所有小组件实例，逐一重新渲染。 */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            refreshAllInternal(appContext)
        }
    }

    private suspend fun refreshAllInternal(appContext: Context) {
        val manager = AppWidgetManager.getInstance(appContext)
        providerClasses.forEach { (size, clazz) ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, clazz))
            ids.forEach { appWidgetId -> renderOne(appContext, manager, appWidgetId, size) }
        }
    }

    /** 只刷新指定的一个 widgetId（配置页保存后调用），会自动判断它属于哪种尺寸。 */
    fun refreshOne(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val manager = AppWidgetManager.getInstance(appContext)
            val size = sizeOf(appContext, manager, appWidgetId) ?: return@launch
            renderOne(appContext, manager, appWidgetId, size)
        }
    }

    private suspend fun renderOne(context: Context, manager: AppWidgetManager, appWidgetId: Int, size: WidgetSize) {
        val config = WidgetConfigStore.load(context, appWidgetId) ?: WidgetConfig.default(context)
        val snapshot = ExpenseWidgetRenderer.buildSnapshot(context, config, size)
        ExpenseWidgetRenderer.render(context, manager, appWidgetId, size, snapshot)
    }

    fun sizeOf(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetSize? {
        val provider = manager.getAppWidgetInfo(appWidgetId)?.provider?.className ?: return null
        return providerClasses.firstOrNull { (_, clazz) -> clazz.name == provider }?.first
    }

    /** App 内"桌面小组件"设置页需要枚举当前所有已放置的 widgetId。 */
    fun allPlacedWidgetIds(context: Context): List<Pair<Int, WidgetSize>> {
        val manager = AppWidgetManager.getInstance(context.applicationContext)
        return providerClasses.flatMap { (size, clazz) ->
            manager.getAppWidgetIds(ComponentName(context.applicationContext, clazz)).map { it to size }
        }
    }
}
