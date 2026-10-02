// 我的页：根据真实登录态展示订单、记忆、帮助及设置，游客仅保留登录和设置。
package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentProfileBinding
import com.fandazi.app.feature.auth.SessionState
import kotlinx.coroutines.launch

/** 复用正式Session与导航，记忆不保存端侧业务数据，旧外卖入口暂保留源码但不显示。 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    /** 绑定固定UI入口，登录成功后的返回目标由MainActivity统一处理。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProfileBinding.bind(view)
        val activity = requireActivity() as MainActivity
        binding.profileLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { activity.applicationSession().logout() }
        }
        binding.profileLoginEntry.setOnClickListener {
            activity.openLogin()
        }
        binding.profileMemoryEntry.setOnClickListener { activity.openProtectedDestination(R.id.memoryFragment) }
        binding.profileOrdersEntry.setOnClickListener { activity.openProtectedDestination(R.id.ordersFragment) }
        binding.profileRefundEntry.setOnClickListener { activity.openProtectedDestination(R.id.ordersFragment) }
        binding.profileSettingsEntry.setOnClickListener {
            // 本期设置只有账户操作；不重新露出已退役的地址/自动下单入口。
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("设置")
                .setMessage(if (activity.applicationSession().tokenOrNull() == null) "当前为游客" else "已登录")
                .setPositiveButton("确定", null).show()
        }
        // 旧隐藏行不再绑定监听器，避免无障碍或错误代码重新唤起退役路径。
        // FAQ 可公开阅读；投诉提交时再由受保护接口校验用户，入口保持清晰可达。
        binding.profileHelpEntry.setOnClickListener { it.findNavController().navigate(R.id.helpFaqFragment) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity.applicationSession().state.collect { render(binding, it) }
            }
        }
    }

    /** 登录身份只显示真实用户名；游客菜单不展示需要业务权限的条目。 */
    private fun render(binding: FragmentProfileBinding, state: SessionState) {
        // 直接消费已提交的真实会话模型，不引入旧地址/自动下单展示类这一未交付依赖。
        val account = (state as? SessionState.Authenticated)?.user
        val authenticated = account != null
        binding.profileAccountName.text = account?.username ?: "游客"
        binding.profileGuestHint.isVisible = !authenticated
        binding.profileLoginEntry.isVisible = !authenticated
        binding.profileLogout.isVisible = authenticated
        binding.profileBusinessEntries.isVisible = authenticated
        binding.profileMemoryEntry.isVisible = authenticated
        binding.profileOrdersEntry.isVisible = authenticated
        binding.profileRefundEntry.isVisible = authenticated
        binding.profileHelpEntry.isVisible = authenticated
        // 旧行只保留布局兼容，监听和导航已退役，不承载任何交易能力。
        binding.profileAddressEntry.isVisible = false
        binding.profileAutomationEntry.isVisible = false
    }
}
