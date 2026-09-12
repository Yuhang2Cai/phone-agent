package com.fandazi.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fandazi.app.core.navigation.SessionRoute
import com.fandazi.app.core.navigation.SessionRouteCoordinator
import com.fandazi.app.feature.auth.SessionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var navHost: NavHostFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applicationSession().state.collect(::navigateForSession)
            }
        }
    }

    private fun navigateForSession(state: SessionState) {
        val desired = SessionRouteCoordinator.routeFor(state)
        val current = when (navHost.navController.currentDestination?.id) {
            R.id.authFragment -> SessionRoute.AUTH
            R.id.homeFragment, R.id.searchFragment, R.id.merchantFragment, R.id.ordersFragment, R.id.paymentFragment -> SessionRoute.HOME
            else -> null
        }
        if (!SessionRouteCoordinator.shouldNavigate(current, desired)) return
        when (desired) {
            SessionRoute.HOME -> navHost.navController.navigate(R.id.homeFragment, null, navOptions {
                popUpTo(R.id.authFragment) { inclusive = true }
            })
            SessionRoute.AUTH -> navHost.navController.navigate(R.id.authFragment, null, navOptions {
                popUpTo(R.id.homeFragment) { inclusive = true }
            })
        }
    }

    fun applicationSession() = (application as FandaziApplication).sessionRepository
}
