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

    @Test
    /** 通用准备确认使用公开摘要形成唯一卡片，不要求旧外卖地址，也不保存内部准备凭证。 */
    fun publicPurchasePreparationBecomesSafeConfirmationCard() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"status":"AWAITING_CONFIRMATION","message":"确认创建团购订单","nextAction":"confirmation_required",
             "candidateCart":{"action":"create_group_buy_order","resourceId":"isolated-quote","title":"确认创建团购订单",
             "amountCents":1200,"quantity":1,"ruleVersion":"deal-v1","expiresAt":"2030-01-01T00:00:00Z","confirmationGrantId":"hidden"},
             "events":[],"cards":[]}
        """.trimIndent()))
        val result = HttpAgentRepository(server.url("/api/").toString(), { null })
            .sendMessage("isolated", "准备购买", AgentMode.CONFIRM) as ApiResult.Success
        assertEquals("confirmation", result.value.cards.single().type)
        assertEquals("isolated-quote", result.value.cards.single().fields["resourceId"])
        assertTrue(result.value.cards.single().fields.values.none { it.contains("hidden") })
        assertNull(result.value.candidateCart)
    }

// 为测试准备依赖、替身和初始状态。
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
// 清理测试创建的资源与状态。
    @After fun tearDown() { server.shutdown() }

    @Test
    /** 同一公开确认卡已有完整补全时不得重复追加候选卡；私有字段不能进入页面模型。 */
    fun enrichedConfirmationIsSingleAndDropsPrivateFields() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"status":"AWAITING_CONFIRMATION","message":"核对","nextAction":"confirm_refund",
             "candidateCart":{"voucherId":"voucher-x","reason":"行程变更","confirmationGrant":"hidden"},
             "cards":[{"type":"after_sale_refund","voucherId":"voucher-x","storeName":"真实门店",
             "dealTitle":"真实套餐","amountCents":3333,"confirmationGrant":"hidden","privateToken":"hidden"}]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null })
        val result = repository.sendMessage("safe-session", "原因", AgentMode.CONFIRM) as ApiResult.Success
        assertEquals(1, result.value.cards.size)
        assertEquals("真实门店", result.value.cards.single().fields["storeName"])
        assertTrue(result.value.cards.single().fields.keys.none { it in setOf("confirmationGrant", "privateToken") })
    }

    @Test
    /** SSE原文字符串应正确解码，私有嵌套负载必须过滤，而待输入类型和状态仍完整保留。 */
    fun waitingEventPreservesPublicTextAndRemovesPrivatePayload() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "id: 6\ndata: {\"type\":\"input_required\",\"summary\":\"请补充原因\",\"status\":\"waiting_input\",\"payload\":{\"reason\":\"a\\nb\",\"confirmationGrant\":\"hidden\",\"nested\":{\"privateToken\":\"hidden\"}}}\n\n"
        ))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { null })
        val event = withTimeout(2000) { repository.streamEvents("safe-session", 0).take(1).toList().single() }
        assertEquals("input_required", event.type)
        assertEquals("waiting_input", event.status)
        assertEquals("a\nb", event.payload["reason"])
        assertTrue(event.payload.values.none { it.contains("hidden") })
    }

    @Test
    /** 售后确认摘要应进入独立卡片，且绝不能把一次性确认凭证或旧外卖购物车传给页面。 */
    fun refundConfirmationUsesSafeCardWithoutLegacyCart() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"status":"AWAITING_CONFIRMATION","message":"请核对退款","nextAction":"confirm_refund",
             "candidateCart":{"voucherId":"voucher-7","orderId":"order-3","dealTitle":"双人餐",
             "amountCents":8800,"expiresAt":"2026-12-31","reason":"行程变更","confirmationGrant":"should-not-render"},
             "events":[],"cards":[]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { "session-jwt" })

        val result = repository.sendMessage("session-1", "行程变更", AgentMode.CONFIRM) as ApiResult.Success

        assertNull(result.value.candidateCart)
        assertEquals("after_sale_refund", result.value.cards.single().type)
        assertEquals("行程变更", result.value.cards.single().fields["reason"])
        assertTrue("confirmationGrant" !in result.value.cards.single().fields)
    }

    @Test
    /** 后端待选券结果必须产生可点击的本人券卡，私有字段不进入页面模型。 */
    fun voucherSelectionCreatesSafeChoiceCard() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"status":"NEEDS_INPUT","message":"请选择团购券","nextAction":"select_voucher",
             "candidateCart":{"vouchers":[{"voucherId":"voucher-1","dealTitle":"双人餐",
             "storeName":"川味小馆","expiresAt":"2026-12-31","privateToken":"secret"}]},"events":[]}
        """.trimIndent()))
        val repository = HttpAgentRepository(server.url("/api/").toString(), { "session-jwt" })

        val result = repository.sendMessage("session-1", "我想申请退款", AgentMode.CONFIRM) as ApiResult.Success

        assertEquals(AgentStatus.NEEDS_INPUT, result.value.status)
        assertEquals("after_sale_voucher_choice", result.value.cards.single().type)
        assertEquals("voucher-1", result.value.cards.single().fields["voucherId"])
        assertTrue("privateToken" !in result.value.cards.single().fields)
    }

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
