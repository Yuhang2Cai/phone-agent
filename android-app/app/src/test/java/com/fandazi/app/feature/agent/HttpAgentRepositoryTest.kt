// 智能助手模块：验证关键行为与边界条件。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAgentRepositoryTest {
    private lateinit var server: MockWebServer

// 为测试准备依赖、替身和初始状态。
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
// 清理测试创建的资源与状态。
    @After fun tearDown() { server.shutdown() }

    @Test
// 验证 `sendsOnlyBearerTokenToPublicAgentMessageApi` 覆盖场景的预期行为与边界。
    fun sendsOnlyBearerTokenToPublicAgentMessageApi() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"status":"COMPLETED","message":"已完成","nextAction":"none","candidateCart":{},"events":[]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { "session-jwt" })

        val result = repository.sendMessage("session-1", "午餐", AgentMode.CONFIRM)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/api/agent/sessions/session-1/messages", request.path)
        assertEquals("Bearer session-jwt", request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Agent-Token"))
        assertTrue(request.body.readUtf8().contains("\"mode\":\"CONFIRM\""))
    }

    @Test
// 验证 `sseReconnectsWithLastEventIdAfterDisconnectAndContinuesEvents` 覆盖场景的预期行为与边界。
    fun sseReconnectsWithLastEventIdAfterDisconnectAndContinuesEvents() = runBlocking {
        server.enqueue(sse(1, "parsed", "已接收点餐请求"))
        server.enqueue(sse(2, "completed", "已生成购物车"))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null }, reconnectDelayMillis = 50)

        val events = withTimeout(2_000) { repository.streamEvents("session-1", 0).take(2).toList() }

        assertEquals(listOf(1L, 2L), events.map(AgentEvent::id))
        assertEquals("0", server.takeRequest().getHeader("Last-Event-ID"))
        assertEquals("1", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test
// 验证 `acceptsOnlyCompleteGatewayConfirmationCart` 覆盖场景的预期行为与边界。
    fun acceptsOnlyCompleteGatewayConfirmationCart() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"status":"AWAITING_CONFIRMATION","message":"请确认","candidateCart":{
              "merchantName":"清粥小菜","items":[{"name":"南瓜小米粥","specification":"去香菜","price":12,"quantity":1}],
              "address":"公司默认地址","addressId":9,"deliveryFee":3,"discount":0,"total":14,"maxAmount":30,
              "authorizationMode":"CONFIRM"
            },"events":[]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null })

        val result = repository.sendMessage("session-1", "清淡午餐", AgentMode.CONFIRM) as ApiResult.Success

        assertEquals("清粥小菜", result.value.candidateCart?.merchantName)
        assertEquals("¥3", result.value.candidateCart?.deliveryFee)
        assertEquals("¥14", result.value.candidateCart?.total)
        assertEquals(9L, result.value.candidateCart?.addressId)
        assertEquals(AgentMode.CONFIRM, result.value.candidateCart?.authorizationMode)
        assertTrue(result.value.candidateCart?.isCompleteForConfirmation() == true)
    }

    @Test
// 验证 `keepsCreatedOrderIdForPaymentHandoff` 覆盖场景的预期行为与边界。
    fun keepsCreatedOrderIdForPaymentHandoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"status":"COMPLETED","message":"订单已创建，请继续支付。","nextAction":"payment_followup","candidateCart":{
              "merchantName":"清粥小菜","items":[{"name":"南瓜小米粥","specification":"去香菜","price":12,"quantity":1}],
              "address":"公司默认地址","addressId":9,"deliveryFee":3,"discount":0,"total":14,"maxAmount":30,
              "authorizationMode":"CONFIRM","orderId":42
            },"events":[]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null })

        val result = repository.sendMessage("session-1", "确认下单", AgentMode.CONFIRM) as ApiResult.Success

        assertEquals(42L, result.value.candidateCart?.orderId)
    }

    @Test
// 验证 `retriesSseAfterInitialSessionNotFoundAndRetainsCursor` 覆盖场景的预期行为与边界。
    fun retriesSseAfterInitialSessionNotFoundAndRetainsCursor() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"会话不存在\"}"))
        server.enqueue(sse(3, "parsed", "已接收点餐请求"))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null }, reconnectDelayMillis = 50)

        val event = withTimeout(2_000) { repository.streamEvents("session-1", 2).take(1).toList().single() }

        assertEquals(3L, event.id)
        assertEquals("2", server.takeRequest().getHeader("Last-Event-ID"))
        assertEquals("2", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test
// 验证 `revokesTaskGrantThroughPublicApi` 覆盖场景的预期行为与边界。
    fun revokesTaskGrantThroughPublicApi() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { "session-jwt" })

        val result = repository.revokeThisTaskAuto("session-1")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/automation/task-grants?sessionId=session-1", request.path)
        assertEquals("Bearer session-jwt", request.getHeader("Authorization"))
    }

// 验证 `sse` 覆盖场景的预期行为与边界。
    private fun sse(id: Long, type: String, summary: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("id: $id\nevent: agent-event\ndata: {\"id\":$id,\"type\":\"$type\",\"summary\":\"$summary\",\"status\":\"completed\"}\n\n")
}
