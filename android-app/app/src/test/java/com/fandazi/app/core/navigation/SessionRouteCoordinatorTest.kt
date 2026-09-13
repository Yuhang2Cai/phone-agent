package com.fandazi.app.core.navigation

import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRouteCoordinatorTest {
    @Test
    fun authenticatedRestorationTargetsHomeAndDoesNotRepeatHomeNavigation() {
        val desired = SessionRouteCoordinator.routeFor(
            SessionState.Authenticated("token-1", User("alice", "alice@example.com"))
        )

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
    }

    @Test
    fun unauthenticatedStateTargetsAuthWhenHomeIsVisible() {
        val desired = SessionRouteCoordinator.routeFor(SessionState.Unauthenticated)

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
    }

    @Test
    fun guestStateTargetsHomeWithoutRepeatingNavigation() {
        val desired = SessionRouteCoordinator.routeFor(SessionState.Guest)

        assertTrue(SessionRouteCoordinator.shouldNavigate(SessionRoute.AUTH, desired))
        assertFalse(SessionRouteCoordinator.shouldNavigate(SessionRoute.HOME, desired))
    }

    @Test
    fun sameSessionRouteIsIdempotentForRepeatedStateEmissions() {
        val first = SessionRouteCoordinator.routeFor(SessionState.Authenticated("token", User("alice", "a@example.com")))
        val second = SessionRouteCoordinator.routeFor(SessionState.Authenticated("token", User("alice", "a@example.com")))

        assertTrue(first == second)
        assertFalse(SessionRouteCoordinator.shouldNavigate(first, second))
    }
}
