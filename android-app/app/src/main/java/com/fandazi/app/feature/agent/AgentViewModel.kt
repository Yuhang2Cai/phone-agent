// 智能助手模块：协调界面状态与业务请求。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AgentViewModel(
    private val repository: AgentRepository,
    private val savedState: SavedStateHandle,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentUiState(
        sheetMode = savedState.get<String>(SHEET_MODE)?.let { runCatching { AgentSheetMode.valueOf(it) }.getOrNull() }
            ?: AgentSheetMode.HIDDEN,
        composerText = savedState[COMPOSER] ?: "",
        messages = savedState.get<ArrayList<ChatMessage>>(MESSAGES)?.toList().orEmpty(),
        events = savedState.get<ArrayList<AgentEvent>>(EVENTS)?.toList().orEmpty(),
        candidateCart = savedState[CANDIDATE_CART],
        confirmation = savedState[CONFIRMATION],
        authorization = savedState[AUTHORIZATION] ?: AgentAuthorization(),
        lastEventId = savedState[LAST_EVENT_ID] ?: 0L,
        createdOrderId = savedState[CREATED_ORDER_ID]
    ))
    val uiState = _uiState.asStateFlow()
    /**
     * 卡片导航请求的只读投影。
     *
     * 当前任务只允许用户点击后产生请求；模型事件、流式进度和普通文本都不会自动写入该值。
     */
    val navigation = _uiState.asStateFlow().map { it.navigation }
    private var eventsJob: Job? = null
    private val sessionId: String = savedState[SESSION_ID] ?: UUID.randomUUID().toString().also { savedState[SESSION_ID] = it }
    private var retryText: String? = null
    private var requestedAmountLimit: String? = null
    private var preferTaskAutoForNextConfirmation = false

// 打开assistant。
    fun openAssistant() {
        updateState { it.copy(sheetMode = AgentSheetMode.HALF, error = null) }
        if (savedState.get<Boolean>(SESSION_ESTABLISHED) == true && eventsJob?.isActive != true) subscribeEvents()
    }

// 关闭assistant。
    fun closeAssistant() {
        eventsJob?.cancel()
        eventsJob = null
        updateState { it.copy(sheetMode = AgentSheetMode.HIDDEN) }
    }

// 处理 `toggleFullScreen` 相关逻辑。
    fun toggleFullScreen() = updateState {
        it.copy(sheetMode = if (it.sheetMode == AgentSheetMode.FULL) AgentSheetMode.HALF else AgentSheetMode.FULL)
    }

// 更新composer。
    fun updateComposer(value: String) = updateState { it.copy(composerText = value) }

// 处理 `onRecordingStarted` 相关逻辑。
    fun onRecordingStarted() = updateState { it.copy(inputState = AgentInputState.RECORDING, error = null) }
// 处理 `onRecognizing` 相关逻辑。
    fun onRecognizing() = updateState { it.copy(inputState = AgentInputState.RECOGNIZING) }
// 处理 `onAsrResult` 相关逻辑。
    fun onAsrResult(text: String) = updateState { it.copy(composerText = text, inputState = AgentInputState.IDLE) }
// 处理 `onAsrError` 相关逻辑。
    fun onAsrError(message: String) = updateState { it.copy(inputState = AgentInputState.IDLE, error = AgentError.Asr(message)) }

// 发送composer。
    fun sendComposer() = sendText(_uiState.value.composerText)

// 发送文本。
    fun sendText(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        retryText = request
        requestedAmountLimit = request.requestedAmountLimit()
        updateState {
            it.copy(
                composerText = "",
                messages = it.messages + ChatMessage(request, true),
                events = emptyList(),
                error = null,
                isSending = true
            )
        }
        if (_uiState.value.sheetMode != AgentSheetMode.HIDDEN && eventsJob?.isActive != true) subscribeEvents()
        viewModelScope.launch(dispatcher) {
            when (val result = repository.sendMessage(sessionId, request, mode())) {
                is ApiResult.Success -> {
                    savedState[SESSION_ESTABLISHED] = true
                    applyResult(result.value)
                    if (_uiState.value.sheetMode != AgentSheetMode.HIDDEN && eventsJob?.isActive != true) subscribeEvents()
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 重试当前数据。
    fun retry() { retryText?.let(::sendText) }

// 处理 `consumeCreatedOrder` 相关逻辑。
    fun consumeCreatedOrder() = updateState { it.copy(createdOrderId = null) }

// 确认订单。
    fun confirmOrder() {
        if (preferTaskAutoForNextConfirmation && !_uiState.value.authorization.isTaskAuto) {
            preferTaskAutoForNextConfirmation = false
            selectThisTaskAuto()
        } else {
            sendText("确认下单")
        }
    }

// 处理 `preferThisTaskAuto` 相关逻辑。
    fun preferThisTaskAuto(maxAmount: BigDecimal) {
        preferTaskAutoForNextConfirmation = true
        requestedAmountLimit = "¥${maxAmount.stripTrailingZeros().toPlainString()}"
    }

// 取消订单。
    fun cancelOrder() {
        if (!_uiState.value.authorization.isTaskAuto) {
            cancelGatewayOrder(displayText = "取消本次下单")
            return
        }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.revokeThisTaskAuto(sessionId)) {
                is ApiResult.Success -> {
                    updateState { it.copy(authorization = AgentAuthorization()) }
                    cancelGatewayOrder(displayText = "取消本次下单")
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 处理 `modifyOrder` 相关逻辑。
    fun modifyOrder() = cancelGatewayOrder(
        displayText = "修改当前订单",
        composerAfter = "请修改订单："
    )

    /** 仅在用户点击确认卡片中的可见选项后调用。 */
    fun selectThisTaskAuto() {
        val card = _uiState.value.confirmation ?: return
        if (_uiState.value.authorization.isTaskAuto) return
        val amount = card.amountLimitText.toAmountOrNull() ?: run {
            updateState { it.copy(error = AgentError.Server("未获得有效金额上限，不能开启本次自动授权")) }
            return
        }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.grantThisTaskAuto(
                ThisTaskAutoRequest(sessionId, card.addressId, amount, Instant.now().plusSeconds(15 * 60))
            )) {
                is ApiResult.Success -> updateState {
                    it.copy(
                        authorization = AgentAuthorization(true, card.address, card.amountLimitText),
                        confirmation = card.copy(authorizationMode = AgentMode.THIS_TASK_AUTO)
                    )
                }.also { sendText("确认下单") }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 处理 `subscribeEvents` 相关逻辑。
    private fun subscribeEvents() {
        eventsJob = viewModelScope.launch(dispatcher) {
            repository.streamEvents(sessionId, _uiState.value.lastEventId)
                .catch {
                    if (!_uiState.value.isSending) {
                        updateState { state -> state.copy(error = AgentError.Server("AI 助手连接中断，请稍后重试")) }
                    }
                }
                .collect { appendEvent(it) }
        }
    }

// 应用结果。
    private fun applyResult(result: AgentMessageResult) {
        updateState { state ->
            val allEvents = state.events
            val cart = result.candidateCart?.withEffectiveAmountLimit()
            when (result.status) {
                AgentStatus.FAILED -> state.copy(
                    isSending = false,
                    messages = state.messages + ChatMessage(userFacingFailure(result.error ?: result.message), false),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    confirmation = null,
                    candidateCart = null,
                    error = AgentError.Server(userFacingFailure(result.error ?: result.message))
                )
                AgentStatus.AWAITING_CONFIRMATION -> state.copy(
                    isSending = false,
                    messages = state.messages + ChatMessage(result.message, false),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    candidateCart = cart,
                    confirmation = cart?.takeIf(CandidateCart::isCompleteForConfirmation)?.toConfirmation(),
                    error = if (cart?.isCompleteForConfirmation() == true) null else AgentError.Server("订单详情不完整，不能确认或开启自动授权")
                )
                else -> state.copy(
                    isSending = false,
                    messages = state.messages + ChatMessage(result.message, false),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    confirmation = null,
                    candidateCart = null,
                    createdOrderId = cart?.orderId.takeIf { result.nextAction == "payment_followup" },
                    error = null
                )
            }
        }
    }

// 处理 `appendEvent` 相关逻辑。
    private fun appendEvent(event: AgentEvent) = updateState { state ->
        val updated = state.events.addIfNew(event)
        state.copy(events = updated, lastEventId = event.id ?: state.lastEventId).also {
            savedState[LAST_EVENT_ID] = it.lastEventId
        }
    }

    /**
     * 接收流式事件并更新进度状态。
     *
     * 这里明确不产生页面导航请求，避免模型返回门店卡后未经用户点击就跳转页面。
     */
    fun onAgentEvent(event: AgentEvent) = appendEvent(event)

// 处理 `showError` 相关逻辑。
    private fun showError(error: ApiError) = updateState {
        it.copy(isSending = false, error = when (error) {
            is ApiError.Http -> if (error.code == 401) AgentError.Authentication else AgentError.Server(userFacingFailure(error.message))
            is ApiError.Network -> AgentError.Network("网络连接不稳定，请检查后重试")
            ApiError.Timeout -> AgentError.Network("请求超时，请重试")
            ApiError.Storage -> AgentError.Server("本地会话不可用")
        })
    }

// 处理 `mode` 相关逻辑。
    private fun mode() = if (_uiState.value.authorization.isTaskAuto) AgentMode.THIS_TASK_AUTO else AgentMode.CONFIRM
    private fun List<AgentEvent>.addIfNew(event: AgentEvent) = if (event.id != null && any { it.id == event.id }) this else this + event
    private fun CandidateCart.toConfirmation() = OrderConfirmation(
        merchantName, items, address, deliveryFee, discount, total, requireNotNull(authorizationMode), maxAmount, addressId
    )
    private fun CandidateCart.withEffectiveAmountLimit(): CandidateCart {
        val requested = requestedAmountLimit ?: return this
        val requestedAmount = requested.toAmountOrNull() ?: return this
        val serverAmount = maxAmount.toAmountOrNull()
        return if (serverAmount == null || requestedAmount < serverAmount) copy(maxAmount = requested) else this
    }
// 取消gateway、订单。
    private fun cancelGatewayOrder(displayText: String, composerAfter: String = "") {
        retryText = "取消下单"
        requestedAmountLimit = null
        preferTaskAutoForNextConfirmation = false
        updateState {
            it.copy(
                isSending = true,
                error = null,
                messages = it.messages + ChatMessage(displayText, true)
            )
        }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.sendMessage(sessionId, "取消下单", mode())) {
                is ApiResult.Success -> {
                    applyResult(result.value)
                    updateState {
                        it.copy(
                            isSending = false,
                            confirmation = null,
                            candidateCart = null,
                            authorization = AgentAuthorization(),
                            composerText = composerAfter
                        )
                    }
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 处理 `userFacingFailure` 相关逻辑。
    private fun userFacingFailure(raw: String): String = when {
        raw.contains("地址") -> "收货地址不可用，请检查后重试"
        raw.contains("库存") || raw.contains("下架") -> "菜品库存有变化，请重新选择"
        raw.contains("价格") || raw.contains("金额") -> "订单金额有变化，请重新确认"
        else -> "这次没有完成，请调整需求后重试"
    }
    private fun String.toAmountOrNull(): BigDecimal? = replace(Regex("[^0-9.]"), "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    private fun String.requestedAmountLimit(): String? = Regex("(\\d+(?:\\.\\d+)?)\\s*元\\s*以内").find(this)?.groupValues?.get(1)?.let { "¥$it" }

// 处理 `persistState` 相关逻辑。
    private fun persistState(state: AgentUiState) {
        savedState[SHEET_MODE] = state.sheetMode.name
        savedState[COMPOSER] = state.composerText
        savedState[MESSAGES] = ArrayList(state.messages)
        savedState[EVENTS] = ArrayList(state.events)
        savedState[CANDIDATE_CART] = state.candidateCart
        savedState[CONFIRMATION] = state.confirmation
        savedState[AUTHORIZATION] = state.authorization
        savedState[CREATED_ORDER_ID] = state.createdOrderId
    }

// 更新状态。
    private inline fun updateState(transform: (AgentUiState) -> AgentUiState) {
        _uiState.update(transform)
        persistState(_uiState.value)
    }

// 清理视图模型持有的任务与订阅。
    override fun onCleared() { eventsJob?.cancel(); super.onCleared() }

    private companion object {
        const val SESSION_ID = "agent_session_id"
        const val SESSION_ESTABLISHED = "agent_session_established"
        const val LAST_EVENT_ID = "agent_last_event_id"
        const val SHEET_MODE = "agent_sheet_mode"
        const val COMPOSER = "agent_composer"
        const val MESSAGES = "agent_messages"
        const val EVENTS = "agent_events"
        const val CANDIDATE_CART = "agent_candidate_cart"
        const val CONFIRMATION = "agent_confirmation"
        const val AUTHORIZATION = "agent_authorization"
        const val CREATED_ORDER_ID = "agent_created_order_id"
    }
}
