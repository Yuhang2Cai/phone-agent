// 身份认证模块：定义该模块的数据与行为。
package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson

class ApiAuthGateway(
    private val apiClient: ApiClient,
    private val gson: Gson = Gson()
) : AuthGateway {
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
        is ApiResult.Success -> ApiResult.Success(gson.fromJson(value, AuthResponse::class.java))
        is ApiResult.Failure -> this
    }

    private data class LoginRequest(val username: String, val password: String)
    /** 旧邮箱仅保留协议兼容，可选昵称使用独立字段，禁止借邮箱存储昵称。 */
    private data class RegisterRequest(val username: String, val email: String, val password: String, val nickname: String?)
}
