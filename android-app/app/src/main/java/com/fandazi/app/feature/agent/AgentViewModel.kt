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
import kotlinx.coroutines.cancelChildren
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
        createdOrderId = savedState[CREATED_ORDER_ID],
        viewedCard = savedState[VIEWED_CARD],
        pendingConfirmationCard = savedState[PENDING_CONFIRMATION_CARD],
        pendingAfterSaleCards = savedState.get<ArrayList<AgentCardPayload>>(PENDING_AFTER_SALE_CARDS)?.toList().orEmpty()
    ))
    val uiState = _uiState.asStateFlow()
    /**
     * 卡片导航请求的只读投影。
     *
     * 当前任务只允许用户点击后产生请求；模型事件、流式进度和普通文本都不会自动写入该值。
     */
    val navigation = _uiState.asStateFlow().map { it.navigation }
    private var eventsJob: Job? = null
    /** HTTP可能晚于待输入SSE返回；轮次编号保证旧回答不能覆盖用户已经开始的新一轮。 */
    private var requestGeneration = 0L
    private var sessionId: String = savedState[SESSION_ID] ?: UUID.randomUUID().toString().also { savedState[SESSION_ID] = it }
    private var retryText: String? = null
    /** 重试保留公开资源摘要气泡；完整资源ID只用于原始请求，不因展示优化被截断。 */
    private var retryDisplayText: String? = null
    private var requestedAmountLimit: String? = null
    private var preferTaskAutoForNextConfirmation = false

    /**
     * 将 Activity 级聊天状态绑定到当前认证账号；null 表示退出或游客，不向服务器修改归属。
     * 同账号页面返回与配置重建保留历史；账号变化取消本地旧请求，换会话并清除旧私有摘要。
     */
    fun bindAuthenticatedOwner(owner: String?) {
        if (savedState.get<String>(SESSION_OWNER) == owner) return
        // 旧账号迟到的 HTTP/SSE 不能回写新账号状态；取消仅影响客户端，不提交任何业务操作。
        ++requestGeneration
        viewModelScope.coroutineContext.cancelChildren()
        eventsJob = null
        retryText = null
        retryDisplayText = null
        requestedAmountLimit = null
        preferTaskAutoForNextConfirmation = false
        sessionId = UUID.randomUUID().toString()
        savedState[SESSION_ID] = sessionId
        savedState[SESSION_OWNER] = owner
        savedState[SESSION_ESTABLISHED] = false
        updateState { AgentUiState() }
    }

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

    /** 非空输入启动一轮公开请求；忙碌时拒绝重复点击，待输入事件解除忙碌后允许继续对话。 */
    fun sendText(text: String, displayText: String? = null) {
        sendRequest(text, displayText, appendUser = true)
    }

    /**
     * 卡片动作入口二次校验原摘要、原run/trace/事件版本与当前等待阶段，返回是否实际接受。
     * 拒绝旧卡不追加问题、不发网络请求、不清当前摘要；普通输入仍由sendText处理。
     */
    fun replyToCard(request: AgentNavigationRequest.AgentReply): Boolean {
        val card = request.sourceCard ?: return false
        if (!isCurrentReplyCard(card)) return false
        // 按卡片种类限定按钮语句，避免用退款摘要发送投诉命令或替换所选公开券号。
        val allowed = when (card.type) {
            "confirmation" -> setOf(if(card.fields["action"] == "pay_mock_order") "确认支付" else "确认", "取消")
            "after_sale_refund" -> setOf("确认退款", "取消退款")
            "after_sale_complaint" -> setOf("确认投诉", "取消投诉")
            "after_sale_voucher_choice" -> setOf(card.fields["voucherId"])
            else -> emptySet()
        }
        if (request.text !in allowed) return false
        sendText(request.text)
        return true
    }

    /** 所有回复按钮共用当前版本检查；售后候选允许多张，但每张必须属于同一最新等待事件。 */
    fun isCurrentReplyCard(card: AgentCardPayload?): Boolean {
        if (card?.type == "confirmation") return isCurrentConfirmation(card)
        val state = _uiState.value
        if (card == null || card.confirmationIdentity == null || state.isSending || state.error != null ||
            card !in state.pendingAfterSaleCards) return false
        val fields = card.fields
        // 选券只接受服务端明确资格；确认还要求完整摘要及真实截止时间，不以客户端推算替代。
        if (card.type == "after_sale_voucher_choice") return fields["canRefund"] == "true" && !fields["voucherId"].isNullOrBlank()
        val expiry = runCatching { java.time.OffsetDateTime.parse(fields["expiresAt"]).toInstant() }.getOrNull()
        if (expiry?.isAfter(Instant.now()) != true || fields["ruleVersion"].isNullOrBlank()) return false
        return when (card.type) {
            "after_sale_refund" -> (!fields["resourceId"].isNullOrBlank() || !fields["voucherId"].isNullOrBlank()) &&
                !fields["dealTitle"].isNullOrBlank() && !fields["reason"].isNullOrBlank() &&
                fields["amountCents"]?.toLongOrNull()?.let { it >= 0 } == true
            "after_sale_complaint" -> !fields["subject"].isNullOrBlank() && !fields["description"].isNullOrBlank()
            else -> false
        }
    }

    /** 判断按钮此刻是否仍属于有效等待摘要；完整字段相等防止同事件被替换金额/资源后误确认。 */
    fun isCurrentConfirmation(card: AgentCardPayload?): Boolean {
        val state = _uiState.value
        if (card == null || card.type != "confirmation" || card.confirmationIdentity == null ||
            state.isSending || state.error != null || state.pendingConfirmationCard != card) return false
        val fields = card.fields
        val expiry = runCatching { java.time.OffsetDateTime.parse(fields["expiresAt"]).toInstant() }.getOrNull()
        // 缺摘要、未知动作、无效金额或过期均失效，不能只因历史按钮仍存在就发送泛化命令。
        return !fields["resourceId"].isNullOrBlank() && !fields["ruleVersion"].isNullOrBlank() &&
            !fields["title"].isNullOrBlank() && fields["amountCents"]?.toLongOrNull()?.let { it >= 0 } == true &&
            fields["action"] in setOf("create_group_buy_order", "pay_mock_order") && expiry?.isAfter(Instant.now()) == true
    }

    /** 从现有公开事件给准备卡绑定原版本；缺任何身份时保留展示但不开放共享确认动作。 */
    private fun bindConfirmationCards(cards: List<AgentCardPayload>, events: List<AgentEvent>): List<AgentCardPayload> {
        return cards.map { card ->
            // 普通只读卡不绑定动作身份；选券绑定待输入事件，确认绑定待确认事件。
            val eventType = when(card.type) {
                "after_sale_voucher_choice" -> "input_required"
                "confirmation", "after_sale_refund", "after_sale_complaint" -> "confirmation_required"
                else -> return@map card
            }
            val event = events.lastOrNull { it.type == eventType && !it.runId.isNullOrBlank() &&
                !it.traceId.isNullOrBlank() && !it.eventId.isNullOrBlank() }
            val identity = event?.let { AgentConfirmationIdentity(requireNotNull(it.runId), requireNotNull(it.traceId), requireNotNull(it.eventId), it.sequence) }
            card.copy(confirmationIdentity = identity)
        }
    }

    /** 同一失败轮重试沿用原问题气泡；正常新轮仍追加用户输入并清空当前临时进度。 */
    private fun sendRequest(text: String, displayText: String?, appendUser: Boolean) {
        val request = text.trim()
        if (request.isEmpty() || _uiState.value.isSending) return
        // 仅当输入完整匹配当前服务端候选券时显示中文末位摘要，普通输入不解析或猜测资源ID。
        val publicDisplay = displayText ?: if (_uiState.value.cards.any {
            it.type == "after_sale_voucher_choice" && it.fields["voucherId"] == request
        }) "选择券号末8位：${request.takeLast(8).uppercase()}" else request
        val generation = ++requestGeneration
        retryText = request
        retryDisplayText = publicDisplay
        requestedAmountLimit = request.requestedAmountLimit()
        updateState {
            it.copy(
                composerText = "",
                messages = if (appendUser) it.messages + ChatMessage(publicDisplay, true)
                    else it.messages.filterNot { message -> message.streaming },
                // 当前卡片清空；历史卡片已随对应助手消息保存，不会丢失或被误认为本轮新结果。
                cards = emptyList(),
                // 新输入/重试立即撤销旧版本的客户端可执行状态，哪怕服务端沿用相同runId重新准备。
                pendingConfirmationCard = null,
                pendingAfterSaleCards = emptyList(),
                events = emptyList(),
                error = null,
                isSending = true
            )
        }
        if (_uiState.value.sheetMode != AgentSheetMode.HIDDEN && eventsJob?.isActive != true) subscribeEvents()
        viewModelScope.launch(dispatcher) {
            when (val result = repository.sendMessage(sessionId, request, mode())) {
                is ApiResult.Success -> {
                    if (generation != requestGeneration) return@launch
                    savedState[SESSION_ESTABLISHED] = true
                    applyResult(result.value)
                    // 仓库负责同一流的断线重连；HTTP完成后不重复订阅，避免旧流错误覆盖成功回答。
                }
                is ApiResult.Failure -> if (generation == requestGeneration) showError(result.error)
            }
        }
    }

// 重试当前数据。
    fun retry() { retryText?.let { sendRequest(it, retryDisplayText, appendUser = false) } }

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

    /** 每次打开或新轮只建立一个订阅；传输异常不把已暂停/完成的业务回答改成失败。 */
    private fun subscribeEvents() {
        eventsJob = viewModelScope.launch(dispatcher) {
            repository.streamEvents(sessionId, _uiState.value.lastEventId)
                .catch {
                    if (_uiState.value.isSending) {
                        // 当前传输中断时停止动画并允许重试；同轮 HTTP 若成功可回补，重试后编号阻止旧轮覆盖。
                        updateState { state -> state.copy(isSending = false, error = AgentError.Server("AI 助手连接中断，请稍后重试")) }
                    }
                }
                .collect { appendEvent(it) }
        }
    }

// 应用结果。
    private fun applyResult(result: AgentMessageResult) {
        updateState { state ->
            // HTTP 是 SSE 暂未连接时的可靠回补；原事件身份去重，不能把 sequence 当成 SSE 游标。
            val allEvents = result.events.fold(state.events) { events, event -> events.addIfNew(event) }
            val cart = result.candidateCart?.withEffectiveAmountLimit()
            val cards = bindConfirmationCards(result.cards, result.events)
            when (result.status) {
                AgentStatus.FAILED -> state.copy(
                    isSending = false,
                    // 失败只渲染当前错误卡，不额外创建失败气泡；重试成功不会留下重复的错误回答。
                    messages = state.messages.filterNot { it.streaming },
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    confirmation = null,
                    candidateCart = null,
                    cards = cards,
                    pendingConfirmationCard = null,
                    pendingAfterSaleCards = emptyList(),
                    error = AgentError.Server(userFacingFailure(result.error ?: result.message))
                )
                AgentStatus.AWAITING_CONFIRMATION -> state.copy(
                    isSending = false,
                    messages = state.messages.withAssistantReply(result.message, cards, allEvents),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    candidateCart = cart,
                    cards = cards,
                    pendingConfirmationCard = cards.lastOrNull { it.type == "confirmation" && it.confirmationIdentity != null },
                    pendingAfterSaleCards = cards.filter { it.type in setOf("after_sale_refund", "after_sale_complaint") && it.confirmationIdentity != null },
                    confirmation = cart?.takeIf(CandidateCart::isCompleteForConfirmation)?.toConfirmation(),
                    // 售后确认不用外卖购物车字段；其公开摘要已转成独立卡片，凭证留在 Agent 会话内。
                    error = if (result.cards.any { it.type == "confirmation" } || result.nextAction in setOf("confirm_refund", "confirm_complaint") ||
                        cart?.isCompleteForConfirmation() == true) null
                    else AgentError.Server("订单详情不完整，不能确认或开启自动授权")
                )
                else -> state.copy(
                    isSending = false,
                    messages = state.messages.withAssistantReply(result.message, cards, allEvents),
                    events = allEvents,
                    lastEventId = allEvents.mapNotNull(AgentEvent::id).maxOrNull() ?: state.lastEventId,
                    confirmation = null,
                    candidateCart = null,
                    cards = cards,
                    pendingConfirmationCard = null,
                    pendingAfterSaleCards = if (result.status == AgentStatus.NEEDS_INPUT)
                        cards.filter { it.type == "after_sale_voucher_choice" && it.confirmationIdentity != null } else emptyList(),
                    createdOrderId = cart?.orderId.takeIf { result.nextAction == "payment_followup" },
                    error = null
                )
            }
        }
    }

    /** 按单调游标接收公开事件；待输入和待确认都是正常暂停，不是失败或自动导航指令。 */
    private fun appendEvent(event: AgentEvent) = updateState { state ->
        if (event.id != null && event.id <= (state.lastEventId ?: 0L)) return@updateState state
        val identity = state.pendingConfirmationCard?.confirmationIdentity ?: state.pendingAfterSaleCards.firstOrNull()?.confirmationIdentity
        val samePendingTrace = identity != null && identity.runId == event.runId && identity.traceId == event.traceId
        // 已归档的等待回复仍可能收到真正终态；只撤销其自身版本，不用旧run终态关闭另一个当前摘要。
        val closesPending = samePendingTrace && event.type != "tool_result" &&
            (event.status?.lowercase() in setOf("succeeded", "completed", "failed", "cancelled") || event.type in setOf("error", "failed"))
        val replacesPending = samePendingTrace && event.type in setOf("confirmation_required", "input_required") && event.eventId != identity?.eventId &&
            event.sequence != null && identity?.sequence != null && event.sequence!! > identity.sequence
        // 已归档回复的迟到/重放事件只推进数据库游标，不得被挪到正在输入的新轮。
        if (!replacesPending && event.traceId != null && state.messages.any { message -> message.events.any { it.traceId == event.traceId } })
            return@updateState state.copy(lastEventId = event.id ?: state.lastEventId,
                pendingConfirmationCard = if (closesPending) null else state.pendingConfirmationCard,
                pendingAfterSaleCards = if (closesPending) emptyList() else state.pendingAfterSaleCards)
        val updated = state.events.addIfNew(event)
        // SSE 游标可推进，但重复的原事件不能再次追加文本或卡片。
        if (updated.size == state.events.size) return@updateState state.copy(lastEventId = event.id ?: state.lastEventId)
        // 网关兼容工具审计没有原 eventId，其 status 是工具结果而非任务状态，不能据此提前结束动画。
        // 原 Agent 事件仍原样使用任务 status；明确的旧 completed/failed 类型继续兼容，不修改 SSE 游标。
        val compatibilityToolAudit = event.type == "tool_result" && event.eventId == null
        val paused = event.type.lowercase() in setOf("input_required", "confirmation_required") ||
            (!compatibilityToolAudit && event.status?.lowercase() in setOf("waiting_input", "needs_input", "waiting_confirmation", "awaiting_confirmation"))
        val terminal = event.type.lowercase() in setOf("completed", "run_completed", "failed", "error") ||
            (!compatibilityToolAudit && event.status?.lowercase() in setOf("succeeded", "failed", "cancelled"))
        val delta = if (event.type == "text_delta") (event.payload["delta"] ?: event.payload["text"]).orEmpty() else ""
        val eventCards = bindConfirmationCards(AgentPublicCards.fromEvent(event), listOf(event))
        val pending = when {
            event.type == "confirmation_required" -> eventCards.lastOrNull { it.type == "confirmation" && it.confirmationIdentity != null }
            closesPending || event.type in setOf("run_started", "input_required") -> null
            else -> state.pendingConfirmationCard
        }
        // 按当前输入流归并增量；最终 HTTP 回复会替换此临时气泡而非另加重复答案。
        val streamedMessages = if (delta.isNotEmpty()) {
            val previous = state.messages.lastOrNull()
            if (previous?.streaming == true) state.messages.dropLast(1) + previous.copy(text = previous.text + delta)
            else state.messages + ChatMessage(delta, false, streaming = true)
        } else state.messages
        state.copy(
            events = updated,
            // 实时卡片与最终 HTTP 使用同一解码和脱敏规则；仅展示，不自动触发导航。
            cards = (state.cards + eventCards).distinct(),
            pendingConfirmationCard = pending,
            // 新暂停摘要覆盖旧版本；终态/新任务撤销售后按钮，不清除历史消息中的展示内容。
            pendingAfterSaleCards = when {
                event.type in setOf("confirmation_required", "input_required") -> eventCards.filter { it.type.startsWith("after_sale_") && it.confirmationIdentity != null }
                // 未归档的其他任务也可能迟到；只有本次摘要所属任务的终态才能撤销操作资格。
                closesPending || (terminal && samePendingTrace) || event.type == "run_started" -> emptyList()
                else -> state.pendingAfterSaleCards
            },
            lastEventId = event.id ?: state.lastEventId,
            isSending = state.isSending && !paused && !terminal,
            // 等待HTTP期间也立即展示服务端补充提示；相同最终回答不会再次追加同一气泡。
            messages = if (paused && event.summary.isNotBlank()) streamedMessages.withAssistantReply(event.summary,
                (state.cards + eventCards).distinct(), updated) else streamedMessages,
            error = if (paused) null else state.error
        ).also {
            savedState[LAST_EVENT_ID] = it.lastEventId
        }
    }

    /** SSE提示与随后HTTP回答可能相同，只消除相邻同一助手文案，不删除历史会话。 */
    private fun List<ChatMessage>.withAssistantReply(text: String, cards: List<AgentCardPayload> = emptyList(),
                                                   events: List<AgentEvent> = emptyList()): List<ChatMessage> {
        val previous = lastOrNull()
        val reply = ChatMessage(text, false, cards, events)
        return if (previous?.let { !it.fromUser && (it.text == text || it.streaming) } == true)
            dropLast(1) + reply else this + reply
    }

    /**
     * 接收流式事件并更新进度状态。
     *
     * 这里明确不产生页面导航请求，避免模型返回门店卡后未经用户点击就跳转页面。
     */
    fun onAgentEvent(event: AgentEvent) = appendEvent(event)

    /** 消费页面已经处理的导航请求，避免页面重绘时重复跳转。 */
    fun consumeNavigation() = updateState { it.copy(navigation = null) }

    /** 用户明确打开详情后保留对应事实摘要；不能从任意推荐列表中猜测选中对象。 */
    fun rememberViewedCard(card: AgentCardPayload) = updateState { it.copy(viewedCard = card) }

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
    /** 原身份跨 HTTP/SSE 去重；旧事件没有原身份时仍保持既有数据库游标兼容。 */
    private fun List<AgentEvent>.addIfNew(event: AgentEvent) = if (any {
        (event.eventId != null && it.eventId == event.eventId) ||
            (event.id != null && it.id == event.id) ||
            // 同一暂停任务后续输入会重新从 sequence=0 发流，必须同时匹配 traceId，不能吞掉恢复事件。
            (event.runId != null && event.traceId != null && event.sequence != null && it.runId == event.runId &&
                it.traceId == event.traceId && it.sequence == event.sequence)
    }) this else this + event
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
        savedState[LAST_EVENT_ID] = state.lastEventId
        savedState[CANDIDATE_CART] = state.candidateCart
        savedState[CONFIRMATION] = state.confirmation
        savedState[AUTHORIZATION] = state.authorization
        savedState[CREATED_ORDER_ID] = state.createdOrderId
        savedState[VIEWED_CARD] = state.viewedCard
        savedState[PENDING_CONFIRMATION_CARD] = state.pendingConfirmationCard
        savedState[PENDING_AFTER_SALE_CARDS] = ArrayList(state.pendingAfterSaleCards)
    }

// 更新状态。
    private inline fun updateState(transform: (AgentUiState) -> AgentUiState) {
        _uiState.update(transform)
        persistState(_uiState.value)
    }

// 清理视图模型持有的任务与订阅。
    override fun onCleared() { eventsJob?.cancel(); super.onCleared() }

    private companion object {
        /** 本地历史所属账号；与服务端强制认证归属分离，禁止客户端跨账号复用会话。 */
        const val SESSION_OWNER = "agent_session_owner"
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
        const val VIEWED_CARD = "agent_viewed_card"
        /** 本地当前等待摘要与原身份；历史卡另存messages，不能用历史存在替代等待阶段。 */
        const val PENDING_CONFIRMATION_CARD = "agent_pending_confirmation_card"
        /** 售后原版本公开摘要，配置重建不使旧卡重新获得操作权。 */
        const val PENDING_AFTER_SALE_CARDS = "agent_pending_after_sale_cards"
    }
}
