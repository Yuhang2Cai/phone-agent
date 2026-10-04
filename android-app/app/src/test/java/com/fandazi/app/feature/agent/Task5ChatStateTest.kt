// Task5 公共聊天定向回归：实际运行状态模型，仅替换网络延迟与故障，不访问真实业务写接口。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证重试、公开事件和多轮历史之间的边界，防止 HTTP 与 SSE 双交付产生重复内容。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Task5ChatStateTest {
    @Test
    fun retryDoesNotRepeatUserBubble() = runTest {
        // 第一次网络失败、原地重试成功；真正发送两次，但页面只能有一个用户问题。
        val repository = ResponsesRepository(mutableListOf(
            ApiResult.Failure(ApiError.Network("隔离连接故障")),
            ApiResult.Success(answer("恢复成功"))
        ))
        val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("你好")
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(1, model.uiState.value.messages.count { it.fromUser })
        assertEquals("恢复成功", model.uiState.value.messages.last().text)
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun httpEventsRemainVisibleWithoutSse() = runTest {
        // SSE 未连接时，HTTP 自带的原始事件仍应进入状态；数据库游标不能由 sequence 伪造。
        val event = AgentEvent(null, "run_completed", "查询完成", status = "succeeded",
            payload = mapOf("eventId" to "isolated-event", "sequence" to "3", "runId" to "isolated-run"))
        val model = AgentViewModel(ResponsesRepository(mutableListOf(ApiResult.Success(answer("查询完成", listOf(event))))),
            SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("帮我找团购")
        runCurrent()
        assertEquals(listOf(event), model.uiState.value.events)
        assertEquals(0L, model.uiState.value.lastEventId)
    }

    @Test
    fun streamFailureStopsBusyState() = runTest {
        // 流中断不能留下永久忙碌状态；HTTP 未结束时也必须允许用户看到恢复入口。
        val repository = object : AgentRepository by ResponsesRepository(mutableListOf()) {
            /** 委托仓库新增四参数展示键入口必须覆盖，不能绕过本测试未完成HTTP替身。 */
            override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode, clientMessageKey: String?): ApiResult<AgentMessageResult> {
                kotlinx.coroutines.awaitCancellation()
            }
            override fun streamEvents(sessionId: String, afterEventId: Long?) = kotlinx.coroutines.flow.flow<AgentEvent> {
                throw java.io.IOException("隔离流中断")
            }
        }
        val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.openAssistant()
        model.sendText("查询团购")
        runCurrent()
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun streamedTextAndCardUseOriginalEventIdentity() = runTest {
        // 同一增量由不同 SSE 游标重放时只能追加一次；卡片必须在 HTTP 结束之前可见。
        val model = AgentViewModel(ResponsesRepository(mutableListOf()), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        val delta = AgentEvent(1, "text_delta", "", status = "running",
            payload = mapOf("delta" to "正在查询", "eventId" to "delta-1", "runId" to "run-1", "sequence" to "1"))
        model.onAgentEvent(delta)
        model.onAgentEvent(delta.copy(id = 2))
        assertEquals("正在查询", model.uiState.value.messages.single().text)
        model.onAgentEvent(AgentEvent(3, "card", "", status = "running", payload = mapOf(
            "eventId" to "card-1", "runId" to "run-1", "sequence" to "2",
            "card" to """{"type":"deal","storePublicId":"isolated-store","dealPublicId":"isolated-deal","title":"隔离套餐"}""")))
        assertEquals("isolated-deal", model.uiState.value.cards.single().dealPublicId)
        assertEquals(null, model.uiState.value.navigation)
    }

    @Test
    fun historyCardsBelongToOriginalReplyAndSurviveRestore() = runTest {
        // 同Activity历史仍固定归属；进程重建不从薄SavedState还原正文，必须从只读后端页恢复。
        val saved = SavedStateHandle()
        val card = AgentCardPayload("deal", "isolated-store", "isolated-deal")
        val repository = ResponsesRepository(mutableListOf(ApiResult.Success(answer("查到套餐").copy(cards = listOf(card))),
            ApiResult.Success(answer("第二轮回答"))))
        val model = AgentViewModel(repository, saved, StandardTestDispatcher(testScheduler))
        model.sendText("查询套餐")
        runCurrent()
        model.sendText("还有其它吗")
        runCurrent()
        assertEquals(listOf(card), model.uiState.value.messages.first { !it.fromUser }.cards)
        val historyRepository=object:AgentRepository by repository {
            /** 完整正文及公开卡事件由合成后端页提供，不能把事件摘要反造为用户话语。 */
            override suspend fun loadHistory(sessionId:String,cursor:String?,limit:Int)=ApiResult.Success(AgentHistoryPage(sessionId,
                listOf(AgentHistoryMessage("fixture-message","assistant","查到套餐","run","trace",null,null)),
                listOf(AgentEvent(1,"card","",payload=mapOf("traceId" to "trace","card" to """{"type":"deal","storePublicId":"isolated-store","dealPublicId":"isolated-deal"}"""))),
                1,null,"complete",null,null,null))
        }
        val restored = AgentViewModel(historyRepository, saved, StandardTestDispatcher(testScheduler))
        assertEquals(0,restored.uiState.value.messages.size)
        restored.openAssistant();runCurrent()
        val historyCard=restored.uiState.value.messages.single().cards.single()
        assertEquals(card.dealPublicId,historyCard.dealPublicId)
        org.junit.Assert.assertTrue(historyCard.historicalReadOnly)
        assertFalse(restored.isCurrentReplyCard(historyCard))
    }

    @Test
    fun httpAndSseMergeOriginalIdentityWithoutReplacingDatabaseCursor() = runTest {
        // 同一真实事件先由 SSE 到达、再由 HTTP 完整回复返回；身份用于去重，数据库游标独立保留。
        val canonical = AgentEvent(null, "run_completed", "查询完成", status = "succeeded",
            payload = mapOf("eventId" to "same-event", "sequence" to "8", "runId" to "same-run", "traceId" to "same-trace"))
        val model = AgentViewModel(ResponsesRepository(mutableListOf(ApiResult.Success(answer("查询完成", listOf(canonical))))),
            SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("查看订单")
        model.onAgentEvent(canonical.copy(id = 2031))
        runCurrent()
        // HTTP 不带数据库 ID，不能把原 sequence=8 当作 Last-Event-ID，也不能再次追加回复。
        assertEquals(1, model.uiState.value.events.count { it.eventId == "same-event" })
        assertEquals(2031L, model.uiState.value.lastEventId)
        assertEquals(1, model.uiState.value.messages.count { !it.fromUser })
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun successfulGatewayToolAuditDoesNotFinishRunningTask() = runTest {
        // 真实 SSE 还含网关工具审计，工具 succeeded 不等于任务 succeeded；HTTP仍未完成时动画须继续。
        val model = AgentViewModel(ResponsesRepository(mutableListOf(ApiResult.Success(answer("最终结果")))),
            SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("查询团购")
        model.onAgentEvent(AgentEvent(2027, "tool_result", "工具执行结果", status = "succeeded",
            payload = mapOf("runId" to "same-run", "traceId" to "same-trace", "taskId" to "list_user_orders")))
        org.junit.Assert.assertTrue(model.uiState.value.isSending)
        assertEquals(1, model.uiState.value.messages.size)
        // HTTP终态仍正常结束忙碌，兼容记录仅作为审计保留、不追加助手回复。
        runCurrent()
        assertFalse(model.uiState.value.isSending)
        assertEquals(1, model.uiState.value.messages.count { !it.fromUser })
    }

    @Test
    fun gatewayCompatibilityReplayDoesNotRepeatArchivedReplyOrCards() = runTest {
        // 原始 HTTP 回复已归档后，网关兼容事件没有 eventId/sequence；只推进游标，不重复绘制。
        val trace = mapOf("runId" to "same-run", "traceId" to "same-trace")
        val original = AgentEvent(null, "run_completed", "查询完成", status = "succeeded",
            payload = trace + mapOf("eventId" to "canonical", "sequence" to "8"))
        val card = AgentCardPayload("order", orderPublicId = "isolated-order")
        val model = AgentViewModel(ResponsesRepository(mutableListOf(ApiResult.Success(answer("查询完成", listOf(original)).copy(cards = listOf(card))))),
            SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("查看订单")
        runCurrent()
        listOf("parsed", "tool_result", "completed").forEachIndexed { index, type ->
            model.onAgentEvent(AgentEvent(3000L + index, type, "兼容记录", status = "COMPLETED", payload = trace))
        }
        assertEquals(1, model.uiState.value.messages.count { !it.fromUser })
        assertEquals(listOf(card), model.uiState.value.messages.last().cards)
        assertEquals(listOf(original), model.uiState.value.events)
        assertEquals(3002L, model.uiState.value.lastEventId)
        assertEquals(null, model.uiState.value.navigation)
    }

    @Test
    fun changingAuthenticatedOwnerClearsLocalHistoryAndStartsFreshSession() = runTest {
        // 同一 Activity 登录另一个账号时，不能展示前一账号消息或复用其服务端会话。
        val repository = ResponsesRepository(mutableListOf(ApiResult.Success(answer("第一账号结果")), ApiResult.Success(answer("第二账号结果"))))
        val saved = SavedStateHandle()
        val model = AgentViewModel(repository, saved, StandardTestDispatcher(testScheduler))
        model.bindAuthenticatedOwner("isolated-user-one")
        model.sendText("查询")
        runCurrent()
        val firstSession = saved.get<String>("agent_session_id")
        model.bindAuthenticatedOwner("isolated-user-one")
        assertEquals(2, model.uiState.value.messages.size)
        model.bindAuthenticatedOwner("isolated-user-two")
        assertEquals(0, model.uiState.value.messages.size)
        org.junit.Assert.assertNotEquals(firstSession, saved.get<String>("agent_session_id"))
        model.sendText("查询")
        runCurrent()
        assertEquals("第二账号结果", model.uiState.value.messages.last().text)
    }

    @Test
    fun resumedRunMayRestartSequenceUnderNewTrace() = runTest {
        // runId 在补槽期间保持稳定，但新输入 traceId 与 sequence 重置合法，不能误删新一轮事件。
        val model = AgentViewModel(ResponsesRepository(mutableListOf()), SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.onAgentEvent(AgentEvent(1, "run_started", "", payload=mapOf("eventId" to "one", "runId" to "same-run", "traceId" to "first", "sequence" to "0")))
        model.onAgentEvent(AgentEvent(2, "run_started", "", payload=mapOf("eventId" to "two", "runId" to "same-run", "traceId" to "second", "sequence" to "0")))
        assertEquals(2, model.uiState.value.events.size)
    }

    /** 构造无业务事实的公开回复；事件由每个测试独立声明，不复用生产映射生成期望值。 */
    private fun answer(text: String, events: List<AgentEvent> = emptyList()) =
        AgentMessageResult(AgentStatus.COMPLETED, text, null, null, events)

    /** 仅替换网络响应，禁止此组测试申请或撤销授权。 */
    private class ResponsesRepository(val responses: MutableList<ApiResult<AgentMessageResult>>) : AgentRepository {
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode) = responses.removeAt(0)
        override fun streamEvents(sessionId: String, afterEventId: Long?) = emptyFlow<AgentEvent>()
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest) = error("不得申请授权")
        override suspend fun revokeThisTaskAuto(sessionId: String) = error("不得撤销授权")
    }
}
