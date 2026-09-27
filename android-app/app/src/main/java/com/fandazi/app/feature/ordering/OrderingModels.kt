// 下单流程模块：定义该模块的数据与行为。
package com.fandazi.app.feature.ordering

import java.math.BigDecimal

data class Merchant(
    val id: Long,
    val name: String,
    val category: String,
    val minimumOrder: BigDecimal,
    val deliveryFee: BigDecimal,
    val deliveryMinutes: Int,
    val open: Boolean,
    val imageUrl: String? = null
)

data class Dish(
    val id: Long,
    val merchantId: Long,
    val category: String,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val stock: Int,
    val listed: Boolean,
    val imageUrl: String?,
    val options: List<DishOption>
)

data class DishOption(
    val id: Long,
    val groupName: String,
    val optionName: String,
    val priceDelta: BigDecimal,
    val available: Boolean
)

data class CartItemInput(val dishId: Long, val quantity: Int, val optionIds: List<Long>)

data class CartDraft(val id: Long, val merchantId: Long, val items: List<CartLine>)

data class CartLine(
    val dishId: Long,
    val dishName: String,
    val quantity: Int,
    val optionIds: List<Long>,
    val optionSummary: String,
    val unitPrice: BigDecimal
)

data class CartQuote(
    val cartDraftId: Long,
    val subtotal: BigDecimal,
    val deliveryFee: BigDecimal,
    val discount: BigDecimal,
    val total: BigDecimal,
    val lines: List<CartLine>
)

data class Address(
    val id: Long,
    val recipient: String,
    val phone: String,
    val detail: String,
    val defaultAddress: Boolean,
    val valid: Boolean = true
)

enum class OrderStatus {
    PENDING_PAYMENT,
    PAID,
    MERCHANT_ACCEPTED,
    PREPARING,
    RIDER_PICKED_UP,
    DELIVERING,
    DELIVERED
}

data class Order(
    val id: Long,
    val status: OrderStatus,
    val recipient: String,
    val phone: String,
    val detail: String,
    val subtotal: BigDecimal,
    val deliveryFee: BigDecimal,
    val discount: BigDecimal,
    val total: BigDecimal,
    val items: List<CartLine>,
    val timeline: List<OrderStatus>,
    val merchantName: String? = null,
    val createdAt: String? = null
)
