// 订单模块：验证关键行为与边界条件。
package com.fandazi.app.feature.order

import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.agent.AgentEvent
import com.fandazi.app.feature.agent.AgentMessageResult
import com.fandazi.app.feature.agent.AgentMode
import com.fandazi.app.feature.agent.AgentStatus
import com.fandazi.app.feature.agent.CandidateCart
import com.fandazi.app.feature.agent.CandidateCartItem
import com.fandazi.app.feature.auth.AuthResponse
import com.fandazi.app.feature.auth.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeApiOrderingFlowTest {
    @Test
    fun fakeApiCoversOrderingAndAgentConfirmationFlowWithoutNetwork() {
        val api = FakeFandaziApi()

        val auth = api.login("demo", "password123").value
        val merchants = api.search("清淡").value
        val menu = api.menu(merchants.single().id).value
        val selectedDish = menu.single()
        val spicyOption = selectedDish.options.single { it.name == "微辣" }
        val address = api.createAddress("公司默认地址").value
        val cart = api.createCart(merchants.single().id).value

        val updatedCart = api.setCartItems(cart.id, listOf(CartLine(selectedDish.id, 1, spicyOption.id))).value
        val quote = api.checkout(updatedCart.id, address.id).value
        val order = api.createOrder(quote.cartId, quote.addressId, "android-fake-order").value
        val paid = api.pay(order.id, "android-fake-pay").value
        val fetched = api.order(order.id).value
        val agent = api.sendAgentMessage("我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。").value

        assertEquals("demo-token", auth.token)
        assertEquals("南瓜小米粥", selectedDish.name)
        assertEquals(listOf(CartLine(selectedDish.id, 1, spicyOption.id)), updatedCart.lines)
        assertEquals("¥15.00", quote.total)
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
        assertEquals(OrderStatus.MERCHANT_ACCEPTED, paid.status)
        assertEquals(listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED), fetched.timeline)
        assertEquals(AgentStatus.AWAITING_CONFIRMATION, agent.status)
        assertEquals("清粥小菜", agent.candidateCart?.merchantName)
        assertTrue(agent.candidateCart?.isCompleteForConfirmation() == true)
        assertEquals(listOf("parse_intent", "search_merchants", "validate_cart", "wait_confirmation"), agent.events.map { it.type })
    }

    private class FakeFandaziApi {
        private val merchant = Merchant(1, "清粥小菜")
        private val dish = Dish(10, "南瓜小米粥", listOf(Option(99, "微辣")))
        private var nextAddressId = 100L
        private var nextCartId = 200L
        private var nextOrderId = 300L
        private val carts = linkedMapOf<Long, Cart>()
        private val orders = linkedMapOf<Long, Order>()

        fun login(username: String, password: String) = success(AuthResponse("$username-token", User(username, "$username@example.com")))
        fun search(keyword: String) = success(listOf(merchant))
        fun menu(merchantId: Long) = success(listOf(dish))
        fun createAddress(label: String) = success(Address(nextAddressId++, label))
        fun createCart(merchantId: Long): ApiResult.Success<Cart> {
            val cart = Cart(nextCartId++, merchantId, emptyList())
            carts[cart.id] = cart
            return success(cart)
        }
        fun setCartItems(cartId: Long, lines: List<CartLine>): ApiResult.Success<Cart> {
            val cart = carts.getValue(cartId).copy(lines = lines)
            carts[cartId] = cart
            return success(cart)
        }
        fun checkout(cartId: Long, addressId: Long) = success(Quote(cartId, addressId, "¥15.00"))
        fun createOrder(cartId: Long, addressId: Long, idempotencyKey: String): ApiResult.Success<Order> {
            val existing = orders.values.firstOrNull { it.idempotencyKey == idempotencyKey }
            if (existing != null) return success(existing)
            val order = Order(nextOrderId++, OrderStatus.PENDING_PAYMENT, listOf(OrderStatus.PENDING_PAYMENT), idempotencyKey)
            orders[order.id] = order
            return success(order)
        }
        fun pay(orderId: Long, idempotencyKey: String): ApiResult.Success<Order> {
            val paid = orders.getValue(orderId).copy(
                status = OrderStatus.MERCHANT_ACCEPTED,
                timeline = listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)
            )
            orders[orderId] = paid
            return success(paid)
        }
        fun order(orderId: Long) = success(orders.getValue(orderId))
        fun sendAgentMessage(text: String) = success(
            AgentMessageResult(
                status = AgentStatus.AWAITING_CONFIRMATION,
                message = "请确认清淡午餐。",
                nextAction = "wait_confirmation",
                candidateCart = CandidateCart(
                    merchantName = "清粥小菜",
                    items = listOf(CandidateCartItem("南瓜小米粥", "不要香菜", "¥12.00", 1)),
                    address = "公司默认地址",
                    deliveryFee = "¥3.00",
                    discount = "¥0.00",
                    total = "¥15.00",
                    maxAmount = "¥30.00",
                    addressId = 100,
                    authorizationMode = AgentMode.CONFIRM
                ),
                events = listOf(
                    AgentEvent(null, "parse_intent", "已解析意图", null, "completed"),
                    AgentEvent(null, "search_merchants", "已筛选商家", "search_merchants", "completed"),
                    AgentEvent(null, "validate_cart", "已校验购物车", "validate_cart", "completed"),
                    AgentEvent(null, "wait_confirmation", "等待确认", null, "pending")
                )
            )
        )

        private fun <T> success(value: T) = ApiResult.Success(value)
    }

    private data class Merchant(val id: Long, val name: String)
    private data class Dish(val id: Long, val name: String, val options: List<Option>)
    private data class Option(val id: Long, val name: String)
    private data class Address(val id: Long, val label: String)
    private data class Cart(val id: Long, val merchantId: Long, val lines: List<CartLine>)
    private data class CartLine(val dishId: Long, val quantity: Int, val optionId: Long)
    private data class Quote(val cartId: Long, val addressId: Long, val total: String)
    private data class Order(val id: Long, val status: OrderStatus, val timeline: List<OrderStatus>, val idempotencyKey: String)
    private enum class OrderStatus { PENDING_PAYMENT, PAID, MERCHANT_ACCEPTED }
}
