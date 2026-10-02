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
    /** 购买登录返回保留资源和数量，不能在认证成功后退回首页丢失目标。 */
    private var pendingProtectedArguments: Bundle? = null
    /** 认证清空旧栈时保留来源页面与导航参数；它们是资源引用，不是价格或授权事实。 */
    private var pendingReturnDestination: Int? = null
    private var pendingReturnArguments: Bundle? = null

// 初始化组件与页面状态。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProtectedDestination = savedInstanceState?.getInt(KEY_PENDING_DESTINATION)?.takeIf { it != 0 }
        pendingProtectedArguments = savedInstanceState?.getBundle("pending_protected_arguments")
        pendingReturnDestination = savedInstanceState?.getInt("pending_return_destination")?.takeIf { it != 0 }
        pendingReturnArguments = savedInstanceState?.getBundle("pending_return_arguments")
        setContentView(R.layout.activity_main)
        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item: MenuItem ->
            // 游客也进入唯一聊天路由，由 H 登录拦截卡禁用发送；仅点击立即登录才打开认证页。
            if (item.itemId == R.id.agentAssistantTab) {
                if (navHost.navController.currentDestination?.id != R.id.agentChatFragment)
                    navHost.navController.navigate(R.id.agentChatFragment)
                return@setOnItemSelectedListener false
            }
            if (navHost.navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true
            navHost.navController.navigate(item.itemId)
            true
        }
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id != R.id.authFragment && pendingProtectedDestination != null) {
                pendingProtectedDestination = null
                pendingProtectedArguments = null
                pendingReturnDestination = null
                pendingReturnArguments = null
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
        // 认证切换时隔离 Activity 级聊天历史；游客与不同账号不复用旧消息、卡片或 SSE 游标。
        ViewModelProvider(this, com.fandazi.app.feature.agent.AgentViewModelFactory(
            (application as FandaziApplication).agentRepository
        ))[com.fandazi.app.feature.agent.AgentViewModel::class.java].bindAuthenticatedOwner(
            (state as? SessionState.Authenticated)?.user?.username
        )
        // 团购身份切换只隔离聊天；旧购物车ViewModel已退役，不创建或读取旧端侧状态。
        val desired = SessionRouteCoordinator.routeFor(state)
        val current = when (navHost.navController.currentDestination?.id) {
            R.id.authFragment -> SessionRoute.AUTH
            R.id.homeFragment, R.id.searchFragment, R.id.storeFragment, R.id.ordersFragment,
            R.id.profileFragment,
            R.id.agentChatFragment, R.id.dealDetailFragment, R.id.dealCheckoutBottomSheetFragment,
            // Task7 本人订单/券详情属于已登录业务路由，状态重新派发时不能丢失当前资源退回首页。
            R.id.groupBuyPaymentFragment, R.id.paymentSuccessFragment,
            R.id.groupOrderDetailFragment, R.id.voucherDetailFragment, R.id.memoryFragment -> SessionRoute.HOME
            else -> null
        }
        Log.d("FandaziSession", "session navigation: state=${state::class.java.simpleName}, current=$current, desired=$desired")
        if (state is SessionState.Authenticated && pendingProtectedDestination != null) {
            val destination = pendingProtectedDestination ?: return
            val targetArguments = pendingProtectedArguments
            val returnDestination = pendingReturnDestination
            val returnArguments = pendingReturnArguments
            pendingProtectedDestination = null
            pendingProtectedArguments = null
            pendingReturnDestination = null
            pendingReturnArguments = null
            // 过期退出与显式登录可能先后进入认证页。清除所有认证栈项，而非只弹出最上层，
            // 否则资源恢复成功后按返回仍会遇到另一张旧登录页。
            val controller = navHost.navController
            while (runCatching { controller.getBackStackEntry(R.id.authFragment) }.isSuccess) {
                if (!controller.popBackStack(R.id.authFragment, true)) break
            }
            // 旧受保护页面的失败状态不沿用：移除当前同目标，再重新创建并读取新身份的事实。
            if (controller.currentDestination?.id == destination) controller.popBackStack(destination, true)
            if (controller.currentDestination == null) {
                controller.navigate(R.id.homeFragment)
            }
            // 注销观察者可能已清空原浏览栈；恢复“我的”或原套餐等来源，再恢复目标，
            // 使返回仍回到正确业务入口，来源页重新向接口读取，不恢复旧身份或报价。
            if (returnDestination != null && returnDestination != destination &&
                controller.currentDestination?.id != returnDestination) {
                controller.navigate(returnDestination, returnArguments, navOptions { launchSingleTop = true })
            }
            controller.navigate(destination, targetArguments, navOptions { launchSingleTop = true })
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
    /** 仅保留历史类编译签名，旧导航不可达；误调用时立即拒绝，不初始化仓库或访问接口。 */
    @Deprecated("旧外卖流程已退役")
    fun orderingRepository(): com.fandazi.app.feature.ordering.OrderingRepository =
        throw IllegalStateException("旧外卖流程已退役")
// 处理 `cartStateStore` 相关逻辑。
    /** 历史购物车存储不再装配，禁止端侧旧状态复活。 */
    @Deprecated("旧外卖流程已退役")
    fun cartStateStore(): com.fandazi.app.feature.ordering.PreferencesCartStateStore =
        throw IllegalStateException("旧外卖流程已退役")
// 处理 `cartOwner` 相关逻辑。
    fun cartOwner() = (applicationSession().state.value as? SessionState.Authenticated)?.user?.username ?: "guest"
// 处理 `automationSettingsRepository` 相关逻辑。
    /** 旧自动下单设置只留编译兼容，不能调用旧服务。 */
    @Deprecated("旧外卖流程已退役")
    fun automationSettingsRepository(): com.fandazi.app.feature.profile.AutomationSettingsRepository =
        throw IllegalStateException("旧外卖流程已退役")

    /** 打开受保护页面；登录返回保留目标参数，不沿用过期授权或页面金额。 */
    fun openProtectedDestination(destination: Int, arguments: Bundle? = null) {
        if (applicationSession().state.value is SessionState.Authenticated) {
            navHost.navController.navigate(destination, arguments)
        } else {
            pendingProtectedDestination = destination
            pendingProtectedArguments = arguments?.let(::Bundle)
            // Session注销的观察者也可能已经打开认证页，单实例替换避免重复认证回退栈。
            navHost.navController.navigate(R.id.authFragment, bundleOf("require_login" to true), navOptions {
                launchSingleTop = true
            })
        }
    }

    /** 购买遇到 401 清除旧身份后登录，返回原套餐数量或原订单并重新读取服务端事实。 */
    fun openPurchaseLogin(destination: Int, arguments: Bundle, expired: Boolean = false) {
        // 在注销清栈之前捕获合法业务来源，拒绝把认证页或导航图当成用户返回目的地。
        navHost.navController.previousBackStackEntry?.let { entry ->
            if (entry.destination.id != R.id.authFragment && entry.destination.id != navHost.navController.graph.id) {
                pendingReturnDestination = entry.destination.id
                pendingReturnArguments = entry.arguments?.let(::Bundle)
            }
        }
        lifecycleScope.launch {
            if (expired) applicationSession().logout()
            openProtectedDestination(destination, arguments)
        }
    }

// 打开login。
    fun openLogin() {
        navHost.navController.navigate(R.id.authFragment, bundleOf("require_login" to true))
    }

// 处理 `onSaveInstanceState` 相关逻辑。
    override fun onSaveInstanceState(outState: Bundle) {
        pendingProtectedDestination?.let { outState.putInt(KEY_PENDING_DESTINATION, it) }
        pendingProtectedArguments?.let { outState.putBundle("pending_protected_arguments", it) }
        pendingReturnDestination?.let { outState.putInt("pending_return_destination", it) }
        pendingReturnArguments?.let { outState.putBundle("pending_return_arguments", it) }
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val KEY_PENDING_DESTINATION = "pending_protected_destination"
    }
}
