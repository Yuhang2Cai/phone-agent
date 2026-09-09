package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        storage.clear()
        mutableState.value = SessionState.Unauthenticated
    }

    fun tokenOrNull(): String? = (mutableState.value as? SessionState.Authenticated)?.token

    private fun persistSuccessful(result: ApiResult<AuthResponse>): ApiResult<AuthResponse> {
        if (result is ApiResult.Success) {
            val session = PersistedSession(result.value.token, result.value.user)
            storage.write(session)
            mutableState.value = session.toSessionState()
        } else {
            mutableState.value = SessionState.Unauthenticated
        }
        return result
    }

    private fun PersistedSession?.toSessionState(): SessionState = when (this) {
        null -> SessionState.Unauthenticated
        else -> SessionState.Authenticated(token, user)
    }
}
