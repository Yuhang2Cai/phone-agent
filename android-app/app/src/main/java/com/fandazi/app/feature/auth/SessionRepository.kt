// 全局身份仓库：串行维护加密会话与界面登录态，接收公开接口对实际令牌的认证拒绝。
package com.fandazi.app.feature.auth

import android.util.Log
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 唯一身份源；认证网关提供服务端身份，存储失败经error暴露，不把缓存存在等同永远有效。 */
class SessionRepository(
    private val authGateway: AuthGateway,
    private val storage: SessionStorage
) : SessionActions {
    /** 串行处理身份持久化与失效，避免清理旧凭据时覆盖同时完成的新登录。 */
    private val sessionMutex = Mutex()
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

// 登录当前数据。
    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.login(username, password))

// 注册当前数据。
    override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> =
        register(username, email, password, null)

    /** 保存后端带可选昵称的真实身份；沿用加密会话写失败保护，不提前宣称注册成功。 */
    override suspend fun register(username: String, email: String, password: String, nickname: String?): ApiResult<AuthResponse> =
        persistSuccessful(authGateway.register(username, email, password, nickname))

    /** 主动退出并清理持久化；清理失败保留既有身份及错误，不伪称退出成功。 */
    override suspend fun logout() = sessionMutex.withLock { clearSession(rejected = false) }

    /**
     * 清除服务端明确拒绝的当前凭据；参数必须是实际请求使用的令牌，不能事后读取新令牌。
     * 旧请求迟到、无令牌请求或重复401不影响新身份；存储失败时也禁止继续使用已拒绝的身份。
     */
    suspend fun invalidateToken(rejectedToken: String) = sessionMutex.withLock {
        // 比较的是完整认证版本，不是用户名；同账号重新登录也必须保留其新令牌。
        if (rejectedToken.isBlank() || tokenOrNull() != rejectedToken) return@withLock
        clearSession(rejected = true)
    }

    /** 在会话锁内清理凭据；主动退出保留既有失败语义，服务端拒绝则必须在内存中失效。 */
    private suspend fun clearSession(rejected: Boolean) {
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
            // 不能因磁盘清理失败而继续把已被服务端拒绝的令牌暴露给任何请求供应器。
            if (rejected) mutableState.value = SessionState.Unauthenticated
            mutableError.value = if (storage.initializationError != null) {
                SessionError.StorageUnavailable
            } else {
                SessionError.StorageClearFailed
            }
        }
    }

    /** 在同一会话锁内进入游客模式，避免写入游客标记与新登录/失效清理交错。 */
    override suspend fun continueAsGuest(): Unit = sessionMutex.withLock {
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

// 处理 `tokenOrNull` 相关逻辑。
    fun tokenOrNull(): String? = (mutableState.value as? SessionState.Authenticated)?.token

    /** 串行保存认证响应；只有令牌成功落盘才发布登录状态，失败返回原错误或存储错误。 */
    private suspend fun persistSuccessful(result: ApiResult<AuthResponse>): ApiResult<AuthResponse> = sessionMutex.withLock {
        if (result !is ApiResult.Success) {
            mutableState.value = SessionState.Unauthenticated
            return@withLock result
        }
        val session = PersistedSession(result.value.token, result.value.user)
        val written = try {
            storage.write(session)
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            false
        }
        if (written) {
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
