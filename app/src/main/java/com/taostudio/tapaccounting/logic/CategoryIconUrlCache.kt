package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.CategoryIcons

/**
 * 「分类名 + 类型 → 图标 URL」的进程级缓存，账单页 / 资产详情 / 资产统计共用。
 *
 * 解析一次图标要查分类树（Room），甚至回退到内置分类 JSON；冷缓存时列表行会先亮
 * 占位图、再切真实图标，看起来就是「闪一下」。所以：
 *  - 列表提交时先预热（各页在 submitList 里解析一遍并写入这里）；
 *  - 行绑定时同步命中，命中且与当前已绑定值相同就完全不碰 ImageView（重绑不闪）。
 *
 * 分类图标变更由 [CategoryIcons.revision] 通知：版本变了整表作废、重新解析，
 * 保证「改完图标各页实时刷新」这条既有规则不变。
 */
object CategoryIconUrlCache {

    private const val MAX_ENTRIES = 400

    private val cache = mutableMapOf<String, String>()
    private var revision = CategoryIcons.revision

    fun key(categoryName: String, type: Int): String = "$type|$categoryName"

    fun get(key: String): String? = synchronized(cache) {
        invalidateIfRevisionChanged()
        cache[key]
    }

    fun put(key: String, url: String) = synchronized(cache) {
        invalidateIfRevisionChanged()
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[key] = url
    }

    /** 必须持有 [cache] 锁调用。 */
    private fun invalidateIfRevisionChanged() {
        if (revision != CategoryIcons.revision) {
            revision = CategoryIcons.revision
            cache.clear()
        }
    }
}
