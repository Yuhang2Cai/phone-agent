// 会话路由协调器：按登录状态决定页面入口。
package com.fandazi.app.core.navigation

import com.fandazi.app.feature.auth.SessionState

enum class SessionRoute {
    AUTH,
    HOME
}

object SessionRouteCoordinator {
// 处理 `routeFor` 相关逻辑。
    fun routeFor(state: SessionState): SessionRoute = when (state) {
        SessionState.Unauthenticated -> SessionRoute.AUTH
        SessionState.Guest -> SessionRoute.HOME
        is SessionState.Authenticated -> SessionRoute.HOME
    }

// 判断是否应当navigate。
    fun shouldNavigate(current: SessionRoute?, desired: SessionRoute): Boolean = current != desired
}
