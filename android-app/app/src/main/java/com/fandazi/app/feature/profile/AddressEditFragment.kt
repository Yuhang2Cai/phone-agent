// 个人资料模块：管理页面交互和状态展示。
package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.databinding.FragmentAddressEditBinding
import com.fandazi.app.feature.ordering.Address
import kotlinx.coroutines.launch

class AddressEditFragment : Fragment(R.layout.fragment_address_edit) {
    private val viewModel: AddressEditViewModel by viewModels {
        AddressEditViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentAddressEditBinding.bind(view)
        val id = arguments?.getLong(ARG_ID) ?: 0L
        val isExisting = id > 0
        val isDefault = arguments?.getBoolean(ARG_DEFAULT) ?: false
        val isValid = arguments?.getBoolean(ARG_VALID) ?: true
        binding.addressEditTitle.text = if (isExisting) "编辑地址" else getString(R.string.address_edit_title)
        binding.addressRecipient.setText(arguments?.getString(ARG_RECIPIENT).orEmpty())
        binding.addressPhone.setText(arguments?.getString(ARG_PHONE).orEmpty())
        binding.addressDetail.setText(arguments?.getString(ARG_DETAIL).orEmpty())
        binding.addressDefault.isChecked = isDefault
        binding.addressDefault.isEnabled = isValid
        binding.addressDelete.isVisible = isExisting
        binding.addressEditBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.addressSave.setOnClickListener { save(binding, id) }
        binding.addressDelete.setOnClickListener {
            if (isDefault) {
                Toast.makeText(requireContext(), "默认地址不能直接删除，请先设置其他默认地址", Toast.LENGTH_LONG).show()
            } else {
                viewModel.delete(id)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val enabled = state !is AddressSubmitState.Submitting
                    binding.addressSave.isEnabled = enabled
                    binding.addressDelete.isEnabled = enabled
                    when (state) {
                        AddressSubmitState.Success -> {
                            viewModel.consumeResult()
                            view.findNavController().navigateUp()
                        }
                        AddressSubmitState.Failure -> {
                            viewModel.consumeResult()
                            Toast.makeText(requireContext(), "操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun save(binding: FragmentAddressEditBinding, id: Long) {
        val address = addressFrom(binding, id)
        if (address.recipient.isBlank() || address.phone.isBlank() || address.detail.isBlank()) {
            Toast.makeText(requireContext(), "请填写完整的联系人、手机号码和详细地址", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.save(address)
    }

    private fun addressFrom(binding: FragmentAddressEditBinding, id: Long) = Address(
        id,
        binding.addressRecipient.text.toString().trim(),
        binding.addressPhone.text.toString().trim(),
        binding.addressDetail.text.toString().trim(),
        binding.addressDefault.isChecked,
        arguments?.getBoolean(ARG_VALID) ?: true
    )

    companion object {
        const val ARG_ID = "address_id"
        const val ARG_RECIPIENT = "address_recipient"
        const val ARG_PHONE = "address_phone"
        const val ARG_DETAIL = "address_detail"
        const val ARG_DEFAULT = "address_default"
        const val ARG_VALID = "address_valid"
    }
}
