// 公开业务API身份装配回归：使用真实Application的ApiClient及SessionRepository，防止仅单测回调漏接正式应用。
package com.fandazi.app.core.network

import com.fandazi.app.FandaziApplication
import com.fandazi.app.feature.auth.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 地址/存储仅在测试中替换；保留应用正式执行器、请求凭据及失效回调装配。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FandaziApplication::class)
class ApiClientIdentityTest {
    private lateinit var server: MockWebServer
    private lateinit var app: FandaziApplication
    private lateinit var storage: IdentityStorage
    /** 注入已有身份模型的合成值，测试不读取机器上的登录数据。 */
    @Before fun prepare() {
        server = MockWebServer().also { it.start() };app = RuntimeEnvironment.getApplication() as FandaziApplication;storage = IdentityStorage()
        val gateway = object : AuthGateway {
            override suspend fun login(username: String, password: String) = ApiResult.Success(AuthResponse("new-synthetic-token", User(username,"synthetic@example.invalid")))
            override suspend fun register(username: String, email: String, password: String) = login(username,password)
        }
        FandaziApplication::class.java.getDeclaredField("sessionRepository").apply { isAccessible = true }.set(app, SessionRepository(gateway,storage))
        ApiClient::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }.set(app.apiClient,server.url("/api/").toString())
    }
    /** 释放服务端端口，真实调用已由执行器完成或取消。 */
    @After fun release() { server.shutdown() }
    /** 真实业务API401必须清理唯一身份源，不能只向当前页面返回错误。 */
    @Test fun current401InvalidatesApplicationIdentity() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401));assertTrue(app.apiClient.get("group-orders") is ApiResult.Failure)
        assertEquals(SessionState.Unauthenticated,app.sessionRepository.state.value);assertNull(storage.value)
    }
    /** 同账号或新账号登录后的新凭据不被旧401清除，判断必须比较实际请求令牌。 */
    @Test fun late401KeepsNewLogin() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(250,TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.Default) { app.apiClient.get("group-orders") }
        assertEquals("Bearer old-synthetic-token",server.takeRequest(2,TimeUnit.SECONDS)?.getHeader("Authorization"))
        app.sessionRepository.login("new-owner","synthetic-password");pending.await()
        assertEquals("new-synthetic-token",app.sessionRepository.tokenOrNull());assertEquals("new-synthetic-token",storage.value?.token)
    }
    /** 403只代表权限不足，503只代表服务不可用，均不等于凭据失效。 */
    @Test fun forbiddenAndServiceFailureDoNotLogout() = runBlocking {
        for (code in listOf(403,503)) { server.enqueue(MockResponse().setResponseCode(code));assertTrue(app.apiClient.get("group-orders") is ApiResult.Failure);assertEquals("old-synthetic-token",app.sessionRepository.tokenOrNull()) }
    }
    /** 简单内存持久化边界用于验证真实会话清理，不包含门店或订单假数据。 */
    private class IdentityStorage : SessionStorage {
        var value: PersistedSession? = PersistedSession("old-synthetic-token",User("old-owner","synthetic@example.invalid"))
        override val initializationError: Throwable? = null
        override fun read() = Result.success(value)
        override suspend fun write(value: PersistedSession): Boolean { this.value = value;return true }
        override suspend fun clear(): Boolean { value = null;return true }
    }
}
