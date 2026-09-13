package com.fandazi.app.feature.profile

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import kotlinx.coroutines.launch

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.profile_logout).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { (requireActivity() as MainActivity).applicationSession().logout() }
        }
    }
}
