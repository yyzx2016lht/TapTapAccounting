package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AIServiceCategoryClassifyTest {

    private val food = Category(id = 1, name = "吃的", type = 0)
    private val fruit = Category(id = 2, name = "水果", type = 0, parentId = 1)
    private val drink = Category(id = 3, name = "喝的", type = 0)
    private val drinkLeaf = Category(id = 4, name = "饮料", type = 0, parentId = 3)
    private val salary = Category(id = 5, name = "工资", type = 1)
    private val categories = listOf(food, fruit, drink, drinkLeaf, salary)

    @Test
    fun `exact leaf name resolves`() {
        assertEquals(fruit, resolveClassifiedCategory("水果", categories, 0))
    }

    @Test
    fun `parent child path resolves`() {
        assertEquals(fruit, resolveClassifiedCategory("吃的 - 水果", categories, 0))
        assertEquals(fruit, resolveClassifiedCategory("吃的/水果", categories, 0))
        assertEquals(drinkLeaf, resolveClassifiedCategory("喝的 > 饮料", categories, 0))
    }

    @Test
    fun `quoted and padded model output resolves`() {
        assertEquals(fruit, resolveClassifiedCategory("  \"水果\"  ", categories, 0))
    }

    @Test
    fun `unknown category is rejected`() {
        assertNull(resolveClassifiedCategory("数码", categories, 0))
        assertNull(resolveClassifiedCategory("水果类", categories, 0))
    }

    @Test
    fun `type mismatch is rejected`() {
        assertNull(resolveClassifiedCategory("工资", categories, 0))
        assertNull(resolveClassifiedCategory("水果", categories, 1))
    }

    @Test
    fun `blank or non consumption type is rejected`() {
        assertNull(resolveClassifiedCategory("", categories, 0))
        assertNull(resolveClassifiedCategory("水果", categories, 2))
    }
}
