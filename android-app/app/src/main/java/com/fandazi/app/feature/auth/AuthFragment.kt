// 身份认证模块：管理页面交互和状态展示。
package com.fandazi.app.feature.auth

import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentAuthBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AuthFragment : Fragment(R.layout.fragment_auth) {
    private var binding: FragmentAuthBinding? = null
    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory((requireActivity() as MainActivity).applicationSession())
    }

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentAuthBinding.bind(view)
        binding = viewBinding
        viewBinding.skipLoginButton.isVisible = arguments?.getBoolean("require_login") != true
        viewBinding.loginModeButton.setOnClickListener { viewModel.showLogin() }
        viewBinding.registerModeButton.setOnClickListener { viewModel.showRegister() }
        viewBinding.skipLoginButton.setOnClickListener {
            Log.d(TAG, "skip login clicked")
            viewModel.continueAsGuest()
        }
        viewBinding.usernameInput.setText(savedInstanceState?.getString(KEY_USERNAME) ?: viewModel.state.value.username)
        viewBinding.emailInput.setText(savedInstanceState?.getString(KEY_EMAIL) ?: viewModel.state.value.email)
        viewBinding.passwordInput.setText(savedInstanceState?.getString(KEY_PASSWORD) ?: viewModel.state.value.password)
        viewBinding.usernameInput.doAfterTextChanged { viewModel.updateDraft(it?.toString().orEmpty(), viewBinding.emailInput.text?.toString().orEmpty(), viewBinding.passwordInput.text?.toString().orEmpty()) }
        viewBinding.emailInput.doAfterTextChanged { viewModel.updateDraft(viewBinding.usernameInput.text?.toString().orEmpty(), it?.toString().orEmpty(), viewBinding.passwordInput.text?.toString().orEmpty()) }
        viewBinding.passwordInput.doAfterTextChanged { viewModel.updateDraft(viewBinding.usernameInput.text?.toString().orEmpty(), viewBinding.emailInput.text?.toString().orEmpty(), it?.toString().orEmpty()) }
        viewBinding.submitButton.setOnClickListener {
            viewModel.submit(
                viewBinding.usernameInput.text?.toString().orEmpty(),
                viewBinding.emailInput.text?.toString().orEmpty(),
                viewBinding.passwordInput.text?.toString().orEmpty()
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (requireActivity() as MainActivity).applicationSession().error
                    .collect { error ->
                        val message = when (error) {
                            SessionError.StorageReadFailed -> getString(R.string.session_read_failed)
                            SessionError.StorageUnavailable -> getString(R.string.session_storage_unavailable)
                            SessionError.StorageWriteFailed,
                            SessionError.StorageClearFailed,
                            null -> null
                        }
                        message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
                    }
            }
        }
    }

// 处理 `onSaveInstanceState` 相关逻辑。
    override fun onSaveInstanceState(outState: Bundle) {
        val viewBinding = binding
        outState.putString(KEY_USERNAME, viewBinding?.usernameInput?.text?.toString().orEmpty())
        outState.putString(KEY_EMAIL, viewBinding?.emailInput?.text?.toString().orEmpty())
        outState.putString(KEY_PASSWORD, viewBinding?.passwordInput?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

// 渲染当前数据。
    private fun render(state: AuthFormState) {
        val viewBinding = binding ?: return
        viewBinding.emailLayout.isVisible = state.isRegisterMode
        viewBinding.loginModeButton.isEnabled = state.isRegisterMode
        viewBinding.registerModeButton.isEnabled = !state.isRegisterMode
        viewBinding.loginIndicator.isVisible = !state.isRegisterMode
        viewBinding.registerIndicator.isVisible = state.isRegisterMode
        viewBinding.loginModeButton.setTextColor(requireContext().getColor(if (state.isRegisterMode) R.color.brand_muted else R.color.brand_orange))
        viewBinding.registerModeButton.setTextColor(requireContext().getColor(if (state.isRegisterMode) R.color.brand_orange else R.color.brand_muted))
        viewBinding.submitButton.isEnabled = !state.isSubmitting
        viewBinding.submitButton.text = getString(if (state.isRegisterMode) R.string.register else R.string.login)
        viewBinding.usernameLayout.error = state.usernameError
        viewBinding.emailLayout.error = state.emailError
        viewBinding.passwordLayout.error = state.passwordError
        state.errorMessage?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
    }

// 在视图销毁时释放视图相关资源。
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "FandaziSession"
        const val KEY_USERNAME = "auth.username"
        const val KEY_EMAIL = "auth.email"
        const val KEY_PASSWORD = "auth.password"
    }
}
