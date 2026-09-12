package com.fandazi.app.feature.merchant

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentMerchantBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface

class MerchantFragment : Fragment(R.layout.fragment_merchant) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        FragmentMerchantBinding.bind(view).agentFab.setOnClickListener { openAgentAssistant(AgentSurface.MERCHANT) }
    }
}
