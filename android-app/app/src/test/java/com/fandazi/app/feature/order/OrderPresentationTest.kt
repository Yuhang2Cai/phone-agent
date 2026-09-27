// 订单模块：验证关键行为与边界条件。
package com.fandazi.app.feature.order

import com.fandazi.app.feature.ordering.CartLine
import com.fandazi.app.feature.ordering.Order
import com.fandazi.app.feature.ordering.OrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OrderPresentationTest {
    private fun order(id: Long, status: OrderStatus, timeline: List<OrderStatus> = listOf(status)) = Order(
        id, status, "Cai", "13800000000", "公司", BigDecimal.TEN, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.TEN, emptyList<CartLine>(), timeline
    )

    @Test
    fun filtersMapPendingDeliveryAndCompletedOrders() {
        val pending = order(1, OrderStatus.PENDING_PAYMENT)
        val preparing = order(2, OrderStatus.PREPARING)
        val delivered = order(3, OrderStatus.DELIVERED)
        val orders = listOf(pending, preparing, delivered)

        assertEquals(listOf(pending), OrderPresentation.filter(orders, OrderFilter.PENDING_PAYMENT))
        assertEquals(listOf(preparing), OrderPresentation.filter(orders, OrderFilter.DELIVERING))
        assertEquals(listOf(delivered), OrderPresentation.filter(orders, OrderFilter.COMPLETED))
    }

    @Test
    fun timelineMarksReachedAndFutureSteps() {
        val order = order(
            2,
            OrderStatus.PREPARING,
            listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED, OrderStatus.PREPARING)
        )

        val timeline = OrderPresentation.timeline(order)

        assertTrue(timeline.first { it.first == OrderStatus.MERCHANT_ACCEPTED }.second)
        assertTrue(timeline.first { it.first == OrderStatus.PREPARING }.second)
        assertFalse(timeline.first { it.first == OrderStatus.DELIVERING }.second)
    }
}
