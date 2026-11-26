// 会话失效回归：验证真实应用装配的 Agent HTTP/SSE 与全局登录态保持一致。
package com.fandazi.app.feature.agent

import com.fandazi.app.FandaziApplication
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.auth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 网络仅替换为本地服务器；保留应用初始化的仓库与令牌供应器，防止只测独立回调而漏掉装配。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FandaziApplication::class)
class AgentSessionExpiryTest {
    private lateinit var server: MockWebServer
    private lateinit var app: FandaziApplication
    private lateinit var storage: TestStorage

    /** 注入合成账号和可控存储，不读取开发机器或模拟器的真实凭据。 */
    @Before fun prepare() {
        server = MockWebServer().also { it.start() }
        app = RuntimeEnvironment.getApplication() as FandaziApplication
        // 身份失效场景要求请求真实发出；新离线门禁的网络前置明确设为在线。
        com.fandazi.app.core.cache.CatalogOnlineTestEnvironment.establish(app)
        storage = TestStorage()
        val gateway = object : AuthGateway {
            override suspend fun login(username: String, password: String) =
                ApiResult.Success(AuthResponse("new-test-token", User(username, "synthetic@example.invalid")))
            override suspend fun register(username: String, email: String, password: String) = login(username, password)
        }
        // BuildConfig 的正式地址不可运行期更改；反射仅替换测试地址及存储，不修改认证实现。
        FandaziApplication::class.java.getDeclaredField("sessionRepository").apply { isAccessible = true }
            .set(app, SessionRepository(gateway, storage))
        HttpAgentRepository::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
            .set(app.agentRepository, server.url("/api/").toString())
    }

    /** 每例释放本地端口，流式失败用超时保护，避免把永不结束误当成功。 */
    @After fun release() { server.shutdown() }

    @Test
    /** 消息接口确实返回401后，“我的”不能仍展示已登录，拒绝的令牌必须从存储移除。 */
    fun message401InvalidatesApplicationSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Authentication is required\"}"))
        assertTrue(app.agentRepository.sendMessage("synthetic-session", "hello", AgentMode.CONFIRM) is ApiResult.Failure)
        assertEquals(SessionState.Unauthenticated, app.sessionRepository.state.value)
        assertNull(storage.session)
    }

    @Test
    /** SSE连接的401同样同步全局身份，不能只转换成泛化网络错误并保留过期凭据。 */
    fun stream401InvalidatesApplicationSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { withTimeout(3000) { app.agentRepository.streamEvents("synthetic-session", 0).toList() } }
        assertTrue(failure.isFailure)
        assertEquals(SessionState.Unauthenticated, app.sessionRepository.state.value)
        assertNull(storage.session)
    }

    @Test
    /** 请求发出后重新登录：旧令牌的迟到401不得清除新的身份或新的持久化会话。 */
    fun delayed401CannotLogoutNewLogin() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.IO) { app.agentRepository.sendMessage("synthetic-session", "hello", AgentMode.CONFIRM) }
        assertEquals("Bearer old-test-token", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Authorization"))
        app.sessionRepository.login("new-owner", "synthetic-password")
        pending.await()
        assertEquals("new-test-token", app.sessionRepository.tokenOrNull())
        assertEquals("new-test-token", storage.session?.token)
    }

    @Test
    /** 权限拒绝和Agent故障不是登录失效；保留当前身份，禁止以注销掩盖服务错误。 */
    fun forbiddenAndServiceUnavailableKeepSession() = runBlocking {
        for (code in listOf(403, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue(app.agentRepository.sendMessage("synthetic-session", "hello", AgentMode.CONFIRM) is ApiResult.Failure)
            assertEquals("old-test-token", app.sessionRepository.tokenOrNull())
        }
    }

    /** 只提供认证持久化边界，不制造任何门店、套餐或订单事实。 */
    private class TestStorage : SessionStorage {
        var session: PersistedSession? = PersistedSession("old-test-token", User("old-owner", "synthetic@example.invalid"))
        override val initializationError: Throwable? = null
        override fun read() = Result.success(session)
        override suspend fun write(value: PersistedSession): Boolean { session = value; return true }
        override suspend fun clear(): Boolean { session = null; return true }
    }
}
