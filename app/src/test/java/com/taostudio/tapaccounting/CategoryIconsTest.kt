package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryIconsTest {

    @Test
    fun placeholderThreeDotsBecomeTheCategorysFirstCharacter() {
        val daily = CategoryIcons.fallback(
            "http://res3.qianjiapp.com/cateic_richang2.png",
            "日常"
        )

        assertEquals("text://日", daily)
        assertTrue(CategoryIcons.isPlaceholder(CategoryIcons.PLACEHOLDER_URL))
        assertTrue(CategoryIcons.isPlaceholder("http://res3.qianjiapp.com/cateic_richang2.png"))
    }

    @Test
    fun otherCategoryUsesTheFourSquareIconInsteadOfItsFirstCharacter() {
        assertEquals(CategoryIcons.OTHER_ICON, CategoryIcons.fallback("", "其他"))
        assertEquals(CategoryIcons.OTHER_ICON, CategoryIcons.fallback(CategoryIcons.PLACEHOLDER_URL, "其它"))
        assertEquals(CategoryIcons.OTHER_ICON, CategoryIcons.fallback("text://其", "其他"))
        assertEquals(
            "http://res3.qianjiapp.com/cateic_yinshi.png",
            CategoryIcons.fallback("http://res3.qianjiapp.com/cateic_yinshi.png", "其他")
        )
        assertEquals("text://早", CategoryIcons.fallback(CategoryIcons.OTHER_ICON, "早餐"))
    }

    @Test
    fun emptyIconUsesOwnNameInsteadOfParentIcon() {
        val icon = CategoryIcons.fallback(
            "",
            "早餐",
            parentIcon = "http://res3.qianjiapp.com/cateic_yinshi.png"
        )

        assertEquals("text://早", icon)
    }

    @Test
    fun realIconAndTextIconAreKept() {
        val real = "http://res3.qianjiapp.com/cateic_yinshi.png"
        assertEquals(real, CategoryIcons.fallback(real, "餐饮"))
        assertEquals("text://宠", CategoryIcons.fallback("text://宠", "宠物"))
        assertFalse(CategoryIcons.isPlaceholder(real))
        assertFalse(CategoryIcons.isPlaceholder("text://宠"))
    }
}
