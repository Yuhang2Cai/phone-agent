package com.fandazi.app.feature.ordering

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutPresentationTest {
    private val dish = Dish(
        id = 7, merchantId = 3, category = "主食", name = "南瓜小米粥",
        description = "温热少油", price = BigDecimal("12"), stock = 2, listed = true,
        imageUrl = null,
        options = listOf(
            DishOption(1, "辣度", "不辣", BigDecimal.ZERO, true),
            DishOption(2, "辣度", "微辣", BigDecimal("1"), true),
            DishOption(3, "辣度", "特辣", BigDecimal.ZERO, false),
            DishOption(4, "加料", "加鸡蛋", BigDecimal("3"), true)
        )
    )

    @Test
    fun optionsAreGroupedAndUnavailableOptionsCannotBeSelected() {
        val groups = DishOptionsPresentation.groups(dish)

        assertEquals(listOf("辣度", "加料"), groups.map { it.name })
        assertTrue(groups.first().options.first().available)
        assertFalse(groups.first().options.last().available)
        assertEquals("特辣（已售罄）", DishOptionsPresentation.optionLabel(groups.first().options.last()))
    }

    @Test
    fun checkoutSummaryContainsAddressAndFeeBreakdown() {
        val quote = CartQuote(1, BigDecimal("24"), BigDecimal("3"), BigDecimal("2"), BigDecimal("25"), emptyList())
        val address = Address(9, "小林", "138****0000", "静安区南京西路 100 号", true)

        val summary = CheckoutPresentation.summary(quote, address)

        assertTrue(summary.contains("静安区南京西路 100 号"))
        assertTrue(summary.contains("商品小计  ¥24"))
        assertTrue(summary.contains("配送费  ¥3"))
        assertTrue(summary.contains("优惠  -¥2"))
        assertTrue(summary.contains("合计  ¥25"))
    }
}
