// AG03聊天接线回归：原文、短回复确认保留、晚事件隔离以及失败只读恢复。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 直接运行正式ViewModel，网络替身只返回协议结果，不实现状态策略。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentTaskViewModelTest {
    /** SSE先展示与HTTP完全相同的卡片时，规范摘要到达仍须发出状态更新，避免按钮永久停留在历史失效样式。 */
    @Test fun canonicalSummaryAfterIdenticalSseRefreshesConfirmationEligibility() = runTest {
        val repository = Repository();repository.taskApi.inputGate = CompletableDeferred()
        val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        var rendered: List<AgentRenderRow> = emptyList()
        // 按正式界面的收集方式计算按钮资格；只在UI状态真正发射时重投影，不能用直接读取私有状态掩盖漏通知。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.uiState.collect { rendered = AgentRenderRows.project(it, true, model::isCurrentReplyCard) }
        }
        model.sendText("退款");runCurrent()
        model.onAgentEvent(repository.taskApi.event);runCurrent()
        val pending = model.uiState.value.pendingAfterSaleCards.single()
        assertFalse(model.isCurrentReplyCard(pending))
        assertFalse(rendered.filterIsInstance<AgentRenderRow.Card>().single().current)
        // 正式HTTP补回规范摘要，不改写SSE已经展示的业务字段；最后必须通知界面开放当前卡，历史卡仍不可操作。
        repository.taskApi.inputGate!!.complete(Unit);runCurrent()
        assertTrue(model.isCurrentReplyCard(pending))
        assertTrue("规范摘要补齐必须重新发射UI状态，使当前确认按钮刷新", rendered.filterIsInstance<AgentRenderRow.Card>().single().current)
        assertFalse(model.isCurrentReplyCard(pending.copy(historicalReadOnly = true)))
    }
    /** 明确启用2.2后正文不裁剪，不调用原消息路由；招呼短答保持有效退款确认。 */
    @Test fun taskInputPreservesRawTextAndGreetingKeepsCurrentConfirmation() = runTest {
        val repository = Repository();val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("  我要退款  ");runCurrent()
        assertEquals(listOf("  我要退款  "), repository.taskApi.inputs);assertEquals(0, repository.legacyCalls)
        val card = model.uiState.value.pendingAfterSaleCards.single();assertTrue(model.isCurrentReplyCard(card))
        model.sendText("你好");runCurrent()
        assertEquals(card, model.uiState.value.pendingAfterSaleCards.single());assertTrue(model.isCurrentReplyCard(card))
        assertEquals(2, model.uiState.value.messages.count { !it.fromUser })
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款", card.copy(historicalReadOnly = true))))
    }
    /** 旧代次增量不追加文字、不覆盖当前确认；失败重试只读恢复而不重发原话。 */
    @Test fun staleEventAndRetryCannotExecuteBusinessAgain() = runTest {
        val repository = Repository();val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("退款");runCurrent();val before = model.uiState.value.messages
        val old = repository.taskApi.event.copy(id = 50, type = "text_delta", payload = repository.taskApi.event.payload + mapOf(
            "taskContext" to Gson().toJson(mapOf("conversationTaskId" to "task", "turnId" to "old", "taskVersion" to 1, "focusVersion" to 1, "executionGeneration" to 0)), "delta" to "错误旧文本"))
        model.onAgentEvent(old);assertEquals(before, model.uiState.value.messages)
        repository.taskApi.fail = true;model.sendText("补充");runCurrent();model.retry();runCurrent()
        assertEquals(2, repository.taskApi.inputs.size);assertEquals(0, repository.legacyCalls)
    }
    /** 有效确认只发送当前规范摘要和任务版本，不发送grant或旧本地购物车。 */
    @Test fun currentConfirmationCarriesCanonicalTaskSummary() = runTest {
        val repository = Repository();val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("退款");runCurrent();assertEquals(1, model.uiState.value.pendingAfterSaleCards.size);val card = model.uiState.value.pendingAfterSaleCards.single()
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款", card)));runCurrent()
        val confirmation = repository.taskApi.confirmation!!
        assertEquals(setOf("taskId", "taskVersion", "focusVersion", "summary"), confirmation.keySet())
        assertEquals(3L, confirmation["taskVersion"].asLong);assertEquals("request_refund", confirmation.getAsJsonObject("summary")["action"].asString)
        assertFalse(confirmation.toString().contains("grant"))
    }

    /** 旧接口计数帮助发现无意回退，不能以兼容入口成功掩盖任务协议未接线。 */
    private class Repository : AgentRepository {
        val taskApi = TaskApi();override val tasks: AgentTaskRepository = taskApi
        var legacyCalls = 0
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> { legacyCalls++;return ApiResult.Success(AgentMessageResult(AgentStatus.COMPLETED, "旧接口", null, null, emptyList())) }
        override fun streamEvents(sessionId: String, afterEventId: Long?) = emptyFlow<AgentEvent>()
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> = error("任务不复用旧授权")
        override suspend fun revokeThisTaskAuto(sessionId: String): ApiResult<Unit> = error("任务不复用旧授权")
    }
    /** 数据全部合成；当前目录版本高于受理围栏一版，模拟完成后仍可确认的正常情况。 */
    private class TaskApi : AgentTaskRepository {
        val inputs = mutableListOf<String>();var fail = false;var confirmation: JsonObject? = null
        /** 只延迟HTTP回包，SSE仍通过正式事件入口交付；用于复现两条通道的到达顺序。 */
        var inputGate: CompletableDeferred<Unit>? = null
        private val task = AgentTaskProjection("task", "transaction", "refund_after_sale", "active", 3, 1, "退款", AgentTaskStopPoint("waiting_confirmation"), lastRunId = "run", actions = setOf("pause", "cancel"))
        private val summary = mapOf("action" to "request_refund", "resourceId" to "voucher", "voucherId" to "voucher", "dealTitle" to "合成套餐", "reason" to "合成原因", "amountCents" to "100", "expiresAt" to "2099-01-01T00:00:00Z", "ruleVersion" to "1")
        val event = AgentEvent(null, "confirmation_required", "请确认退款", status = "waiting_confirmation", payload = mapOf(
            "eventId" to "event", "runId" to "run", "traceId" to "trace", "sequence" to "1", "summary" to Gson().toJson(summary),
            "taskContext" to Gson().toJson(mapOf("conversationTaskId" to "task", "turnId" to "turn", "taskVersion" to 2, "focusVersion" to 2, "executionGeneration" to 1))))
        override suspend fun input(sessionId: String, text: String, requestId: String, selection: AgentSelectionRef?, confirmation: JsonObject?): ApiResult<AgentMessageResult> {
            inputs += text;this.confirmation = confirmation
            inputGate?.await()
            if (fail) return ApiResult.Failure(ApiError.Network("响应丢失"))
            return ApiResult.Success(if (inputs.size == 1) AgentMessageResult(AgentStatus.AWAITING_CONFIRMATION, "请确认退款", "confirm_refund", null, listOf(event), cards = listOf(AgentCardPayload("after_sale_refund", fields = summary)), taskConfirmationSummary = com.google.gson.JsonParser.parseString(Gson().toJson(summary)).asJsonObject)
            else AgentMessageResult(AgentStatus.COMPLETED, "你好，原任务保留", "task_state", null, emptyList()))
        }
        override suspend fun directory(sessionId: String): ApiResult<AgentTaskDirectory> = ApiResult.Success(AgentTaskDirectory(2, 5, task, emptyList()))
        override suspend fun events(sessionId: String, after: Long): ApiResult<List<AgentTaskEvent>> = ApiResult.Success(emptyList())
        override suspend fun pending(sessionId: String): ApiResult<List<AgentTaskCommand>> = ApiResult.Success(emptyList())
        override suspend fun byRequest(sessionId: String, requestId: String): ApiResult<AgentTaskCommand> = error("此例无控制")
        override suspend fun command(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> = error("此例无控制")
        override suspend fun recheck(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> = error("此例无控制")
        override suspend fun control(sessionId: String, request: AgentTaskControl): ApiResult<AgentTaskCommand> = error("此例无控制")
        override suspend fun task(sessionId: String, taskId: String): ApiResult<AgentTaskProjection> = ApiResult.Success(task)
    }
}
