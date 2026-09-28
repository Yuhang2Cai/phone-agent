// 下单流程模块：验证关键行为与边界条件。
package com.fandazi.app.feature.ordering

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class OrderingViewModelTest {
    @Test
    fun staleDialogCannotConfirmOrCancelANewerSwitchToTheSameMerchant() = runTest {
        val first = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        val draft = CartDraft(200, 1, emptyList())
        var clears = 0
        val store = object : CartStateStore {
            override fun read(owner: String) = CartSnapshot(owner, first, draft, null)
            override fun write(snapshot: CartSnapshot) = Unit
            override fun clear(owner: String) { clears++ }
        }
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler), store)
        viewModel.openMerchant(2)
        runCurrent()
        val oldId = requireNotNull(viewModel.uiState.value.pendingMerchantSwitchRequestId)
        viewModel.openMerchant(2)
        runCurrent()
        val pending = viewModel.uiState.value
        assertEquals(2L, pending.pendingMerchantSwitchRequestId)

        viewModel.confirmMerchantSwitch(oldId)
        viewModel.cancelMerchantSwitch(oldId)
        runCurrent()
        assertEquals(pending, viewModel.uiState.value)
        assertEquals(0, clears)
        assertEquals(null, viewModel.merchantNavigationRequest.value)
        assertEquals(listOf("merchant:2", "merchant:2"), repository.selectionCalls)

        viewModel.confirmMerchantSwitch(requireNotNull(pending.pendingMerchantSwitchRequestId))
        runCurrent()
        assertEquals(1, clears)
        assertEquals(null, viewModel.uiState.value.cartDraft)
        assertEquals(MerchantNavigationRequest(3, 2), viewModel.merchantNavigationRequest.value)
        assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(listOf("merchant:2", "merchant:2", "menu:2"), repository.selectionCalls)
    }

    @Test
    fun acknowledgementAfterNavigationCannotClearReentrantNewRequest() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(), StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(1)
        runCurrent()
        assertTrue(viewModel.consumeMerchantNavigation(1) {
            assertEquals(1L, it)
            viewModel.openMerchant(2)
            runCurrent()
        })
        assertEquals(MerchantNavigationRequest(2, 2), viewModel.merchantNavigationRequest.value)
    }

    @Test
    fun oldAcknowledgementCannotClearNewRequest() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(), StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(1)
        runCurrent()
        val first = requireNotNull(viewModel.merchantNavigationRequest.value)
        assertEquals(MerchantNavigationRequest(1, 1), first)
        viewModel.openMerchant(2)
        assertEquals(null, viewModel.merchantNavigationRequest.value)
        runCurrent()
        val latest = requireNotNull(viewModel.merchantNavigationRequest.value)
        assertEquals(MerchantNavigationRequest(2, 2), latest)

        viewModel.acknowledgeMerchantNavigation(first.requestId)
        assertEquals(latest, viewModel.merchantNavigationRequest.value)
        val destinations = mutableListOf<Long>()
        assertEquals(false, viewModel.consumeMerchantNavigation(first.requestId) { destinations += it })
        assertTrue(destinations.isEmpty())
        assertTrue(viewModel.consumeMerchantNavigation(latest.requestId) {
            assertEquals(latest, viewModel.merchantNavigationRequest.value)
            destinations += it
        })
        assertEquals(listOf(2L), destinations)
        assertEquals(null, viewModel.merchantNavigationRequest.value)
        assertEquals(false, viewModel.consumeMerchantNavigation(latest.requestId) { destinations += it })
        assertEquals(listOf(2L), destinations)
    }

    @Test
    fun failedNavigationKeepsRequestForRetry() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(), StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(2)
        runCurrent()
        val request = requireNotNull(viewModel.merchantNavigationRequest.value)
        try {
            viewModel.consumeMerchantNavigation(request.requestId) { error("导航尚未完成") }
            org.junit.Assert.fail("导航失败必须保留请求")
        } catch (_: IllegalStateException) {
            assertEquals(request, viewModel.merchantNavigationRequest.value)
        }
    }

    @Test
// 验证 `navigationWaitsForMerchantButDoesNotWaitForMenu` 覆盖场景的预期行为与边界。
    fun navigationWaitsForMerchantButDoesNotWaitForMenu() = runTest {
        val repository = FakeOrderingRepository()
        val merchantReady = CompletableDeferred<Unit>()
        val menuReady = CompletableDeferred<Unit>()
        val merchantLoader = repository.merchantLoader
        val menuLoader = repository.menuLoader
        repository.merchantLoader = { merchantReady.await(); merchantLoader(it) }
        repository.menuLoader = { menuReady.await(); menuLoader(it) }
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<Long>()
        val navigationStates = mutableListOf<OrderingUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                events += it.merchantId
                navigationStates += viewModel.uiState.value
            }
        }

        viewModel.openMerchant(2)
        runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(listOf("merchant:2"), repository.selectionCalls)

        merchantReady.complete(Unit)
        runCurrent()
        assertEquals(listOf(2L), events)
        assertEquals(2L, navigationStates.single().selectedMerchant?.id)
        assertTrue(navigationStates.single().isLoading)
        assertTrue(navigationStates.single().menu.isEmpty())
        assertEquals(listOf("merchant:2", "menu:2"), repository.selectionCalls)

        menuReady.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(1, events.size)
    }

    @Test
// 验证 `failedLookupNeverNavigates` 覆盖场景的预期行为与边界。
    fun failedLookupNeverNavigates() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(), StandardTestDispatcher(testScheduler))
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect { events += it.merchantId }
        }

        viewModel.openMerchant(404)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(null, viewModel.uiState.value.selectedMerchant)
        assertEquals("商家不存在", viewModel.uiState.value.error)
    }

    @Test
// 验证 `menuFailureKeepsResolvedMerchantAndNavigation` 覆盖场景的预期行为与边界。
    fun menuFailureKeepsResolvedMerchantAndNavigation() = runTest {
        val repository = FakeOrderingRepository()
        repository.menuLoader = { ApiResult.Failure(ApiError.Network("菜单加载失败")) }
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect { events += it.merchantId }
        }

        viewModel.openMerchant(2)
        advanceUntilIdle()

        assertEquals(listOf(2L), events)
        assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
        assertTrue(viewModel.uiState.value.menu.isEmpty())
        assertEquals(null, viewModel.uiState.value.selectedDish)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("菜单加载失败", viewModel.uiState.value.error)
    }

    @Test
    // 模拟页面停止收集和重建：未确认请求仍可见，成功导航后的重新收集不能重复导航。
    fun unacknowledgedRequestSurvivesRecollectionUntilConsumed() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(), StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(1)
        runCurrent()
        val observed = mutableListOf<MerchantNavigationRequest>()
        val firstPage = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect { observed += it }
        }
        assertEquals(listOf(MerchantNavigationRequest(1, 1)), observed)
        firstPage.cancel()
        val destinations = mutableListOf<Long>()
        val secondPage = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                observed += it
                viewModel.consumeMerchantNavigation(it.requestId) { id -> destinations += id }
            }
        }
        assertEquals(listOf(MerchantNavigationRequest(1, 1), MerchantNavigationRequest(1, 1)), observed)
        assertEquals(listOf(1L), destinations)
        assertEquals(null, viewModel.merchantNavigationRequest.value)
        secondPage.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                viewModel.consumeMerchantNavigation(it.requestId) { id -> destinations += id }
            }
        }
        runCurrent()
        assertEquals(listOf(1L), destinations)
        viewModel.openMerchant(1)
        runCurrent()
        assertEquals(listOf(1L, 1L), destinations)
        assertEquals(null, viewModel.merchantNavigationRequest.value)
    }

    @Test
// 验证 `opensMerchantByIdWithoutSearchResults` 覆盖场景的预期行为与边界。
    fun opensMerchantByIdWithoutSearchResults() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))

        viewModel.openMerchant(2)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.merchants.isEmpty())
        assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
        assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(listOf("merchant:2", "menu:2"), repository.selectionCalls)
    }

    @Test
    // 暂停订阅者时连续打开商家；恢复后只能看到最新代次，菜单加载不再等待导航确认。
    fun supersededNavigationIsNotDeliveredWhenBlockedCollectorResumes() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        val releaseCollector = CompletableDeferred<Unit>()
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                events += it.merchantId
                if (events.size == 1) releaseCollector.await()
            }
        }
        viewModel.openMerchant(1)
        runCurrent()
        assertEquals(listOf(1L), events)

        viewModel.openMerchant(2)
        runCurrent()
        assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
        assertEquals(listOf("merchant:1", "menu:1", "merchant:2", "menu:2"), repository.selectionCalls)
        viewModel.openMerchant(1)
        runCurrent()
        assertEquals(1L, viewModel.uiState.value.selectedMerchant?.id)

        releaseCollector.complete(Unit)
        // 明确执行当前时刻的后台订阅任务；不得把最新事件缺失误判为调度尚未推进。
        runCurrent()

        assertEquals(listOf(1L, 1L), events)
        assertEquals(MerchantNavigationRequest(3, 1), viewModel.merchantNavigationRequest.value)
        assertEquals(listOf("merchant:1", "menu:1", "merchant:2", "menu:2", "merchant:1", "menu:1"), repository.selectionCalls)
        assertEquals(setOf(1L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    // 取消操作必须撤销尚未送达的导航，订阅者恢复后也不能打开已取消的商家。
    fun cancelledNavigationIsNotDeliveredWhenBlockedCollectorResumes() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        val releaseCollector = CompletableDeferred<Unit>()
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                events += it.merchantId
                if (events.size == 1) releaseCollector.await()
            }
        }
        viewModel.openMerchant(1)
        runCurrent()
        viewModel.openMerchant(2)
        runCurrent()
        assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
        assertEquals(listOf("merchant:1", "menu:1", "merchant:2", "menu:2"), repository.selectionCalls)

        viewModel.cancelMerchantSwitch()
        val cancelledState = viewModel.uiState.value
        releaseCollector.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L), events)
        assertEquals(cancelledState, viewModel.uiState.value)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(null, viewModel.merchantNavigationRequest.value)
    }

    @Test
    fun changingCartOwnerDiscardsNavigationWaitingForBlockedCollector() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        val releaseCollector = CompletableDeferred<Unit>()
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect {
                events += it.merchantId
                if (events.size == 1) releaseCollector.await()
            }
        }
        viewModel.openMerchant(1)
        runCurrent()
        viewModel.openMerchant(2)
        runCurrent()
        assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
        assertEquals(listOf("merchant:1", "menu:1", "merchant:2", "menu:2"), repository.selectionCalls)

        viewModel.switchCartOwner("另一用户")
        val switchedState = viewModel.uiState.value
        releaseCollector.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L), events)
        assertEquals(switchedState, viewModel.uiState.value)
        assertEquals(null, viewModel.uiState.value.selectedMerchant)
    }

    @Test
    fun staleConfirmationPreservesLookupAndMenuAlreadyInFlight() = runTest {
        // 分别停在查商家和加载菜单，防止只保留 isLoading 却仍递增代次的局部修复。
        for (duringLookup in listOf(true, false)) {
            val repository = FakeOrderingRepository()
            val release = CompletableDeferred<Unit>()
            val merchantLoader = repository.merchantLoader
            val menuLoader = repository.menuLoader
            repository.merchantLoader = { if (duringLookup) release.await(); merchantLoader(it) }
            repository.menuLoader = { if (!duringLookup) release.await(); menuLoader(it) }
            val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            val events = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.merchantNavigationRequest.filterNotNull().collect { events += it.merchantId }
            }
            viewModel.openMerchant(2)
            runCurrent()
            val openingState = viewModel.uiState.value
            assertTrue(openingState.isLoading)
            assertEquals(null, openingState.pendingMerchantSwitch)

            viewModel.confirmMerchantSwitch()
            assertEquals(openingState, viewModel.uiState.value)
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
            assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
            assertEquals(false, viewModel.uiState.value.isLoading)
            assertEquals(listOf(2L), events)
            assertEquals(listOf("merchant:2", "menu:2"), repository.selectionCalls)
        }
    }

    @Test
// 验证 `openingMerchantImmediatelyClearsPreviousMenuAndDish` 覆盖场景的预期行为与边界。
    fun openingMerchantImmediatelyClearsPreviousMenuAndDish() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(1)
        advanceUntilIdle()
        assertEquals(1L, viewModel.uiState.value.menu.single().merchantId)

        viewModel.openMerchant(2)

        assertTrue(viewModel.uiState.value.menu.isEmpty())
        assertEquals(null, viewModel.uiState.value.selectedDish)
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
    }

    @Test
// 验证 `failedLookupClearsPreviousSelectionAndDoesNotLoadMenu` 覆盖场景的预期行为与边界。
    fun failedLookupClearsPreviousSelectionAndDoesNotLoadMenu() = runTest {
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.search("粥")
        advanceUntilIdle()
        viewModel.openMerchant(1)
        advanceUntilIdle()
        repository.selectionCalls.clear()
        repository.merchantLoader = { ApiResult.Failure(ApiError.Http(404, "商家不存在")) }

        viewModel.openMerchant(1)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.selectedMerchant)
        assertTrue(viewModel.uiState.value.menu.isEmpty())
        assertEquals(null, viewModel.uiState.value.selectedDish)
        assertEquals("商家不存在", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(listOf("merchant:1"), repository.selectionCalls)

        viewModel.openMerchant(2)
        assertEquals(null, viewModel.uiState.value.error)
        advanceUntilIdle()
    }

    @Test
// 验证 `slowMerchantLookupCannotReplaceNewerSelectionOrError` 覆盖场景的预期行为与边界。
    fun slowMerchantLookupCannotReplaceNewerSelectionOrError() = runTest {
        for (slowResult in listOf(
            ApiResult.Success(Merchant(1, "旧商家", "粥", BigDecimal.TEN, BigDecimal.ONE, 20, true)),
            ApiResult.Failure(ApiError.Network("旧请求失败"))
        )) {
            val repository = FakeOrderingRepository()
            val first = CompletableDeferred<ApiResult<Merchant>>()
            val defaultLoader = repository.merchantLoader
            repository.merchantLoader = { if (it == 1L) first.await() else defaultLoader(it) }
            val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            val events = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.merchantNavigationRequest.filterNotNull().collect { events += it.merchantId }
            }
            viewModel.openMerchant(1)
            runCurrent()
            viewModel.openMerchant(2)
            advanceUntilIdle()
            val newerState = viewModel.uiState.value

            first.complete(slowResult)
            advanceUntilIdle()

            assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
            assertEquals(newerState, viewModel.uiState.value)
            assertEquals(listOf("merchant:1", "merchant:2", "menu:2"), repository.selectionCalls)
            assertEquals(listOf(2L), events)
        }
    }

    @Test
// 验证 `slowMenuCannotReplaceNewerSelectionOrError` 覆盖场景的预期行为与边界。
    fun slowMenuCannotReplaceNewerSelectionOrError() = runTest {
        for (fail in listOf(false, true)) {
            val repository = FakeOrderingRepository()
            val first = CompletableDeferred<ApiResult<List<Dish>>>()
            val defaultLoader = repository.menuLoader
            repository.menuLoader = { if (it == 1L) first.await() else defaultLoader(it) }
            val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            viewModel.openMerchant(1)
            runCurrent()
            viewModel.openMerchant(2)
            advanceUntilIdle()
            val newerState = viewModel.uiState.value

            first.complete(if (fail) ApiResult.Failure(ApiError.Timeout) else defaultLoader(1))
            advanceUntilIdle()

            assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
            assertEquals(newerState, viewModel.uiState.value)
        }
    }

    @Test
// 验证 `cancellingInvalidatesOutstandingLookupAndMenu` 覆盖场景的预期行为与边界。
    fun cancellingInvalidatesOutstandingLookupAndMenu() = runTest {
        for (duringLookup in listOf(true, false)) {
            val repository = FakeOrderingRepository()
            val release = CompletableDeferred<Unit>()
            val merchantLoader = repository.merchantLoader
            val menuLoader = repository.menuLoader
            repository.merchantLoader = { if (duringLookup) release.await(); merchantLoader(it) }
            repository.menuLoader = { if (!duringLookup) release.await(); menuLoader(it) }
            val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            viewModel.openMerchant(2)
            runCurrent()

            viewModel.cancelMerchantSwitch()
            val cancelled = viewModel.uiState.value
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isLoading)
            assertEquals(cancelled, viewModel.uiState.value)
        }
    }

    @Test
// 验证 `changingCartOwnerInvalidatesOutstandingMerchantRequest` 覆盖场景的预期行为与边界。
    fun changingCartOwnerInvalidatesOutstandingMerchantRequest() = runTest {
        val repository = FakeOrderingRepository()
        val release = CompletableDeferred<Unit>()
        val loader = repository.merchantLoader
        repository.merchantLoader = { release.await(); loader(it) }
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
        viewModel.openMerchant(1)
        runCurrent()

        viewModel.switchCartOwner("另一用户")
        val switched = viewModel.uiState.value
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(switched, viewModel.uiState.value)
        assertEquals(listOf("merchant:1"), repository.selectionCalls)
    }

    @Test
// 验证 `emptySearchResultsExposeEmptyState` 覆盖场景的预期行为与边界。
    fun emptySearchResultsExposeEmptyState() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(searchResult = ApiResult.Success(emptyList())), StandardTestDispatcher(testScheduler))

        viewModel.search("不存在")
        advanceUntilIdle()

        assertEquals(SearchStatus.Empty, viewModel.uiState.value.searchStatus)
    }

    @Test
// 验证 `unauthorizedSearchExposesAuthRequiredState` 覆盖场景的预期行为与边界。
    fun unauthorizedSearchExposesAuthRequiredState() = runTest {
        val viewModel = OrderingViewModel(FakeOrderingRepository(searchResult = ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(401, "login required"))), StandardTestDispatcher(testScheduler))

        viewModel.search("清淡")
        advanceUntilIdle()

        assertEquals(SearchStatus.AuthRequired, viewModel.uiState.value.searchStatus)
    }

    @Test
// 验证 `selectingMerchantOptionAddressAndPayingProducesCompletedOrderTimeline` 覆盖场景的预期行为与边界。
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
// 验证 `optionSelectionKeepsOneChoicePerGroupAndMergesRepeatedCartLine` 覆盖场景的预期行为与边界。
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
// 验证 `persistedCartIsRestoredForSameOwner` 覆盖场景的预期行为与边界。
    fun persistedCartIsRestoredForSameOwner() {
        val draft = CartDraft(200, 1, emptyList())
        val quote = CartQuote(200, BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), emptyList())
        val store = object : CartStateStore {
// 验证 `read` 覆盖场景的预期行为与边界。
            override fun read(owner: String) = CartSnapshot(owner, Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true), draft, quote)
// 验证 `write` 覆盖场景的预期行为与边界。
            override fun write(snapshot: CartSnapshot) = Unit
// 验证 `clear` 覆盖场景的预期行为与边界。
            override fun clear(owner: String) = Unit
        }

        val viewModel = OrderingViewModel(FakeOrderingRepository(), cartStore = store, cartOwner = "Cai")

        assertEquals(200L, viewModel.uiState.value.cartDraft?.id)
        assertEquals(BigDecimal("15"), viewModel.uiState.value.quote?.total)
    }

    @Test
// 验证 `switchingMerchantRequiresConfirmationBeforeCartIsCleared` 覆盖场景的预期行为与边界。
    fun switchingMerchantRequiresConfirmationBeforeCartIsCleared() = runTest {
        val first = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        val second = Merchant(2, "另一家店", "快餐", BigDecimal.TEN, BigDecimal("2"), 20, true)
        val draft = CartDraft(200, 1, emptyList())
        var cartClears = 0
        val store = object : CartStateStore {
// 验证 `read` 覆盖场景的预期行为与边界。
            override fun read(owner: String) = CartSnapshot(owner, first, draft, null)
// 验证 `write` 覆盖场景的预期行为与边界。
            override fun write(snapshot: CartSnapshot) = Unit
// 验证 `clear` 覆盖场景的预期行为与边界。
            override fun clear(owner: String) { cartClears += 1 }
        }
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(
            repository,
            StandardTestDispatcher(testScheduler),
            store,
            "Cai"
        )
        val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.merchantNavigationRequest.filterNotNull().collect { events += it.merchantId }
        }

        viewModel.openMerchant(2)
        advanceUntilIdle()
        assertEquals(second, viewModel.uiState.value.pendingMerchantSwitch)
        assertEquals(draft, viewModel.uiState.value.cartDraft)
        assertEquals(first, viewModel.uiState.value.selectedMerchant)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(0, cartClears)
        assertTrue(events.isEmpty())
        assertEquals(listOf("merchant:2"), repository.selectionCalls)
        viewModel.cancelMerchantSwitch()
        assertEquals(draft, viewModel.uiState.value.cartDraft)
        assertEquals(null, viewModel.uiState.value.pendingMerchantSwitch)
        assertEquals(0, cartClears)
        viewModel.openMerchant(2)
        advanceUntilIdle()
        viewModel.confirmMerchantSwitch()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.cartDraft)
        assertEquals(second, viewModel.uiState.value.selectedMerchant)
        assertEquals(null, viewModel.uiState.value.pendingMerchantSwitch)
        assertEquals(1, cartClears)
        assertEquals(listOf("merchant:2", "merchant:2", "menu:2"), repository.selectionCalls)
        assertEquals(listOf(2L), events)
        assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
    }

    @Test
// 验证 `newOpenDiscardsPreviousPendingSwitchBeforeConfirmation` 覆盖场景的预期行为与边界。
    fun newOpenDiscardsPreviousPendingSwitchBeforeConfirmation() = runTest {
        val first = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        val draft = CartDraft(200, 1, emptyList())
        val store = object : CartStateStore {
// 验证 `read` 覆盖场景的预期行为与边界。
            override fun read(owner: String) = CartSnapshot(owner, first, draft, null)
// 验证 `write` 覆盖场景的预期行为与边界。
            override fun write(snapshot: CartSnapshot) = Unit
// 验证 `clear` 覆盖场景的预期行为与边界。
            override fun clear(owner: String) = Unit
        }
        val repository = FakeOrderingRepository()
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler), store)
        viewModel.openMerchant(2)
        advanceUntilIdle()
        assertEquals(2L, viewModel.uiState.value.pendingMerchantSwitch?.id)

        viewModel.openMerchant(1)
        assertEquals(null, viewModel.uiState.value.pendingMerchantSwitch)
        val openingState = viewModel.uiState.value
        viewModel.confirmMerchantSwitch()
        assertEquals(openingState, viewModel.uiState.value)
        advanceUntilIdle()

        assertEquals(draft, viewModel.uiState.value.cartDraft)
        assertEquals(null, viewModel.uiState.value.pendingMerchantSwitch)
        assertEquals(1L, viewModel.uiState.value.selectedMerchant?.id)
        assertEquals(setOf(1L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(listOf("merchant:2", "merchant:1", "menu:1"), repository.selectionCalls)
    }

    @Test
// 验证 `confirmedSwitchIgnoresOldMenuResponse` 覆盖场景的预期行为与边界。
    fun confirmedSwitchIgnoresOldMenuResponse() = runTest {
        val first = Merchant(1, "清粥小菜", "粥汤", BigDecimal.TEN, BigDecimal("3"), 25, true)
        val draft = CartDraft(200, 1, emptyList())
        val store = object : CartStateStore {
// 验证 `read` 覆盖场景的预期行为与边界。
            override fun read(owner: String) = CartSnapshot(owner, first, draft, null)
// 验证 `write` 覆盖场景的预期行为与边界。
            override fun write(snapshot: CartSnapshot) = Unit
// 验证 `clear` 覆盖场景的预期行为与边界。
            override fun clear(owner: String) = Unit
        }
        val repository = FakeOrderingRepository()
        val oldMenu = CompletableDeferred<ApiResult<List<Dish>>>()
        val loader = repository.menuLoader
        repository.menuLoader = { if (it == 1L) oldMenu.await() else loader(it) }
        val viewModel = OrderingViewModel(repository, StandardTestDispatcher(testScheduler), store)
        viewModel.openMerchant(1)
        runCurrent()
        viewModel.openMerchant(2)
        advanceUntilIdle()
        viewModel.confirmMerchantSwitch()
        advanceUntilIdle()
        val confirmed = viewModel.uiState.value

        oldMenu.complete(loader(1))
        advanceUntilIdle()

        assertEquals(2L, confirmed.selectedMerchant?.id)
        assertEquals(null, confirmed.cartDraft)
        assertEquals(confirmed, viewModel.uiState.value)
    }

    @Test
// 验证 `validationFailureKeepsEditableLinesAndDuplicateOrderSubmissionIsIgnored` 覆盖场景的预期行为与边界。
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
// 验证 `orderRetryReusesTheSameIdempotencyKey` 覆盖场景的预期行为与边界。
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
// 验证 `paymentRetryKeepsPendingOrderAndReusesIdempotencyKey` 覆盖场景的预期行为与边界。
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
        val selectionCalls = mutableListOf<String>()
        var merchantLoader: suspend (Long) -> ApiResult<Merchant> = { id ->
            when (id) {
                1L -> ApiResult.Success(merchant)
                2L -> ApiResult.Success(Merchant(2, "另一家店", "快餐", BigDecimal.TEN, BigDecimal("2"), 20, true))
                else -> ApiResult.Failure(ApiError.Http(404, "商家不存在"))
            }
        }
        var menuLoader: suspend (Long) -> ApiResult<List<Dish>> = { id ->
            ApiResult.Success(listOf(dish.copy(id = id * 10, merchantId = id)))
        }

// 验证 `searchMerchants` 覆盖场景的预期行为与边界。
        override suspend fun searchMerchants(keyword: String) = searchResult
// 验证 `loadMerchant` 覆盖场景的预期行为与边界。
        override suspend fun loadMerchant(merchantId: Long): ApiResult<Merchant> {
            selectionCalls += "merchant:$merchantId"
            return merchantLoader(merchantId)
        }
// 验证 `loadMenu` 覆盖场景的预期行为与边界。
        override suspend fun loadMenu(merchantId: Long): ApiResult<List<Dish>> {
            selectionCalls += "menu:$merchantId"
            return menuLoader(merchantId)
        }
// 验证 `createCartDraft` 覆盖场景的预期行为与边界。
        override suspend fun createCartDraft(merchantId: Long) = ApiResult.Success(draft)
// 验证 `updateCart` 覆盖场景的预期行为与边界。
        override suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>): ApiResult<CartDraft> {
            lastItems = items
            val input = items.single()
            return ApiResult.Success(draft.copy(items = listOf(line.copy(quantity = input.quantity, optionIds = input.optionIds))))
        }
// 验证 `validateCart` 覆盖场景的预期行为与边界。
        override suspend fun validateCart(cartDraftId: Long): ApiResult<CartQuote> = if (failValidation) {
            ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(409, "库存不足"))
        } else {
            ApiResult.Success(CartQuote(cartDraftId, BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line.copy(quantity = lastItems.firstOrNull()?.quantity ?: 1))))
        }
// 验证 `loadAddresses` 覆盖场景的预期行为与边界。
        override suspend fun loadAddresses() = ApiResult.Success(listOf(address))
// 验证 `createOrder` 覆盖场景的预期行为与边界。
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
// 验证 `payOrder` 覆盖场景的预期行为与边界。
        override suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order> {
            payKey = idempotencyKey
            payKeys += idempotencyKey
            if (failPayOnce) {
                failPayOnce = false
                return ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("模拟支付失败"))
            }
            return ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))
        }
// 验证 `loadOrders` 覆盖场景的预期行为与边界。
        override suspend fun loadOrders() = ApiResult.Success(emptyList<Order>())
// 验证 `loadOrder` 覆盖场景的预期行为与边界。
        override suspend fun loadOrder(orderId: Long) = ApiResult.Success(order(OrderStatus.MERCHANT_ACCEPTED, listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED)))

// 验证 `order` 覆盖场景的预期行为与边界。
        private fun order(status: OrderStatus, timeline: List<OrderStatus>) = Order(300, status, "Cai", "13800000000", "公司默认地址", BigDecimal("12"), BigDecimal("3"), BigDecimal.ZERO, BigDecimal("15"), listOf(line), timeline)
    }
}
