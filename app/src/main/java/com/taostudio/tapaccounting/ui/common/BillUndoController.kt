package com.taostudio.tapaccounting.ui.common

import android.view.View
import com.taostudio.tapaccounting.data.local.entity.DeletedBill

/**
 * 删除账单后的瞬时撤销提示（已停用）。
 *
 * A 方案：删除只进回收站，不再弹底部 Snackbar，也不在超时后补 Toast；
 * 需要恢复时统一走「我的 → 回收站」。这里保留空 API，兼容各个历史删除入口，
 * 避免一次性改动详情页、搜索、日历、资产页等多处调用点。
 */
object BillUndoController {

    fun request(message: String, rows: List<DeletedBill>) = Unit

    fun consumePending(anchor: View, onRestored: () -> Unit) = Unit

    fun showNow(
        anchor: View?,
        message: String,
        rows: List<DeletedBill>,
        onRestored: () -> Unit,
    ) = Unit
}
