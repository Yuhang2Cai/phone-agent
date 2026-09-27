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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getInjectsBearerTokenFromSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"username\":\"alice\"}"))
        val client = ApiClient(server.url("/api/").toString(), tokenProvider = { "jwt-token" })

        val result = client.get("users/me")

        assertTrue(result is ApiResult.Success)
        assertEquals("Bearer jwt-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun idempotentGetRetriesOnceAfterNetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = ApiClient(server.url("/api/").toString(), tokenProvider = { null })

        val result = client.get("users/me")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
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
