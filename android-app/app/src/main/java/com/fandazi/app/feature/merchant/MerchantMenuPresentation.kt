package com.fandazi.app.feature.merchant

import com.fandazi.app.feature.ordering.Dish
import java.math.BigDecimal

data class MerchantMenuSection(val category: String, val dishes: List<MerchantMenuDish>)
data class MerchantMenuDish(val dish: Dish) {
    val isAddable: Boolean get() = dish.listed && dish.stock > 0 && dish.options.groupBy { it.groupName }.values.all { group -> group.any { it.available } }
}

object MerchantMenuPresentation {
    fun sections(dishes: List<Dish>): List<MerchantMenuSection> = dishes.groupBy { it.category }
        .map { (category, items) -> MerchantMenuSection(category, items.map(::MerchantMenuDish)) }

    fun cartSummary(quantity: Int, total: BigDecimal): String = "已选 ${quantity} 份\n合计 ¥${total.stripTrailingZeros().toPlainString()}"
}
