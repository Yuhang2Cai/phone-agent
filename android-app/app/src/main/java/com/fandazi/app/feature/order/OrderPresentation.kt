// 订单模块：将业务状态映射为界面可展示内容。
package com.fandazi.app.feature.order

import com.fandazi.app.feature.ordering.Order
import com.fandazi.app.feature.ordering.OrderStatus

enum class OrderFilter { ALL, PENDING_PAYMENT, DELIVERING, COMPLETED }

object OrderPresentation {
    fun statusLabel(status: OrderStatus): String = when (status) {
        OrderStatus.PENDING_PAYMENT -> "待支付"
        OrderStatus.PAID -> "已支付"
        OrderStatus.MERCHANT_ACCEPTED -> "商家已接单"
        OrderStatus.PREPARING -> "制作中"
        OrderStatus.RIDER_PICKED_UP -> "骑手已取餐"
        OrderStatus.DELIVERING -> "配送中"
        OrderStatus.DELIVERED -> "已送达"
    }

    fun filter(orders: List<Order>, filter: OrderFilter): List<Order> = orders.filter { order ->
        when (filter) {
            OrderFilter.ALL -> true
            OrderFilter.PENDING_PAYMENT -> order.status == OrderStatus.PENDING_PAYMENT
            OrderFilter.DELIVERING -> order.status in setOf(
                OrderStatus.PAID,
                OrderStatus.MERCHANT_ACCEPTED,
                OrderStatus.PREPARING,
                OrderStatus.RIDER_PICKED_UP,
                OrderStatus.DELIVERING
            )
            OrderFilter.COMPLETED -> order.status == OrderStatus.DELIVERED
        }
    }

    fun filterLabel(filter: OrderFilter): String = when (filter) {
        OrderFilter.ALL -> "全部"
        OrderFilter.PENDING_PAYMENT -> "待支付"
        OrderFilter.DELIVERING -> "配送中"
        OrderFilter.COMPLETED -> "已完成"
    }

    fun timeline(order: Order): List<Pair<OrderStatus, Boolean>> = listOf(
        OrderStatus.MERCHANT_ACCEPTED,
        OrderStatus.PREPARING,
        OrderStatus.RIDER_PICKED_UP,
        OrderStatus.DELIVERING,
        OrderStatus.DELIVERED
    ).map { it to (it in order.timeline) }
}
