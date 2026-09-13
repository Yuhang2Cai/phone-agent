package com.fandazi.app.feature.ordering

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class OrderingViewModelTest {
    @Test
    fun emptySearchResultsExposeEmptyState() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(searchResult = ApiResult.Success(emptyList())), StandardTestDispatcher(testScheduler))

        viewModel.search("不存在")
        advanceUntilIdle()

        assertEquals(SearchStatus.Empty, viewModel.uiState.value.searchStatus)
    }

    @Test
    fun unauthorizedSearchExposesAuthRequiredState() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(searchResult = ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(401, "login required"))), StandardTestDispatcher(testScheduler))

        viewModel.search("清淡")
        advanceUntilIdle()

        assertEquals(SearchStatus.AuthRequired, viewModel.uiState.value.searchStatus)
    }

    @Test
    fun selectingMerchantOptionAddressAndPayingProducesCompletedOrderTimeline() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))

        viewModel.search("清淡")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        viewModel.selectOption(10, 99)
        viewModel.addSelectedDish()
        advanceUntilIdle()
        viewModel.selectDefaultAddress()
        advanceUntilIdle()
        viewModel.createOrder()
        advanceUntilIdle()
        viewModel.payOrder()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("清粥小菜", state.merchants.single().name)
        assertEquals("不要香菜", state.selectedOptions[10L]?.single()?.optionName)
        assertEquals("公司默认地址", state.selectedAddress?.detail)
        assertEquals(OrderStatus.MERCHANT_ACCEPTED, state.currentOrder?.status)
        assertEquals(listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED), state.currentOrder?.timeline)
        assertTrue(repository.createOrderKey?.startsWith("android-order-") == true)
        assertTrue(repository.payKey?.startsWith("android-pay-") == true)
    }

    private class FakeOrderingRepository(
        private val searchResult: ApiResult<List<Merchant>> = ApiResult.Success(
            listOf(Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true))
        )
    ) : OrderingRepository {
        var createOrderKey: String? = null
        var payKey: String? = null
        private val merchant = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        private val option = DishOption(99, "忌口", "不要香菜", BigDecimal.ZERO, true)
        private val dish = Dish(10, 1, "粥", "南瓜小米粥", "温热清淡", BigDecimal("12"), 8, true, "", listOf(option))
        private val address = Address(7, "Cai", "13800000000", "公司默认地址", true, true)
        private val draft = CartDraft(200, 1, emptyList())
        private val line = CartLine(10, "南瓜小米粥", 1, listOf(99), "不要香菜", BigDecimal("12"))

        override suspend fun searchMerchants(keyword: String) = searchResult
        override suspend fun loadMenu(merchantId: Long) = ApiResult.Success(listOf(dish))
        override suspend fun createCartDraft(merchantId: Long) = ApiResult.Success(draft)
        override suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>) = ApiResult.Success(draft.copy(items = listOf(line)))
        override suspend fun validateCart(cartDraftId: Long) = ApiResult.Success(CartQuote(cartDraftId, BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line)))
        override suspend fun loadAddresses() = ApiResult.Success(listOf(address))
        override suspend fun createOrder(cartDraftId: Long, addressId: Long, idempotencyKey: String): ApiResult<Order> {
            createOrderKey = idempotencyKey
            return ApiResult.Success(order(OrderStatus.PENDING_PAYMENT, listOf(OrderStatus.PENDING_PAYMENT)))
        }
        override suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order> {
            payKey = idempotencyKey
            return ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))
        }
        override suspend fun loadOrders() = ApiResult.Success(emptyList<Order>())
        override suspend fun loadOrder(orderId: Long) = ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))

        private fun order(status: OrderStatus, timeline: List<OrderStatus>) = Order(300, status, "Cai", "13800000000", "公司默认地址", BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line), timeline)
    }
}
