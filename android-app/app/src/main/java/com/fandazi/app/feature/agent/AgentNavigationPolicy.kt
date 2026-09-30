// AI 助手导航策略：集中约束登录门槛和卡片事件的导航边界。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.navigation.SessionRoute

/**
 * 描述用户点击 AI 助手入口后应该进入的目标。
 *
 * 该策略只处理访问权限，不携带门店、套餐或订单业务数据；业务数据必须由后端接口返回。
 */
sealed interface AgentEntryDestination {
    /** 已登录用户可以进入聊天页。 */
    data object Chat : AgentEntryDestination

    /** 游客只能进入登录页，并记录登录成功后的回访目标。 */
    data class Login(val returnTo: SessionRoute) : AgentEntryDestination
}

/**
 * 计算 AI 助手入口目标。
 *
 * @param isLoggedIn 当前会话是否已经完成认证。
 * @return 登录用户进入聊天页，游客进入登录页。
 */
fun agentEntryDestination(isLoggedIn: Boolean): AgentEntryDestination =
    if (isLoggedIn) AgentEntryDestination.Chat else AgentEntryDestination.Login(SessionRoute.HOME)
