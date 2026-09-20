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

    @Test
    fun optionSelectionKeepsOneChoicePerGroupAndMergesRepeatedCartLine() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))

        viewModel.search("清淡")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        viewModel.selectOption(10, 99)
        viewModel.selectOption(10, 100)
        viewModel.selectOption(10, 101)

        assertEquals(listOf("不要香菜", "加豆腐"), viewModel.uiState.value.selectedOptions[10L]?.map { it.optionName })
        viewModel.addSelectedDish()
        advanceUntilIdle()
        viewModel.addSelectedDish()
        advanceUntilIdle()

        assertEquals(2, repository.lastItems.single().quantity)
        assertEquals(listOf(99L, 101L), repository.lastItems.single().optionIds)
    }

    @Test
    fun persistedCartIsRestoredForSameOwner() {
        val draft = CartDraft(200, 1, emptyList())
        val quote = CartQuote(200, BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), emptyList())
        val store = object : CartStateStore {
            override fun read(owner: String) = CartSnapshot(owner, Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true), draft, quote)
            override fun write(snapshot: CartSnapshot) = Unit
            override fun clear(owner: String) = Unit
        }

        val viewModel = OrderingViewModel(FakeOrderingRepository(), cartStore = store, cartOwner = "Cai")

        assertEquals(200L, viewModel.uiState.value.cartDraft?.id)
        assertEquals(BigDecimal("15"), viewModel.uiState.value.quote?.total)
    }

    @Test
    fun switchingMerchantRequiresConfirmationBeforeCartIsCleared() = runTest {
        val first = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        val second = Merchant(2, "另一家店", "快餐", BigDecimal.TEN, BigDecimal("2"), 20, true)
        val draft = CartDraft(200, 1, emptyList())
        val store = object : CartStateStore {
            override fun read(owner: String) = CartSnapshot(owner, first, draft, null)
            override fun write(snapshot: CartSnapshot) = Unit
            override fun clear(owner: String) = Unit
        }
        val viewModel = OrderingViewModel(
            FakeOrderingRepository(ApiResult.Success(listOf(first, second))),
            StandardTestDispatcher(testScheduler),
            store,
            "Cai"
        )

        viewModel.search("午餐")
        advanceUntilIdle()
        viewModel.openMerchant(2)
        assertEquals(second, viewModel.uiState.value.pendingMerchantSwitch)
        assertEquals(draft, viewModel.uiState.value.cartDraft)
        viewModel.cancelMerchantSwitch()
        assertEquals(draft, viewModel.uiState.value.cartDraft)
        viewModel.openMerchant(2)
        viewModel.confirmMerchantSwitch()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.cartDraft)
        assertEquals(second, viewModel.uiState.value.selectedMerchant)
    }

    @Test
    fun validationFailureKeepsEditableLinesAndDuplicateOrderSubmissionIsIgnored() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.search("清淡")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        repository.failValidation = true
        viewModel.addSelectedDish()
        advanceUntilIdle()

        assertEquals("南瓜小米粥", viewModel.uiState.value.cartLines.single().dishName)
        assertEquals("库存不足", viewModel.uiState.value.error)

        repository.failValidation = false
        viewModel.updateCartLine(10, emptyList(), 2)
        advanceUntilIdle()
        viewModel.selectDefaultAddress()
        advanceUntilIdle()
        viewModel.createOrder()
        viewModel.createOrder()
        advanceUntilIdle()

        assertEquals(1, repository.createOrderCalls)
    }

    @Test
    fun orderRetryReusesTheSameIdempotencyKey() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.search("清淡")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        viewModel.addSelectedDish()
        advanceUntilIdle()
        viewModel.selectDefaultAddress()
        advanceUntilIdle()
        repository.failCreateOrderOnce = true

        viewModel.createOrder()
        advanceUntilIdle()
        viewModel.createOrder()
        advanceUntilIdle()

        assertEquals(2, repository.createOrderKeys.size)
        assertEquals(repository.createOrderKeys[0], repository.createOrderKeys[1])
    }

    @Test
    fun paymentRetryKeepsPendingOrderAndReusesIdempotencyKey() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.search("清淡")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        viewModel.addSelectedDish()
        advanceUntilIdle()
        viewModel.selectDefaultAddress()
        advanceUntilIdle()
        viewModel.createOrder()
        advanceUntilIdle()
        repository.failPayOnce = true

        viewModel.payOrder()
        advanceUntilIdle()
        assertEquals(OrderStatus.PENDING_PAYMENT, viewModel.uiState.value.currentOrder?.status)
        viewModel.payOrder()
        advanceUntilIdle()

        assertEquals(2, repository.payKeys.size)
        assertEquals(repository.payKeys[0], repository.payKeys[1])
        assertEquals(OrderStatus.MERCHANT_ACCEPTED, viewModel.uiState.value.currentOrder?.status)
    }

    private class FakeOrderingRepository(
        private val searchResult: ApiResult<List<Merchant>> = ApiResult.Success(
            listOf(Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true))
        )
    ) : OrderingRepository {
        var createOrderKey: String? = null
        var payKey: String? = null
        var lastItems: List<CartItemInput> = emptyList()
        var failValidation = false
        var createOrderCalls = 0
        var failCreateOrderOnce = false
        val createOrderKeys = mutableListOf<String>()
        var failPayOnce = false
        val payKeys = mutableListOf<String>()
        private val merchant = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        private val option = DishOption(99, "忌口", "不要香菜", BigDecimal.ZERO, true)
        private val tofu = DishOption(100, "加料", "加豆腐", BigDecimal("2"), true)
        private val moreTofu = DishOption(101, "加料", "加豆腐", BigDecimal("3"), true)
        private val dish = Dish(10, 1, "粥", "南瓜小米粥", "温热清淡", BigDecimal("12"), 8, true, "", listOf(option, tofu, moreTofu))
        private val address = Address(7, "Cai", "13800000000", "公司默认地址", true, true)
        private val draft = CartDraft(200, 1, emptyList())
        private val line = CartLine(10, "南瓜小米粥", 1, listOf(99), "不要香菜", BigDecimal("12"))

        override suspend fun searchMerchants(keyword: String) = searchResult
        override suspend fun loadMenu(merchantId: Long) = ApiResult.Success(listOf(dish))
        override suspend fun createCartDraft(merchantId: Long) = ApiResult.Success(draft)
        override suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>): ApiResult<CartDraft> {
            lastItems = items
            val input = items.single()
            return ApiResult.Success(draft.copy(items = listOf(line.copy(quantity = input.quantity, optionIds = input.optionIds))))
        }
        override suspend fun validateCart(cartDraftId: Long): ApiResult<CartQuote> = if (failValidation) {
            ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(409, "库存不足"))
        } else {
            ApiResult.Success(CartQuote(cartDraftId, BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line.copy(quantity = lastItems.firstOrNull()?.quantity ?: 1))))
        }
        override suspend fun loadAddresses() = ApiResult.Success(listOf(address))
        override suspend fun createOrder(cartDraftId: Long, addressId: Long, idempotencyKey: String): ApiResult<Order> {
            createOrderCalls += 1
            createOrderKey = idempotencyKey
            createOrderKeys += idempotencyKey
            if (failCreateOrderOnce) {
                failCreateOrderOnce = false
                return ApiResult.Failure(com.fandazi.app.core.network.ApiError.Timeout)
            }
            return ApiResult.Success(order(OrderStatus.PENDING_PAYMENT, listOf(OrderStatus.PENDING_PAYMENT)))
        }
        override suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order> {
            payKey = idempotencyKey
            payKeys += idempotencyKey
            if (failPayOnce) {
                failPayOnce = false
                return ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("模拟支付失败"))
            }
            return ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))
        }
        override suspend fun loadOrders() = ApiResult.Success(emptyList<Order>())
        override suspend fun loadOrder(orderId: Long) = ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))

        private fun order(status: OrderStatus, timeline: List<OrderStatus>) = Order(300, status, "Cai", "13800000000", "公司默认地址", BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line), timeline)
    }
}
