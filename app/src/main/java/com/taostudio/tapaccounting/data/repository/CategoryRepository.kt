package com.taostudio.tapaccounting.data.repository

import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.CategoryNode
import com.taostudio.tapaccounting.CategoryIcons
import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.dao.CategoryDao
import com.taostudio.tapaccounting.data.local.dao.RecurringPatternDao
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.logic.BillDeleteHelper

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val billDao: BillDao? = null,
    private val recurringPatternDao: RecurringPatternDao? = null
) {

    companion object {
        /** Current hierarchical display paths keyed by stable category ID. */
        fun displayNamesById(categories: List<Category>): Map<Long, String> {
            val byId = categories.associateBy { it.id }
            val resolved = mutableMapOf<Long, String>()

            fun resolve(category: Category, visiting: Set<Long>): String {
                resolved[category.id]?.let { return it }
                if (category.id in visiting) return category.name
                val parent = category.parentId?.let(byId::get)
                val name = if (parent == null) {
                    category.name
                } else {
                    "${resolve(parent, visiting + category.id)} - ${category.name}"
                }
                resolved[category.id] = name
                return name
            }

            categories.forEach { resolve(it, emptySet()) }
            return resolved
        }
    }

    val expenseCategories: Flow<List<Category>> = categoryDao.getCategoriesByType(0)
    val incomeCategories: Flow<List<Category>> = categoryDao.getCategoriesByType(1)

    /** 同步读取指定类型的分类列表（扁平，需在协程 IO 上下文中调用）。*/
    suspend fun getCategoriesListByType(type: Int): List<Category> =
        categoryDao.getCategoriesListByType(type)

    /** 把扁平 List<Category> 重建为父子嵌套的 List<CategoryNode>（兼容旧 UI）。*/
        fun buildCategoryTree(flatList: List<Category>): List<CategoryNode> {
        val childrenByParent = flatList.filter { it.parentId != null }.groupBy { it.parentId }

        fun buildNode(cat: Category, parentIcon: String): CategoryNode {
            // 用分类自己的 iconId。空值或三个点占位图在 fallback 里换成首字，
            // 不能先塞进父分类图标，否则子分类会继承父级的「···」或父级首字。
            val icon = CategoryIcons.fallback(cat.iconId, cat.name, parentIcon)
            val node = CategoryNode(cat.name, icon)
            node.id = cat.id
            childrenByParent[cat.id]?.forEach { child ->
                node.subs.add(buildNode(child, cat.iconId))
            }
            return node
        }

        return flatList.filter { it.parentId == null }.map { buildNode(it, "") }
    }

    /** Get category tree by type (IO context). */suspend fun getCategoryTree(type: Int): List<CategoryNode> =
        buildCategoryTree(categoryDao.getCategoriesListByType(type))

    suspend fun findCategoryByDisplayName(type: Int, displayName: String): Category? {
        val parts = displayName
            .replace(" > ", "/::/")
            .replace(" - ", "/::/")
            .replace("·", "/::/")
            .split("/::/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val categories = categoryDao.getCategoriesListByType(type)
        val leafName = parts.last()
        val parentName = parts.getOrNull(parts.size - 2)
        val parent = if (parentName.isNullOrBlank()) {
            null
        } else {
            categories.firstOrNull { it.parentId == null && it.name == parentName }
        }

        return when {
            parent != null -> categories.firstOrNull { it.parentId == parent.id && it.name == leafName }
            else -> categories.firstOrNull { it.parentId == null && it.name == leafName }
                ?: categories.firstOrNull { it.name == leafName }
        }
    }

    suspend fun addCategory(category: Category): Long {
        val maxOrder = categoryDao.getMaxSortOrder(category.type, category.parentId) ?: 0
        val categoryWithOrder = category.copy(sortOrder = maxOrder + 10)
        return categoryDao.insertCategory(categoryWithOrder)
    }

    suspend fun getCategoryByName(name: String): Category? {
        return categoryDao.getCategoryByName(name)
    }

    suspend fun updateCategory(category: Category) {
        val old = categoryDao.getCategoryById(category.id)
        categoryDao.updateCategory(category)
        // 换了图标：刷该分类下账单的图标快照（首页监听 bills 变化后会重绑，切回来立即生效），
        // 并递增版本号让各列表页的内存图标缓存失效。
        if (old != null && old.iconId != category.iconId) {
            billDao?.syncCateIconByCategoryId(category.id, category.iconId)
            CategoryIcons.bumpRevision()
        }
        if (old == null || old.name == category.name) return
        // 无外键的历史账单：按旧叶子名就地替换（含「祖先改名」时旧叶子出现在路径开头的情形）
        billDao?.syncCategoryNameByOldName(old.name, category.name)
        // 无 categoryId 的历史周期模式：同样按旧叶子名就地替换，否则它既显示旧名、又因名字对不上而匹配不到新账单
        recurringPatternDao?.syncCategoryNameByOldName(old.name, category.name, System.currentTimeMillis())
        // 有外键的账单：连同子分类一起重算——一级分类改名会改变其所有子分类路径的前缀
        val children = categoryDao.getChildrenByParentId(category.id)
        resyncBillDisplayNames(listOf(category.id) + children.map { it.id })
    }

    /**
     * 把「按 categoryId 挂着的账单」的展示名重算成分类树里的当前路径。
     *
     * `bills.categoryName` 只是展示路径的快照（"吃的 - 三餐"），分类树才是权威；而统计页与
     * 洞察都按这份快照聚合，从不回查 categoryId。所以只要分类树形状变了——改名、提升为一级、
     * 降级为二级、拖动换父级——快照就会过期，统计页会凭空多出一个旧名字的「幽灵分类」。
     * 这里按 id 把快照刷成最新；退款前缀由 SQL 负责保留。
     *
     * `recurring_patterns.categoryName` 是同一类快照，一并刷新：周期模式列表和到期提醒都会显示它。
     */
    private suspend fun resyncBillDisplayNames(categoryIds: List<Long>) {
        if (billDao == null && recurringPatternDao == null) return
        val ids = categoryIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return
        val paths = displayNamesById(categoryDao.getAllCategoriesList())
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            val path = paths[id] ?: return@forEach
            billDao?.syncCategoryNameByCategoryId(id, path)
            recurringPatternDao?.syncCategoryNameByCategoryId(id, path, now)
        }
    }

    suspend fun saveOrderedCategories(categories: List<Category>) {
        categories.forEachIndexed { index, category ->
            categoryDao.updateCategory(category.copy(sortOrder = (index + 1) * 10))
        }
    }

    suspend fun saveOrderedCategoryTree(categories: List<Category>) {
        // 拖动排序可能改变父级归属；先记下旧 parentId，最后只重算真正换了父级的那些
        val parentBefore = categoryDao.getAllCategoriesList().associate { it.id to it.parentId }
        var rootOrder = 10
        var currentParentId: Long? = null
        val childOrders = mutableMapOf<Long, Int>()

        categories.forEach { category ->
            if (category.parentId == null) {
                categoryDao.updateCategory(category.copy(parentId = null, sortOrder = rootOrder))
                currentParentId = category.id
                childOrders[currentParentId] = 10
                rootOrder += 10
            } else {
                val resolvedParentId = currentParentId
                if (resolvedParentId == null) {
                    categoryDao.updateCategory(category.copy(parentId = null, sortOrder = rootOrder))
                    currentParentId = category.id
                    childOrders[currentParentId] = 10
                    rootOrder += 10
                } else {
                    val childOrder = childOrders[resolvedParentId] ?: 10
                    categoryDao.updateCategory(
                        category.copy(parentId = resolvedParentId, sortOrder = childOrder)
                    )
                    childOrders[resolvedParentId] = childOrder + 10
                }
            }
        }

        val reparented = categoryDao.getAllCategoriesList()
            .filter { parentBefore[it.id] != it.parentId }
            .map { it.id }
        if (reparented.isNotEmpty()) resyncBillDisplayNames(reparented)
    }

    /**
     * 删除分类（原逻辑：同时删除子分类），现在改为：
     * - 若该分类有子分类，禁止删除（调用方应先检查）
     * - 处理该分类下账单（迁移或删除）由外部决策后传入 billHandling
     */
    suspend fun deleteById(id: Long) {
        // 同时删除以该 id 为 parentId 的所有子分类
        val children = categoryDao.getAllCategoriesList().filter { it.parentId == id }
        children.forEach { categoryDao.deleteById(it.id) }
        categoryDao.deleteById(id)
        // 删除后这些分类的图标解析结果会变（退回首字/空），缓存不能沿用
        CategoryIcons.bumpRevision()
    }

    /**
     * 统计指定分类（含所有子分类）下的账单数量
     */
    suspend fun countBillsUnderCategory(categoryId: Long): Int {
        val dao = billDao ?: return 0
        val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
        val children = categoryDao.getChildrenByParentId(categoryId)

        // 统计所有相关分类（自身 + 子分类）并按账单 id 去重。
        // 这里仅查询账单 id，避免把整条账单拉进内存导致删除前卡顿。
        val allCats = listOfNotNull(self) + children
        val billIds = mutableSetOf<Long>()
        for (cat in allCats) {
            dao.getBillIdsByCategoryIdList(cat.id)
                .asSequence()
                .filter { it > 0L }
                .forEach { billIds.add(it) }
            dao.getBillIdsByCategoryNameList(cat.name)
                .asSequence()
                .filter { it > 0L }
                .forEach { billIds.add(it) }
        }
        return billIds.size
    }

    /**
     * 删除叶子分类，并将该分类下账单迁移到 targetCategoryId。
     * 若 targetCategoryId 为 null，则将该账单的 categoryId 置 null。
     */
    suspend fun deleteCategoryAndMigrateBills(categoryId: Long, targetCategoryId: Long?, db: AppDatabase? = null) {
        // P1-11: 迁移 + 删分类必须同一事务
        val block: suspend () -> Unit = {
            val dao = billDao
            val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
            val children = categoryDao.getChildrenByParentId(categoryId)
            val allCats = listOfNotNull(self) + children

            if (dao != null) {
                for (cat in allCats) {
                    if (targetCategoryId != null) {
                        dao.migrateCategoryId(cat.id, targetCategoryId)
                        dao.migrateCategoryByName(cat.name, targetCategoryId)
                    } else {
                        dao.clearCategoryId(cat.id)
                        dao.clearCategoryByName(cat.name)
                    }
                }
            }
            children.forEach { categoryDao.deleteById(it.id) }
            categoryDao.deleteById(categoryId)
        }
        if (db != null) db.withTransaction { block() } else block()
    }

    /**
     * 删除叶子分类，并连同该分类下的账单一起删除。
     */
    suspend fun deleteCategoryAndBills(categoryId: Long, db: AppDatabase? = null) {
        // P1-11: delete bills + category in one transaction
        val block: suspend () -> Unit = {
            val dao = billDao
            val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
            val children = categoryDao.getChildrenByParentId(categoryId)
            val allCats = listOfNotNull(self) + children

            if (dao != null) {
                for (cat in allCats) {
                    val billsById = dao.getBillsByCategoryIdList(cat.id)
                    val billsByName = dao.getBillsByCategoryNameList(cat.name)
                    val billsToDelete = (billsById + billsByName)
                        .distinctBy { it.id }
                        .filter { it.id > 0L }
                    if (billsToDelete.isNotEmpty()) {
                        if (db != null) {
                            BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)
                        } else {
                            dao.delete(billsToDelete)
                        }
                    }
                }
            }
            children.forEach { categoryDao.deleteById(it.id) }
            categoryDao.deleteById(categoryId)
        }
        if (db != null) db.withTransaction { block() } else block()
    }

    /**
     * Promote a secondary category to a top-level category (parentId = null).
     */
    suspend fun promoteToParent(categoryId: Long) {
        val cat = categoryDao.getAllCategoriesList().find { it.id == categoryId } ?: return
        categoryDao.updateCategory(cat.copy(parentId = null))
        // 展示路径从 "父 - 子" 变成裸名字，账单里的快照必须跟着重算，否则统计页会留着旧的父名
        resyncBillDisplayNames(listOf(categoryId))
    }

    /**
     * 将一级分类降级为指定父分类的二级分类。
     * 要求该一级分类没有子分类（调用方应先检查）。
     */
    suspend fun demoteToChild(categoryId: Long, newParentId: Long) {
        val cat = categoryDao.getAllCategoriesList().find { it.id == categoryId } ?: return
        categoryDao.updateCategory(cat.copy(parentId = newParentId))
        // 展示路径从裸名字变成 "父 - 子"，账单里的快照必须跟着重算
        resyncBillDisplayNames(listOf(categoryId))
    }

    /** 获取指定分类的子分类列表 */
    suspend fun getChildren(parentId: Long): List<Category> =
        categoryDao.getChildrenByParentId(parentId)
}


