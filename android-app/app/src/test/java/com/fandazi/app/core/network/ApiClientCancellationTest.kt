// 普通HTTP取消回归：通过真实OkHttp与本机服务证明Call释放、有限重试和旧筛选隔离，不涉及ASR。
package com.fandazi.app.core.network

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 测试公开ApiClient，不以假Flow模拟取消；旧实现阻塞读取应在活动Call取消断言上失败。 */
class ApiClientCancellationTest {
    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<Call>()
    /** 每例独立真实服务端，事件监听保存Call对象，不记录请求令牌或响应正文。 */
    @Before fun prepare() { server = MockWebServer().also { it.start() } }
    /** 关闭服务端以及有限延迟连接，避免上例请求污染次数断言。 */
    @After fun release() { server.shutdown() }
    /** 延迟响应头时页面退出，必须在150ms内取消真实连接而非等待服务端回包。 */
    @Test fun cancellingDelayedHeadersCancelsRealCall() = runBlocking {
        assertWaitingCallCancelled(MockResponse().setBody("{}").setHeadersDelay(1200, TimeUnit.MILLISECONDS))
    }
    /** 响应头已到但读体被延迟时也必须取消，防止仅取消协程结果而持续占用网络线程。 */
    @Test fun cancellingDelayedBodyCancelsRealCall() = runBlocking {
        assertWaitingCallCancelled(MockResponse().setBody("{}").setBodyDelay(1200, TimeUnit.MILLISECONDS))
    }
    /** 已取消任务不能创建任何请求，包括进入IO调度之前取消的情况。 */
    @Test fun alreadyCancelledTaskCreatesNoCall() = runBlocking {
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { client().get("stores") }
        job.cancel(); job.join(); assertTrue(calls.isEmpty()); assertEquals(0, server.requestCount)
    }
    /** 第一次GET失败回调取消原任务后不得再发第二次请求，真实服务端计数证明没有自动续发。 */
    @Test fun cancelledGetFailureDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)); server.enqueue(MockResponse().setBody("{}"))
        val jobRef = AtomicReference<Job?>()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
            override fun callFailed(call: Call, ioe: IOException) { jobRef.get()?.cancel() }
        }).build()
        val api = ApiClient(server.url("/api/").toString(), { null }, http)
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { api.get("stores") }; jobRef.set(job);job.start()
        withTimeout(3000) { job.join() }
        assertEquals("GET取消后仍重试", 1, calls.size); assertEquals(1, server.requestCount)
    }
    /** GET仍保留最多一次网络重试；重构不能为了取消而删除已有恢复能力。 */
    @Test fun getNetworkFailureRetriesAtMostOnce() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        assertTrue(client().get("stores") is ApiResult.Failure); assertEquals(2, calls.size)
    }
    /** 写请求断连只能返回状态未知的网络失败，不自动重试，避免重复下单或退款。 */
    @Test fun postAndPatchNeverRetryAfterDisconnect() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val api = client(); assertTrue(api.postJson("write", "{}") is ApiResult.Failure)
        assertTrue(api.patch("write", "{}") is ApiResult.Failure);assertEquals(2, calls.size)
    }
    /** 替换筛选时旧工作取消并释放Call；慢旧响应不会覆写快速新筛选结果。 */
    @Test fun replacingFilterCancelsOldRequestAndOnlyPublishesNewResult() = runBlocking {
        server.enqueue(MockResponse().setBody("old").setBodyDelay(1200, TimeUnit.MILLISECONDS));server.enqueue(MockResponse().setBody("new"))
        val api = client();val shown = CopyOnWriteArrayList<String>()
        val old = launch(Dispatchers.Default) { (api.get("stores?filter=old") as? ApiResult.Success)?.let { shown += it.value } }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));delay(50);old.cancel()
        val latest = api.get("stores?filter=new");assertTrue(latest is ApiResult.Success);shown += (latest as ApiResult.Success).value
        withTimeout(3000) { old.join() };assertEquals(listOf("new"), shown.toList());assertTrue("旧筛选Call未释放", calls.first().isCanceled())
    }
    /** 使用有限延迟避免旧版本红灯等待20秒超时；关键断言依然在取消后150ms观察真实Call。 */
    private suspend fun CoroutineScope.assertWaitingCallCancelled(response: MockResponse) {
        server.enqueue(response);val job = launch(Dispatchers.Default) { client().get("stores") }
        try {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));delay(50);job.cancel();delay(150)
            assertTrue("协程取消没有传到OkHttp Call", calls.single().isCanceled())
            withTimeout(1000) { job.join() };assertEquals(0, calls.single().clientRunningCount())
        } finally { job.cancelAndJoin() }
    }
    // Call取消和join是关键证据；运行数由共享客户端的dispatcher查询，避免复制网络生命周期。
    private lateinit var observedClient: OkHttpClient
    private fun Call.clientRunningCount(): Int = observedClient.dispatcher.runningCallsCount()
    /** 注入真实事件监听，仅记录生命周期对象，客户端仍由产品ApiClient派生既有超时和认证设置。 */
    private fun client(): ApiClient {
        observedClient = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
        return ApiClient(server.url("/api/").toString(), { null }, observedClient)
    }
}
