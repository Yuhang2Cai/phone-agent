package com.fandazi.app.core.navigation

import com.fandazi.app.feature.auth.SessionState

enum class SessionRoute {
    AUTH,
    HOME
}

object SessionRouteCoordinator {
    fun routeFor(state: SessionState): SessionRoute = when (state) {
        SessionState.Unauthenticated -> SessionRoute.AUTH
        is SessionState.Authenticated -> SessionRoute.HOME
    }

    fun shouldNavigate(current: SessionRoute?, desired: SessionRoute): Boolean = current != desired
}
