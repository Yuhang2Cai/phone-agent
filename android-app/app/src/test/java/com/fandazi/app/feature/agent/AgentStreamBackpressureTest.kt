// SSE真实传输回归：用本机OkHttp连接验证有界背压、数据库游标和取消，不能以假Flow替代交付链路。
package com.fandazi.app.feature.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException

/** 16槽必须容纳不了1000突发事件；暂停真实消费300ms，数量断言直接揭示trySend忽略失败的回归。 */
class AgentStreamBackpressureTest {
    private lateinit var server: MockWebServer

    /** 每例独立端口和连接，避免上一例重连请求污染游标断言。 */
    @Before fun prepare() { server = MockWebServer().also { it.start() } }

    /** 释放真实服务端连接；运行超时保护在各用例内部，不把进程卡住当成通过。 */
    @After fun release() { server.shutdown() }

    /** 终态和文字事件全部交付，指定数量断言在旧实现上失败，环境或路径错误不算建立步骤通过。 */
    @Test fun saturatedBufferDeliversAllThousandEventsInOrder() = runBlocking {
        server.enqueue(sse(1, 1000))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val received = CopyOnWriteArrayList<AgentEvent>()
        val repository = repository()
        withTimeoutOrNull(2000) {
            repository.streamEvents("saturation", 0).buffer(16).take(1000).collect { event ->
                received += event
                // 第一条已进入业务消费，随后暂停让真实生产者面对队列饱和，而不是只延迟连接建立。
                if (received.size == 1) delay(300)
            }
        }
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/agent/sessions/saturation/events", request?.path)
        assertTrue("没有事件输入，不能认定复现饱和", received.isNotEmpty())
        println("SSE_SATURATION input=1000 capacity=16 pauseMs=300 delivered=${received.size} terminal=${received.any { it.type == "completed" }}")
        assertEquals("饱和导致事件缺失", 1000, received.size)
        assertEquals((1L..1000L).toList(), received.map { it.id })
        assertEquals("completed", received.last().type)
    }

    /** 消费者等待时取消必须关闭活动Call，不能留下堵塞生产者或继续向旧页面交付事件。 */
    @Test fun cancellationWhileFullReleasesActiveCall() = runBlocking {
        val active = AtomicReference<Call?>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { active.set(call) }
        }).build()
        server.enqueue(sse(1, 1000))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Long?>()
        val collect = launch(Dispatchers.Default) {
            repository(client).streamEvents("cancel-full", 0).buffer(16).collect { event ->
                seen += event.id; started.complete(Unit); blocked.await()
            }
        }
        withTimeout(2000) { started.await() }
        delay(300)
        withTimeout(2000) { collect.cancelAndJoin() }
        assertTrue("活动OkHttp连接未取消", active.get()?.isCanceled() == true)
        withTimeout(2000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        assertEquals("取消后旧消费者继续收到回调", listOf(1L), seen.toList())
    }

    /** 第一段400条交付成功后才用400续接；所有1000条必须顺序完整，读到但未交付不能跳过。 */
    @Test fun disconnectResumesAfterDeliveredDatabaseCursor() = runBlocking {
        server.enqueue(sse(1, 400)); server.enqueue(sse(401, 1000))
        val received = CopyOnWriteArrayList<AgentEvent>()
        withTimeoutOrNull(3000) {
            repository().streamEvents("reconnect", 0).buffer(16).take(1000).collect { event ->
                received += event
                if (received.size == 1) delay(300)
            }
        }
        assertEquals("断流续接丢失未交付事件", 1000, received.size)
        assertEquals((1L..1000L).toList(), received.map { it.id })
        assertEquals("0", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
        assertEquals("400", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
        assertEquals("completed", received.last().type)
    }

    /** SSE行id是数据库恢复游标；JSON内id、eventId和sequence属于协议身份，不得覆盖它。 */
    @Test fun databaseSseIdWinsOverPayloadIdentity() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "id: 7\ndata: {\"id\":999,\"type\":\"text_delta\",\"payload\":{\"sequence\":\"99\",\"eventId\":\"protocol-7\"}}\n\n"
        ))
        server.enqueue(sse(8, 8))
        val events = withTimeout(2000) { repository().streamEvents("identity", 0).take(2).toList() }
        assertEquals(listOf(7L, 8L), events.map { it.id })
        assertEquals("0", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
        assertEquals("7", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
        assertEquals("99", events.first().payload["sequence"])
    }

    /** 没有SSE数据库id时保留原恢复游标，不能把JSON协议id错误当作已持久化的新位置。 */
    @Test fun missingDatabaseIdDoesNotAdvanceFromJsonIdentity() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"id\":999,\"type\":\"text_delta\",\"payload\":{\"sequence\":\"1\"}}\n\n"
        ))
        server.enqueue(sse(8, 8))
        val events = withTimeout(2000) { repository().streamEvents("no-database-id", 0).take(2).toList() }
        assertNull(events.first().id)
        assertEquals(8L, events.last().id)
        assertEquals("0", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
        assertEquals("0", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Last-Event-ID"))
    }

    /** 三次连续服务故障后必须停止；403也停止，不永久重连或把权限失败变成注销。 */
    @Test fun failuresRespectBudgetAndForbiddenStops() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val failure = runCatching { withTimeout(2000) { repository().streamEvents("fail-budget", 0).toList() } }.exceptionOrNull()
        assertTrue("应传播实际HTTP故障，而不是测试超时", failure is IOException)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"error\":\"合成权限拒绝\"}"))
        val forbidden = runCatching { withTimeout(2000) { repository().streamEvents("forbidden", 0).toList() } }.exceptionOrNull()
        assertTrue("403应停止并传播服务器错误", forbidden is IOException && forbidden.message == "合成权限拒绝")
        assertEquals(4, server.requestCount)
    }

    /** 使用真实仓库与本机服务，重连100ms既可稳定观察续接，也能限制失败预算用例的运行时间。 */
    private fun repository(client: OkHttpClient? = null) = HttpAgentRepository(
        server.url("/api/").toString(), { null }, client = client, reconnectDelayMillis = 100
    )

    /** 生成明确数据库id和最后completed的有限真实SSE字节；没有确认凭证或交易写入。 */
    private fun sse(start: Int, end: Int) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        (start..end).joinToString("") { id ->
            "id: $id\ndata: {\"id\":$id,\"type\":\"${if (id == 1000) "completed" else "text_delta"}\",\"payload\":{\"delta\":\"固定回放\"}}\n\n"
        }
    )
}
