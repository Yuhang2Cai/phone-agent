// 智能助手模块：验证关键行为与边界条件。
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
// 验证 `floatingAssistantIsLimitedToOrderingSurfaces` 覆盖场景的预期行为与边界。
    fun floatingAssistantIsLimitedToOrderingSurfaces() {
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.HOME))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.SEARCH))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.MERCHANT))
        assertTrue(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.ORDERS))
        assertFalse(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.AUTH))
        assertFalse(AgentFloatingButtonPolicy.isVisibleOn(AgentSurface.PAYMENT))
    }

    @Test
// 验证 `assistantOpensHalfScreenTogglesFullScreenAndCloses` 覆盖场景的预期行为与边界。
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
// 验证 `sendsTypedFoodRequestAndShowsConfirmationCardInConfirmMode` 覆盖场景的预期行为与边界。
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
// 验证 `savedStateRestoresSheetConversationAndConfirmationInANewViewModel` 覆盖场景的预期行为与边界。
    fun savedStateRestoresSheetConversationAndConfirmationInANewViewModel() = runTest {
        val savedState = SavedStateHandle()
        val repository = FakeAgentRepository(result = confirmationResult())
        val first = AgentViewModel(repository, savedState, StandardTestDispatcher(testScheduler))
        first.openAssistant()
        first.toggleFullScreen()
        first.sendText("帮我点清淡午餐")
        advanceUntilIdle()

        val restored = AgentViewModel(repository, savedState, StandardTestDispatcher(testScheduler))

        assertEquals(AgentSheetMode.FULL, restored.uiState.value.sheetMode)
        assertEquals("帮我点清淡午餐", restored.uiState.value.messages.first().text)
        assertEquals("清粥小菜", restored.uiState.value.confirmation?.merchantName)
    }

    @Test
// 验证 `asrResultRefillsComposerWithoutSending` 覆盖场景的预期行为与边界。
    fun asrResultRefillsComposerWithoutSending() {
        val repository = FakeAgentRepository()
        val viewModel = AgentViewModel(repository, SavedStateHandle())

        viewModel.onAsrResult("不要香菜")

        assertEquals("不要香菜", viewModel.uiState.value.composerText)
        assertEquals(null, repository.lastText)
    }

    @Test
// 验证 `streamedEventsAreShownAndReconnectCursorIsRetained` 覆盖场景的预期行为与边界。
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
// 验证 `confirmSendsExplicitConfirmationButCancelDoesNotCreateOrder` 覆盖场景的预期行为与边界。
    fun confirmSendsExplicitConfirmationButCancelDoesNotCreateOrder() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        viewModel.confirmOrder()
        advanceUntilIdle()
        assertEquals("确认下单", repository.lastText)

        viewModel.cancelOrder()
        advanceUntilIdle()
        assertEquals("取消下单", repository.lastText)
        assertEquals(null, viewModel.uiState.value.confirmation)
        assertTrue(viewModel.uiState.value.messages.any { it.fromUser && it.text.contains("取消") })
    }

    @Test
// 验证 `modifyCancelsExistingGatewayCandidateBeforeStartingReplacementRequest` 覆盖场景的预期行为与边界。
    fun modifyCancelsExistingGatewayCandidateBeforeStartingReplacementRequest() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        viewModel.modifyOrder()
        advanceUntilIdle()

        assertEquals("取消下单", repository.lastText)
        assertEquals(null, viewModel.uiState.value.confirmation)
        assertEquals("请修改订单：", viewModel.uiState.value.composerText)
    }

    @Test
// 验证 `taskAutoRequiresExplicitChoiceAndKeepsAddressAndAmountVisible` 覆盖场景的预期行为与边界。
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
// 验证 `choosingTaskAutoGrantsThenResumesWorkflowInAutoMode` 覆盖场景的预期行为与边界。
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
// 验证 `cancellingTaskAutoWaitsForServerRevocationBeforeClearingAuthorization` 覆盖场景的预期行为与边界。
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
// 验证 `amountLimitInRequestIsShownWhenGatewayCartOmitsIt` 覆盖场景的预期行为与边界。
    fun amountLimitInRequestIsShownWhenGatewayCartOmitsIt() = runTest {
        val original = confirmationResult().value
        val resultWithoutLimit = original.copy(candidateCart = original.candidateCart!!.copy(maxAmount = ""))
        val viewModel = AgentViewModel(FakeAgentRepository(result = ApiResult.Success(resultWithoutLimit)), SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.sendText("清淡午餐，30 元以内")
        advanceUntilIdle()

        assertEquals("¥30", viewModel.uiState.value.confirmation?.amountLimitText)
    }

    @Test
// 验证 `taskAutoGrantUsesNaturalLanguageBudgetWhenItIsStricterThanGatewayLimit` 覆盖场景的预期行为与边界。
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
// 验证 `firstMessageEstablishesSseAfterTheSessionExists` 覆盖场景的预期行为与边界。
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
// 验证 `failedGatewayResultShowsRetryableErrorAndClearsConfirmation` 覆盖场景的预期行为与边界。
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

        assertEquals(AgentError.Server("这次没有完成，请调整需求后重试"), viewModel.uiState.value.error)
        assertEquals(null, viewModel.uiState.value.confirmation)
        assertEquals(null, viewModel.uiState.value.candidateCart)
    }

    @Test
// 验证 `completedConfirmExposesCreatedOrderOnceForPaymentNavigation` 覆盖场景的预期行为与边界。
    fun completedConfirmExposesCreatedOrderOnceForPaymentNavigation() = runTest {
        val createdCart = (confirmationResult().value.candidateCart ?: error("cart")).copy(orderId = 42L)
        val repository = FakeAgentRepository(result = ApiResult.Success(
            AgentMessageResult(AgentStatus.COMPLETED, "订单已创建，请继续支付。", "payment_followup", createdCart, emptyList())
        ))
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))

        viewModel.sendText("确认下单")
        advanceUntilIdle()
        assertEquals(42L, viewModel.uiState.value.createdOrderId)

        viewModel.consumeCreatedOrder()
        assertEquals(null, viewModel.uiState.value.createdOrderId)
    }

    @Test
// 验证 `authenticationAndServerErrorsAreExposedWithRetryAction` 覆盖场景的预期行为与边界。
    fun authenticationAndServerErrorsAreExposedWithRetryAction() = runTest {
        val unauthorized = AgentViewModel(FakeAgentRepository(result = ApiResult.Failure(ApiError.Http(401, "Authentication is required"))), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        unauthorized.sendText("午餐")
        advanceUntilIdle()
        assertEquals(AgentError.Authentication, unauthorized.uiState.value.error)

        val unavailable = AgentViewModel(FakeAgentRepository(result = ApiResult.Failure(ApiError.Http(503, "Agent unavailable"))), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        unavailable.sendText("午餐")
        advanceUntilIdle()
        assertEquals(AgentError.Server("这次没有完成，请调整需求后重试"), unavailable.uiState.value.error)
    }

// 验证 `confirmationResult` 覆盖场景的预期行为与边界。
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

// 验证 `sendMessage` 覆盖场景的预期行为与边界。
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            lastText = text
            lastMode = mode
            return result
        }

// 验证 `streamEvents` 覆盖场景的预期行为与边界。
        override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> {
            streamStartCursor = afterEventId
            return kotlinx.coroutines.flow.flow { events.forEach { event -> emit(event) } }
        }

// 验证 `grantThisTaskAuto` 覆盖场景的预期行为与边界。
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> {
            grantRequests += 1
            lastGrantAmount = request.maxAmount
            return ApiResult.Success(Unit)
        }

// 验证 `revokeThisTaskAuto` 覆盖场景的预期行为与边界。
        override suspend fun revokeThisTaskAuto(sessionId: String): ApiResult<Unit> {
            revokeRequests += 1
            return ApiResult.Success(Unit)
        }
    }
}
