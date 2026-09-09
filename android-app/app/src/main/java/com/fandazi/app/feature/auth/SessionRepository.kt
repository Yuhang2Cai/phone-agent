package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SessionRepository(
    private val authGateway: AuthGateway,
    private val storage: SessionStorage
) : SessionActions {
    private val initialRead = storage.read()
    private val mutableState = MutableStateFlow(initialRead.getOrNull().toSessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow(
        when {
            storage.initializationError != null -> SessionError.StorageUnavailable
            initialRead.isFailure -> SessionError.StorageReadFailed
            else -> null
        }
    )
    val error: StateFlow<SessionError?> = mutableError.asStateFlow()

    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.login(username, password))

    override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.register(username, email, password))

    override suspend fun logout() {
        val cleared = try {
            withContext(Dispatchers.IO) { storage.clear() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
        if (cleared) {
            mutableState.value = SessionState.Unauthenticated
            mutableError.value = null
        } else {
            mutableError.value = if (storage.initializationError != null) {
                SessionError.StorageUnavailable
            } else {
                SessionError.StorageClearFailed
            }
        }
    }

    fun tokenOrNull(): String? = (mutableState.value as? SessionState.Authenticated)?.token

    private suspend fun persistSuccessful(result: ApiResult<AuthResponse>): ApiResult<AuthResponse> {
        if (result !is ApiResult.Success) {
            mutableState.value = SessionState.Unauthenticated
            return result
        }
        val session = PersistedSession(result.value.token, result.value.user)
        val written = try {
            withContext(Dispatchers.IO) { storage.write(session) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
        return if (written) {
            mutableState.value = session.toSessionState()
            mutableError.value = null
            result
        } else {
            mutableState.value = SessionState.Unauthenticated
            mutableError.value = if (storage.initializationError != null) {
                SessionError.StorageUnavailable
            } else {
                SessionError.StorageWriteFailed
            }
            ApiResult.Failure(com.fandazi.app.core.network.ApiError.Storage)
        }
    }

    private fun PersistedSession?.toSessionState(): SessionState = when (this) {
        null -> SessionState.Unauthenticated
        else -> SessionState.Authenticated(token, user)
    }
}
