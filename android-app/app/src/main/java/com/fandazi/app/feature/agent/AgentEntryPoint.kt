// 助手入口：复用客户页面的悬浮入口行为。
package com.fandazi.app.feature.agent

import androidx.fragment.app.Fragment

/** 客户页面共用的悬浮入口；认证与支付页面不调用。 */
fun Fragment.openAgentAssistant(surface: AgentSurface) {
    check(AgentFloatingButtonPolicy.isVisibleOn(surface)) { "Agent entry point is not permitted on $surface" }
    AgentSheetFragment.show(parentFragmentManager)
    (activity as? com.fandazi.app.MainActivity)?.let { activity ->
        androidx.lifecycle.ViewModelProvider(activity, AgentViewModelFactory((activity.application as com.fandazi.app.FandaziApplication).agentRepository, activity.application))
            .get(AgentViewModel::class.java)
            .openAssistant()
    }
}
