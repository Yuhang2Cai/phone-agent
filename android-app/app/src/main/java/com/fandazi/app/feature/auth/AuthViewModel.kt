package com.fandazi.app.feature.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val processor: AuthFormProcessor) : ViewModel() {
    val state: StateFlow<AuthFormState> = processor.state

    fun showLogin() = processor.setRegisterMode(false)
    fun showRegister() = processor.setRegisterMode(true)
    fun updateDraft(username: String, email: String, password: String) = processor.updateDraft(username, email, password)
    fun continueAsGuest() {
        Log.d(TAG, "AuthViewModel continueAsGuest started")
        viewModelScope.launch { processor.continueAsGuest() }
    }
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
