// 助手入口策略：限制可显示悬浮入口的页面。
package com.fandazi.app.feature.agent

enum class AgentSurface { HOME, SEARCH, MERCHANT, ORDERS, AUTH, PAYMENT }

/** 通过统一策略排除支付与认证页面的助手入口。 */
object AgentFloatingButtonPolicy {
    fun isVisibleOn(surface: AgentSurface): Boolean = surface in setOf(
        AgentSurface.HOME,
        AgentSurface.SEARCH,
        AgentSurface.MERCHANT,
        AgentSurface.ORDERS
    )
}
