package com.fandazi.app.feature.merchant

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.ordering.Address
import com.fandazi.app.feature.ordering.CartDraft
import com.fandazi.app.feature.ordering.CartItemInput
import com.fandazi.app.feature.ordering.CartQuote
import com.fandazi.app.feature.ordering.Dish
import com.fandazi.app.feature.ordering.Merchant
import com.fandazi.app.feature.ordering.MerchantReview
import com.fandazi.app.feature.ordering.Order
import com.fandazi.app.feature.ordering.OrderingRepository
import com.fandazi.app.feature.ordering.PagedResult
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MerchantFeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()

// 为测试准备依赖、替身和初始状态。
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
// 清理测试创建的资源与状态。
    @After fun tearDown() = Dispatchers.resetMain()

// 验证 `initialPageReplacesItemsAndUsesServerPageNumber` 覆盖场景的预期行为与边界。
    @Test fun initialPageReplacesItemsAndUsesServerPageNumber() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(page(0, false, 1, 2))
        repository.respond(page(4, true, 3))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        assertTrue(viewModel.state.value.isInitialLoading)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.nextPage)
        assertFalse(viewModel.state.value.isInitialLoading)

        viewModel.loadInitial()
        assertTrue(viewModel.state.value.items.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(3L), viewModel.state.value.items.map { it.id })
        assertEquals(5, viewModel.state.value.nextPage)
        assertTrue(viewModel.state.value.endReached)
        assertEquals(listOf(Triple(0, 4, true), Triple(0, 4, true)), repository.calls)
    }

// 验证 `nextPageAppendsAndDeduplicatesByMerchantId` 覆盖场景的预期行为与边界。
    @Test fun nextPageAppendsAndDeduplicatesByMerchantId() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(page(0, false, 1, 2))
        repository.respond(page(1, true, 2, 3))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.loadNext()
        assertTrue(viewModel.state.value.isAppending)
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), viewModel.state.value.items.map { it.id })
        assertEquals(2, viewModel.state.value.nextPage)
        assertTrue(viewModel.state.value.endReached)
        viewModel.loadNext()
        assertEquals(listOf(0, 1), repository.calls.map { it.first })
        assertFalse(viewModel.state.value.isAppending)
    }

// 验证 `duplicateTriggersWhileLoadingMakeOnlyOneCall` 覆盖场景的预期行为与边界。
    @Test fun duplicateTriggersWhileLoadingMakeOnlyOneCall() = runTest(dispatcher) {
        val repository = FeedRepository()
        val first = repository.pending()
        val second = repository.pending()
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        viewModel.loadNext()
        runCurrent()
        assertEquals(listOf(0), repository.calls.map { it.first })
        first.complete(ApiResult.Success(page(0, false, 1)))
        advanceUntilIdle()

        viewModel.loadNext()
        viewModel.loadNext()
        runCurrent()
        assertEquals(listOf(0, 1), repository.calls.map { it.first })
        second.complete(ApiResult.Success(page(1, true, 2)))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), viewModel.state.value.items.map { it.id })
    }

// 验证 `initialFailureExposesOnlyInitialErrorAndRetryStartsAtZero` 覆盖场景的预期行为与边界。
    @Test fun initialFailureExposesOnlyInitialErrorAndRetryStartsAtZero() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(ApiResult.Failure(ApiError.Network("offline")))
        repository.respond(page(0, true, 1))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        advanceUntilIdle()
        assertEquals("offline", viewModel.state.value.initialError)
        assertNull(viewModel.state.value.appendError)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertFalse(viewModel.state.value.isInitialLoading)

        viewModel.retryInitial()
        advanceUntilIdle()
        assertEquals(listOf(0, 0), repository.calls.map { it.first })
        assertEquals(listOf(1L), viewModel.state.value.items.map { it.id })
        assertNull(viewModel.state.value.initialError)
    }

// 验证 `appendFailureRetainsItemsAndRetryUsesSamePage` 覆盖场景的预期行为与边界。
    @Test fun appendFailureRetainsItemsAndRetryUsesSamePage() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(page(0, false, 1))
        repository.respond(ApiResult.Failure(ApiError.Http(503, "busy")))
        repository.respond(page(1, true, 2))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.loadNext()
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.nextPage)
        assertEquals("busy", viewModel.state.value.appendError)
        assertNull(viewModel.state.value.initialError)
        assertFalse(viewModel.state.value.isAppending)

        viewModel.retryAppend()
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 1), repository.calls.map { it.first })
        assertEquals(listOf(1L, 2L), viewModel.state.value.items.map { it.id })
        assertNull(viewModel.state.value.appendError)
    }

// 验证 `loadNextDoesNothingUntilAppendErrorIsExplicitlyRetried` 覆盖场景的预期行为与边界。
    @Test fun loadNextDoesNothingUntilAppendErrorIsExplicitlyRetried() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(page(0, false, 1))
        repository.respond(ApiResult.Failure(ApiError.Network("offline")))
        repository.respond(page(1, true, 2))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.loadNext()
        advanceUntilIdle()
        val failedState = viewModel.state.value

        viewModel.loadNext()
        viewModel.loadNext()
        advanceUntilIdle()

        assertEquals(listOf(0, 1), repository.calls.map { it.first })
        assertEquals(failedState, viewModel.state.value)
    }

// 验证 `lastPagePreventsFurtherCalls` 覆盖场景的预期行为与边界。
    @Test fun lastPagePreventsFurtherCalls() = runTest(dispatcher) {
        val repository = FeedRepository()
        repository.respond(page(0, true, 1))
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.loadNext()
        viewModel.retryAppend()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.endReached)
        assertEquals(listOf(0), repository.calls.map { it.first })
    }

// 验证 `newerInitialGenerationDiscardsStaleSuccess` 覆盖场景的预期行为与边界。
    @Test fun newerInitialGenerationDiscardsStaleSuccess() = runTest(dispatcher) {
        val repository = FeedRepository()
        val old = repository.pending(ignoreCancellation = true)
        val fresh = repository.pending()
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        runCurrent()
        viewModel.loadInitial()
        runCurrent()
        fresh.complete(ApiResult.Success(page(0, true, 2)))
        advanceUntilIdle()
        old.complete(ApiResult.Success(page(0, false, 1)))
        advanceUntilIdle()

        assertEquals(listOf(2L), viewModel.state.value.items.map { it.id })
        assertTrue(viewModel.state.value.endReached)
        assertEquals(2, repository.calls.size)
    }

// 验证 `newerInitialGenerationDiscardsStaleFailure` 覆盖场景的预期行为与边界。
    @Test fun newerInitialGenerationDiscardsStaleFailure() = runTest(dispatcher) {
        val repository = FeedRepository()
        val old = repository.pending(ignoreCancellation = true)
        val fresh = repository.pending()
        val viewModel = MerchantFeedViewModel(repository, dispatcher)

        viewModel.loadInitial()
        runCurrent()
        viewModel.loadInitial()
        runCurrent()
        fresh.complete(ApiResult.Success(page(0, true, 2)))
        advanceUntilIdle()
        old.complete(ApiResult.Failure(ApiError.Network("stale")))
        advanceUntilIdle()

        assertEquals(listOf(2L), viewModel.state.value.items.map { it.id })
        assertNull(viewModel.state.value.initialError)
    }

// 验证 `page` 覆盖场景的预期行为与边界。
    private fun page(number: Int, last: Boolean, vararg ids: Long) = PagedResult(
        content = ids.map { Merchant(it, "Merchant $it", "food", BigDecimal.TEN, BigDecimal.ONE, 20, true) },
        number = number,
        last = last,
        totalElements = ids.size.toLong()
    )

    private class FeedRepository : OrderingRepository {
        val calls = mutableListOf<Triple<Int, Int, Boolean>>()
        private val responses = ArrayDeque<suspend () -> ApiResult<PagedResult<Merchant>>>()

// 验证 `respond` 覆盖场景的预期行为与边界。
        fun respond(page: PagedResult<Merchant>) = respond(ApiResult.Success(page))

// 验证 `respond` 覆盖场景的预期行为与边界。
        fun respond(result: ApiResult<PagedResult<Merchant>>) {
            responses.addLast { result }
        }

// 验证 `pending` 覆盖场景的预期行为与边界。
        fun pending(ignoreCancellation: Boolean = false): CompletableDeferred<ApiResult<PagedResult<Merchant>>> {
            val deferred = CompletableDeferred<ApiResult<PagedResult<Merchant>>>()
            responses.addLast {
                if (ignoreCancellation) withContext(NonCancellable) { deferred.await() } else deferred.await()
            }
            return deferred
        }

// 验证 `loadMerchants` 覆盖场景的预期行为与边界。
        override suspend fun loadMerchants(page: Int, size: Int, openOnly: Boolean): ApiResult<PagedResult<Merchant>> {
            calls += Triple(page, size, openOnly)
            return responses.removeFirst().invoke()
        }

// 验证 `searchMerchants` 覆盖场景的预期行为与边界。
        override suspend fun searchMerchants(keyword: String): ApiResult<List<Merchant>> = unsupported()
// 验证 `loadMenu` 覆盖场景的预期行为与边界。
        override suspend fun loadMenu(merchantId: Long): ApiResult<List<Dish>> = unsupported()
// 验证 `createCartDraft` 覆盖场景的预期行为与边界。
        override suspend fun createCartDraft(merchantId: Long): ApiResult<CartDraft> = unsupported()
// 验证 `updateCart` 覆盖场景的预期行为与边界。
        override suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>): ApiResult<CartDraft> = unsupported()
// 验证 `validateCart` 覆盖场景的预期行为与边界。
        override suspend fun validateCart(cartDraftId: Long): ApiResult<CartQuote> = unsupported()
// 验证 `loadAddresses` 覆盖场景的预期行为与边界。
        override suspend fun loadAddresses(): ApiResult<List<Address>> = unsupported()
// 验证 `createOrder` 覆盖场景的预期行为与边界。
        override suspend fun createOrder(cartDraftId: Long, addressId: Long, idempotencyKey: String): ApiResult<Order> = unsupported()
// 验证 `payOrder` 覆盖场景的预期行为与边界。
        override suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order> = unsupported()
// 验证 `loadOrders` 覆盖场景的预期行为与边界。
        override suspend fun loadOrders(): ApiResult<List<Order>> = unsupported()
// 验证 `loadOrder` 覆盖场景的预期行为与边界。
        override suspend fun loadOrder(orderId: Long): ApiResult<Order> = unsupported()

        private fun <T> unsupported(): T = error("Unexpected repository call")
    }
}
