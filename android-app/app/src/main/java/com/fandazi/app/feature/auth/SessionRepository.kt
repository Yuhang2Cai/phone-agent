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
    private val storage: SessionStorage,
    /** 注入时钟便于准确验证到期边界，运行期沿用设备UTC毫秒，不保存设备私有信息。 */
    private val now: () -> Long = System::currentTimeMillis
) : SessionActions {
    /** 串行处理身份持久化与失效，避免清理旧凭据时覆盖同时完成的新登录。 */
    private val sessionMutex = Mutex()
    private val initialRead = storage.read()
    @Volatile private var persistedSession = initialRead.getOrNull()
    /** 续期锁与身份修改锁分开，等待网络时退出和换账号不会被长期阻塞。 */
    private val renewalMutex = Mutex()
    private var lastRenewalFailure: ApiResult.Failure? = null
    private var failureAt = 0L
    private var failureForRefresh: String? = null
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
    override suspend fun logout() {
        val refresh = sessionMutex.withLock {
            val old=persistedSession?.refreshToken
            clearSession(rejected=false)
            // 原存储清理失败时保留既有身份，不伪称退出成功，也不提前撤销仍被使用的会话。
            if(mutableState.value==SessionState.Unauthenticated)old else null
        }
        if(refresh!=null)kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            // 本地状态发布会销毁旧页面，撤销仍需在有界HTTP超时内尽力完成；结果不恢复本地身份。
            authGateway.revoke(refresh)
        }
    }

    /**
     * 清除服务端明确拒绝的当前凭据；参数必须是实际请求使用的令牌，不能事后读取新令牌。
     * 旧请求迟到、无令牌请求或重复401不影响新身份；存储失败时也禁止继续使用已拒绝的身份。
     */
    suspend fun invalidateToken(rejectedToken: String) {
        if(rejectedToken.isBlank()||tokenOrNull()!=rejectedToken)return
        if(persistedSession?.refreshToken!=null) {
            // 普通401先交给刷新权威判断，网络/5xx不是退出理由；明确刷新401在renewIfNeeded内清身份。
            renewIfNeeded(rejectedToken)
            return
        }
        sessionMutex.withLock {
        // 比较的是完整认证版本，不是用户名；同账号重新登录也必须保留其新令牌。
        if (rejectedToken.isBlank() || tokenOrNull() != rejectedToken) return@withLock
        clearSession(rejected = true)
        }
    }

    /**
     * 返回当前可发包访问凭据；不足一分钟或对应真实401时单次续期。
     * 失败保留加密包和待请求标识，只有明确刷新401清身份；退出/新登录后的迟到结果不能覆盖新版本。
     */
    suspend fun renewIfNeeded(rejectedToken: String? = null): ApiResult<String?> = renewalMutex.withLock {
        val current=persistedSession ?: return@withLock ApiResult.Success(tokenOrNull())
        if(tokenOrNull()!=current.token)return@withLock ApiResult.Success(tokenOrNull())
        if(rejectedToken!=null&&current.token!=rejectedToken)return@withLock ApiResult.Success(tokenOrNull())
        if(current.refreshToken.isNullOrBlank())return@withLock ApiResult.Success(current.token)
        val expiry=current.accessExpiresAtEpochMs ?: 0L
        if(rejectedToken==null&&current.pendingRefreshRequestId==null&&expiry-now()>60_000L)return@withLock ApiResult.Success(current.token)
        // 一次网络故障后并发等待者共享短暂失败，避免同一断网触发十几个续期；后续显式调用仍可恢复。
        if(lastRenewalFailure!=null&&failureForRefresh==current.refreshToken&&now()-failureAt in 0 until 3_000L)return@withLock lastRenewalFailure!!
        val captured=sessionMutex.withLock {
            if(persistedSession!=current||tokenOrNull()!=current.token)return@withLock null
            val pending=current.copy(pendingRefreshRequestId=current.pendingRefreshRequestId ?: java.util.UUID.randomUUID().toString())
            if(!writeRenewalSafely(pending)) {mutableError.value=SessionError.StorageWriteFailed;null}
            else {persistedSession=pending;pending}
        } ?: return@withLock rememberFailure(current.refreshToken,ApiResult.Failure(com.fandazi.app.core.network.ApiError.Storage))
        // 网络等待不持有身份锁；调用方取消不会把旧响应应用到新登录，异常取消继续向外传播。
        val result=authGateway.refresh(requireNotNull(captured.refreshToken),requireNotNull(captured.pendingRefreshRequestId))
        sessionMutex.withLock {
            if(persistedSession!=captured||tokenOrNull()!=captured.token)return@withLock ApiResult.Success(tokenOrNull())
            when(result) {
                is ApiResult.Failure -> {
                    if((result.error as? com.fandazi.app.core.network.ApiError.Http)?.code==401)clearSession(rejected=true)
                    rememberFailure(captured.refreshToken,result)
                }
                is ApiResult.Success -> {
                    val response=result.value
                    // 完整性和归属核对先于落盘；坏响应不能更新身份或丢掉可恢复的旧凭据包。
                    if(response.sessionId!=captured.sessionId||response.user.username!=captured.user.username||response.refreshToken.isNullOrBlank()||
                        response.accessExpiresAtEpochMs==null||response.refreshExpiresAtEpochMs==null||response.accessExpiresAtEpochMs<=now())
                        return@withLock rememberFailure(captured.refreshToken,ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("续期响应不完整")))
                    val updated=response.persisted()
                    if(!writeRenewalSafely(updated)) {
                        mutableError.value=SessionError.StorageWriteFailed
                        return@withLock rememberFailure(captured.refreshToken,ApiResult.Failure(com.fandazi.app.core.network.ApiError.Storage))
                    }
                    persistedSession=updated;mutableState.value=updated.toSessionState(false);mutableError.value=null
                    lastRenewalFailure=null;failureForRefresh=null
                    ApiResult.Success(updated.token)
                }
            }
        }
    }

    /** 短暂合并同一失败版本，只记录错误类别，绝不输出刷新秘密。 */
    private fun rememberFailure(refresh: String?,result: ApiResult.Failure): ApiResult.Failure {
        failureForRefresh=refresh;failureAt=now();lastRenewalFailure=result;return result
    }
    /** 完整响应转换为同一次加密写入的包，成功轮换清掉上一轮待请求标识。 */
    private fun AuthResponse.persisted() = PersistedSession(token,user,sessionId,refreshToken,accessExpiresAtEpochMs,refreshExpiresAtEpochMs)
    /** 存储异常与false都属于可恢复落盘失败，取消继续传播，不把异常泄露为界面崩溃。 */
    private suspend fun writeRenewalSafely(value: PersistedSession): Boolean = try {storage.write(value)} catch(failure: Exception) {
        if(failure is CancellationException)throw failure
        false
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
            persistedSession=null
            storage.clearGuest()
            mutableState.value = SessionState.Unauthenticated
            mutableError.value = null
        } else {
            // 不能因磁盘清理失败而继续把已被服务端拒绝的令牌暴露给任何请求供应器。
            if (rejected) {persistedSession=null;mutableState.value = SessionState.Unauthenticated}
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
            persistedSession=null
            mutableState.value = SessionState.Guest
            mutableError.value = null
            Log.d(TAG, "continueAsGuest succeeded: state=Guest")
        } else {
            mutableError.value = SessionError.StorageWriteFailed
            // 只记录状态类型，Authenticated数据类默认正文含访问凭据，不能随存储错误写日志。
            Log.e(TAG, "continueAsGuest failed: state remains ${mutableState.value::class.java.simpleName}, error=StorageWriteFailed")
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
        val session = result.value.persisted()
        val written = try {
            storage.write(session)
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            false
        }
        if (written) {
            persistedSession=session
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
        this != null -> SessionState.Authenticated(token, user, sessionId)
        guest -> SessionState.Guest
        else -> SessionState.Unauthenticated
    }

    private companion object {
        const val TAG = "FandaziSession"
    }
}
