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
    private val _uiState = MutableStateFlow(AgentUiState(lastEventId = savedState[LAST_EVENT_ID] ?: 0L))
    val uiState = _uiState.asStateFlow()
    private var eventsJob: Job? = null
    private val sessionId: String = savedState[SESSION_ID] ?: UUID.randomUUID().toString().also { savedState[SESSION_ID] = it }
    private var retryText: String? = null
    private var requestedAmountLimit: String? = null

    fun openAssistant() {
        _uiState.update { it.copy(sheetMode = AgentSheetMode.HALF, error = null) }
        if (savedState.get<Boolean>(SESSION_ESTABLISHED) == true && eventsJob?.isActive != true) subscribeEvents()
    }

    fun closeAssistant() {
        eventsJob?.cancel()
        eventsJob = null
        _uiState.update { it.copy(sheetMode = AgentSheetMode.HIDDEN) }
    }

    fun toggleFullScreen() = _uiState.update {
        it.copy(sheetMode = if (it.sheetMode == AgentSheetMode.FULL) AgentSheetMode.HALF else AgentSheetMode.FULL)
    }

    fun updateComposer(value: String) = _uiState.update { it.copy(composerText = value) }

    fun onAsrResult(text: String) = updateComposer(text)
    fun onAsrError(message: String) = _uiState.update { it.copy(error = AgentError.Asr(message)) }

    fun sendComposer() = sendText(_uiState.value.composerText)

    fun sendText(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        retryText = request
        requestedAmountLimit = request.requestedAmountLimit()
        _uiState.update { it.copy(composerText = "", messages = it.messages + ChatMessage(request, true), error = null, isSending = true) }
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

    fun confirmOrder() { sendText("确认下单") }

    fun cancelOrder() {
        if (!_uiState.value.authorization.isTaskAuto) {
            clearConfirmation("已取消本次下单。")
            return
        }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.revokeThisTaskAuto(sessionId)) {
                is ApiResult.Success -> clearConfirmation("已取消本次下单，自动授权已撤销。")
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun modifyOrder() = _uiState.update { it.copy(confirmation = null, composerText = "请修改订单：") }

    /** Called only by a visible, user-tapped choice in the confirmation card. */
    fun selectThisTaskAuto() {
        val card = _uiState.value.confirmation ?: return
        if (_uiState.value.authorization.isTaskAuto) return
        val amount = card.amountLimitText.toAmountOrNull() ?: run {
            _uiState.update { it.copy(error = AgentError.Server("未获得有效金额上限，不能开启本次自动授权")) }
            return
        }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.grantThisTaskAuto(
                ThisTaskAutoRequest(sessionId, card.addressId, amount, Instant.now().plusSeconds(15 * 60))
            )) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        authorization = AgentAuthorization(true, card.address, card.amountLimitText),
                        confirmation = card.copy(authorizationMode = AgentMode.THIS_TASK_AUTO)
                    )
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    private fun subscribeEvents() {
        eventsJob = viewModelScope.launch(dispatcher) {
            repository.streamEvents(sessionId, _uiState.value.lastEventId)
                .catch { error -> _uiState.update { it.copy(error = AgentError.Server(error.message ?: "事件连接中断")) } }
                .collect { appendEvent(it) }
        }
    }

    private fun applyResult(result: AgentMessageResult) {
        _uiState.update { state ->
            val allEvents = result.events.fold(state.events) { current, event -> current.addIfNew(event) }
            val cart = result.candidateCart
            when (result.status) {
                AgentStatus.FAILED -> state.copy(
                    isSending = false,
                    messages = state.messages + ChatMessage(result.message, false),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    confirmation = null,
                    candidateCart = null,
                    error = AgentError.Server(result.error ?: result.message)
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
                    candidateCart = null
                )
            }
        }
    }

    private fun appendEvent(event: AgentEvent) = _uiState.update { state ->
        val updated = state.events.addIfNew(event)
        state.copy(events = updated, lastEventId = event.id ?: state.lastEventId).also {
            savedState[LAST_EVENT_ID] = it.lastEventId
        }
    }

    private fun showError(error: ApiError) = _uiState.update {
        it.copy(isSending = false, error = when (error) {
            is ApiError.Http -> if (error.code == 401) AgentError.Authentication else AgentError.Server(error.message)
            is ApiError.Network -> AgentError.Network(error.message)
            ApiError.Timeout -> AgentError.Network("请求超时，请重试")
            ApiError.Storage -> AgentError.Server("本地会话不可用")
        })
    }

    private fun mode() = if (_uiState.value.authorization.isTaskAuto) AgentMode.THIS_TASK_AUTO else AgentMode.CONFIRM
    private fun List<AgentEvent>.addIfNew(event: AgentEvent) = if (event.id != null && any { it.id == event.id }) this else this + event
    private fun CandidateCart.toConfirmation() = OrderConfirmation(
        merchantName, items, address, deliveryFee, discount, total, requireNotNull(authorizationMode), maxAmount, addressId
    )
    private fun clearConfirmation(message: String) = _uiState.update {
        it.copy(confirmation = null, candidateCart = null, authorization = AgentAuthorization(), messages = it.messages + ChatMessage(message, false))
    }
    private fun String.toAmountOrNull(): BigDecimal? = replace(Regex("[^0-9.]"), "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    private fun String.requestedAmountLimit(): String? = Regex("(\\d+(?:\\.\\d+)?)\\s*元\\s*以内").find(this)?.groupValues?.get(1)?.let { "¥$it" }

    override fun onCleared() { eventsJob?.cancel(); super.onCleared() }

    private companion object {
        const val SESSION_ID = "agent_session_id"
        const val SESSION_ESTABLISHED = "agent_session_established"
        const val LAST_EVENT_ID = "agent_last_event_id"
    }
}
