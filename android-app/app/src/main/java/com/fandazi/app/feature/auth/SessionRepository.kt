package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SessionRepository(
    private val authGateway: AuthGateway,
    private val storage: SessionStorage
) : SessionActions {
    private val mutableState = MutableStateFlow(storage.read().toSessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.login(username, password))

    override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.register(username, email, password))

    override suspend fun logout() {
        if (withContext(Dispatchers.IO) { storage.clear() }) {
            mutableState.value = SessionState.Unauthenticated
        }
    }

    fun tokenOrNull(): String? = (mutableState.value as? SessionState.Authenticated)?.token

    private suspend fun persistSuccessful(result: ApiResult<AuthResponse>): ApiResult<AuthResponse> {
        if (result !is ApiResult.Success) {
            mutableState.value = SessionState.Unauthenticated
            return result
        }
        val session = PersistedSession(result.value.token, result.value.user)
        return if (withContext(Dispatchers.IO) { storage.write(session) }) {
            mutableState.value = session.toSessionState()
            result
        } else {
            mutableState.value = SessionState.Unauthenticated
            ApiResult.Failure(com.fandazi.app.core.network.ApiError.Storage)
        }
    }

    private fun PersistedSession?.toSessionState(): SessionState = when (this) {
        null -> SessionState.Unauthenticated
        else -> SessionState.Authenticated(token, user)
    }
}
