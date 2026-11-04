package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category

/**
 * 把 AutoAccounting category.js 的分类名折成敲敲记账本地分类。
 *
 * 两边的分类表不完全一致（例如 AA 的“购物”在本地更接近“日用品”），
 * 只做明确映射；找不到就返回 null，让用户自己选，不把陌生分类写进统计。
 */
object CategoryNameResolver {

    /** 键是 AutoAccounting 分类名，值是本地分类名候选（按顺序）。 */
    private val aliases = mapOf(
        "购物" to listOf("日用品", "其他"),
        "穿戴" to listOf("服饰鞋包", "衣服"),
        "话费网费" to listOf("网费话费", "网费"),
        "水电煤" to listOf("水电煤", "水电"),
        "餐饮" to listOf("三餐", "餐饮"),
        "零食" to listOf("零食", "吃的"),
        "饮料" to listOf("饮料", "喝的"),
        "旅行" to listOf("旅行"),
        "打车" to listOf("打车"),
        "交通" to listOf("交通"),
        "医疗" to listOf("医疗", "看病"),
        "娱乐" to listOf("娱乐"),
        "学习" to listOf("学习"),
        "住房" to listOf("住房", "房租"),
        "保险" to listOf("保险"),
        "退款" to listOf("退款"),
        "工资" to listOf("工资"),
        "其他" to listOf("其他"),
    )

    /** 插件/规则里用的“父 - 子”路径分隔符（兼容 `父-子`、`父/子`、`父>子`）。 */
    private val pathSeparator = Regex("\\s*(?:-|/|>|›)\\s*")

    fun resolve(categories: List<Category>, rawName: String?, type: Int): Category? {
        val raw = rawName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidates = categories.filter { it.type == type }
        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { return it }

        // “吃的 - 买菜”：先按父子精确匹配；子分类同名时靠父分类区分。
        val parts = raw.split(pathSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val parentName = parts[parts.size - 2]
            val leafName = parts.last()
            val byId = candidates.associateBy { it.id }
            candidates.firstOrNull { cat ->
                cat.name.equals(leafName, ignoreCase = true) &&
                    cat.parentId?.let { byId[it] }?.name.equals(parentName, ignoreCase = true)
            }?.let { return it }
            candidates.firstOrNull { it.name.equals(leafName, ignoreCase = true) }?.let { return it }
            return resolve(categories, leafName, type)
        }

        aliases[raw]?.forEach { target ->
            candidates.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** 返回插件侧通用的显示路径：有父分类时为“父 - 子”，否则为本名。 */
    fun displayPath(categories: List<Category>, category: Category): String {
        val parent = category.parentId?.let { pid -> categories.firstOrNull { it.id == pid } }
        return if (parent != null) "${parent.name} - ${category.name}" else category.name
    }
}
