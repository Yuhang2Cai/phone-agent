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

// 初始化组件与页面状态。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProtectedDestination = savedInstanceState?.getInt(KEY_PENDING_DESTINATION)?.takeIf { it != 0 }
        setContentView(R.layout.activity_main)
        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item: MenuItem ->
            // AI 助手使用正式导航页面；游客点击时先进入登录页，避免绕过 Agent 的认证门槛。
            if (item.itemId == R.id.agentAssistantTab) {
                if (applicationSession().state.value is SessionState.Authenticated) {
                    navHost.navController.navigate(R.id.agentChatFragment)
                } else {
                    pendingProtectedDestination = R.id.agentChatFragment
                    openLogin()
                }
                return@setOnItemSelectedListener false
            }
            if (navHost.navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true
            navHost.navController.navigate(item.itemId)
            true
        }
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id != R.id.authFragment && pendingProtectedDestination != null) {
                pendingProtectedDestination = null
            }
            // 门店三个内容状态属于首页浏览链路，继续显示并选中首页 Tab；套餐详情按设计隐藏底部导航。
            bottomNavigation.isVisible = destination.id in setOf(
                R.id.homeFragment,
                R.id.searchFragment,
                R.id.storeFragment,
                R.id.agentChatFragment,
                R.id.ordersFragment,
                R.id.profileFragment
            )
            if (bottomNavigation.isVisible) {
                val selectedItem = if (destination.id in setOf(R.id.searchFragment, R.id.storeFragment)) {
                    R.id.homeFragment
                } else if (destination.id == R.id.agentChatFragment) {
                    R.id.agentAssistantTab
                } else {
                    destination.id
                }
                bottomNavigation.menu.findItem(selectedItem)?.isChecked = true
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applicationSession().state.collect(::navigateForSession)
            }
        }
    }

// 处理 `navigateForSession` 相关逻辑。
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
            R.id.automationSettingsFragment, R.id.cartFragment, R.id.paymentFragment,
            R.id.agentChatFragment -> SessionRoute.HOME
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

// 处理 `applicationSession` 相关逻辑。
    fun applicationSession() = (application as FandaziApplication).sessionRepository
// 处理 `orderingRepository` 相关逻辑。
    fun orderingRepository() = (application as FandaziApplication).orderingRepository
// 处理 `cartStateStore` 相关逻辑。
    fun cartStateStore() = (application as FandaziApplication).cartStateStore
// 处理 `cartOwner` 相关逻辑。
    fun cartOwner() = (applicationSession().state.value as? SessionState.Authenticated)?.user?.username ?: "guest"
// 处理 `automationSettingsRepository` 相关逻辑。
    fun automationSettingsRepository() = (application as FandaziApplication).automationSettingsRepository

// 打开protected、destination。
    fun openProtectedDestination(destination: Int) {
        if (applicationSession().state.value is SessionState.Authenticated) {
            navHost.navController.navigate(destination)
        } else {
            pendingProtectedDestination = destination
            navHost.navController.navigate(R.id.authFragment, bundleOf("require_login" to true))
        }
    }

// 打开login。
    fun openLogin() {
        navHost.navController.navigate(R.id.authFragment, bundleOf("require_login" to true))
    }

// 处理 `onSaveInstanceState` 相关逻辑。
    override fun onSaveInstanceState(outState: Bundle) {
        pendingProtectedDestination?.let { outState.putInt(KEY_PENDING_DESTINATION, it) }
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val KEY_PENDING_DESTINATION = "pending_protected_destination"
    }
}
