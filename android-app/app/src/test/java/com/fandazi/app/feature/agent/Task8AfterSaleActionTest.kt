// 售后动作身份回归：直接运行聊天状态机，验证历史选券/确认/取消不再发送到当前任务。
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

/** 测试状态与网络调用边界，不以离线结果替代最新 APK Maestro 及视觉检查。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Task8AfterSaleActionTest {
    /** 三种售后动作均须绑定原版本；同 run 的新摘要也必须撤销旧版本。 */
    @Test fun oldRefundComplaintAndVoucherActionsCannotAffectNewSummary() = runTest {
        for (type in listOf("after_sale_refund", "after_sale_complaint", "after_sale_voucher_choice")) {
            val repo = Responses(mutableListOf(waiting(type, "old"), waiting(type, "new")))
            val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
            model.sendText("开始售后"); runCurrent()
            val old = model.uiState.value.cards.single()
            model.sendText("重新选择"); runCurrent()
            val actions = when(type) {
                "after_sale_refund" -> listOf("确认退款", "取消退款")
                "after_sale_complaint" -> listOf("确认投诉", "取消投诉")
                else -> listOf("voucher-id")
            }
            actions.forEach { assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply(it, old))) }
            assertEquals(2, repo.sent.size)
        }
    }

    /** 当前完整卡可以操作一次；篡改摘要或无来源的按钮命令不能绕过身份检查。 */
    @Test fun currentCardAcceptsOnlyBoundExpectedAction() = runTest {
        val repo = Responses(mutableListOf(waiting("after_sale_refund", "current"), terminal()))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("退款"); runCurrent()
        val card = model.uiState.value.cards.single()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款")))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认投诉", card)))
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款", card.copy(fields = card.fields + ("amountCents" to "1")))))
        assertTrue(model.replyToCard(AgentNavigationRequest.AgentReply("取消退款", card))); runCurrent()
        assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款", card)))
        assertEquals(listOf("退款", "取消退款"), repo.sent)
    }

    /** 过期、缺事件身份、服务端终态分别拒绝旧确认，不因历史卡可见而继续授权。 */
    @Test fun expiredAndUnboundCardsAreNotActionable() = runTest {
        for (result in listOf(waiting("after_sale_refund", "expired", "2000-01-01T00:00:00Z"),
                waiting("after_sale_refund", "missing").copy(events = emptyList()))) {
            val repo = Responses(mutableListOf(result))
            val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
            model.sendText("退款"); runCurrent()
            assertFalse(model.replyToCard(AgentNavigationRequest.AgentReply("确认退款", model.uiState.value.cards.single())))
            assertEquals(1, repo.sent.size)
        }
    }

    /** 实时新版本取代旧确认；迟到的其他任务终态不撤销当前授权，同任务终态必须撤销。 */
    @Test fun streamingReplacementAndTerminalRespectAfterSaleIdentity() = runTest {
        val first = waiting("after_sale_refund", "first")
        val repo = Responses(mutableListOf(first))
        val model = AgentViewModel(repo, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.sendText("退款"); runCurrent()
        val old = model.uiState.value.cards.single()
        val replacement = waiting("after_sale_refund", "replacement")
        val summary = replacement.cards.single().fields + ("action" to "request_refund")
        val event = replacement.events.single().copy(id = 20L, payload = replacement.events.single().payload +
            mapOf("sequence" to "11", "summary" to com.google.gson.Gson().toJson(summary)))
        model.onAgentEvent(event)
        val current = model.uiState.value.pendingAfterSaleCards.single()
        assertFalse(model.isCurrentReplyCard(old))
        assertTrue(model.isCurrentReplyCard(current))
        // 同一事件重放只推进游标，不新增第二份可操作摘要。
        val messages = model.uiState.value.messages
        model.onAgentEvent(event.copy(id = 21L))
        assertEquals(messages, model.uiState.value.messages)
        model.onAgentEvent(AgentEvent(22L, "run_completed", "旧任务结束", status = "cancelled",
            payload = mapOf("runId" to "old-run", "traceId" to "old-trace")))
        assertTrue(model.isCurrentReplyCard(current))
        model.onAgentEvent(AgentEvent(23L, "run_completed", "当前任务结束", status = "cancelled",
            payload = mapOf("runId" to "same-run", "traceId" to "same-trace")))
        assertFalse(model.isCurrentReplyCard(current))
        assertEquals(1, repo.sent.size)
    }

    /** 进程重建不保留旧确认资格；当前Activity显式操作仍由其他测试覆盖，重建不得自动重发。 */
    @Test fun restorationRejectsAllCapturedAfterSaleActions() = runTest {
        for ((type, action) in listOf("after_sale_refund" to "确认退款",
                "after_sale_complaint" to "确认投诉", "after_sale_voucher_choice" to "voucher-id")) {
            val state = SavedStateHandle()
            val repo = Responses(mutableListOf(waiting(type, "old"), waiting(type, "new"), terminal()))
            val model = AgentViewModel(repo, state, StandardTestDispatcher(testScheduler))
            model.sendText("旧请求"); runCurrent()
            val old = model.uiState.value.cards.single()
            model.sendText("新请求"); runCurrent()
            val current = model.uiState.value.cards.single()
            val restored = AgentViewModel(repo, state, StandardTestDispatcher(testScheduler))
            assertFalse(restored.replyToCard(AgentNavigationRequest.AgentReply(action, old)))
            assertFalse(restored.replyToCard(AgentNavigationRequest.AgentReply(action, current)))
            // 重建后连续点旧动作也不得发送；所有旧卡只读，重新取得资格必须走新明确请求。
            assertFalse(restored.replyToCard(AgentNavigationRequest.AgentReply(action, current)))
            runCurrent()
            assertEquals(listOf("旧请求","新请求"),repo.sent)
            assertFalse(restored.isCurrentReplyCard(current))
        }
    }

    /** 构造公开摘要及同 run 不同事件版本，不携带凭证或真实用户资料。 */
    private fun waiting(type: String, version: String, expiry: String = "2099-01-01T00:00:00Z"): AgentMessageResult {
        val choice = type == "after_sale_voucher_choice"
        val event = AgentEvent(null, if(choice) "input_required" else "confirmation_required", "售后等待",
            payload = mapOf("eventId" to version, "runId" to "same-run", "traceId" to "same-trace", "sequence" to "10"))
        val fields = mapOf("voucherId" to "voucher-id", "resourceId" to "voucher-id", "canRefund" to "true",
            "dealTitle" to "隔离测试摘要", "amountCents" to "3334", "reason" to "行程变化", "subject" to "支付问题",
            "description" to "测试说明", "expiresAt" to expiry, "ruleVersion" to "refund-v1")
        return AgentMessageResult(if(choice) AgentStatus.NEEDS_INPUT else AgentStatus.AWAITING_CONFIRMATION,
            "售后等待", if(choice) "choose_voucher" else if(type == "after_sale_refund") "confirm_refund" else "confirm_complaint",
            null, listOf(event), cards=listOf(AgentCardPayload(type, fields=fields)))
    }

    /** 取消终态不声称已产生任何业务写入。 */
    private fun terminal() = AgentMessageResult(AgentStatus.COMPLETED, "已取消", "cancelled", null, emptyList())

    /** 只替换网络边界；多余请求立即失败，避免静默吞掉旧卡误操作。 */
    private class Responses(val results: MutableList<AgentMessageResult>): AgentRepository {
        val sent = mutableListOf<String>()
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> {
            sent += text
            return ApiResult.Success(results.removeAt(0))
        }
        override fun streamEvents(sessionId: String, afterEventId: Long?) = emptyFlow<AgentEvent>()
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest) = error("不得自动授权")
        override suspend fun revokeThisTaskAuto(sessionId: String) = error("不涉及自动授权")
    }
}
