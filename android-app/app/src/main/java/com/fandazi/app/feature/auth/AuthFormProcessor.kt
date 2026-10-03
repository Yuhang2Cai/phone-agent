// 身份认证模块：定义该模块的数据与行为。
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
    /** 可选昵称只用于注册请求；登录仍用唯一账号，不能当成认证凭据。 */
    val nickname: String = "",
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

// 处理 `setRegisterMode` 相关逻辑。
    fun setRegisterMode(isRegisterMode: Boolean) {
        mutableState.value = mutableState.value.copy(isRegisterMode = isRegisterMode)
    }

// 更新draft。
    fun updateDraft(username: String, email: String, password: String, nickname: String = "") {
        mutableState.value = mutableState.value.copy(username = username, email = email, password = password, nickname = nickname)
    }

// 处理 `continueAsGuest` 相关逻辑。
    suspend fun continueAsGuest() = sessions.continueAsGuest()

// 处理 `submitLogin` 相关逻辑。
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

// 处理 `submitRegister` 相关逻辑。
    suspend fun submitRegister(username: String, email: String, password: String, nickname: String = "") {
        if (!submitting.compareAndSet(false, true)) return
        try {
            // 昵称为可选后端资料；超长立即拒绝，空白以未填写发送，不混入旧邮箱字段。
            if (nickname.trim().length > 40) {
                mutableState.value = mutableState.value.copy(errorMessage = "昵称不能超过40字")
                return
            }
            val errors = validate(username = username, email = email, password = password, isRegisterMode = true)
            if (!publishErrors(errors, true)) {
                return
            }
            mutableState.value = mutableState.value.copy(isSubmitting = true, errorMessage = null)
            val result = try {
                sessions.register(username.trim(), email.trim(), password, nickname.trim().takeIf { it.isNotBlank() })
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

// 处理 `publishErrors` 相关逻辑。
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

// 校验当前数据。
    private fun validate(username: String, email: String?, password: String, isRegisterMode: Boolean): ValidationErrors = ValidationErrors(
        username = if (username.trim().isEmpty()) "请输入用户名" else null,
        // 注册已经改为账号加密码即可完成；只有用户主动填写兼容字段时才校验邮箱格式。
        email = if (isRegisterMode && !email.isNullOrBlank() && !EMAIL_PATTERN.matches(email.trim())) "请输入有效邮箱" else null,
        password = if (password.length < MIN_PASSWORD_LENGTH) "密码至少需要 8 位" else null
    )

// 处理 `complete` 相关逻辑。
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
