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

    fun resolve(categories: List<Category>, rawName: String?, type: Int): Category? {
        val raw = rawName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidates = categories.filter { it.type == type }
        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { return it }
        aliases[raw]?.forEach { target ->
            candidates.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
