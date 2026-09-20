package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.databinding.FragmentAddressBinding
import com.fandazi.app.feature.ordering.Address
import kotlinx.coroutines.launch

class AddressFragment : Fragment(R.layout.fragment_address) {
    private var binding: FragmentAddressBinding? = null
    private var loading = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentAddressBinding.bind(view)
        binding?.addressBack?.setOnClickListener { view.findNavController().navigateUp() }
        binding?.addressAdd?.setOnClickListener { openEditor(view, null) }
        binding?.addressState?.setOnClickListener { loadAddresses() }
    }

    override fun onResume() {
        super.onResume()
        loadAddresses()
    }

    override fun onDestroyView() {
        loading = false
        binding = null
        super.onDestroyView()
    }

    private fun loadAddresses() {
        val current = binding ?: return
        if (loading) return
        loading = true
        current.addressState.isVisible = true
        current.addressState.text = getString(R.string.address_loading)
        current.addressList.removeAllViews()
        val repo = (requireActivity() as MainActivity).orderingRepository()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repo.loadAddresses()) {
                is ApiResult.Success -> {
                    current.addressState.text = getString(R.string.address_empty)
                    current.addressState.isVisible = result.value.isEmpty()
                    result.value.forEach { address -> addAddressCard(current, address) }
                }
                is ApiResult.Failure -> {
                    current.addressState.isVisible = true
                    current.addressState.text = getString(R.string.address_error)
                }
            }
            loading = false
        }
    }

    private fun addAddressCard(current: FragmentAddressBinding, address: Address) {
        val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_address_card, current.addressList, false)
        card.background = requireContext().getDrawable(if (address.defaultAddress) R.drawable.address_card_default_background else R.drawable.address_card_background)
        card.findViewById<TextView>(R.id.address_radio).apply {
            background = requireContext().getDrawable(if (address.defaultAddress) R.drawable.address_radio_selected else R.drawable.address_radio_unselected)
            text = if (address.defaultAddress) "✓" else ""
            isEnabled = !address.defaultAddress && address.valid
            setOnClickListener { setDefault(address) }
        }
        card.findViewById<TextView>(R.id.address_name).text = addressName(address)
        card.findViewById<TextView>(R.id.address_default_badge).isVisible = address.defaultAddress
        card.findViewById<TextView>(R.id.address_detail_text).text = "●  ${address.detail}"
        card.findViewById<TextView>(R.id.address_contact).text = "♙  ${address.recipient}    ${maskPhone(address.phone)}"
        card.findViewById<TextView>(R.id.address_invalid_badge).isVisible = !address.valid
        card.findViewById<View>(R.id.address_edit).setOnClickListener { openEditor(requireView(), address) }
        card.setOnClickListener { openEditor(requireView(), address) }
        card.contentDescription = "${addressName(address)}，${address.detail}，${address.recipient}${if (address.defaultAddress) "，默认地址" else ""}"
        current.addressList.addView(card)
    }

    private fun setDefault(address: Address) {
        if (loading || !address.valid) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            when ((requireActivity() as MainActivity).orderingRepository().setDefaultAddress(address.id)) {
                is ApiResult.Success -> {
                    loading = false
                    loadAddresses()
                }
                is ApiResult.Failure -> {
                    loading = false
                    Toast.makeText(requireContext(), "默认地址设置失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openEditor(view: View, address: Address?) {
        view.findNavController().navigate(
            R.id.addressEditFragment,
            bundleOf(
                AddressEditFragment.ARG_ID to (address?.id ?: 0L),
                AddressEditFragment.ARG_RECIPIENT to address?.recipient.orEmpty(),
                AddressEditFragment.ARG_PHONE to address?.phone.orEmpty(),
                AddressEditFragment.ARG_DETAIL to address?.detail.orEmpty(),
                AddressEditFragment.ARG_DEFAULT to (address?.defaultAddress ?: false),
                AddressEditFragment.ARG_VALID to (address?.valid ?: true)
            )
        )
    }

    private fun addressName(address: Address): String = when {
        address.defaultAddress -> "公司默认地址"
        !address.valid -> "其他地址"
        else -> "${address.recipient}的地址"
    }

    private fun maskPhone(phone: String): String = if (phone.length >= 7) {
        phone.take(3) + "****" + phone.takeLast(4)
    } else phone
}
