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
        viewModel.sendText("帮我点午餐")
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
    fun choosingTaskAutoGrantsThenResumesWorkflowInAutoMode() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()
        repository.result = ApiResult.Success(
            AgentMessageResult(AgentStatus.COMPLETED, "订单已创建并完成模拟支付。", "completed", null, emptyList())
        )

        viewModel.selectThisTaskAuto()
        advanceUntilIdle()

        assertEquals(1, repository.grantRequests)
        assertEquals("确认下单", repository.lastText)
        assertEquals(AgentMode.THIS_TASK_AUTO, repository.lastMode)
        assertEquals(null, viewModel.uiState.value.confirmation)
    }

    @Test
    fun cancellingTaskAutoWaitsForServerRevocationBeforeClearingAuthorization() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()
        viewModel.selectThisTaskAuto()
        advanceUntilIdle()

        viewModel.cancelOrder()
        advanceUntilIdle()

        assertEquals(1, repository.revokeRequests)
        assertFalse(viewModel.uiState.value.authorization.isTaskAuto)
        assertEquals(null, viewModel.uiState.value.confirmation)
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
    fun taskAutoGrantUsesNaturalLanguageBudgetWhenItIsStricterThanGatewayLimit() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("清淡午餐，20 元以内")
        advanceUntilIdle()
        repository.result = ApiResult.Success(
            AgentMessageResult(AgentStatus.COMPLETED, "订单已创建并完成模拟支付。", "completed", null, emptyList())
        )

        viewModel.selectThisTaskAuto()
        advanceUntilIdle()

        assertEquals("20", repository.lastGrantAmount?.stripTrailingZeros()?.toPlainString())
    }

    @Test
    fun firstMessageEstablishesSseAfterTheSessionExists() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.openAssistant()
        assertEquals(null, repository.streamStartCursor)

        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        assertEquals(0L, repository.streamStartCursor)
    }

    @Test
    fun failedGatewayResultShowsRetryableErrorAndClearsConfirmation() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        repository.result = ApiResult.Success(
            AgentMessageResult(AgentStatus.FAILED, "请求未完成。", "retry", null, emptyList(), "服务暂不可用")
        )
        viewModel.sendText("重试")
        advanceUntilIdle()

        assertEquals(AgentError.Server("服务暂不可用"), viewModel.uiState.value.error)
        assertEquals(null, viewModel.uiState.value.confirmation)
        assertEquals(null, viewModel.uiState.value.candidateCart)
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
                addressId = 9L,
                authorizationMode = AgentMode.CONFIRM
            ),
            events = emptyList()
        )
    )

    private class FakeAgentRepository(
        var result: ApiResult<AgentMessageResult> = ApiResult.Success(
            AgentMessageResult(AgentStatus.COMPLETED, "好的", null, null, emptyList())
        ),
        private val events: List<AgentEvent> = emptyList()
    ) : AgentRepository {
        var lastText: String? = null
        var lastMode: AgentMode? = null
        var streamStartCursor: Long? = null
        var grantRequests = 0
        var revokeRequests = 0
        var lastGrantAmount: java.math.BigDecimal? = null

        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            lastText = text
            lastMode = mode
            return result
        }

        override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> {
            streamStartCursor = afterEventId
            return kotlinx.coroutines.flow.flow { events.forEach { event -> emit(event) } }
        }

        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> {
            grantRequests += 1
            lastGrantAmount = request.maxAmount
            return ApiResult.Success(Unit)
        }

        override suspend fun revokeThisTaskAuto(sessionId: String): ApiResult<Unit> {
            revokeRequests += 1
            return ApiResult.Success(Unit)
        }
    }
}
