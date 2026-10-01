// Task5 P1 回归：只验证历史摘要动作的本地归属防线，网络用隔离响应替换，不执行真实确认交易。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 同一会话历史按钮必须带原摘要，不能把泛化“确认/取消”作用到当前另一个暂停任务。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Task5ConfirmationActionTest {
    @Test fun cancelledHistoryCannotCancelNewRun() = runTest {
        val repo = Responses(mutableListOf(prepare("run-one", "version-one", 1), terminal(), prepare("run-two", "version-two", 2), terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备一份"); runCurrent()
        val old = model.uiState.value.cards.single()
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old))); runCurrent()
        model.sendText("准备两份"); runCurrent()
        val before = model.uiState.value
        // 原失败场景：已经取消的数量1历史卡，不能影响数量2等待确认的消息或当前摘要。
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old))); runCurrent()
        assertEquals(before, model.uiState.value)
        assertEquals(3, repo.requests.size)
        assertTrue(model.uiState.value.messages.any { it.cards.contains(old) })
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("取消", model.uiState.value.cards.single()))); runCurrent()
        assertEquals("取消", repo.requests.last())
    }

    @Test fun sameRunReplacementRejectsOldSummaryVersion() = runTest {
        val repo = Responses(mutableListOf(prepare("same-run", "version-one", 1), prepare("same-run", "version-two", 2), terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备一份"); runCurrent()
        val old = model.uiState.value.cards.single()
        model.sendText("改为两份"); runCurrent()
        val before = model.uiState.value
        // runId 与 traceId 都故意相同；只有新 confirmation_required.eventId 才能区分重新准备的版本。
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", old))); runCurrent()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old))); runCurrent()
        assertEquals(before, model.uiState.value)
        assertEquals(2, repo.requests.size)
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("取消", model.uiState.value.cards.single()))); runCurrent()
    }

    @Test fun terminalCardCannotConfirmOrCancel() = runTest {
        val repo = Responses(mutableListOf(prepare("run", "v1", 1), terminal(), terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备"); runCurrent()
        val old = model.uiState.value.cards.single()
        model.sendText("取消"); runCurrent()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", old)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old)))
        assertEquals(2, repo.requests.size)
    }

    @Test fun identicalSummaryStillRequiresLatestPreparationEvent() = runTest {
        val first = prepare("same-run", "v1", 1)
        val second = prepare("same-run", "v2", 1).copy(cards = first.cards)
        val repo = Responses(mutableListOf(first, second, terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备"); runCurrent()
        val old = model.uiState.value.cards.single()
        model.sendText("原条件重新准备"); runCurrent()
        val current = model.uiState.value.cards.single()
        // 公共摘要、runId、traceId全部相同；新事件版本仍必须撤销旧按钮，不能退化为runId或字段比较。
        assertEquals(old.fields, current.fields)
        assertNotEquals(old.confirmationIdentity, current.confirmationIdentity)
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", old)))
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("取消", current))); runCurrent()
        assertEquals(3, repo.requests.size)
    }

    @Test fun sseReplacementReplayAndTerminalRespectCurrentBinding() = runTest {
        val first = prepare("run", "v1", 1)
        val repo = Responses(mutableListOf(first))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备"); runCurrent()
        val old = model.uiState.value.cards.single()
        val replacement = prepare("run", "v2", 2)
        val event = replacement.events.single().copy(id = 20L, payload = replacement.events.single().payload +
            mapOf("sequence" to "11", "summary" to com.google.gson.Gson().toJson(replacement.cards.single().fields)))
        // HTTP后同run的新准备事件仍允许更新当前版本；原身份重放只能推进SSE游标，不能再次生成卡。
        model.onAgentEvent(event)
        val current = requireNotNull(model.uiState.value.pendingConfirmationCard)
        assertFalse(model.isCurrentConfirmation(old))
        assertTrue(model.isCurrentConfirmation(current))
        val before = model.uiState.value.messages
        model.onAgentEvent(event.copy(id = 21L))
        assertEquals(before, model.uiState.value.messages)
        assertEquals(21L, model.uiState.value.lastEventId)
        // 网关工具成功不是任务终态；其它run的迟到终态也不能撤销当前等待摘要。
        model.onAgentEvent(AgentEvent(22L, "tool_result", "工具成功", status = "succeeded",
            payload = mapOf("runId" to "run", "traceId" to "same-trace")))
        model.onAgentEvent(AgentEvent(23L, "run_completed", "旧任务取消", status = "cancelled",
            payload = mapOf("runId" to "old-run", "traceId" to "old-trace")))
        assertTrue(model.isCurrentConfirmation(current))
        model.onAgentEvent(AgentEvent(24L, "run_completed", "当前任务取消", status = "cancelled",
            payload = mapOf("runId" to "run", "traceId" to "same-trace")))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", current)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", current)))
        assertEquals(1, repo.requests.size)
    }

    @Test fun restoreKeepsOnlyCurrentEffectiveSummary() = runTest {
        val state = SavedStateHandle()
        val repo = Responses(mutableListOf(prepare("run-one", "v1", 1), prepare("run-two", "v2", 2), terminal()))
        val first = AgentViewModel(repo, state, StandardTestDispatcher(testScheduler))
        first.sendText("准备一份"); runCurrent()
        val old = first.uiState.value.cards.single()
        first.sendText("替换两份"); runCurrent()
        val current = first.uiState.value.cards.single()
        val restored = AgentViewModel(repo, state, StandardTestDispatcher(testScheduler))
        // 旋转/进程重建保留历史展示，不把已失效摘要重新提升为当前等待版本。
        assertTrue(restored.uiState.value.messages.any { it.cards.contains(old) })
        assertFalse(restored.replyToCard(AgentNavigationRequest.AgentReply("取消", old)))
        assertTrue(restored.replyToCard(AgentNavigationRequest.AgentReply("取消", current))); runCurrent()
    }

    @Test fun waitingInputAndInFlightInvalidateOldActions() = runTest {
        val input = AgentMessageResult(AgentStatus.NEEDS_INPUT, "请补充城市", null, null, emptyList())
        val repo = Responses(mutableListOf(prepare("run", "v1", 1), input, terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备"); runCurrent()
        val old = model.uiState.value.cards.single()
        model.sendText("修改范围")
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old)))
        runCurrent()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", old)))
        // 拒绝旧卡不能锁死补输入；正常用户补充仍经原发送管道进入下一轮。
        model.sendText("隔离城市"); runCurrent()
        assertEquals("隔离城市", repo.requests.last())
    }

    @Test fun forgedSummaryWrongConfirmAndUnboundReplyAreRejected() = runTest {
        val repo = Responses(mutableListOf(prepare("run", "v1", 1), terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("准备"); runCurrent()
        val current = model.uiState.value.cards.single()
        val altered = current.copy(fields = current.fields + ("amountCents" to "1"))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", altered)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认支付", current)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消")))
        assertEquals(1, repo.requests.size)
        // 合法确认只在隔离仓库内检查语句，不调用任何真实服务或交易工具。
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("确认", current))); runCurrent()
        assertEquals("确认", repo.requests.last())
    }

    @Test fun accountSwitchAndExpiredSummaryRejectCapturedAction() = runTest {
        val expired = prepare("run", "v1", 1, "2000-01-01T00:00:00Z")
        val repo = Responses(mutableListOf(expired, prepare("run", "v2", 1)))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.bindAuthenticatedOwner("isolated-one")
        model.sendText("准备"); runCurrent()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认", model.uiState.value.cards.single())))
        model.sendText("重准备"); runCurrent()
        val old = model.uiState.value.cards.single()
        model.bindAuthenticatedOwner("isolated-two")
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("取消", old)))
        assertEquals(0, model.uiState.value.messages.size)
    }

    /** 构造现有公开准备协议，版本用原事件ID，金额/数量为隔离值，不包含内部凭证。 */
    private fun prepare(run: String, version: String, quantity: Int, expiry: String = "2099-01-01T00:00:00Z"): AgentMessageResult {
        val fields = mapOf("action" to "create_group_buy_order", "resourceId" to version,
            "title" to "确认创建团购订单", "quantity" to quantity.toString(), "amountCents" to (9000 * quantity).toString(),
            "ruleVersion" to "deal-v1", "expiresAt" to expiry)
        val event = AgentEvent(null, "confirmation_required", "确认创建团购订单", status = "waiting_confirmation",
            payload = mapOf("eventId" to version, "runId" to run, "traceId" to "same-trace", "sequence" to "10"))
        return AgentMessageResult(AgentStatus.AWAITING_CONFIRMATION, "确认创建团购订单", "confirmation_required", null,
            listOf(event), cards = listOf(AgentCardPayload("confirmation", fields = fields)))
    }

    /** 仅提供无卡片终态，防止把测试终态当成已发生的购买、付款或售后。 */
    private fun terminal() = AgentMessageResult(AgentStatus.COMPLETED, "已取消本次操作", "cancelled", null, emptyList())

    /** 真实状态机的网络隔离边界；记录实际送出的命令，拒绝授权调用。 */
    private class Responses(val responses: MutableList<AgentMessageResult>) : AgentRepository {
        val requests = mutableListOf<String>()
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            requests += text
            return ApiResult.Success(responses.removeAt(0))
        }
        override fun streamEvents(sessionId: String, afterEventId: Long?) = emptyFlow<AgentEvent>()
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest) = error("不得授权")
        override suspend fun revokeThisTaskAuto(sessionId: String) = error("不得撤销")
    }
}
