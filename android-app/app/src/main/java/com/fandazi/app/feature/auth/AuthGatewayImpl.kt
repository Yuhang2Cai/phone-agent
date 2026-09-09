package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson

class ApiAuthGateway(
    private val apiClient: ApiClient,
    private val gson: Gson = Gson()
) : AuthGateway {
    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        apiClient.postJson("auth/login", gson.toJson(LoginRequest(username, password))).mapAuthResponse()

    override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> =
        apiClient.postJson("auth/register", gson.toJson(RegisterRequest(username, email, password))).mapAuthResponse()

    private fun ApiResult<String>.mapAuthResponse(): ApiResult<AuthResponse> = when (this) {
        is ApiResult.Success -> ApiResult.Success(gson.fromJson(value, AuthResponse::class.java))
        is ApiResult.Failure -> this
    }

    private data class LoginRequest(val username: String, val password: String)
    private data class RegisterRequest(val username: String, val email: String, val password: String)
}
