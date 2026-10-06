// 续期发包边界回归：真实OkHttp与MockWebServer计数，证明写0重放、读取最多一次认证重读。
package com.fandazi.app.core.network
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.async

/** 仅发本机合成请求，不使用真实账户、后端业务或静态固定JWT。 */
class SessionRenewalInterceptorTest {
    /** 两个业务名额全部等待认证时，独立刷新仍真实发包完成，防止同一Dispatcher造成死锁。 */
    @Test fun saturatedBusinessDispatcherStillAllowsRefresh()=kotlinx.coroutines.runBlocking {
        MockWebServer().use {server->server.start();server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("{}")}
            val dispatcher=okhttp3.Dispatcher().apply{maxRequests=2;maxRequestsPerHost=2}
            val shared=OkHttpClient.Builder().dispatcher(dispatcher).build()
            val auth=ApiClient(server.url("/api/").toString(),{null},isolatedAuthenticationClient(shared))
            val entered=java.util.concurrent.CountDownLatch(2);val lock=kotlinx.coroutines.sync.Mutex();var token:String?=null
            val business=shared.newBuilder().addInterceptor(SessionRenewalInterceptor {
                entered.countDown();check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
                lock.lock()
                try{if(token==null){check(auth.postJson("auth/refresh","{}") is ApiResult.Success);token="synthetic-new"};ApiResult.Success(token)}finally{lock.unlock()}
            }).build()
            val executor=CoroutineCallExecutor(business)
            kotlinx.coroutines.withTimeout(10_000L) {
                val first=async {executor.execute(Request.Builder().url(server.url("/api/users/me")).build())}
                val second=async {executor.execute(Request.Builder().url(server.url("/api/users/me")).build())}
                assertTrue(first.await() is ApiResult.Success);assertTrue(second.await() is ApiResult.Success)
            }
            assertEquals(3,server.requestCount)
        }
    }
    /** 普通写先续期后只提交一次；真实401后更新后续凭据仍不重发本次写。 */
    @Test fun write401NeverResendsBusinessRequest() {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(401))
            val forces=mutableListOf<String?>()
            val client=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(SessionRenewalInterceptor {force->forces+=force;ApiResult.Success(if(force==null)"old" else "new")}).build()
            client.newCall(Request.Builder().url(server.url("/api/purchase/orders")).post("{}".toRequestBody()).build()).execute().use{assertEquals(401,it.code)}
            assertEquals(1,server.requestCount);assertEquals(listOf(null,"old"),forces)
        }
    }
    /** 只读接口可在401后用新凭据读一次，不能进入无限认证重试。 */
    @Test fun read401RefreshesAndRetriesExactlyOnce() {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(401));server.enqueue(MockResponse().setBody("{}"))
            val client=OkHttpClient.Builder().addInterceptor(SessionRenewalInterceptor{force->ApiResult.Success(if(force==null)"old" else "new")}).build()
            client.newCall(Request.Builder().url(server.url("/api/users/me")).build()).execute().use{assertEquals(200,it.code)}
            assertEquals(2,server.requestCount);assertEquals("Bearer old",server.takeRequest().getHeader("Authorization"));assertEquals("Bearer new",server.takeRequest().getHeader("Authorization"))
        }
    }
    /** 续期断网失败时原业务写根本不能到达服务端，防止过期凭据继续提交。 */
    @Test fun refreshNetworkFailurePreventsBusinessWrite() {
        MockWebServer().use {server->server.start()
            val client=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(SessionRenewalInterceptor{ApiResult.Failure(ApiError.Network("合成断网"))}).build()
            try{client.newCall(Request.Builder().url(server.url("/api/purchase/orders")).post("{}".toRequestBody()).build()).execute();fail("续期失败应阻止业务写")}catch(expected:IOException){assertEquals(0,server.requestCount)}
        }
    }
    /** 无效登录不影响普通公开目录读取；认证接口也必须绕开刷新，防止递归。 */
    @Test fun publicAndAuthenticationRequestsNeverRecursivelyRefresh() {
        MockWebServer().use {server->server.start();var renewals=0
            val client=OkHttpClient.Builder().addInterceptor(SessionRenewalInterceptor{renewals++;ApiResult.Failure(ApiError.Network("不应调用"))}).build()
            for(path in listOf("/api/stores","/api/deals/synthetic","/api/auth/refresh")) {
                server.enqueue(MockResponse().setBody("{}"));client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
            }
            assertEquals(0,renewals);assertEquals(3,server.requestCount)
        }
    }
    /** 已取消调用不触发任何续期或业务网络动作。 */
    @Test fun cancelledCallNeverRenewsOrSends() {
        MockWebServer().use {server->server.start();var renewals=0
            val client=OkHttpClient.Builder().addInterceptor(SessionRenewalInterceptor{renewals++;ApiResult.Success("synthetic")}).build()
            val call=client.newCall(Request.Builder().url(server.url("/api/users/me")).build());call.cancel()
            try{call.execute();fail("取消必须传播")}catch(expected:IOException){assertEquals(0,renewals);assertEquals(0,server.requestCount)}
        }
    }
}
