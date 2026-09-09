package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun loginPersistsTokenAndPublishesAuthenticatedSession() = runBlocking {
        val storage = InMemorySessionStorage()
        val repository = SessionRepository(FakeAuthGateway(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com")))), storage)

        val result = repository.login("alice", "password123")

        assertTrue(result is ApiResult.Success)
        assertEquals(SessionState.Authenticated("token-1", User("alice", "alice@example.com")), repository.state.value)
        assertEquals("token-1", storage.read()?.token)
    }

    @Test
    fun invalidCredentialsPublishRecoverableErrorWithoutPersistingSession() = runBlocking {
        val storage = InMemorySessionStorage()
        val repository = SessionRepository(FakeAuthGateway(ApiResult.Failure(ApiError.Http(401, "Invalid username or password"))), storage)

        val result = repository.login("alice", "wrong-password")

        assertEquals(ApiResult.Failure(ApiError.Http(401, "Invalid username or password")), result)
        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(null, storage.read())
    }

    @Test
    fun timeoutLeavesUserUnauthenticated() = runBlocking {
        val repository = SessionRepository(FakeAuthGateway(ApiResult.Failure(ApiError.Timeout)), InMemorySessionStorage())

        val result = repository.login("alice", "password123")

        assertEquals(ApiResult.Failure(ApiError.Timeout), result)
        assertEquals(SessionState.Unauthenticated, repository.state.value)
    }

    @Test
    fun logoutClearsPersistedSessionAndState() = runBlocking {
        val storage = InMemorySessionStorage(PersistedSession("token-1", User("alice", "alice@example.com")))
        val repository = SessionRepository(FakeAuthGateway(), storage)

        repository.logout()

        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(null, storage.read())
    }

    @Test
    fun newRepositoryRestoresPersistedSessionAfterProcessRecreation() {
        val storage = InMemorySessionStorage(PersistedSession("token-1", User("alice", "alice@example.com")))

        val repository = SessionRepository(FakeAuthGateway(), storage)

        assertEquals(SessionState.Authenticated("token-1", User("alice", "alice@example.com")), repository.state.value)
    }

    private class FakeAuthGateway(
        private val loginResult: ApiResult<AuthResponse> = ApiResult.Failure(ApiError.Network("not configured"))
    ) : AuthGateway {
        override suspend fun login(username: String, password: String): ApiResult<AuthResponse> = loginResult

        override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> = loginResult
    }

    private class InMemorySessionStorage(initial: PersistedSession? = null) : SessionStorage {
        private var session = initial

        override fun read(): PersistedSession? = session

        override fun write(value: PersistedSession) {
            session = value
        }

        override fun clear() {
            session = null
        }
    }
}
