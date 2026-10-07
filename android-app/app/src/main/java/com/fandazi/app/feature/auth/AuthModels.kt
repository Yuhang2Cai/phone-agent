// 身份认证模块：定义该模块的数据与行为。
package com.fandazi.app.feature.auth

data class User(
    val username: String,
    val email: String,
    /** 后端返回的可选昵称；旧响应及旧会话无该字段时保持空值。 */
    val nickname: String? = null
)

data class AuthResponse(
    val token: String,
    val user: User,
    /** 稳定会话身份与刷新秘密仅用于加密认证存储，不能写公开缓存或日志。 */
    val sessionId: String? = null,
    val refreshToken: String? = null,
    /** 服务端UTC毫秒期限，旧响应缺失时沿用原短期登录兼容。 */
    val accessExpiresAtEpochMs: Long? = null,
    val refreshExpiresAtEpochMs: Long? = null
)

data class PersistedSession(
    val token: String,
    val user: User,
    /** 完整凭据包同一次加密commit保存，避免轮换成功后只保存JWT。 */
    val sessionId: String? = null,
    val refreshToken: String? = null,
    val accessExpiresAtEpochMs: Long? = null,
    val refreshExpiresAtEpochMs: Long? = null,
    /** 请求前预先落盘，丢响应或进程死亡后使用同一标识恢复同一轮换。 */
    val pendingRefreshRequestId: String? = null
)

sealed interface SessionState {
    data object Unauthenticated : SessionState
    data object Guest : SessionState
    /** 界面只持有访问身份；刷新秘密始终留在身份仓库和加密存储。 */
    data class Authenticated(val token: String, val user: User, val sessionId: String? = null) : SessionState
}

/** 导航比较同一sid，不因自动换JWT清草稿；无sid的历史登录继续按令牌区分明确重登。 */
fun authenticationIdentity(state: SessionState): String? = (state as? SessionState.Authenticated)?.let { it.sessionId ?: it.token }

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
    /** 默认拒绝明确没有实现续期的旧适配器，不制造续期成功。 */
    suspend fun refresh(refreshToken: String, requestId: String): com.fandazi.app.core.network.ApiResult<AuthResponse> =
        com.fandazi.app.core.network.ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(401,"当前服务不支持续期"))
    /** 在线撤销仅作用于持有的会话；历史无刷新身份不调用这一兼容默认入口。 */
    suspend fun revoke(refreshToken: String): com.fandazi.app.core.network.ApiResult<Unit> = com.fandazi.app.core.network.ApiResult.Success(Unit)
// 登录当前数据。
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
// 注册当前数据。
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
    /** 兼容旧适配器的三字段入口；未实现昵称时明确拒绝非空值，禁止静默丢弃用户输入。 */
    suspend fun register(username: String, email: String, password: String, nickname: String?): com.fandazi.app.core.network.ApiResult<AuthResponse> {
        require(nickname.isNullOrBlank()) { "当前认证适配器不支持昵称" }
        return register(username, email, password)
    }
}

interface SessionActions {
// 登录当前数据。
    suspend fun login(username: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
// 注册当前数据。
    suspend fun register(username: String, email: String, password: String): com.fandazi.app.core.network.ApiResult<AuthResponse>
    /** 兼容旧适配器的三字段入口；未实现昵称时明确拒绝非空值，禁止静默丢弃用户输入。 */
    suspend fun register(username: String, email: String, password: String, nickname: String?): com.fandazi.app.core.network.ApiResult<AuthResponse> {
        require(nickname.isNullOrBlank()) { "当前认证适配器不支持昵称" }
        return register(username, email, password)
    }
// 退出登录当前数据。
    suspend fun logout()
// 处理 `continueAsGuest` 相关逻辑。
    suspend fun continueAsGuest() = Unit
}
