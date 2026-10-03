// 身份认证模块：管理页面交互和状态展示。
package com.fandazi.app.feature.auth

import android.os.Bundle
import android.view.View
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
import com.fandazi.app.databinding.FragmentAuthExactBinding
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Figma账号密码认证页；UI只收集用户输入，身份、可选昵称和登录成功均由会话服务确认。 */
class AuthFragment : Fragment(R.layout.fragment_auth_exact) {
    private var binding: FragmentAuthExactBinding? = null
    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory((requireActivity() as MainActivity).applicationSession())
    }

    /** 绑定真实认证草稿、模式切换与返回来源，保留游客门槛和受保护页面恢复行为。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentAuthExactBinding.bind(view)
        binding = viewBinding
        viewBinding.skipLoginButton.isVisible = arguments?.getBoolean("require_login") != true
        viewBinding.authBackButton.isVisible = !viewBinding.skipLoginButton.isVisible
        // 注册返回先切回登录；受保护登录可取消回原页面，首次入口返回才明确选择游客。
        val goBack = {
            when {
                viewModel.state.value.isRegisterMode -> viewModel.showLogin()
                arguments?.getBoolean("require_login") == true -> findNavController().navigateUp()
                else -> viewModel.continueAsGuest()
            }
            Unit
        }
        viewBinding.authBackButton.setOnClickListener { goBack() }
        viewBinding.loginModeButton.setOnClickListener { viewModel.showLogin() }
        viewBinding.registerModeButton.setOnClickListener { viewModel.showRegister() }
        viewBinding.skipLoginButton.setOnClickListener {
            goBack()
        }
        viewBinding.usernameInput.setText(savedInstanceState?.getString(KEY_USERNAME) ?: viewModel.state.value.username)
        viewBinding.emailInput.setText(savedInstanceState?.getString(KEY_EMAIL) ?: viewModel.state.value.email)
        viewBinding.passwordInput.setText(savedInstanceState?.getString(KEY_PASSWORD) ?: viewModel.state.value.password)
        viewBinding.nicknameInput.setText(savedInstanceState?.getString(KEY_NICKNAME) ?: viewModel.state.value.nickname)
        if (savedInstanceState?.getBoolean(KEY_REGISTER_MODE) == true) viewModel.showRegister()
        // 输入草稿一起更新以保留模式/旋转恢复；昵称不能偷塞旧邮箱字段，也不会提前显示为已保存资料。
        val updateDraft = {
            viewModel.updateDraft(viewBinding.usernameInput.text?.toString().orEmpty(), viewBinding.emailInput.text?.toString().orEmpty(),
                viewBinding.passwordInput.text?.toString().orEmpty(), viewBinding.nicknameInput.text?.toString().orEmpty())
        }
        viewBinding.usernameInput.doAfterTextChanged { updateDraft() }
        viewBinding.emailInput.doAfterTextChanged { updateDraft() }
        viewBinding.passwordInput.doAfterTextChanged { updateDraft() }
        viewBinding.nicknameInput.doAfterTextChanged { updateDraft() }
        viewBinding.submitButton.setOnClickListener {
            viewModel.submit(
                viewBinding.usernameInput.text?.toString().orEmpty(),
                viewBinding.emailInput.text?.toString().orEmpty(),
                viewBinding.passwordInput.text?.toString().orEmpty(),
                viewBinding.nicknameInput.text?.toString().orEmpty()
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

    /** 仅保存当前用户的临时输入草稿；后端昵称事实仍取注册/登录响应，不持久化虚假保存状态。 */
    override fun onSaveInstanceState(outState: Bundle) {
        val viewBinding = binding
        outState.putString(KEY_USERNAME, viewBinding?.usernameInput?.text?.toString().orEmpty())
        outState.putString(KEY_EMAIL, viewBinding?.emailInput?.text?.toString().orEmpty())
        outState.putString(KEY_PASSWORD, viewBinding?.passwordInput?.text?.toString().orEmpty())
        outState.putString(KEY_NICKNAME, viewBinding?.nicknameInput?.text?.toString().orEmpty())
        outState.putBoolean(KEY_REGISTER_MODE, viewModel.state.value.isRegisterMode)
        super.onSaveInstanceState(outState)
    }

    /** 静态布局随模式切换；昵称可选、邮箱隐藏、真实请求进行中禁用重复提交。 */
    private fun render(state: AuthFormState) {
        val viewBinding = binding ?: return
        // 兼容邮箱不是新增必填项，昵称只在注册显示并真实提交；删除旧Tab/插画不改变认证流程。
        viewBinding.emailLayout.isVisible = false
        viewBinding.nicknameRow.isVisible = state.isRegisterMode
        viewBinding.authLoginInstruction.isVisible = !state.isRegisterMode
        viewBinding.loginModeButton.isVisible = state.isRegisterMode
        viewBinding.registerModeButton.isVisible = !state.isRegisterMode
        viewBinding.brandText.setText(if (state.isRegisterMode) R.string.auth_exact_register_title else R.string.auth_exact_brand)
        viewBinding.authTagline.setText(if (state.isRegisterMode) R.string.auth_exact_register_subtitle else R.string.auth_exact_login_subtitle)
        viewBinding.authFormHeading.setText(if (state.isRegisterMode) R.string.auth_exact_register_heading else R.string.auth_exact_login_heading)
        viewBinding.authModePrompt.setText(if (state.isRegisterMode) R.string.auth_exact_has_account else R.string.auth_exact_no_account)
        viewBinding.authAgreement.setText(if (state.isRegisterMode) R.string.auth_exact_register_agreement else R.string.auth_exact_login_agreement)
        viewBinding.submitButton.isEnabled = !state.isSubmitting
        viewBinding.submitButton.text = getString(if (state.isRegisterMode) R.string.auth_exact_complete_register else R.string.login)
        viewBinding.usernameLayout.error = state.usernameError
        viewBinding.emailLayout.error = state.emailError
        viewBinding.passwordLayout.error = state.passwordError
        state.errorMessage?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
    }

    /** 解除旧视图引用，防止返回或重建后继续向失效控件渲染身份数据。 */
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_USERNAME = "auth.username"
        const val KEY_EMAIL = "auth.email"
        const val KEY_PASSWORD = "auth.password"
        /** 可选昵称与模式属于同一表单草稿，恢复时不构造真实身份或重复注册。 */
        const val KEY_NICKNAME = "auth.nickname"
        const val KEY_REGISTER_MODE = "auth.register_mode"
    }
}
