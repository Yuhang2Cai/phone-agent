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
}
