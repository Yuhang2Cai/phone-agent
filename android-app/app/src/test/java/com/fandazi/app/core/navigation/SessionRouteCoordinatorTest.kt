// 页面导航模块：验证关键行为与边界条件。
package com.fandazi.app.core.navigation

import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRouteCoordinatorTest {
    @Test
// 验证 `authenticatedRestorationTargetsHomeAndDoesNotRepeatHomeNavigation` 覆盖场景的预期行为与边界。
    fun authenticatedRestorationTargetsHomeAndDoesNotRepeatHomeNavigation() {
        val desired = SessionRouteCoordinator.routeFor(
            SessionState.Authenticated("token-1", User("alice", "alice@example.com"))
        )

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
    }

    @Test
// 验证 `unauthenticatedStateTargetsAuthWhenHomeIsVisible` 覆盖场景的预期行为与边界。
    fun unauthenticatedStateTargetsAuthWhenHomeIsVisible() {
        val desired = SessionRouteCoordinator.routeFor(SessionState.Unauthenticated)

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
    }

    @Test
// 验证 `guestStateTargetsHomeWithoutRepeatingNavigation` 覆盖场景的预期行为与边界。
    fun guestStateTargetsHomeWithoutRepeatingNavigation() {
        val desired = SessionRouteCoordinator.routeFor(SessionState.Guest)

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
    }

    @Test
// 验证 `sameSessionRouteIsIdempotentForRepeatedStateEmissions` 覆盖场景的预期行为与边界。
    fun sameSessionRouteIsIdempotentForRepeatedStateEmissions() {
        val first = SessionRouteCoordinator.routeFor(SessionState.Authenticated("token", User("alice", "a@example.com")))
        val second = SessionRouteCoordinator.routeFor(SessionState.Authenticated("token", User("alice", "a@example.com")))

        assertTrue(first == second)
        assertFalse(SessionRouteCoordinator.shouldNavigate(first, second))
    }
}
