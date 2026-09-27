// 主界面入口：根据会话状态切换导航目标。
package com.fandazi.app

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import androidx.core.os.bundleOf
import android.util.Log
import com.fandazi.app.core.navigation.SessionRoute
import com.fandazi.app.core.navigation.SessionRouteCoordinator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.fandazi.app.feature.auth.SessionState

class MainActivity : AppCompatActivity() {
    private lateinit var navHost: NavHostFragment
    private lateinit var bottomNavigation: com.google.android.material.bottomnavigation.BottomNavigationView
    private var pendingProtectedDestination: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProtectedDestination = savedInstanceState?.getInt(KEY_PENDING_DESTINATION)?.takeIf { it != 0 }
        setContentView(R.layout.activity_main)
        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item: MenuItem ->
            if (navHost.navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true
            navHost.navController.navigate(item.itemId)
            true
        }
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id != R.id.authFragment && pendingProtectedDestination != null) {
                pendingProtectedDestination = null
            }
            bottomNavigation.isVisible = destination.id in setOf(R.id.homeFragment, R.id.searchFragment, R.id.ordersFragment, R.id.profileFragment)
            if (bottomNavigation.isVisible) {
                bottomNavigation.menu.findItem(if (destination.id == R.id.searchFragment) R.id.homeFragment else destination.id)?.isChecked = true
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applicationSession().state.collect(::navigateForSession)
            }
        }
    }

    private fun navigateForSession(state: SessionState) {
        ViewModelProvider(
            this,
            com.fandazi.app.feature.ordering.OrderingViewModelFactory(orderingRepository(), cartStateStore(), cartOwner())
        )[com.fandazi.app.feature.ordering.OrderingViewModel::class.java].switchCartOwner(
            (state as? SessionState.Authenticated)?.user?.username ?: "guest"
        )
        val desired = SessionRouteCoordinator.routeFor(state)
        val current = when (navHost.navController.currentDestination?.id) {
            R.id.authFragment -> SessionRoute.AUTH
            R.id.homeFragment, R.id.searchFragment, R.id.merchantFragment, R.id.ordersFragment,
            R.id.profileFragment, R.id.addressFragment, R.id.addressEditFragment,
            R.id.automationSettingsFragment, R.id.cartFragment, R.id.paymentFragment -> SessionRoute.HOME
            else -> null
        }
        Log.d("FandaziSession", "session navigation: state=${state::class.java.simpleName}, current=$current, desired=$desired")
        if (state is SessionState.Authenticated && pendingProtectedDestination != null) {
            val destination = pendingProtectedDestination ?: return
            pendingProtectedDestination = null
            navHost.navController.navigate(destination, null, navOptions {
                popUpTo(R.id.authFragment) { inclusive = true }
            })
            return
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
    fun orderingRepository() = (application as FandaziApplication).orderingRepository
    fun cartStateStore() = (application as FandaziApplication).cartStateStore
    fun cartOwner() = (applicationSession().state.value as? SessionState.Authenticated)?.user?.username ?: "guest"
    fun automationSettingsRepository() = (application as FandaziApplication).automationSettingsRepository

    fun openProtectedDestination(destination: Int) {
        if (applicationSession().state.value is SessionState.Authenticated) {
            navHost.navController.navigate(destination)
        } else {
            pendingProtectedDestination = destination
            navHost.navController.navigate(R.id.authFragment, bundleOf("require_login" to true))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingProtectedDestination?.let { outState.putInt(KEY_PENDING_DESTINATION, it) }
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val KEY_PENDING_DESTINATION = "pending_protected_destination"
    }
}
