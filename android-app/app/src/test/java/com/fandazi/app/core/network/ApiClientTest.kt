// 网络请求模块：验证关键行为与边界条件。
package com.fandazi.app.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer

    /** 秒杀错误要保留固定原因，界面不能根据中文提示猜测售罄或限购。 */
    @Test fun businessReasonSurvivesHttpErrorParsing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"SOLD_OUT","reasonCode":"SOLD_OUT","error":"库存已抢完"}"""))
        val client=ApiClient(server.url("/api/").toString(),tokenProvider={null})
        val result=client.get("seckill-activities/test/eligibility") as ApiResult.Failure
        val error=result.error as ApiError.Http
        assertEquals("库存已抢完",error.message)
        // 反射只用于建立字段缺失的有效失败断言，避免把编译失败当产品红灯。
        assertEquals("SOLD_OUT",error.javaClass.methods.firstOrNull { it.name=="getReasonCode" }?.invoke(error))
    }

    @Before
// 为测试准备依赖、替身和初始状态。
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
// 清理测试创建的资源与状态。
    fun tearDown() {
        server.shutdown()
    }

    @Test
// 验证 `getInjectsBearerTokenFromSession` 覆盖场景的预期行为与边界。
    fun getInjectsBearerTokenFromSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"username\":\"alice\"}"))
        val client = ApiClient(server.url("/api/").toString(), tokenProvider = { "jwt-token" })

        val result = client.get("users/me")

        assertTrue(result is ApiResult.Success)
        assertEquals("Bearer jwt-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
// 验证 `idempotentGetRetriesOnceAfterNetworkFailure` 覆盖场景的预期行为与边界。
    fun idempotentGetRetriesOnceAfterNetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = ApiClient(server.url("/api/").toString(), tokenProvider = { null })

        val result = client.get("users/me")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
// 验证 `clientUsesBackendErrorFieldForValidationAuthenticationAndConflictResponses` 覆盖场景的预期行为与边界。
    fun clientUsesBackendErrorFieldForValidationAuthenticationAndConflictResponses() = runBlocking {
        listOf(
            400 to "Invalid username or password",
            401 to "Authentication is required",
            409 to "Username is already in use"
        ).forEach { (code, message) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("{\"error\":\"$message\"}"))
        }
        val client = ApiClient(server.url("/api/").toString(), tokenProvider = { null })

        val errors = listOf("auth/login", "users/me", "auth/register").map { path ->
            client.get(path).let { result ->
                assertTrue(result is ApiResult.Failure)
                (result as ApiResult.Failure).error as ApiError.Http
            }
        }

        assertEquals(listOf(400, 401, 409), errors.map { it.code })
        assertEquals(
            listOf("Invalid username or password", "Authentication is required", "Username is already in use"),
            errors.map { it.message }
        )
    }
}
