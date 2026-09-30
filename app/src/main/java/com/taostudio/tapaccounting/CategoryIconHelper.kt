package com.taostudio.tapaccounting

import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.repository.CategoryRepository

object CategoryIconHelper {

    /**
     * 从 Room 数据库中查找分类图标（suspend，需在协程 IO 上下文中调用）。
     *
     * 返回前统一过一遍 [withPlaceholderFallback]：任何走到「占位图 / 空图标」的分类，
     * 都会改用分类名首字生成的文字图标，保证界面上不再出现千篇一律的「···」。
     */
    suspend fun findCategoryIcon(ctx: Context, name: String, type: Int): String {
        val resolved = resolveCategoryIcon(ctx, name, type)
        return withPlaceholderFallback(ctx, name, resolved)
    }

    private suspend fun resolveCategoryIcon(ctx: Context, name: String, type: Int): String {
        if (type == 2 || name.contains("转账")) {
            return "android.resource://${ctx.packageName}/${R.drawable.ic_transfer}"
        }

        // 支持多种父子分隔符，取最后一段作为叶子名
        val parts = when {
            name.contains(" > ") -> name.split(" > ").map { it.trim() }
            name.contains(" - ") -> name.split(" - ").map { it.trim() }
            name.contains("/::/") -> name.split("/::/").map { it.trim() }
            name.contains("·")   -> name.split("·").map { it.trim() }
            else                 -> listOf(name.trim())
        }
        val leafName   = parts.last()
        val parentName = if (parts.size >= 2) parts[parts.size - 2] else ""

        val dbType = if (type == 1) 1 else 0
        val repo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val resolvedCategory = repo.findCategoryByDisplayName(dbType, name)
        if (resolvedCategory != null) {
            // 返回分类自己的图标（含空值和三个点占位图）。
            // 外层 withPlaceholderFallback 会换成首字；这里若改返回父分类图标，
            // 子分类没有图标时会把父级的「···」原样显示出来。
            return resolvedCategory.iconId
        }

        fun searchRecursive(list: List<CategoryNode>, target: String): String? {
            for (node in list) {
                if (node.name.trim() == target && node.icon.isNotEmpty()) return node.icon
                val sub = searchRecursive(node.subs, target)
                if (!sub.isNullOrEmpty()) return sub
            }
            return null
        }

        // 1. 先按叶子名在 DB 对应类型树中查找
        var icon = searchRecursive(repo.getCategoryTree(dbType), leafName)
        if (!icon.isNullOrEmpty()) return icon

        // 2. 再在另一种类型树中找（容错）
        icon = searchRecursive(repo.getCategoryTree(if (dbType == 0) 1 else 0), leafName)
        if (!icon.isNullOrEmpty()) return icon

        // 3. 如果有父类名，用父类名查一遍（子分类没有自己图标时，显示父类图标）
        if (parentName.isNotEmpty()) {
            icon = searchRecursive(repo.getCategoryTree(dbType), parentName)
            if (!icon.isNullOrEmpty()) return icon
            icon = searchRecursive(repo.getCategoryTree(if (dbType == 0) 1 else 0), parentName)
            if (!icon.isNullOrEmpty()) return icon
        }

        val allBuiltin = Prefs.loadDefaultFromRaw(ctx, Prefs.TYPE_INCOME) + Prefs.loadDefaultFromRaw(ctx, Prefs.TYPE_EXPENSE)

        // 4. 在内置 JSON 中精确匹配叶子名
        icon = searchRecursive(allBuiltin, leafName)
        if (!icon.isNullOrEmpty()) return icon

        // 5. 在内置 JSON 中精确匹配父类名
        if (parentName.isNotEmpty()) {
            icon = searchRecursive(allBuiltin, parentName)
            if (!icon.isNullOrEmpty()) return icon
        }

        // 6. 模糊匹配
        val flat = mutableListOf<CategoryNode>()
        fun flatten(list: List<CategoryNode>) { list.forEach { flat.add(it); flatten(it.subs) } }
        flatten(allBuiltin)

        val matchTarget = if (leafName.isNotEmpty()) leafName else name.trim()
        val match = flat.filter { matchTarget.contains(it.name) || it.name.contains(matchTarget) }
            .sortedByDescending { it.name.length }.firstOrNull()
        if (match != null && match.icon.isNotEmpty()) return match.icon

        if (leafName.contains("早") || leafName.contains("午") || leafName.contains("晚") || leafName.contains("饭")) {
            flat.find { it.name.contains("三餐") || it.name.contains("餐饮") }?.let { return it.icon }
        }
        return ""
    }

    /** 兜底：结果是占位图或空值时，用分类名的首字生成文字图标（规则见 [CategoryIcons]）。 */
    private fun withPlaceholderFallback(ctx: Context, name: String, resolved: String): String =
        CategoryIcons.fallback(resolved, name)
}

