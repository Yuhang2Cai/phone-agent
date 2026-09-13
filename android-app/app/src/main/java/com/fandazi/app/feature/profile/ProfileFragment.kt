package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import android.widget.TextView
import com.fandazi.app.feature.ordering.Address

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.profile_logout).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { (requireActivity() as MainActivity).applicationSession().logout() }
        }
        val activity = requireActivity() as MainActivity
        val list = view.findViewById<LinearLayout>(R.id.profile_addresses)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = activity.orderingRepository().loadAddresses()) {
                is com.fandazi.app.core.network.ApiResult.Success -> result.value.forEach { address ->
                    val row = TextView(requireContext()).apply {
                        text = "${address.recipient} · ${address.phone}\n${address.detail}${if (address.defaultAddress) " · 默认地址" else ""}"
                        setPadding(0, 16, 0, 16)
                    }
                    row.setOnClickListener {
                        if (!address.defaultAddress) viewLifecycleOwner.lifecycleScope.launch { activity.orderingRepository().setDefaultAddress(address.id) }
                    }
                    row.setOnLongClickListener {
                        if (address.defaultAddress) false else { viewLifecycleOwner.lifecycleScope.launch { activity.orderingRepository().deleteAddress(address.id) }; true }
                    }
                    list.addView(row)
                }
                is com.fandazi.app.core.network.ApiResult.Failure -> list.addView(TextView(requireContext()).apply { text = "地址加载失败，请重试" })
            }
        }
    }
}
