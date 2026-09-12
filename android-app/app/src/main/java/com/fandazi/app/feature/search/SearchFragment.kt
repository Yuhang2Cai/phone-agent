package com.fandazi.app.feature.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentSearchBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface

class SearchFragment : Fragment(R.layout.fragment_search) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        FragmentSearchBinding.bind(view).agentFab.setOnClickListener { openAgentAssistant(AgentSurface.SEARCH) }
    }
}
