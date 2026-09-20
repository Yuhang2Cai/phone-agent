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
    private var eventsJob: Job? = null
    private val sessionId: String = savedState[SESSION_ID] ?: UUID.randomUUID().toString().also { savedState[SESSION_ID] = it }
    private var retryText: String? = null
    private var requestedAmountLimit: String? = null
    private var preferTaskAutoForNextConfirmation = false

    fun openAssistant() {
        updateState { it.copy(sheetMode = AgentSheetMode.HALF, error = null) }
        if (savedState.get<Boolean>(SESSION_ESTABLISHED) == true && eventsJob?.isActive != true) subscribeEvents()
    }

    fun closeAssistant() {
        eventsJob?.cancel()
        eventsJob = null
        updateState { it.copy(sheetMode = AgentSheetMode.HIDDEN) }
    }

    fun toggleFullScreen() = updateState {
        it.copy(sheetMode = if (it.sheetMode == AgentSheetMode.FULL) AgentSheetMode.HALF else AgentSheetMode.FULL)
    }

    fun updateComposer(value: String) = updateState { it.copy(composerText = value) }

    fun onRecordingStarted() = updateState { it.copy(inputState = AgentInputState.RECORDING, error = null) }
    fun onRecognizing() = updateState { it.copy(inputState = AgentInputState.RECOGNIZING) }
    fun onAsrResult(text: String) = updateState { it.copy(composerText = text, inputState = AgentInputState.IDLE) }
    fun onAsrError(message: String) = updateState { it.copy(inputState = AgentInputState.IDLE, error = AgentError.Asr(message)) }

    fun sendComposer() = sendText(_uiState.value.composerText)

    fun sendText(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        retryText = request
        requestedAmountLimit = request.requestedAmountLimit()
        updateState { it.copy(composerText = "", messages = it.messages + ChatMessage(request, true), error = null, isSending = true) }
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

    fun retry() { retryText?.let(::sendText) }

    fun consumeCreatedOrder() = updateState { it.copy(createdOrderId = null) }

    fun confirmOrder() {
        if (preferTaskAutoForNextConfirmation && !_uiState.value.authorization.isTaskAuto) {
            preferTaskAutoForNextConfirmation = false
            selectThisTaskAuto()
        } else {
            sendText("确认下单")
        }
    }

    fun preferThisTaskAuto(maxAmount: BigDecimal) {
        preferTaskAutoForNextConfirmation = true
        requestedAmountLimit = "¥${maxAmount.stripTrailingZeros().toPlainString()}"
    }

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

    fun modifyOrder() = cancelGatewayOrder(
        displayText = "修改当前订单",
        composerAfter = "请修改订单："
    )

    /** Called only by a visible, user-tapped choice in the confirmation card. */
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

    private fun subscribeEvents() {
        eventsJob = viewModelScope.launch(dispatcher) {
            repository.streamEvents(sessionId, _uiState.value.lastEventId)
                .catch { updateState { it.copy(error = AgentError.Server("AI 助手连接中断，请稍后重试")) } }
                .collect { appendEvent(it) }
        }
    }

    private fun applyResult(result: AgentMessageResult) {
        updateState { state ->
            val allEvents = result.events.fold(state.events) { current, event -> current.addIfNew(event) }
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
                    createdOrderId = cart?.orderId.takeIf { result.nextAction == "payment_followup" }
                )
            }
        }
    }

    private fun appendEvent(event: AgentEvent) = updateState { state ->
        val updated = state.events.addIfNew(event)
        state.copy(events = updated, lastEventId = event.id ?: state.lastEventId).also {
            savedState[LAST_EVENT_ID] = it.lastEventId
        }
    }

    private fun showError(error: ApiError) = updateState {
        it.copy(isSending = false, error = when (error) {
            is ApiError.Http -> if (error.code == 401) AgentError.Authentication else AgentError.Server(userFacingFailure(error.message))
            is ApiError.Network -> AgentError.Network("网络连接不稳定，请检查后重试")
            ApiError.Timeout -> AgentError.Network("请求超时，请重试")
            ApiError.Storage -> AgentError.Server("本地会话不可用")
        })
    }

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

    private fun userFacingFailure(raw: String): String = when {
        raw.contains("地址") -> "收货地址不可用，请检查后重试"
        raw.contains("库存") || raw.contains("下架") -> "菜品库存有变化，请重新选择"
        raw.contains("价格") || raw.contains("金额") -> "订单金额有变化，请重新确认"
        else -> "这次没有完成，请调整需求后重试"
    }
    private fun String.toAmountOrNull(): BigDecimal? = replace(Regex("[^0-9.]"), "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    private fun String.requestedAmountLimit(): String? = Regex("(\\d+(?:\\.\\d+)?)\\s*元\\s*以内").find(this)?.groupValues?.get(1)?.let { "¥$it" }

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

    private inline fun updateState(transform: (AgentUiState) -> AgentUiState) {
        _uiState.update(transform)
        persistState(_uiState.value)
    }

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
