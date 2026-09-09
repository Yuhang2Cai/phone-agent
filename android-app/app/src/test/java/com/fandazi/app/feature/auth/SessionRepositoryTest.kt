package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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
        assertEquals("token-1", storage.read().getOrNull()?.token)
    }

    @Test
    fun invalidCredentialsPublishRecoverableErrorWithoutPersistingSession() = runBlocking {
        val storage = InMemorySessionStorage()
        val repository = SessionRepository(FakeAuthGateway(ApiResult.Failure(ApiError.Http(401, "Invalid username or password"))), storage)

        val result = repository.login("alice", "wrong-password")

        assertEquals(ApiResult.Failure(ApiError.Http(401, "Invalid username or password")), result)
        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(null, storage.read().getOrNull())
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
        assertEquals(null, storage.read().getOrNull())
    }

    @Test
    fun newRepositoryRestoresPersistedSessionAfterProcessRecreation() {
        val storage = InMemorySessionStorage(PersistedSession("token-1", User("alice", "alice@example.com")))

        val repository = SessionRepository(FakeAuthGateway(), storage)

        assertEquals(SessionState.Authenticated("token-1", User("alice", "alice@example.com")), repository.state.value)
    }

    @Test
    fun failedSessionWriteDoesNotPublishAuthenticatedState() = runBlocking {
        val repository = SessionRepository(
            FakeAuthGateway(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com")))),
            InMemorySessionStorage(writeResult = false)
        )

        val result = repository.login("alice", "password123")

        assertEquals(ApiResult.Failure(ApiError.Storage), result)
        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(SessionError.StorageWriteFailed, repository.error.value)
    }

    @Test
    fun storageInitializationFailureIsExposed() {
        val repository = SessionRepository(
            FakeAuthGateway(),
            InMemorySessionStorage(initializationError = IllegalStateException("keystore unavailable"))
        )

        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(SessionError.StorageUnavailable, repository.error.value)
    }

    @Test
    fun failedClearKeepsSessionAndExposesError() = runBlocking {
        val session = PersistedSession("token-1", User("alice", "alice@example.com"))
        val repository = SessionRepository(
            FakeAuthGateway(),
            InMemorySessionStorage(initial = session, clearResult = false)
        )

        repository.logout()

        assertEquals(SessionState.Authenticated("token-1", User("alice", "alice@example.com")), repository.state.value)
        assertEquals(SessionError.StorageClearFailed, repository.error.value)
    }

    @Test
    fun storageReadFailurePublishesErrorAndDoesNotPretendToRestoreSession() {
        val repository = SessionRepository(
            FakeAuthGateway(),
            InMemorySessionStorage(readResult = Result.failure(IllegalStateException("decrypt failed")))
        )

        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertEquals(SessionError.StorageReadFailed, repository.error.value)
    }

    @Test
    fun cancellationDuringSessionWriteIsRethrown() = runBlocking {
        val cancellation = CancellationException("test cancellation")
        val repository = SessionRepository(
            FakeAuthGateway(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com")))),
            InMemorySessionStorage(writeFailure = cancellation)
        )

        try {
            repository.login("alice", "password123")
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
    }

    private class FakeAuthGateway(
        private val loginResult: ApiResult<AuthResponse> = ApiResult.Failure(ApiError.Network("not configured"))
    ) : AuthGateway {
        override suspend fun login(username: String, password: String): ApiResult<AuthResponse> = loginResult

        override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> = loginResult
    }

    private class InMemorySessionStorage(
        initial: PersistedSession? = null,
        private val writeResult: Boolean = true,
        private val clearResult: Boolean = true,
        override val initializationError: Throwable? = null,
        private val readResult: Result<PersistedSession?>? = null,
        private val writeFailure: Throwable? = null
    ) : SessionStorage {
        private var session = initial

        override fun read(): Result<PersistedSession?> = readResult ?: Result.success(session)

        override suspend fun write(value: PersistedSession): Boolean {
            writeFailure?.let { throw it }
            if (writeResult) session = value
            return writeResult
        }

        override suspend fun clear(): Boolean {
            if (clearResult) session = null
            return clearResult
        }
    }
}
