// 身份认证模块：封装持久化查询与写入。
package com.fandazi.app.feature.auth

import android.util.Log
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRepository(
    private val authGateway: AuthGateway,
    private val storage: SessionStorage
) : SessionActions {
    private val initialRead = storage.read()
    private val initialGuestRead = storage.readGuest()
    private val mutableState = MutableStateFlow(initialRead.getOrNull().toSessionState(initialGuestRead.getOrNull() == true))
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
            storage.clear()
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            false
        }
        if (cleared) {
            storage.clearGuest()
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

    override suspend fun continueAsGuest() {
        Log.d(TAG, "continueAsGuest requested")
        if (storage.writeGuest()) {
            mutableState.value = SessionState.Guest
            mutableError.value = null
            Log.d(TAG, "continueAsGuest succeeded: state=Guest")
        } else {
            mutableError.value = SessionError.StorageWriteFailed
            Log.e(TAG, "continueAsGuest failed: state remains ${mutableState.value}, error=StorageWriteFailed")
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
            storage.write(session)
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            false
        }
        return if (written) {
            storage.clearGuest()
            mutableState.value = session.toSessionState(false)
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

    private fun PersistedSession?.toSessionState(guest: Boolean): SessionState = when {
        this != null -> SessionState.Authenticated(token, user)
        guest -> SessionState.Guest
        else -> SessionState.Unauthenticated
    }

    private companion object {
        const val TAG = "FandaziSession"
    }
}
