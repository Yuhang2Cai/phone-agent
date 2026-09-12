package com.fandazi.app.feature.agent

enum class AgentSurface { HOME, SEARCH, MERCHANT, ORDERS, AUTH, PAYMENT }

/** Keep payment and authentication outside the assistant entry point by policy, not by convention. */
object AgentFloatingButtonPolicy {
    fun isVisibleOn(surface: AgentSurface): Boolean = surface in setOf(
        AgentSurface.HOME,
        AgentSurface.SEARCH,
        AgentSurface.MERCHANT,
        AgentSurface.ORDERS
    )
}
