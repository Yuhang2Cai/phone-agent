package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthFormState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val isRegisterMode: Boolean = false,
    val isSubmitting: Boolean = false,
    val usernameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val errorMessage: String? = null
)

class AuthFormProcessor(private val sessions: SessionActions) {
    private val submitting = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    fun setRegisterMode(isRegisterMode: Boolean) {
        mutableState.value = mutableState.value.copy(isRegisterMode = isRegisterMode)
    }

    fun updateDraft(username: String, email: String, password: String) {
        mutableState.value = mutableState.value.copy(username = username, email = email, password = password)
    }

    suspend fun continueAsGuest() = sessions.continueAsGuest()

    suspend fun submitLogin(username: String, password: String) {
        if (!submitting.compareAndSet(false, true)) return
        try {
            val errors = validate(username = username, email = null, password = password, isRegisterMode = false)
            if (!publishErrors(errors, false)) {
                return
            }
            mutableState.value = mutableState.value.copy(isSubmitting = true, errorMessage = null)
            val result = try {
                sessions.login(username.trim(), password)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                ApiResult.Failure(ApiError.Network(exception.message ?: "Authentication request failed"))
            }
            complete(result)
        } finally {
            submitting.set(false)
            mutableState.value = mutableState.value.copy(isSubmitting = false)
        }
    }

    suspend fun submitRegister(username: String, email: String, password: String) {
        if (!submitting.compareAndSet(false, true)) return
        try {
            val errors = validate(username = username, email = email, password = password, isRegisterMode = true)
            if (!publishErrors(errors, true)) {
                return
            }
            mutableState.value = mutableState.value.copy(isSubmitting = true, errorMessage = null)
            val result = try {
                sessions.register(username.trim(), email.trim(), password)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                ApiResult.Failure(ApiError.Network(exception.message ?: "Registration request failed"))
            }
            complete(result)
        } finally {
            submitting.set(false)
            mutableState.value = mutableState.value.copy(isSubmitting = false)
        }
    }

    private fun publishErrors(errors: ValidationErrors, isRegisterMode: Boolean): Boolean {
        mutableState.value = mutableState.value.copy(
            isRegisterMode = isRegisterMode,
            isSubmitting = false,
            usernameError = errors.username,
            emailError = errors.email,
            passwordError = errors.password,
            errorMessage = null
        )
        return errors.isValid
    }

    private fun validate(username: String, email: String?, password: String, isRegisterMode: Boolean): ValidationErrors = ValidationErrors(
        username = if (username.trim().isEmpty()) "请输入用户名" else null,
        email = if (isRegisterMode && (email.isNullOrBlank() || !EMAIL_PATTERN.matches(email.trim()))) "请输入有效邮箱" else null,
        password = if (password.length < MIN_PASSWORD_LENGTH) "密码至少需要 8 位" else null
    )

    private fun complete(result: ApiResult<AuthResponse>) {
        mutableState.value = when (result) {
            is ApiResult.Success -> mutableState.value.copy(isSubmitting = false, errorMessage = null)
            is ApiResult.Failure -> mutableState.value.copy(isSubmitting = false, errorMessage = result.error.toUserMessage())
        }
    }

    private fun ApiError.toUserMessage(): String = when (this) {
        is ApiError.Http -> when (code) {
            401 -> "用户名或密码错误"
            400 -> when {
                message.containsAny("already in use", "duplicate", "already exists") -> "用户名或邮箱已被使用"
                message.containsAny("invalid username or password", "incorrect password", "invalid credentials") -> "用户名或密码错误"
                else -> "请求参数有误，请检查后重试"
            }
            409 -> if (message.containsAny("already in use", "duplicate", "already exists")) {
                "用户名或邮箱已被使用"
            } else {
                "请求冲突，请稍后重试"
            }
            in 422..499 -> "请求参数有误，请检查后重试"
            else -> message.ifBlank { "服务暂时不可用，请稍后重试" }
        }
        ApiError.Timeout -> "请求超时，请重试"
        is ApiError.Network -> "网络不可用，请检查连接"
        ApiError.Storage -> "本地会话保存失败，请重试"
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { lowercase().contains(it) }

    private data class ValidationErrors(val username: String?, val email: String?, val password: String?) {
        val isValid: Boolean get() = username == null && email == null && password == null
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
