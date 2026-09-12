package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {
    @Test
    fun floatingAssistantIsLimitedToOrderingSurfaces() {
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.HOME))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.SEARCH))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.MERCHANT))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.ORDERS))
        assertFalse(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.AUTH))
        assertFalse(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.PAYMENT))
    }

    @Test
    fun assistantOpensHalfScreenTogglesFullScreenAndCloses() = runTest {
        val viewModel = AgentViewModel(FakeAgentRepository(), SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.openAssistant()
        assertEquals(AgentSheetMode.HALF, viewModel.uiState.value.sheetMode)

        viewModel.toggleFullScreen()
        assertEquals(AgentSheetMode.FULL, viewModel.uiState.value.sheetMode)

        viewModel.closeAssistant()
        assertEquals(AgentSheetMode.HIDDEN, viewModel.uiState.value.sheetMode)
    }

    @Test
    fun sendsTypedFoodRequestAndShowsConfirmationCardInConfirmMode() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.sendText("我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。")
        advanceUntilIdle()

        assertEquals("我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。", repository.lastText)
        assertEquals(AgentMode.CONFIRM, repository.lastMode)
        assertEquals("清粥小菜", viewModel.uiState.value.confirmation?.merchantName)
        assertEquals("¥30.00", viewModel.uiState.value.confirmation?.amountLimitText)
    }

    @Test
    fun asrResultRefillsComposerWithoutSending() {
        val repository = FakeAgentRepository()
        val viewModel = AgentViewModel(repository, SavedStateHandle())

        viewModel.onAsrResult("不要香菜")

        assertEquals("不要香菜", viewModel.uiState.value.composerText)
        assertEquals(null, repository.lastText)
    }

    @Test
    fun streamedEventsAreShownAndReconnectCursorIsRetained() = runTest {
        val event = AgentEvent(7L, "tool", "已筛选清淡菜品", "search_dishes", "completed")
        val repository = FakeAgentRepository(events = listOf(event))
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.openAssistant()
        advanceUntilIdle()

        assertEquals(listOf(event), viewModel.uiState.value.events)
        assertEquals(7L, viewModel.uiState.value.lastEventId)
        assertEquals(0L, repository.streamStartCursor)
    }

    @Test
    fun confirmSendsExplicitConfirmationButCancelDoesNotCreateOrder() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        viewModel.confirmOrder()
        advanceUntilIdle()
        assertEquals("确认下单", repository.lastText)

        viewModel.cancelOrder()
        assertEquals(null, viewModel.uiState.value.confirmation)
        assertTrue(viewModel.uiState.value.messages.last().text.contains("取消"))
    }

    @Test
    fun taskAutoRequiresExplicitChoiceAndKeepsAddressAndAmountVisible() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.authorization.isTaskAuto)
        viewModel.selectThisTaskAuto()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.authorization.isTaskAuto)
        assertEquals("公司默认地址", viewModel.uiState.value.confirmation?.address)
        assertEquals("¥30.00", viewModel.uiState.value.confirmation?.amountLimitText)
        assertEquals(1, repository.grantRequests)
    }

    @Test
    fun amountLimitInRequestIsShownWhenGatewayCartOmitsIt() = runTest {
        val original = confirmationResult().value
        val resultWithoutLimit = original.copy(candidateCart = original.candidateCart!!.copy(maxAmount = ""))
        val viewModel = AgentViewModel(FakeAgentRepository(result = ApiResult.Success(resultWithoutLimit)), SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.sendText("清淡午餐，30 元以内")
        advanceUntilIdle()

        assertEquals("¥30", viewModel.uiState.value.confirmation?.amountLimitText)
    }

    @Test
    fun authenticationAndServerErrorsAreExposedWithRetryAction() = runTest {
        val unauthorized = AgentViewModel(FakeAgentRepository(result = ApiResult.Failure(ApiError.Http(401, "Authentication is required"))), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        unauthorized.sendText("午餐")
        advanceUntilIdle()
        assertEquals(AgentError.Authentication, unauthorized.uiState.value.error)

        val unavailable = AgentViewModel(FakeAgentRepository(result = ApiResult.Failure(ApiError.Http(503, "Agent unavailable"))), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        unavailable.sendText("午餐")
        advanceUntilIdle()
        assertEquals(AgentError.Server("Agent unavailable"), unavailable.uiState.value.error)
    }

    private fun confirmationResult(): ApiResult.Success<AgentMessageResult> = ApiResult.Success(
        AgentMessageResult(
            status = AgentStatus.AWAITING_CONFIRMATION,
            message = "为你找到一份清淡午餐，请确认。",
            nextAction = "confirm",
            candidateCart = CandidateCart(
                merchantName = "清粥小菜",
                items = listOf(CandidateCartItem("南瓜小米粥", "去香菜", "¥12.00", 1)),
                address = "公司默认地址",
                deliveryFee = "¥3.00",
                discount = "-¥1.00",
                total = "¥14.00",
                maxAmount = "¥30.00",
                addressId = 9L
            ),
            events = emptyList()
        )
    )

    private class FakeAgentRepository(
        private val result: ApiResult<AgentMessageResult> = ApiResult.Success(
            AgentMessageResult(AgentStatus.COMPLETED, "好的", null, null, emptyList())
        ),
        private val events: List<AgentEvent> = emptyList()
    ) : AgentRepository {
        var lastText: String? = null
        var lastMode: AgentMode? = null
        var streamStartCursor: Long? = null
        var grantRequests = 0

        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            lastText = text
            lastMode = mode
            return result
        }

        override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> {
            streamStartCursor = afterEventId
            return kotlinx.coroutines.flow.flow { events.forEach(::emit) }
        }

        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> {
            grantRequests += 1
            return ApiResult.Success(Unit)
        }
    }
}
