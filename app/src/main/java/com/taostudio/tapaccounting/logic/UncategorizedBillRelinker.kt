package com.taostudio.tapaccounting.logic

import androidx.room.withTransaction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category

/**
 * 把「只有分类名、没有 categoryId」的账单重新挂回分类。
 *
 * ## 为什么需要它
 *
 * 分类预算完全按 `categoryId` 聚合——`sumBudgetExpenseByCategories` 带
 * `AND categoryId IN (:categoryIds)`，`sumBudgetExpenseByAllCategories` 带
 * `AND categoryId IS NOT NULL`。所以只要账单丢了 `categoryId`，它对该分类的预算就是**隐形**的：
 * 已用金额恒为 0，不报错，统计页也照常显示（统计页按 `categoryName` 聚合）。
 * 这是最难被察觉的一类数据问题，因此既要在恢复后补一次，也要在版本升级时补一次。
 *
 * ## 匹配规则（刻意保守）
 *
 * - 只认「叶子名 + 收支类型」在**全表唯一**的分类；同名歧义（例如支出、收入下各有一个「衣服」）
 *   一律跳过，宁可漏填也不填错。
 * - 分类已被删除（找不到对应叶子名）时跳过，绝不猜测。
 * - 退款账单的 `type` 是 `TYPE_INCOME`，但它的 `categoryId` 继承自原**支出**分类
 *   （见 `BillMutationService.saveRefundBill`），因此退款名要按支出类型查。
 *   **同时**还要剥掉名字里的「退款：」/「退款·」前缀再取叶子名——`normalizeForStorage`
 *   会刻意保留这个前缀（它是展示的一部分），不剥掉的话退款名永远匹配不到分类。
 *
 * 分类名先经 [CategoryNameNormalizer.normalizeForStorage]，所以历史遗留的 `>`、`/::/`、`·`
 * 等写法同样能匹配上。
 */
object UncategorizedBillRelinker {

    private const val REFUND_PREFIX = "\u9000\u6b3e\uff1a"
    private const val REFUND_PREFIX_ALT = "\u9000\u6b3e\u00b7"

    /** 日志里每类跳过的名字最多记这么多，避免刷屏。 */
    private const val MAX_REPORTED_NAMES = 15

    /**
     * 回填结果。
     *
     * 之所以把「跳过明细」也带出来：这些分类名对应的账单**仍然对分类预算隐形**，
     * 是唯一需要人工确认的部分（同名歧义、分类已删除）。没有这份明细，
     * 线上就只能靠拉数据库才能定位，所以它必须可观测。
     */
    data class RelinkReport(
        val relinkedBills: Int,
        val scannedNames: Int,
        /** 「同名同类型」不唯一而跳过的叶子名（去重、已截断）。 */
        val ambiguousLeaves: List<String>,
        /** 找不到对应分类（分类已删除）而跳过的叶子名（去重、已截断）。 */
        val unknownLeaves: List<String>
    ) {
        val isNoop: Boolean get() = relinkedBills == 0 && ambiguousLeaves.isEmpty() && unknownLeaves.isEmpty()
    }

    /**
     * 回填本机所有「有名字、没 categoryId」的账单。
     *
     * 幂等：只处理 `categoryId IS NULL` 的行，重复调用不会产生额外改动。
     */
    suspend fun relink(db: AppDatabase): RelinkReport {
        val categories = db.categoryDao().getAllCategoriesList()
        if (categories.isEmpty()) return RelinkReport(0, 0, emptyList(), emptyList())

        val snapshots = db.billDao().getUncategorizedNameSnapshots()
        if (snapshots.isEmpty()) return RelinkReport(0, 0, emptyList(), emptyList())

        val uniqueByLeafAndType = uniqueLeafTypeIndex(categories)
        // 同名同类型出现多次时，该组合不会进入索引；记下来用于诊断。
        val duplicatedLeaves = categories
            .groupBy { it.name to it.type }
            .filterValues { it.size > 1 }
            .keys
            .mapTo(linkedSetOf()) { (leaf, _) -> leaf }

        val ambiguous = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()

        val relinked = db.withTransaction {
            var updated = 0
            snapshots.forEach { snapshot ->
                val leaf = leafForLookup(snapshot.categoryName)
                if (leaf.isBlank()) return@forEach

                val categoryId = resolveCategoryId(
                    rawName = snapshot.categoryName,
                    billType = snapshot.type,
                    uniqueByLeafAndType = uniqueByLeafAndType
                )
                if (categoryId == null) {
                    if (leaf in duplicatedLeaves) ambiguous += leaf else unknown += leaf
                    return@forEach
                }
                updated += db.billDao().relinkCategoryId(snapshot.categoryName, snapshot.type, categoryId)
            }
            updated
        }

        return RelinkReport(
            relinkedBills = relinked,
            scannedNames = snapshots.size,
            ambiguousLeaves = ambiguous.take(MAX_REPORTED_NAMES).toList(),
            unknownLeaves = unknown.take(MAX_REPORTED_NAMES).toList()
        )
    }

    /**
     * 「叶子名 + 收支类型」→ 分类 id，**只保留全表唯一的组合**。
     *
     * 抽成纯函数是为了让「同名歧义必须跳过」这条规则能被单测覆盖。
     */
    internal fun uniqueLeafTypeIndex(categories: List<Category>): Map<Pair<String, Int>, Long> =
        categories
            .groupBy { it.name to it.type }
            .filterValues { it.size == 1 }
            .mapValues { (_, sameNameCategories) -> sameNameCategories.single().id }

    /**
     * 判断一条「有分类名、没 categoryId」的账单该挂回哪个分类；无法唯一确定时返回 `null`。
     */
    internal fun resolveCategoryId(
        rawName: String,
        billType: Int,
        uniqueByLeafAndType: Map<Pair<String, Int>, Long>
    ): Long? {
        val leaf = leafForLookup(rawName)
        if (leaf.isBlank()) return null
        val lookupType = if (isRefundName(rawName)) Bill.TYPE_EXPENSE else billType
        return uniqueByLeafAndType[leaf to lookupType]
    }

    /**
     * 查分类时用的叶子名：先规范化，再剥掉退款前缀，最后取路径末段。
     *
     * 剥前缀这一步不能省——[CategoryNameNormalizer.normalizeForStorage] 会把「退款：」写回结果里
     * （它是展示路径的一部分），而 `categories.name` 里只有叶子名，两者对不上。
     */
    private fun leafForLookup(rawName: String): String = CategoryNameNormalizer.leafName(
        CategoryNameNormalizer.stripRefundPrefix(
            CategoryNameNormalizer.normalizeForStorage(rawName)
        )
    )

    private fun isRefundName(rawName: String): Boolean =
        rawName.startsWith(REFUND_PREFIX) || rawName.startsWith(REFUND_PREFIX_ALT)
}
