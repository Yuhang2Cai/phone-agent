// 身份认证模块：定义该模块的数据与行为。
package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson

class ApiAuthGateway(
    private val apiClient: ApiClient,
    private val gson: Gson = Gson()
) : AuthGateway {
    /** 认证客户端必须不包含自动续期拦截器，防止刷新请求递归等待自己的互斥锁。 */
    override suspend fun refresh(refreshToken: String, requestId: String): ApiResult<AuthResponse> =
        apiClient.postJson("auth/refresh",gson.toJson(mapOf("refreshToken" to refreshToken,"requestId" to requestId))).mapAuthResponse()

    /** 204不解析成认证包，撤销失败由身份仓库保留为远端未完成，不恢复已清本地身份。 */
    override suspend fun revoke(refreshToken: String): ApiResult<Unit> = when(val result=apiClient.postJson("auth/logout",gson.toJson(mapOf("refreshToken" to refreshToken)))) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Failure -> result
    }
// 登录当前数据。
    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        apiClient.postJson("auth/login", gson.toJson(LoginRequest(username, password))).mapAuthResponse()

// 注册当前数据。
    override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> =
        register(username, email, password, null)

    /** 昵称随真实注册请求提交，服务端回传后才进入会话，不在端上伪造已保存资料。 */
    override suspend fun register(username: String, email: String, password: String, nickname: String?): ApiResult<AuthResponse> =
        apiClient.postJson("auth/register", gson.toJson(RegisterRequest(username, email, password, nickname))).mapAuthResponse()

    private fun ApiResult<String>.mapAuthResponse(): ApiResult<AuthResponse> = when (this) {
        // 非法认证JSON归为可恢复协议故障，不能把解析异常当刷新秘密被明确拒绝。
        is ApiResult.Success -> runCatching { gson.fromJson(value,AuthResponse::class.java) }.fold(
            onSuccess={if(it?.token.isNullOrBlank()||it?.user==null)ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("认证响应不完整")) else ApiResult.Success(it)},
            onFailure={ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("认证响应格式错误"))}
        )
        is ApiResult.Failure -> this
    }

    private data class LoginRequest(val username: String, val password: String)
    /** 旧邮箱仅保留协议兼容，可选昵称使用独立字段，禁止借邮箱存储昵称。 */
    private data class RegisterRequest(val username: String, val email: String, val password: String, val nickname: String?)
}
