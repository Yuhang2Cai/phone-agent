// 初始SSE测量入口：真实OkHttp接收1000突发事件并暂停消费者；只记录基线，不把丢事件视为功能通过。
package com.fandazi.app.feature.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 固定16槽队列和300毫秒停顿；后续可靠性验收另由AgentStreamBackpressureTest要求全量交付。 */
class PerformanceReplayInputTest {
    /** 回放协议逐条递增、最后completed；记录已收数量/终态/单调性，避免把环境失败当测量结果。 */
    @Test fun recordsBurstInputAndSlowConsumer() = runBlocking {
        val server = MockWebServer()
        val received = CopyOnWriteArrayList<AgentEvent>()
        val body = (1..1000).joinToString("") { id ->
            "id: $id\ndata: {\"id\":$id,\"type\":\"${if (id == 1000) "completed" else "text_delta"}\",\"summary\":\"回放\",\"payload\":{\"delta\":\"固定文本\"}}\n\n"
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        // 第一连接之后保持读等待，测试超时取消必须真正释放当前Call，不能伪造1000事件后续补齐。
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val repository = HttpAgentRepository(server.url("/api/").toString(), { null }, reconnectDelayMillis = 100)
            withTimeoutOrNull(1500) {
                repository.streamEvents("fixed-perf-session", 0).buffer(16).collect { event ->
                    received += event
                    if (received.size == 1) delay(300)
                }
            }
            val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("/api/agent/sessions/fixed-perf-session/events", request?.path)
            assertEquals("0", request?.getHeader("Last-Event-ID"))
            assertTrue("没有实际接收事件，不能作为基线", received.isNotEmpty())
            assertTrue("同连接事件次序错误", received.zipWithNext().all { (a, b) -> a.id!! < b.id!! })
            println("PERF_INPUT input=1000 capacity=16 pauseMs=300 received=${received.size} terminal=${received.any { it.type == "completed" }} lastId=${received.last().id}")
        } finally {
            server.shutdown()
        }
    }
}
