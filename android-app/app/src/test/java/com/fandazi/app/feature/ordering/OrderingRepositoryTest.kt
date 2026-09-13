package com.fandazi.app.feature.ordering

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OrderingRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchMenuCartAddressOrderAndPayUsePublicAuthenticatedApi() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"id":1,"name":"清粥小菜","category":"粥汤","minimumOrder":10,"deliveryFee":3,"deliveryMinutes":25,"open":true}]}"""))
        server.enqueue(MockResponse().setBody("""[{"id":10,"merchantId":1,"category":"粥","name":"南瓜小米粥","description":"温热清淡","price":12,"stock":8,"listed":true,"imageUrl":"/images/congee.png","options":[{"id":99,"groupName":"忌口","optionName":"不要香菜","priceDelta":0,"available":true}]}]"""))
        server.enqueue(MockResponse().setBody("""{"id":200,"merchantId":1,"items":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":200,"merchantId":1,"items":[{"dishId":10,"dishName":"南瓜小米粥","quantity":1,"optionIds":[99],"optionSummary":"不要香菜","unitPrice":12}]}"""))
        server.enqueue(MockResponse().setBody("""[{"id":7,"recipient":"Cai","phone":"13800000000","detail":"公司默认地址","defaultAddress":true,"valid":true}]"""))
        server.enqueue(MockResponse().setBody("""{"cartDraftId":200,"subtotal":12,"deliveryFee":3,"discount":0,"total":15,"lines":[{"dishId":10,"dishName":"南瓜小米粥","quantity":1,"optionIds":[99],"optionSummary":"不要香菜","unitPrice":12}]}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody(orderJson(300, "PENDING_PAYMENT")))
        server.enqueue(MockResponse().setBody(orderJson(300, "MERCHANT_ACCEPTED")))
        server.enqueue(MockResponse().setBody("""[${orderJson(300, "MERCHANT_ACCEPTED")}]"""))

        val repository = HttpOrderingRepository(ApiClient(server.url("/api/").toString(), tokenProvider = { "jwt-token" }))

        val merchants = repository.searchMerchants("清淡").successValue()
        val menu = repository.loadMenu(merchants.single().id).successValue()
        val draft = repository.createCartDraft(merchants.single().id).successValue()
        val updated = repository.updateCart(draft.id, listOf(CartItemInput(menu.single().id, 1, listOf(menu.single().options.single().id)))).successValue()
        val addresses = repository.loadAddresses().successValue()
        val quote = repository.validateCart(updated.id).successValue()
        val order = repository.createOrder(quote.cartDraftId, addresses.single().id, "idem-create").successValue()
        val paid = repository.payOrder(order.id, "idem-pay").successValue()
        val orders = repository.loadOrders().successValue()

        assertEquals("清粥小菜", merchants.single().name)
        assertEquals("南瓜小米粥", menu.single().name)
        assertEquals(listOf(99L), updated.items.single().optionIds)
        assertEquals("公司默认地址", addresses.single().detail)
        assertEquals("15", quote.total.stripTrailingZeros().toPlainString())
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
        assertEquals(OrderStatus.MERCHANT_ACCEPTED, paid.status)
        assertEquals(listOf(OrderStatus.MERCHANT_ACCEPTED), orders.map { it.status })

        assertEquals("/api/merchants?keyword=%E6%B8%85%E6%B7%A1", server.takeRequest().path)
        assertEquals("/api/merchants/1/dishes", server.takeRequest().path)
        assertEquals("/api/cart/drafts", server.takeRequest().path)
        assertEquals("/api/cart/drafts/200/items", server.takeRequest().path)
        assertEquals("/api/addresses", server.takeRequest().path)
        assertEquals("/api/cart/drafts/200/validate", server.takeRequest().path)
        assertEquals("idem-create", server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals("idem-pay", server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals("/api/orders", server.takeRequest().path)
    }

    private fun <T> ApiResult<T>.successValue(): T {
        assertTrue(this is ApiResult.Success)
        return (this as ApiResult.Success<T>).value
    }

    private fun orderJson(id: Long, status: String) = """{"id":$id,"status":"$status","recipient":"Cai","phone":"13800000000","detail":"公司默认地址","subtotal":12,"deliveryFee":3,"discount":0,"total":15,"items":[{"dishId":10,"dishName":"南瓜小米粥","quantity":1,"optionIds":[99],"optionSummary":"不要香菜","unitPrice":12}],"timeline":["PENDING_PAYMENT","PAID","MERCHANT_ACCEPTED"]}"""
}
