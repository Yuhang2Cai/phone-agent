package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthFormState(
    val isRegisterMode: Boolean = false,
    val isSubmitting: Boolean = false,
    val usernameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val errorMessage: String? = null,
    val navigateHome: Boolean = false
)

class AuthFormProcessor(private val sessions: SessionActions) {
    private val submitting = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    fun setRegisterMode(isRegisterMode: Boolean) {
        mutableState.value = AuthFormState(isRegisterMode = isRegisterMode)
    }

    fun consumeNavigation() {
        mutableState.value = mutableState.value.copy(navigateHome = false)
    }

    suspend fun submitLogin(username: String, password: String) {
        val errors = validate(username, email = null, password, isRegisterMode = false)
        if (!publishErrors(errors, false) || !submitting.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(isSubmitting = true, errorMessage = null)
        complete(sessions.login(username.trim(), password))
    }

    suspend fun submitRegister(username: String, email: String, password: String) {
        val errors = validate(username, email, password, isRegisterMode = true)
        if (!publishErrors(errors, true) || !submitting.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(isSubmitting = true, errorMessage = null)
        complete(sessions.register(username.trim(), email.trim(), password))
    }

    private fun publishErrors(errors: ValidationErrors, isRegisterMode: Boolean): Boolean {
        mutableState.value = AuthFormState(
            isRegisterMode = isRegisterMode,
            usernameError = errors.username,
            emailError = errors.email,
            passwordError = errors.password
        )
        return errors.isValid
    }

    private fun validate(username: String, email: String?, password: String, isRegisterMode: Boolean): ValidationErrors = ValidationErrors(
        username = if (username.trim().isEmpty()) "请输入用户名" else null,
        email = if (isRegisterMode && (email.isNullOrBlank() || !EMAIL_PATTERN.matches(email.trim()))) "请输入有效邮箱" else null,
        password = if (password.length < MIN_PASSWORD_LENGTH) "密码至少需要 8 位" else null
    )

    private fun complete(result: ApiResult<AuthResponse>) {
        submitting.set(false)
        mutableState.value = when (result) {
            is ApiResult.Success -> mutableState.value.copy(isSubmitting = false, navigateHome = true, errorMessage = null)
            is ApiResult.Failure -> mutableState.value.copy(isSubmitting = false, errorMessage = result.error.toUserMessage())
        }
    }

    private fun ApiError.toUserMessage(): String = when (this) {
        is ApiError.Http -> when (code) {
            401 -> "用户名或密码错误"
            409 -> "用户名或邮箱已被使用"
            else -> message
        }
        ApiError.Timeout -> "请求超时，请重试"
        is ApiError.Network -> "网络不可用，请检查连接"
    }

    private data class ValidationErrors(val username: String?, val email: String?, val password: String?) {
        val isValid: Boolean get() = username == null && email == null && password == null
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
