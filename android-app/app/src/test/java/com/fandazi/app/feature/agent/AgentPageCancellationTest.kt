// 聊天离页回归：Activity级状态不能让普通HTTP继续持有旧页面请求；返回只续接，不重发业务语句。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 在真实HttpAgentRepository上观察普通消息Call，SSE假Flow仅用于隔离此测试的网络写请求。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPageCancellationTest {
    private lateinit var server: MockWebServer
    /** 固定主调度器便于精确触发open/send/close，真实HTTP仍由执行器线程完成。 */
    @Before fun prepare() { server = MockWebServer().also { it.start() };Dispatchers.setMain(StandardTestDispatcher()) }
    /** 恢复主调度器和服务端，不让下一测试复用页面任务或连接。 */
    @After fun release() { Dispatchers.resetMain();server.shutdown() }
    /** 尚未收到HTTP时离页即释放Call；再次打开只订阅同会话，不能再次POST原句或伪称已撤销交易。 */
    @Test fun leavingPageCancelsPendingMessageWithoutResending() = runTest {
        val active = AtomicReference<Call?>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() { override fun callStart(call: Call) { active.set(call) } }).build()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(800, TimeUnit.MILLISECONDS))
        val http = HttpAgentRepository(server.url("/api/").toString(), { null }, client = client)
        var subscriptions = 0
        val repository = object : AgentRepository by http {
            /** 15.5返回页先只读恢复，再续接SSE；替身显式实现真实空页，不能让默认503冒充成功。 */
            override suspend fun loadHistory(sessionId: String, cursor: String?, limit: Int) = com.fandazi.app.core.network.ApiResult.Success(
                AgentHistoryPage(sessionId,emptyList(),emptyList(),0,null,"empty",null,null,null))
            override fun streamEvents(sessionId: String, afterEventId: Long?) = kotlinx.coroutines.flow.emptyFlow<AgentEvent>().also { subscriptions++ }
        }
        val model = AgentViewModel(repository, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        model.openAssistant();model.sendText("hello");runCurrent()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));model.closeAssistant();runCurrent()
        assertTrue("离开聊天页普通HTTP未取消", active.get()?.isCanceled() == true)
        model.openAssistant();runCurrent();assertEquals(1, server.requestCount);assertEquals(2, subscriptions)
        assertFalse(model.uiState.value.messages.any { it.text.contains("已回滚") || it.text.contains("已撤销交易") })
        model.bindAuthenticatedOwner("new-owner");runCurrent()
    }
}
