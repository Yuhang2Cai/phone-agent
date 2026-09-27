// 身份认证模块：协调界面状态与业务请求。
package com.fandazi.app.feature.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val processor: AuthFormProcessor) : ViewModel() {
    val state: StateFlow<AuthFormState> = processor.state

// 处理 `showLogin` 相关逻辑。
    fun showLogin() = processor.setRegisterMode(false)
// 处理 `showRegister` 相关逻辑。
    fun showRegister() = processor.setRegisterMode(true)
// 更新draft。
    fun updateDraft(username: String, email: String, password: String) = processor.updateDraft(username, email, password)
// 处理 `continueAsGuest` 相关逻辑。
    fun continueAsGuest() {
        Log.d(TAG, "AuthViewModel continueAsGuest started")
        viewModelScope.launch { processor.continueAsGuest() }
    }
// 处理 `submit` 相关逻辑。
    fun submit(username: String, email: String, password: String) {
        viewModelScope.launch {
            if (state.value.isRegisterMode) processor.submitRegister(username, email, password)
            else processor.submitLogin(username, password)
        }
    }

    private companion object {
        const val TAG = "FandaziSession"
    }
}

class AuthViewModelFactory(private val sessions: SessionActions) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AuthViewModel(AuthFormProcessor(sessions)) as T
}
