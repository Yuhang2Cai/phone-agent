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
import java.math.BigDecimal

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
        server.enqueue(MockResponse().setBody("""{"content":[{"id":1,"name":"清粥小菜","category":"粥汤","minimumOrder":10,"deliveryFee":3,"deliveryMinutes":25,"open":true}],"number":0,"last":true,"totalElements":1}"""))
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

    @Test
    fun merchantPagesDetailAndReviewsUsePublicApiContracts() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"id":7,"name":"小饭馆","category":"中餐","minimumOrder":18.50,"deliveryFee":2.00,"deliveryMinutes":30,"open":true,"imageUrl":"/images/7.png"}],"number":0,"last":false,"totalElements":5}"""))
        server.enqueue(MockResponse().setBody("""{"id":7,"name":"小饭馆","category":"中餐","minimumOrder":18.50,"deliveryFee":2.00,"deliveryMinutes":30,"open":true,"imageUrl":"/images/7.png"}"""))
        server.enqueue(MockResponse().setBody("""{"content":[{"id":31,"merchantId":7,"reviewerName":"小圆","rating":4.50,"content":"很好吃","createdAt":"2026-09-26T10:15:30Z"}],"number":0,"last":false,"totalElements":12}"""))

        val repository = HttpOrderingRepository(ApiClient(server.url("/api/").toString(), tokenProvider = { "jwt-token" }))
        val merchants = repository.loadMerchants(0).successValue()
        val merchant = repository.loadMerchant(7).successValue()
        val reviews = repository.loadReviews(7, 0).successValue()

        assertEquals(0, merchants.number)
        assertEquals(false, merchants.last)
        assertEquals(5L, merchants.totalElements)
        assertEquals(7L, merchants.content.single().id)
        assertEquals("小饭馆", merchant.name)
        assertEquals(BigDecimal("18.50"), merchant.minimumOrder)
        assertEquals("/images/7.png", merchant.imageUrl)
        assertEquals(0, reviews.number)
        assertEquals(false, reviews.last)
        assertEquals(12L, reviews.totalElements)
        assertEquals(31L, reviews.content.single().id)
        assertEquals(7L, reviews.content.single().merchantId)
        assertEquals("小圆", reviews.content.single().reviewerName)
        assertEquals(BigDecimal("4.50"), reviews.content.single().rating)
        assertEquals("很好吃", reviews.content.single().content)
        assertEquals("2026-09-26T10:15:30Z", reviews.content.single().createdAt)

        assertEquals("/api/merchants?page=0&size=4&openOnly=true", server.takeRequest().path)
        assertEquals("/api/merchants/7", server.takeRequest().path)
        assertEquals("/api/merchants/7/reviews?page=0&size=10", server.takeRequest().path)
    }

    private fun <T> ApiResult<T>.successValue(): T {
        assertTrue(this is ApiResult.Success)
        return (this as ApiResult.Success<T>).value
    }

    private fun orderJson(id: Long, status: String) = """{"id":$id,"status":"$status","recipient":"Cai","phone":"13800000000","detail":"公司默认地址","subtotal":12,"deliveryFee":3,"discount":0,"total":15,"items":[{"dishId":10,"dishName":"南瓜小米粥","quantity":1,"optionIds":[99],"optionSummary":"不要香菜","unitPrice":12}],"timeline":["PENDING_PAYMENT","PAID","MERCHANT_ACCEPTED"]}"""
}
