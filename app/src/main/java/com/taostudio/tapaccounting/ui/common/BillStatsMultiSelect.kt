package com.taostudio.tapaccounting.ui.common

import android.content.Context
import android.widget.TextView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillMutationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多选栏「计入 / 不计入」的共用逻辑。
 *
 * 5 个多选栏（首页 / 搜索 / 日历 / 资产详情 / 资产统计）共用这里，
 * 走 [BillMutationService.setExcludeFromStatsBatch]：先逐条校验共享账本
 * 所有者权限，再在一个事务内写库并入队同步，绝不是直接 UPDATE DAO。
 *
 * 单个按钮同时承担两个方向：
 *  - 选中的账单**全部已不计入** → 按钮显示「计入」，点击批量恢复计入；
 *  - 其余情况（全部计入 / 混合）→ 按钮显示「不计入」，点击批量排除。
 *    混合时**优先不计入**。
 */
object BillStatsMultiSelect {

    enum class Action { INCLUDE, EXCLUDE }

    /** 混合选择时优先「不计入」：只有全部已排除时才反过来批量计入。 */
    fun resolveAction(bills: List<Bill>): Action =
        if (bills.isNotEmpty() && bills.all { it.excludeFromStats }) Action.INCLUDE else Action.EXCLUDE

    /** 按当前选择刷新按钮文案与可用状态（无选择时禁用）。 */
    fun refreshButton(button: TextView, bills: List<Bill>) {
        val hasSelection = bills.isNotEmpty()
        button.isEnabled = hasSelection
        button.alpha = if (hasSelection) 1f else 0.45f
        button.text = button.context.getString(
            if (hasSelection && resolveAction(bills) == Action.INCLUDE) R.string.include
            else R.string.exclude
        )
    }

    /**
     * 对当前选中的账单执行一次批量「计入 / 不计入」。
     *
     * @param onDone 成功后的回调，参数是刚刚设置的值（true = 不计入）。
     *   **不会退出多选**：调用方一般只刷新列表并清空勾选，保留多选模式，
     *   让用户可以连续处理多批。
     */
    fun apply(
        context: Context,
        scope: CoroutineScope,
        bills: List<Bill>,
        onDone: (exclude: Boolean) -> Unit
    ) {
        if (bills.isEmpty()) return
        val exclude = resolveAction(bills) == Action.EXCLUDE
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val error = try {
                BillMutationService.setExcludeFromStatsBatch(db, bills, exclude)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            withContext(Dispatchers.Main) {
                if (error != null) {
                    Utils.toast(context, context.getString(R.string.toast_exclude_stats_not_owner))
                } else {
                    Utils.toast(
                        context,
                        context.getString(
                            if (exclude) R.string.toast_exclude_from_stats_fmt else R.string.toast_include_from_stats_fmt,
                            bills.size
                        )
                    )
                    onDone(exclude)
                }
            }
        }
    }
}
