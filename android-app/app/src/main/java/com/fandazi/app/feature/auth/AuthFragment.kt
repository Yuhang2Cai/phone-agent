package com.fandazi.app.feature.auth

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentAuthBinding.bind(view)
        binding = viewBinding
        viewBinding.loginModeButton.setOnClickListener { viewModel.showLogin() }
        viewBinding.registerModeButton.setOnClickListener { viewModel.showRegister() }
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

    private fun render(state: AuthFormState) {
        val viewBinding = binding ?: return
        viewBinding.emailLayout.isVisible = state.isRegisterMode
        viewBinding.loginModeButton.isEnabled = state.isRegisterMode
        viewBinding.registerModeButton.isEnabled = !state.isRegisterMode
        viewBinding.submitButton.isEnabled = !state.isSubmitting
        viewBinding.submitButton.text = getString(if (state.isRegisterMode) R.string.register else R.string.login)
        viewBinding.usernameLayout.error = state.usernameError
        viewBinding.emailLayout.error = state.emailError
        viewBinding.passwordLayout.error = state.passwordError
        state.errorMessage?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
