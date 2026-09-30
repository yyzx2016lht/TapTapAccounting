package com.taostudio.tapaccounting.data.repository

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryRepositoryTest {
    @Test
    fun displayNamesById_usesCurrentParentAndChildNames() {
        val categories = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )

        val names = CategoryRepository.displayNamesById(categories)

        assertEquals("餐饮", names[1L])
        assertEquals("餐饮 - 早餐", names[2L])
    }

    @Test
    fun displayNamesById_keepsDuplicateLeafNamesDistinctById() {
        val categories = listOf(
            Category(id = 1, name = "日常", type = 0),
            Category(id = 2, name = "旅行", type = 0),
            Category(id = 3, name = "交通", type = 0, parentId = 1),
            Category(id = 4, name = "交通", type = 0, parentId = 2)
        )

        val names = CategoryRepository.displayNamesById(categories)

        assertEquals("日常 - 交通", names[3L])
        assertEquals("旅行 - 交通", names[4L])
    }

    // --- 以下锁定 bills.categoryName 快照的重算规则 ---
    //
    // bills.categoryName 存的是展示路径快照（"餐饮 - 早餐"），而统计页与洞察都按它聚合，
    // 从不回查 categoryId。所以分类树形状一变，快照必须按下面这些规则重算，
    // 否则统计页会凭空多出一个旧名字的「幽灵分类」。

    @Test
    fun renamingATopLevelCategoryAlsoChangesItsChildrenPaths() {
        // 一级分类「餐饮」改名为「吃饭」后，子分类「早餐」的账单路径前缀必须跟着变。
        val before = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )
        val after = listOf(
            Category(id = 1, name = "吃饭", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )

        assertEquals("餐饮 - 早餐", CategoryRepository.displayNamesById(before)[2L])
        assertEquals("吃饭 - 早餐", CategoryRepository.displayNamesById(after)[2L])
    }

    @Test
    fun promotingAChildDropsItsParentPrefix() {
        // 二级分类提升为一级后，账单路径从 "餐饮 - 早餐" 变成裸名字 "早餐"。
        val before = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )
        val after = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = null)
        )

        assertEquals("餐饮 - 早餐", CategoryRepository.displayNamesById(before)[2L])
        assertEquals("早餐", CategoryRepository.displayNamesById(after)[2L])
    }

    @Test
    fun demotingATopLevelCategoryAddsTheParentPrefix() {
        // 一级分类「早餐」降级到「餐饮」下，账单路径要补上前缀。
        val after = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )

        assertEquals("餐饮 - 早餐", CategoryRepository.displayNamesById(after)[2L])
    }

    @Test
    fun reparentingByDragRewritesThePathToTheNewParent() {
        // 拖动把「交通」从「日常」下挪到「旅行」下。
        val after = listOf(
            Category(id = 1, name = "日常", type = 0),
            Category(id = 2, name = "旅行", type = 0),
            Category(id = 3, name = "交通", type = 0, parentId = 2)
        )

        assertEquals("旅行 - 交通", CategoryRepository.displayNamesById(after)[3L])
    }
}
