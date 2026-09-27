// 个人资料模块：管理页面交互和状态展示。
package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import kotlinx.coroutines.launch
import androidx.navigation.findNavController

class ProfileFragment : Fragment(R.layout.fragment_profile) {
// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.profile_logout).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { (requireActivity() as MainActivity).applicationSession().logout() }
        }
        view.findViewById<View>(R.id.profile_address_entry).setOnClickListener {
            (requireActivity() as MainActivity).openProtectedDestination(R.id.addressFragment)
        }
        view.findViewById<View>(R.id.profile_automation_entry).setOnClickListener {
            (requireActivity() as MainActivity).openProtectedDestination(R.id.automationSettingsFragment)
        }
    }
}
