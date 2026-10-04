// 薄恢复索引回归：验证长会话不进入SavedState，草稿完整保留，旧授权不会通过进程恢复复活。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 使用真实ViewModel状态出口，不通过只测试新辅助类掩盖原完整列表仍被写入的问题。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentStateCheckpointTest {
    /** 200条长正文旧状态必须清除，2048字草稿是测量夹具而非产品长度上限。 */
    @Test fun longHistoryNeverSavedAndDraftIsNeverTruncated() = runTest {
        val draft = "草稿".repeat(2048)
        val saved = SavedStateHandle(mapOf(
            "agent_session_owner" to "fixture-owner", "agent_session_id" to "fixture-session",
            "agent_session_established" to true, "agent_composer" to draft,
            "agent_messages" to ArrayList((1..200).map { ChatMessage("长正文".repeat(1600), it%2==0) }),
            "agent_events" to arrayListOf(AgentEvent(1,"confirmation_required","旧确认")),
            "agent_authorization" to AgentAuthorization(true,"旧地址","100")
        ))
        val vm = AgentViewModel(checkpointRepository(), saved, StandardTestDispatcher(testScheduler))
        vm.updateComposer(draft)
        assertEquals(draft, vm.uiState.value.composerText)
        assertFalse("历史正文不能继续写入SavedState", saved.contains("agent_messages"))
        assertFalse(saved.contains("agent_events"))
        assertFalse(saved.contains("agent_authorization"))
        assertFalse(vm.uiState.value.authorization.isTaskAuto)
    }

    /** 连续2000事件更新不保存事件列表或卡片，数据库游标仍以最后已应用ID保存。 */
    @Test fun tokenUpdatesKeepOnlyCursorAndThinFields() = runTest {
        val saved = SavedStateHandle()
        val vm = AgentViewModel(checkpointRepository(),saved,StandardTestDispatcher(testScheduler))
        repeat(2000) { vm.onAgentEvent(AgentEvent(it.toLong()+1,"progress","进度",payload=mapOf("eventId" to "id-$it"))) }
        assertEquals(2000L, saved.get<Long>("agent_last_event_id"))
        assertFalse(saved.contains("agent_messages"))
        assertFalse(saved.contains("agent_events"))
        assertFalse(saved.contains("agent_candidate_cart"))
        assertFalse(saved.contains("agent_pending_confirmation_card"))
    }

    /** 本场景不发网络；明确替身避免依赖另一测试类的私有夹具。 */
    private fun checkpointRepository() = object : AgentRepository {
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): com.fandazi.app.core.network.ApiResult<AgentMessageResult> =
            error("状态保存场景不得发送消息")
        override fun streamEvents(sessionId: String, afterEventId: Long?) = kotlinx.coroutines.flow.emptyFlow<AgentEvent>()
        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest) = com.fandazi.app.core.network.ApiResult.Success(Unit)
        override suspend fun revokeThisTaskAuto(sessionId: String) = com.fandazi.app.core.network.ApiResult.Success(Unit)
    }
}
