package com.fandazi.app.feature.agent

import androidx.fragment.app.Fragment

/** Shared floating-entry behavior for customer pages. Authentication and payment deliberately do not call this. */
fun Fragment.openAgentAssistant(surface: AgentSurface) {
    check(AgentFloatingButtonPolicy.isVisibleOn(surface)) { "Agent entry point is not permitted on $surface" }
    AgentSheetFragment.show(parentFragmentManager)
    (activity as? com.fandazi.app.MainActivity)?.let { activity ->
        androidx.lifecycle.ViewModelProvider(activity, AgentViewModelFactory((activity.application as com.fandazi.app.FandaziApplication).agentRepository))
            .get(AgentViewModel::class.java)
            .openAssistant()
    }
}
