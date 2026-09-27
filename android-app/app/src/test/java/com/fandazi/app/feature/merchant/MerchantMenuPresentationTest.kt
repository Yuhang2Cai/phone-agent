// 商家菜单模块：验证关键行为与边界条件。
package com.fandazi.app.feature.merchant

import com.fandazi.app.feature.ordering.Dish
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MerchantMenuPresentationTest {
    @Test
// 验证 `groupsDishesByCategoryAndMarksUnavailableItems` 覆盖场景的预期行为与边界。
    fun groupsDishesByCategoryAndMarksUnavailableItems() {
        val dishes = listOf(
            dish(id = 1, category = "招牌粥品", name = "南瓜小米粥", stock = 3, listed = true),
            dish(id = 2, category = "主食", name = "鸡丝拌饭", stock = 0, listed = true),
            dish(id = 3, category = "招牌粥品", name = "鲜虾鸡蛋粥", stock = 0, listed = false)
        )

        val sections = MerchantMenuPresentation.sections(dishes)

        assertEquals(listOf("招牌粥品", "主食"), sections.map { it.category })
        assertEquals(listOf("南瓜小米粥", "鲜虾鸡蛋粥"), sections[0].dishes.map { it.dish.name })
        assertFalse(sections[1].dishes.single().isAddable)
        assertFalse(sections[0].dishes[1].isAddable)
    }

    @Test
// 验证 `cartSummaryUsesQuantityAndTotal` 覆盖场景的预期行为与边界。
    fun cartSummaryUsesQuantityAndTotal() {
        assertEquals(
            "已选 2 份\n合计 ¥30",
            MerchantMenuPresentation.cartSummary(quantity = 2, total = BigDecimal("30"))
        )
    }

// 验证 `dish` 覆盖场景的预期行为与边界。
    private fun dish(id: Long, category: String, name: String, stock: Int, listed: Boolean) = Dish(
        id = id,
        merchantId = 1,
        category = category,
        name = name,
        description = "描述",
        price = BigDecimal("12"),
        stock = stock,
        listed = listed,
        imageUrl = null,
        options = emptyList()
    )
}
