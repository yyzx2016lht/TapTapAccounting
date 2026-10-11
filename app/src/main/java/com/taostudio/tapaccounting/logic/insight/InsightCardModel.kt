package com.taostudio.tapaccounting.logic.insight

enum class InsightSeverity { WARN, POSITIVE, INFO }

enum class InsightType {
    MONTH_TOTAL_DELTA,     // 本月总支出 vs 上月同期
    MONTH_PROJECTION,      // 无上月数据时的月底预估
    MONTH_CATEGORY_DELTA,  // 分类支出 vs 上月同期
    LARGE_EXPENSE,         // 单笔大额
    WEEKEND_SPEND,         // 周末日均 vs 工作日日均
    CATEGORY_CONCENTRATION // 分类支出集中
}

sealed class InsightAction {
    data class OpenCategory(val categoryName: String) : InsightAction()
    data class OpenBill(val billId: Long) : InsightAction()
}

data class InsightCardModel(
    val id: String,
    val type: InsightType,
    val title: String,
    val body: String,
    val severity: InsightSeverity,
    /** 右侧强调值，如 "+23%"、"¥800"。 */
    val highlight: String? = null,
    val action: InsightAction? = null,
    /** 排序用的影响金额。 */
    val impact: Double = 0.0
)

data class InsightReport(
    val cards: List<InsightCardModel>,
    /** 区块副标题，说明对比口径，如 "对比上月 1–10 日"。 */
    val comparisonLabel: String
) {
    companion object {
        val EMPTY = InsightReport(emptyList(), "")
    }
}
