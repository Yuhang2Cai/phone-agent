// 普通执行器生命周期回归：真实连接覆盖读体关闭、取消异常、超时和实际凭据失效边界。
package com.fandazi.app.core.network

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 不共享SSE重连器；所有用例使用一次普通HTTP调用和独立本机服务。 */
class CoroutineCallExecutorTest {
    private lateinit var server: MockWebServer
    /** 每例真实服务端独立，合成授权值仅用于请求身份比较，不与实际账号关联。 */
    @Before fun prepare() { server = MockWebServer().also { it.start() } }
    /** 释放真实端口；取消断言与调用数不能由服务端销毁代替。 */
    @After fun release() { server.shutdown() }
    /** 读体后Response关闭且Call完成，成功结果不得持有活跃Response或流。 */
    @Test fun successClosesResponseAndCompletesCall() = runBlocking {
        val ended = AtomicInteger();val closed = AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyEnd(call: Call, byteCount: Long) { closed.incrementAndGet() }
            override fun callEnd(call: Call) { ended.incrementAndGet() }
        }).build()
        server.enqueue(MockResponse().setBody("result"))
        assertEquals(ApiResult.Success("result"), CoroutineCallExecutor(client).execute(request()))
        // callEnd先于dispatcher移除运行项，等待两者都完成，避免把合法回调时序误报为资源泄漏。
        withTimeout(1000) { while (ended.get() != 1 || client.dispatcher.runningCallsCount() != 0) delay(10) }
        assertEquals(1, closed.get());assertEquals(0, client.dispatcher.runningCallsCount())
    }
    /** 超时仍是Timeout类别，不能因改成异步而误变成普通Network或自动重试。 */
    @Test fun timeoutPreservesApiErrorCategory() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("{}"))
        val result = CoroutineCallExecutor(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()).execute(request())
        assertEquals(ApiResult.Failure(ApiError.Timeout), result);assertEquals(1, server.requestCount)
    }
    /** 只使用真实已发送请求Authorization，403不调用身份失效，401回调实际旧凭据而非新供应器值。 */
    @Test fun unauthorizedUsesActualCredentialAndForbiddenDoesNotInvalidate() = runBlocking {
        var current = "old-synthetic-token";val rejected = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $current").build()) }.build()
        val executor = CoroutineCallExecutor(client, onUnauthorized = { rejected += it; if (it == current) current = "" })
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"身份失效\"}").setHeadersDelay(250, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.Default) { executor.execute(request()) }
        assertEquals("Bearer old-synthetic-token", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Authorization"));current = "new-synthetic-token"
        assertEquals(ApiResult.Failure(ApiError.Http(401, "身份失效")), pending.await());assertEquals(listOf("old-synthetic-token"), rejected.toList());assertEquals("new-synthetic-token", current)
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"权限不足\"}"))
        assertEquals(ApiResult.Failure(ApiError.Http(403, "权限不足")), executor.execute(request()));assertEquals(1, rejected.size)
    }
    /** 取消后的返回仍抛CancellationException，不能以Failure给仓库一个可自动重试的网络结果。 */
    @Test fun cancellationPropagatesInsteadOfFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(1000, TimeUnit.MILLISECONDS))
        val client = OkHttpClient();val pending = async(Dispatchers.Default) { CoroutineCallExecutor(client).execute(request()) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));pending.cancel()
        val failure = runCatching { pending.await() }.exceptionOrNull();assertTrue(failure is CancellationException)
        withTimeout(1000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
        assertEquals(1, server.requestCount)
    }
    /** 本机测试URI，无真实业务数据；执行器只处理连接与结果映射。 */
    private fun request() = Request.Builder().url(server.url("/api/test")).build()
}
