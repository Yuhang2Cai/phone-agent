// 智能助手模块：验证关键行为与边界条件。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {
    @Test
    /** 选券只在用户气泡显示末8位，网络和重试仍传完整服务端券ID，不把展示摘要当作操作资源。 */
    fun selectedVoucherBubbleUsesSuffixButRequestKeepsFullPublicId() = runTest {
        val id = "f56983f3-bd37-4e01-aed2-00b775244693"
        val repository = FakeAgentRepository(result = ApiResult.Success(AgentMessageResult(
            AgentStatus.NEEDS_INPUT, "请选择团购券", "choose_voucher", null, emptyList(), cards = listOf(
                AgentCardPayload("after_sale_voucher_choice", fields = mapOf("voucherId" to id, "canRefund" to "true"))
            ))))
        val vm = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.sendText("申请退款")
        advanceUntilIdle()
        repository.result = ApiResult.Success(AgentMessageResult(AgentStatus.NEEDS_INPUT, "请补充原因", "collect_refund_reason", null, emptyList()))
        vm.sendText(id)
        assertEquals("选择券号末8位：75244693", vm.uiState.value.messages.last().text)
        advanceUntilIdle()
        assertEquals(id, repository.lastText)
        vm.retry()
        advanceUntilIdle()
        assertEquals(id, repository.lastText)
        // 重试沿用同一用户气泡，但两次网络请求仍使用完整服务端券 ID。
        assertEquals(1, vm.uiState.value.messages.count { it.fromUser && it.text == "选择券号末8位：75244693" })
    }

    @Test
    /** SSE待输入必须停止活动指示并恢复输入，不等待尚未返回的HTTP回答，也不能自动导航。 */
    fun waitingInputEventStopsProgressWithoutFailureOrNavigation() = runTest {
        val pending = CompletableDeferred<ApiResult<AgentMessageResult>>()
        val repository = object : AgentRepository by FakeAgentRepository() {
            override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode) = pending.await()
        }
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.openAssistant()
        viewModel.sendText("我要申请退款")
        runCurrent()
        assertTrue(viewModel.uiState.value.isSending)
        viewModel.onAgentEvent(AgentEvent(8, "input_required", "请补充退款原因", status = "waiting_input"))
        assertFalse(viewModel.uiState.value.isSending)
        assertEquals(null, viewModel.uiState.value.error)
        assertEquals(null, viewModel.uiState.value.navigation)
        pending.complete(ApiResult.Success(AgentMessageResult(AgentStatus.NEEDS_INPUT, "请补充退款原因", "collect_refund_reason", null, emptyList())))
        advanceUntilIdle()
    }

    @Test
    /** 忙碌中双击只发送一次；待输入后新轮回答不能被上一轮迟到HTTP覆盖。 */
    fun duplicateSendIsBlockedAndLatePreviousReplyDoesNotReplaceNewTurn() = runTest {
        val replies = listOf(CompletableDeferred<ApiResult<AgentMessageResult>>(), CompletableDeferred())
        var calls = 0
        val repository = object : AgentRepository by FakeAgentRepository() {
            override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode) = replies[calls++].await()
        }
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("退款")
        viewModel.sendText("退款")
        runCurrent()
        assertEquals(1, calls)
        viewModel.onAgentEvent(AgentEvent(1, "input_required", "原因", status = "waiting_input"))
        viewModel.sendText("行程变更")
        runCurrent()
        replies[1].complete(ApiResult.Success(AgentMessageResult(AgentStatus.AWAITING_CONFIRMATION, "新轮确认", "confirm_refund", null, emptyList())))
        runCurrent()
        replies[0].complete(ApiResult.Success(AgentMessageResult(AgentStatus.NEEDS_INPUT, "旧轮待输入", "collect_refund_reason", null, emptyList())))
        advanceUntilIdle()
        assertEquals("新轮确认", viewModel.uiState.value.messages.last().text)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    /** 重连重放旧事件不能倒退游标，确认事件也是暂停而非失败。 */
    fun repeatedOldEventKeepsCursorAndConfirmationStopsProgress() = runTest {
        val viewModel = AgentViewModel(FakeAgentRepository(), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.onAgentEvent(AgentEvent(20, "confirmation_required", "核对后确认", status = "awaiting_confirmation"))
        viewModel.onAgentEvent(AgentEvent(12, "tool", "旧进度", status = "running"))
        assertEquals(20L, viewModel.uiState.value.lastEventId)
        assertFalse(viewModel.uiState.value.isSending)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    /** 版本化waiting_confirmation即使由通用progress承载，也必须暂停，不依赖某个兼容事件名。 */
    fun versionedWaitingConfirmationPausesEvenBeforeHttpCompletes() = runTest {
        val pending = CompletableDeferred<ApiResult<AgentMessageResult>>()
        val repository = object : AgentRepository by FakeAgentRepository() {
            override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode) = pending.await()
        }
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.sendText("申请退款")
        runCurrent()
        viewModel.onAgentEvent(AgentEvent(21, "progress", "核对后确认", status = "waiting_confirmation"))
        assertFalse(viewModel.uiState.value.isSending)
        assertEquals(null, viewModel.uiState.value.error)
        assertEquals(null, viewModel.uiState.value.navigation)
        pending.complete(ApiResult.Success(AgentMessageResult(AgentStatus.AWAITING_CONFIRMATION, "核对后确认", "confirm_refund", null, emptyList())))
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.confirmation)
    }
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
// 验证 `firstMessageStartsProgressStreamBeforeSendingRequest` 覆盖场景的预期行为与边界。
    fun firstMessageStartsProgressStreamBeforeSendingRequest() = runTest {
        val repository = FakeAgentRepository(result = confirmationResult())
        val viewModel = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        viewModel.openAssistant()

        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        assertTrue(repository.streamWasStartedWhenMessageSent)
    }

    @Test
    /** HTTP 事件作为 SSE 缺失时的历史回补保留；任务已结束，不能启动批量假进度动画。 */
    fun responseEventsAreRetainedWithoutStartingBulkProgressAnimation() = runTest {
        val responseEvents = listOf(
            AgentEvent(null, "parsed", "已识别请求意图。"),
            AgentEvent(null, "tool", "工具调用完成。", "search_merchants", "tool"),
            AgentEvent(null, "tool", "工具调用完成。", "create_cart_draft", "tool")
        )
        val result = AgentMessageResult(AgentStatus.COMPLETED, "已完成", null, null, responseEvents)
        val viewModel = AgentViewModel(
            FakeAgentRepository(result = ApiResult.Success(result)),
            SavedStateHandle(),
            StandardTestDispatcher(testScheduler)
        )
        viewModel.openAssistant()

        viewModel.sendText("帮我点午餐")
        advanceUntilIdle()

        assertEquals(responseEvents, viewModel.uiState.value.events)
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
// 验证 `successfulResponseClearsAnEarlyProgressStreamFailure` 覆盖场景的预期行为与边界。
    fun successfulResponseClearsAnEarlyProgressStreamFailure() = runTest {
        val repository = FakeAgentRepository(streamFailure = true)
        val viewModel = AgentViewModel(
            repository,
            SavedStateHandle(),
            StandardTestDispatcher(testScheduler)
        )
        viewModel.openAssistant()

        viewModel.sendText("30 元以内")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.error)
        assertEquals("好的", viewModel.uiState.value.messages.last().text)
    }

    @Test
// 验证 `progressPresentationShowsOnlyLatestStepWhileRequestIsRunning` 覆盖场景的预期行为与边界。
    fun progressPresentationShowsOnlyLatestStepWhileRequestIsRunning() {
        val events = listOf(
            AgentEvent(1L, "parsed", "已识别请求意图。"),
            AgentEvent(2L, "tool", "正在执行工具", "search_merchants", "running"),
            AgentEvent(3L, "tool", "正在执行工具", "create_cart_draft", "running")
        )

        assertEquals("📋  正在准备订单", AgentProgressPresentation.current(events, isSending = true))
        assertEquals(null, AgentProgressPresentation.current(events, isSending = false))
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
        private val events: List<AgentEvent> = emptyList(),
        private val streamFailure: Boolean = false
    ) : AgentRepository {
        var lastText: String? = null
        var lastMode: AgentMode? = null
        var streamStartCursor: Long? = null
        var streamWasStartedWhenMessageSent = false
        private var streamStarted = false
        var grantRequests = 0
        var revokeRequests = 0
        var lastGrantAmount: java.math.BigDecimal? = null

// 验证 `sendMessage` 覆盖场景的预期行为与边界。
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            streamWasStartedWhenMessageSent = streamStarted
            lastText = text
            lastMode = mode
            return result
        }

// 验证 `streamEvents` 覆盖场景的预期行为与边界。
        override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> {
            streamStarted = true
            streamStartCursor = afterEventId
            return kotlinx.coroutines.flow.flow {
                if (streamFailure) error("session is not established yet")
                events.forEach { event -> emit(event) }
            }
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
