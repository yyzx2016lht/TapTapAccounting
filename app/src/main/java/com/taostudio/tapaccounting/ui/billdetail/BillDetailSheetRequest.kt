package com.taostudio.tapaccounting.ui.billdetail

import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 打开「底部账单详情弹层」的入参。
 *
 * 只承载「看哪笔账单、怎么看」的数据；按钮回调统一放在 [BillDetailSheetActions]。
 * 首页 / 日历 / 资产 / 统计 / 聊天 / 账单搜索 都走这一份入参，避免各写一套。
 */
data class BillDetailSheetRequest(
    val bill: Bill,
    /** 资产页：按「本资产」分摊展示金额；其他入口为 null。 */
    val assetDisplay: AssetDisplay? = null,
    /** 只读（统计页）：隐藏「不计入统计 / 退款 / 删除」，只保留编辑。 */
    val readOnly: Boolean = false,
    /** 账单列表：删除/撤销时用它定位那一行播行动画；不传就没有动画。 */
    val listRecyclerView: RecyclerView? = null,
) {
    /** 资产页按哪个资产的分摊金额展示；其他入口为 null。 */
    val displayAssetId: Long? get() = assetDisplay?.assetId
}

/** 资产页的金额展示：按「本资产」分摊后的金额与币种。 */
data class AssetDisplay(
    val assetId: Long,
    val currency: String,
    val amount: Double,
    /** 金额下面的补充说明（退款 / 跨币种公式），没有就是 null。 */
    val formula: String? = null,
)
