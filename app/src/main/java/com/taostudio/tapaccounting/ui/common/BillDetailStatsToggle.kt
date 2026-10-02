package com.taostudio.tapaccounting.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.sync.SharedMutationHooks
import com.taostudio.tapaccounting.logic.BillMutationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账单详情底部弹层里「计入统计」开关的统一绑定。
 *
 * 约定（2026-10-02 需求）：
 *  - **标签表示当前状态**：计入中显示「已计入」，已排除显示「不计入」。
 *    点击后的结果由 toast 说明，避免"标签说的"和"点击做的"相反。
 *  - 恒为描边样式，与 退款/编辑 同级，不抢「删除」的视觉权重。
 *  - **先写库成功，再刷新界面**：写库失败时按钮回到原状并 toast，绝不出现
 *    "界面说改了但数据库没改"。
 *  - 不可操作的账单（退款 / 转账还款 / 余额调整排除 / 共享账本里别人创建的）
 *    直接隐藏按钮。
 *
 * 弹层和全屏详情页都必须走这里，保证落库逻辑只有一处。
 */
object BillDetailStatsToggle {

    /**
     * @param context 用于取数据库、字符串与 toast
     * @param scope 生命周期协程作用域（`lifecycleScope`）
     * @param onSaved 写库成功、界面刷新之后的回调（true = 从此不计入统计）
     */
    fun bind(
        button: TextView,
        bill: Bill,
        context: Context,
        scope: CoroutineScope,
        onSaved: ((Boolean) -> Unit)? = null
    ) {
        if (!isEligible(bill)) {
            button.visibility = View.GONE
            return
        }

        val db = AppDatabase.getDatabase(context)
        var excluded = bill.excludeFromStats

        fun refresh() {
            button.text = context.getString(
                if (excluded) R.string.exclude else R.string.include_done
            )
            button.setBackgroundResource(R.drawable.bg_dialog_button_outline)
            button.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            button.isEnabled = true
            button.alpha = 1f
            button.visibility = View.VISIBLE
        }

        refresh()

        // 共享账本里别人创建的账单不可改。异步确认所有者，避免点下去才抛异常。
        if (bill.isShared) {
            scope.launch(Dispatchers.IO) {
                val owner = SharedMutationHooks.isOwner(db, bill)
                if (!owner) {
                    withContext(Dispatchers.Main) { button.visibility = View.GONE }
                }
            }
        }

        button.setOnClickListener {
            if (!button.isEnabled) return@setOnClickListener
            button.isEnabled = false
            button.alpha = 0.5f
            val target = !excluded
            scope.launch(Dispatchers.IO) {
                try {
                    BillMutationService.setExcludeFromStats(db, bill, target)
                    withContext(Dispatchers.Main) {
                        excluded = target
                        refresh()
                        Utils.toast(
                            context,
                            context.getString(
                                if (target) R.string.toast_exclude_from_stats
                                else R.string.toast_include_from_stats
                            )
                        )
                        onSaved?.invoke(target)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    // SharedMutationHooks.requireOwner 抛出的越权异常
                    withContext(Dispatchers.Main) {
                        refresh()
                        Utils.toast(context, context.getString(R.string.toast_exclude_stats_not_owner))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        refresh()
                        Utils.toast(context, context.getString(R.string.toast_exclude_stats_update_failed))
                    }
                }
            }
        }
    }

    /** 该账单是否允许展示并修改「计入统计」开关。 */
    private fun isEligible(bill: Bill): Boolean {
        if (bill.subType == Bill.SUBTYPE_REFUND) return false
        if (bill.type != Bill.TYPE_EXPENSE && bill.type != Bill.TYPE_INCOME) return false
        if (bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED) return false
        return true
    }
}
