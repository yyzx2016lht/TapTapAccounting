package com.taostudio.tapaccounting.data.repository

import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.CategoryNode
import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.dao.CategoryDao
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.logic.BillDeleteHelper

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val billDao: BillDao? = null
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

    /** 鍚屾璇诲彇鎸囧畾绫诲瀷鐨勫垎绫诲垪琛紙鎵佸钩锛岄渶鍦ㄥ崗绋?IO 涓婁笅鏂囦腑璋冪敤锛?*/
    suspend fun getCategoriesListByType(type: Int): List<Category> =
        categoryDao.getCategoriesListByType(type)

    /** 鎶婃墎骞?List<Category> 閲嶅缓涓虹埗瀛愬祵濂楃殑 List<CategoryNode>锛堝吋瀹规棫 UI锛?*/
        fun buildCategoryTree(flatList: List<Category>): List<CategoryNode> {
        val childrenByParent = flatList.filter { it.parentId != null }.groupBy { it.parentId }

        fun buildNode(cat: Category, parentIcon: String): CategoryNode {
            val icon = if (cat.iconId.isNotEmpty()) cat.iconId else parentIcon
            val node = CategoryNode(cat.name, icon)
            node.id = cat.id
            childrenByParent[cat.id]?.forEach { child ->
                node.subs.add(buildNode(child, icon))
            }
            return node
        }

        return flatList.filter { it.parentId == null }.map { buildNode(it, it.iconId) }
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
        if (old != null && old.name != category.name && billDao != null) {
            val oldParent = if (old.parentId != null) categoryDao.getCategoryById(old.parentId) else null
            val newParent = if (category.parentId != null) categoryDao.getCategoryById(category.parentId) else null
            val oldFullPath = if (oldParent != null) "${oldParent.name} - ${old.name}" else old.name
            val newFullPath = if (newParent != null) "${newParent.name} - ${category.name}" else category.name
            billDao.syncCategoryNameByCategoryId(category.id, newFullPath)
            billDao.syncCategoryNameByOldName(old.name, category.name)
        }
    }

    suspend fun saveOrderedCategories(categories: List<Category>) {
        categories.forEachIndexed { index, category ->
            categoryDao.updateCategory(category.copy(sortOrder = (index + 1) * 10))
        }
    }

    suspend fun saveOrderedCategoryTree(categories: List<Category>) {
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
    }

    /**
     * 鍒犻櫎鍒嗙被锛堝師閫昏緫锛氬悓鏃跺垹闄ゅ瓙鍒嗙被锛夛紝鐜板湪鏀逛负锛?
     * - 鑻ヨ鍒嗙被鏈夊瓙鍒嗙被锛岀姝㈠垹闄わ紙璋冪敤鏂瑰簲鍏堟鏌ワ級
     * - 澶勭悊璇ュ垎绫讳笅璐﹀崟锛堣縼绉绘垨鍒犻櫎锛夌敱澶栭儴鍐崇瓥鍚庝紶鍏?billHandling
     */
    suspend fun deleteById(id: Long) {
        // 鍚屾椂鍒犻櫎浠ヨ id 涓?parentId 鐨勬墍鏈夊瓙鍒嗙被
        val children = categoryDao.getAllCategoriesList().filter { it.parentId == id }
        children.forEach { categoryDao.deleteById(it.id) }
        categoryDao.deleteById(id)
    }

    /**
     * 缁熻鎸囧畾鍒嗙被锛堝惈鎵€鏈夊瓙鍒嗙被锛変笅鐨勮处鍗曟暟閲?
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
     * 鍒犻櫎鍙跺瓙鍒嗙被锛屽苟灏嗚鍒嗙被涓嬭处鍗曡縼绉诲埌 targetCategoryId銆?
     * 鑻?targetCategoryId 涓?null锛屽垯灏嗚处鍗曠殑 categoryId 缃?null銆?
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
     * 鍒犻櫎鍙跺瓙鍒嗙被锛屽苟杩炲悓璇ュ垎绫讳笅鐨勮处鍗曚竴璧峰垹闄ゃ€?
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
    }

    /**
     * 灏嗕竴绾у垎绫婚檷绾т负鎸囧畾鐖跺垎绫荤殑浜岀骇鍒嗙被銆?
     * 瑕佹眰璇ヤ竴绾у垎绫绘病鏈夊瓙鍒嗙被锛堣皟鐢ㄦ柟搴斿厛妫€鏌ワ級銆?
     */
    suspend fun demoteToChild(categoryId: Long, newParentId: Long) {
        val cat = categoryDao.getAllCategoriesList().find { it.id == categoryId } ?: return
        categoryDao.updateCategory(cat.copy(parentId = newParentId))
    }

    /** 鑾峰彇鎸囧畾鍒嗙被鐨勫瓙鍒嗙被鍒楄〃 */
    suspend fun getChildren(parentId: Long): List<Category> =
        categoryDao.getChildrenByParentId(parentId)
}


