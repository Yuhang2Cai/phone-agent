package com.fandazi.app.feature.auth

data class User(
    val username: String,
    val email: String
)

data class AuthResponse(
    val token: String,
    val user: User
)

data class PersistedSession(
    val token: String,
    val user: User
)

sealed interface SessionState {
    data object Unauthenticated : SessionState
    data class Authenticated(val token: String, val user: User) : SessionState
}

interface SessionStorage {
    val initializationError: Throwable?
    fun read(): PersistedSession?
    suspend fun write(value: PersistedSession): Boolean
    suspend fun clear(): Boolean
}

enum class SessionError {
    StorageUnavailable,
    StorageWriteFailed,
    StorageClearFailed
}

interface AuthGateway {
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
}

interface SessionActions {
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
    suspend fun logout()
}
