// 身份认证模块：定义该模块的数据与行为。
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
    data object Guest : SessionState
    data class Authenticated(val token: String, val user: User) : SessionState
}

interface SessionStorage {
    val initializationError: Throwable?
// 读取当前数据。
    fun read(): Result<PersistedSession?>
// 处理 `write` 相关逻辑。
    suspend fun write(value: PersistedSession): Boolean
// 清除当前数据。
    suspend fun clear(): Boolean
// 读取guest。
    fun readGuest(): Result<Boolean> = Result.success(false)
// 处理 `writeGuest` 相关逻辑。
    suspend fun writeGuest(): Boolean = true
// 清除guest。
    suspend fun clearGuest(): Boolean = true
}

enum class SessionError {
    StorageUnavailable,
    StorageReadFailed,
    StorageWriteFailed,
    StorageClearFailed
}

interface AuthGateway {
// 登录当前数据。
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
// 注册当前数据。
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
}

interface SessionActions {
// 登录当前数据。
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
// 注册当前数据。
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
// 退出登录当前数据。
    suspend fun logout()
// 处理 `continueAsGuest` 相关逻辑。
    suspend fun continueAsGuest() = Unit
}
