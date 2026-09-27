// 个人资料模块：管理页面交互和状态展示。
package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.databinding.FragmentAutomationSettingsBinding
import com.fandazi.app.feature.ordering.Address
import kotlinx.coroutines.launch
import java.math.BigDecimal

class AutomationSettingsFragment : Fragment(R.layout.fragment_automation_settings) {
    private var defaultAddress: Address? = null
    private var rendering = false
    private var saving = false
    private var policyAddressChanged = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentAutomationSettingsBinding.bind(view)
        val username = ((requireActivity() as MainActivity).applicationSession().state.value as? com.fandazi.app.feature.auth.SessionState.Authenticated)?.user?.username.orEmpty()
        val taskPreference = TaskAutomationPreference(requireContext(), username)
        binding.automationBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.automationAddress.setOnClickListener { view.findNavController().navigate(R.id.addressFragment) }
        binding.automationThisTask.isChecked = taskPreference.isEnabled()
        binding.automationThisTask.setOnCheckedChangeListener { _, checked ->
            val limit = binding.automationLimit.text.toString().replace("¥", "").trim().toBigDecimalOrNull() ?: BigDecimal("30")
            taskPreference.save(checked, limit)
        }
        binding.automationLongTerm.setOnCheckedChangeListener { _, checked ->
            if (!rendering) updateLongTerm(binding, checked)
        }
        binding.automationLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.automationLongTerm.isChecked) updateLongTerm(binding, true)
            renderBlockReason(binding)
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { loadSettings(FragmentAutomationSettingsBinding.bind(it)) }
    }

    private fun loadSettings(binding: FragmentAutomationSettingsBinding) {
        val activity = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            val addresses = activity.orderingRepository().loadAddresses()
            defaultAddress = (addresses as? ApiResult.Success)?.value?.firstOrNull { it.defaultAddress && it.valid }
            binding.automationAddress.text = defaultAddress?.let { "公司默认地址\n${it.detail}  ›" } ?: getString(R.string.automation_address_empty)
            when (val policy = activity.automationSettingsRepository().loadPolicy()) {
                is ApiResult.Success -> policy.value?.let {
                    policyAddressChanged = it.enabled && defaultAddress?.id != it.defaultAddressId
                    rendering = true
                    binding.automationLongTerm.isChecked = it.enabled && !policyAddressChanged
                    binding.automationLimit.setText("¥${it.maxAmount.stripTrailingZeros().toPlainString()}")
                    rendering = false
                }
                is ApiResult.Failure -> Unit
            }
            if (binding.automationLimit.text.isNullOrBlank()) binding.automationLimit.setText("¥30")
            renderBlockReason(binding)
        }
    }

    private fun updateLongTerm(binding: FragmentAutomationSettingsBinding, enabled: Boolean) {
        val address = defaultAddress
        val limit = binding.automationLimit.text.toString().replace("¥", "").trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
        val state = AutomationSettingsState(address, limit, longTermEnabled = enabled)
        val block = if (enabled) state.blockReason() else null
        if (block != null || limit.signum() <= 0) {
            rendering = true
            binding.automationLongTerm.isChecked = false
            rendering = false
            binding.automationBlockReason.text = block?.message ?: "请设置有效的最高金额"
            return
        }
        if (address == null || saving) return
        saving = true
        setControlsEnabled(binding, false)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = (requireActivity() as MainActivity).automationSettingsRepository().savePolicy(
                AutomationPolicy(enabled, address.id, limit)
            )
            saving = false
            setControlsEnabled(binding, true)
            if (result is ApiResult.Failure) {
                rendering = true
                binding.automationLongTerm.isChecked = !enabled
                rendering = false
                Toast.makeText(requireContext(), "设置保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
            } else {
                policyAddressChanged = false
            }
            renderBlockReason(binding)
        }
    }

    private fun renderBlockReason(binding: FragmentAutomationSettingsBinding) {
        val limit = binding.automationLimit.text.toString().replace("¥", "").trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
        val state = AutomationSettingsState(defaultAddress, limit, longTermEnabled = binding.automationLongTerm.isChecked)
        binding.automationBlockReason.text = when {
            policyAddressChanged -> "默认地址已变化，请重新开启长期自动下单"
            binding.automationLongTerm.isChecked && limit.signum() <= 0 -> "请设置有效的最高金额"
            binding.automationLongTerm.isChecked -> state.blockReason()?.message.orEmpty()
            defaultAddress == null -> "长期自动下单需要先设置有效默认地址"
            else -> ""
        }
    }

    private fun setControlsEnabled(binding: FragmentAutomationSettingsBinding, enabled: Boolean) {
        binding.automationThisTask.isEnabled = enabled
        binding.automationLongTerm.isEnabled = enabled
        binding.automationLimit.isEnabled = enabled
        binding.automationAddress.isEnabled = enabled
    }

}
