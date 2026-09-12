package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentOrdersBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface

class OrdersFragment : Fragment(R.layout.fragment_orders) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrdersBinding.bind(view)
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.ORDERS) }
        binding.mockPaymentButton.setOnClickListener { view.findNavController().navigate(R.id.paymentFragment) }
    }
}
