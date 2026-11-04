package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryNameResolverTest {

    private val eat = Category(id = 1, name = "吃的", type = 0)
    private val drink = Category(id = 2, name = "喝的", type = 0)
    private val snack = Category(id = 3, name = "零食", type = 0)
    private val veg = Category(id = 11, name = "买菜", type = 0, parentId = 1)
    private val drinkLeaf = Category(id = 21, name = "饮料", type = 0, parentId = 2)
    private val snackDrink = Category(id = 31, name = "饮料", type = 0, parentId = 3)
    private val salary = Category(id = 99, name = "工资", type = 1)
    private val all = listOf(eat, drink, snack, veg, drinkLeaf, snackDrink, salary)

    @Test
    fun `plain name still resolves`() {
        assertEquals(veg, CategoryNameResolver.resolve(all, "买菜", 0))
    }

    @Test
    fun `parent child path resolves leaf`() {
        assertEquals(veg, CategoryNameResolver.resolve(all, "吃的 - 买菜", 0))
        assertEquals(veg, CategoryNameResolver.resolve(all, "吃的-买菜", 0))
        assertEquals(veg, CategoryNameResolver.resolve(all, "吃的/买菜", 0))
    }

    @Test
    fun `duplicate leaf names are disambiguated by parent`() {
        assertEquals(drinkLeaf, CategoryNameResolver.resolve(all, "喝的 - 饮料", 0))
        assertEquals(snackDrink, CategoryNameResolver.resolve(all, "零食 - 饮料", 0))
    }

    @Test
    fun `unknown parent falls back to leaf`() {
        assertEquals(veg, CategoryNameResolver.resolve(all, "其他 - 买菜", 0))
    }

    @Test
    fun `type mismatch returns null`() {
        assertNull(CategoryNameResolver.resolve(all, "吃的 - 买菜", 1))
    }

    @Test
    fun `display path includes parent`() {
        assertEquals("吃的 - 买菜", CategoryNameResolver.displayPath(all, veg))
        assertEquals("吃的", CategoryNameResolver.displayPath(all, eat))
    }
}
