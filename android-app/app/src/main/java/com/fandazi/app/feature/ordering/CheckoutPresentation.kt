// 下单流程模块：将业务状态映射为界面可展示内容。
package com.fandazi.app.feature.ordering

object DishOptionsPresentation {
    data class Group(val name: String, val options: List<DishOption>)

// 处理 `groups` 相关逻辑。
    fun groups(dish: Dish): List<Group> = dish.options.groupBy { it.groupName }
        .map { (name, options) -> Group(name, options) }

// 处理 `optionLabel` 相关逻辑。
    fun optionLabel(option: DishOption): String =
        if (option.available) option.optionName else "${option.optionName}（已售罄）"
}

object CheckoutPresentation {
// 处理 `summary` 相关逻辑。
    fun summary(quote: CartQuote?, address: Address?): String {
        if (quote == null) return "购物车金额待校验"
        val addressText = address?.detail ?: "请选择收货地址"
        return "收货地址  $addressText\n商品小计  ¥${quote.subtotal}\n配送费  ¥${quote.deliveryFee}\n优惠  -¥${quote.discount}\n合计  ¥${quote.total}"
    }
}
