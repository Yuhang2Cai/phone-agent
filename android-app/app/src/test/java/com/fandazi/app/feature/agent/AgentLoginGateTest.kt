// AI 助手入口测试：防止游客绕过登录门槛，也防止卡片事件触发隐式页面跳转。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.navigation.SessionRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoginGateTest {
    @Test
    /** 游客选择 AI 助手时必须先进入登录页，并保留回访目标。 */
    fun guestSelectingAgentReturnsLoginDestination() {
        assertEquals(
            AgentEntryDestination.Login(returnTo = SessionRoute.HOME),
            agentEntryDestination(isLoggedIn = false)
        )
    }

    @Test
    /** 已登录用户选择 AI 助手时直接进入聊天页。 */
    fun authenticatedSelectingAgentReturnsChatDestination() {
        assertEquals(AgentEntryDestination.Chat, agentEntryDestination(isLoggedIn = true))
    }

    @Test
    /** 接收到门店卡事件只能更新消息状态，不能自行产生导航请求。 */
    fun receivingStoreCardDoesNotNavigateUntilClick() = runTest {
        val viewModel = AgentViewModel(
            repository = EventOnlyAgentRepository(),
            savedState = SavedStateHandle(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        viewModel.onAgentEvent(
            AgentEvent(
                id = 1L,
                type = "card",
                summary = "门店卡已生成",
                toolName = "search_stores",
                status = "completed"
            )
        )

        assertNull(viewModel.navigation.first())
    }

    /** 仅用于验证事件状态边界，不返回任何本地业务数据。 */
    private class EventOnlyAgentRepository : AgentRepository {
        override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode) =
            error("该测试不应发起消息请求")

        override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> = emptyFlow()

        override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest) =
            error("该测试不应发起授权请求")

        override suspend fun revokeThisTaskAuto(sessionId: String) =
            error("该测试不应发起撤销请求")
    }
}
