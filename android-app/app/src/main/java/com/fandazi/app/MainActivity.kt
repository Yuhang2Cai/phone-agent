// 主界面入口：根据会话状态切换导航目标。
package com.fandazi.app

import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.activity.OnBackPressedCallback
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
import com.fandazi.app.core.navigation.TopLevelNavigator
import com.fandazi.app.core.navigation.SessionRouteCoordinator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.fandazi.app.feature.auth.SessionState

/** 主导航宿主：协调会话/五Tab/明确认证返回，并处理真实键盘遮挡安全区；不保存令牌或重放交易。 */
class MainActivity : AppCompatActivity(), com.facebook.react.modules.core.DefaultHardwareBackBtnHandler {
    private lateinit var navHost: NavHostFragment
    private lateinit var bottomNavigation: com.google.android.material.bottomnavigation.BottomNavigationView
    private var pendingProtectedDestination: Int? = null
    /** 购买登录返回保留资源和数量，不能在认证成功后退回首页丢失目标。 */
    private var pendingProtectedArguments: Bundle? = null
    /** 认证清空旧栈时保留来源页面与导航参数；它们是资源引用，不是价格或授权事实。 */
    private var pendingReturnDestination: Int? = null
    private var pendingReturnArguments: Bundle? = null
    /** 已处理的公开身份种类与用户名，用于当前Activity和薄恢复的栈隔离。 */
    private var navigationOwner: String? = null
    /** 稳定sid用于比较身份；历史无sid才使用JWT，均只在内存比较，不进入日志、Bundle或目录缓存。 */
    private var navigationToken: String? = null
    private var navigationIdentityInitialized = false
    private var restoredNavigationOwner: String? = null
    /** 顶层业务栈完整保存后，非首页根页的系统返回恢复首页；详情仍由NavHost正常逐级返回。 */
    private val topLevelBack = object : OnBackPressedCallback(false) {
        /** 只在非首页根页启用；恢复公开浏览栈，其余返回由原NavHost及系统处理。 */
        override fun handleOnBackPressed() {
            // 活动内页先交给 RN 返回栈；未消费时恢复原生首页，不退出整个应用。
            val activityPage = navHost.childFragmentManager.primaryNavigationFragment as? com.fandazi.app.feature.activity.ActivityHostFragment
            if (activityPage?.handleBack() != true) invokeDefaultOnBackPressed()
        }
    }

    /**
     * 为所有现有页面保留真实IME以上的可用区域，避免边到边窗口把输入和发送放在键盘下。
     *
     * 只增加实际重叠的底部padding；系统已经resize时重叠为0，不重复扣除键盘高度。
     * 状态栏/导航栏仍按既有页面处理，Insets原样下传；关闭键盘恢复初始padding。
     */
    private fun configureImeSafeArea() {
        val content = findViewById<View>(android.R.id.content)
        val initialBottom = content.paddingBottom
        val location = IntArray(2)
        fun applySafeArea(insets: WindowInsetsCompat) {
            // Android11以上窗口度量保持完整窗口边界，结合屏幕坐标计算真实遮挡；旧系统由adjustResize处理。
            val overlap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                content.getLocationOnScreen(location)
                val keyboardTop = windowManager.currentWindowMetrics.bounds.bottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                (location[1] + content.height - keyboardTop).coerceAtLeast(0)
            } else 0
            val targetBottom = initialBottom + overlap
            // 相同安全区不再触发布局，防止padding→layout回调循环，也不额外滚动上翻中的历史。
            if (content.paddingBottom != targetBottom) content.updatePadding(bottom = targetBottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            applySafeArea(insets)
            // 保留子页状态栏/表单处理，不消费或伪造系统Insets。
            insets
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 首次Insets可能早于尺寸确定，系统resize也可能随后改变外框；布局后用同一事实复核。
            ViewCompat.getRootWindowInsets(content)?.let(::applySafeArea)
        }
        ViewCompat.requestApplyInsets(content)
    }

/** 装配正式导航及可见会话观察；只恢复薄入口和公开身份标记，不恢复交易事实。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProtectedDestination = savedInstanceState?.getInt(KEY_PENDING_DESTINATION)?.takeIf { it != 0 }
        pendingProtectedArguments = savedInstanceState?.getBundle("pending_protected_arguments")
        pendingReturnDestination = savedInstanceState?.getInt("pending_return_destination")?.takeIf { it != 0 }
        pendingReturnArguments = savedInstanceState?.getBundle("pending_return_arguments")
        restoredNavigationOwner = savedInstanceState?.getString(KEY_NAVIGATION_OWNER)
        setContentView(R.layout.activity_main)
        configureImeSafeArea()
        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item: MenuItem ->
            // 性能起点在导航和运行时初始化之前；只记录单调时间，不记录用户或任务数据。
            if (item.itemId == R.id.activityFragment) {
                val clickedAt = android.os.SystemClock.elapsedRealtime()
                (application as FandaziApplication).activityRuntime.beginEntry(clickedAt)
            }
            // AI和四个普通菜单统一走明确映射，返回true让实际选中状态与正式目的地保持一致。
            TopLevelNavigator.select(navHost.navController, item.itemId)
        }
        bottomNavigation.setOnItemReselectedListener { item ->
            // 重选仅核对所属Tab，不清查询、丢锚点或追加同页；详情返回仍由原返回控件处理。
            TopLevelNavigator.select(navHost.navController, item.itemId)
        }
        onBackPressedDispatcher.addCallback(this, topLevelBack)
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            // 只拦截非首页业务根；搜索/门店/订单详情/认证/首页退出不被此回调改变。
            // 比较输入显式发送后进入聊天属于业务子链，系统返回须回原输入/对比，而不是被Tab根返回吞掉。
            val fromCompareInput = destination.id == R.id.agentChatFragment &&
                navHost.navController.previousBackStackEntry?.destination?.id == R.id.compareQuestionFragment
            topLevelBack.isEnabled = !fromCompareInput && destination.id in setOf(R.id.ordersFragment, R.id.activityFragment, R.id.profileFragment, R.id.agentChatFragment)
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
                R.id.profileFragment,
                R.id.activityFragment
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
                // 重新打开时只主动维护认证，不重发消息或交易；离线失败保留身份和公开缓存。
                launch { applicationSession().renewIfNeeded() }
                applicationSession().state.collect(::navigateForSession)
            }
        }
    }

/** 会话版本变化先清旧栈/任务，再恢复明确登录入口；相同身份的普通派发不重置页面。 */
    private fun navigateForSession(state: SessionState) {
        // 聊天/订单401的全局身份广播可能先于页面错误回调。清栈前保留当前明确根入口，避免登录误回首页。
        // 仅保存目的地ID，不沿用旧订单、聊天、授权或失败语句；登录后新身份重新读取，不重放业务写入。
        val rejectedRoot = navHost.navController.currentDestination?.id
        if (state == SessionState.Unauthenticated &&
            rejectedRoot in setOf(R.id.agentChatFragment, R.id.ordersFragment, R.id.activityFragment)) {
            pendingProtectedDestination = rejectedRoot
            pendingProtectedArguments = null
            pendingReturnDestination = null
            pendingReturnArguments = null
        }
        val controller = navHost.navController
        val owner = TopLevelNavigator.ownerLabel(state)
        // 同一sid的自动续期不等于换账号，不应因JWT字节变化清掉聊天、草稿和保存栈。
        val token = com.fandazi.app.feature.auth.authenticationIdentity(state)
        val identityChanged = if (navigationIdentityInitialized) {
            owner != navigationOwner || token != navigationToken
        } else {
            restoredNavigationOwner != null && restoredNavigationOwner != owner
        }
        navigationOwner = owner
        navigationToken = token
        navigationIdentityInitialized = true
        val desired = SessionRouteCoordinator.routeFor(state)
        val agent = ViewModelProvider(this, com.fandazi.app.feature.agent.AgentViewModelFactory(
            (application as FandaziApplication).agentRepository, application
        ))[com.fandazi.app.feature.agent.AgentViewModel::class.java]
        if (identityChanged) {
            // 同账号明确重新登录的新sid也不能沿用旧确认/SSE/恢复任务；正常续期的同sid不进入此分支。
            agent.bindAuthenticatedOwner(null)
            val keepIntent = (state is SessionState.Authenticated && controller.currentDestination?.id == R.id.authFragment) ||
                (state == SessionState.Unauthenticated && controller.currentDestination?.id in setOf(R.id.agentChatFragment, R.id.ordersFragment, R.id.activityFragment))
            val pending = if (keepIntent) pendingProtectedDestination else null
            val pendingArgs = if (keepIntent) pendingProtectedArguments else null
            val returnTo = if (keepIntent) pendingReturnDestination else null
            val returnArgs = if (keepIntent) pendingReturnArguments else null
            pendingProtectedDestination = null; pendingProtectedArguments = null
            pendingReturnDestination = null; pendingReturnArguments = null
            // 清理保存栈也清理其ViewModel。认证目标只保留用户明确入口，不能由旧Tab顺序决定。
            TopLevelNavigator.reset(controller, if (desired == SessionRoute.AUTH) R.id.authFragment else R.id.homeFragment)
            pendingProtectedDestination = pending; pendingProtectedArguments = pendingArgs
            pendingReturnDestination = returnTo; pendingReturnArguments = returnArgs
        }
        agent.bindAuthenticatedOwner((state as? SessionState.Authenticated)?.user?.username, token)
        val current = when (navHost.navController.currentDestination?.id) {
            R.id.authFragment -> SessionRoute.AUTH
            R.id.homeFragment, R.id.searchFragment, R.id.storeFragment, R.id.ordersFragment,
            R.id.profileFragment, R.id.activityFragment,
            R.id.agentChatFragment, R.id.dealDetailFragment, R.id.dealCheckoutBottomSheetFragment,
            // 秒杀公开入口与交易结果复用同一页面，身份更新由页面清确认并回读，不能误判为未知路由。
            R.id.seckillFragment, R.id.seckillStoreFragment,
            // 对比属于公开目录路由，会话普通派发不能误退首页丢失当前选择。
            R.id.compareSelectionFragment, R.id.dealCompareFragment, R.id.compareQuestionFragment, R.id.comparePurchaseFragment,
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
                val rootMenu = TopLevelNavigator.menuForRoot(returnDestination)
                if (rootMenu != null) TopLevelNavigator.select(controller, rootMenu)
                else controller.navigate(returnDestination, returnArguments, navOptions { launchSingleTop = true })
            }
            val rootMenu = TopLevelNavigator.menuForRoot(destination)
            if (rootMenu != null) TopLevelNavigator.select(controller, rootMenu)
            else controller.navigate(destination, targetArguments, navOptions { launchSingleTop = true })
            return
        }
        if (!SessionRouteCoordinator.shouldNavigate(current, desired)) return
        // 初次恢复到不匹配入口同样销毁保存栈，不能只弹活动页而遗留可restore的私人页面。
        TopLevelNavigator.reset(controller, if (desired == SessionRoute.AUTH) R.id.authFragment else R.id.homeFragment)
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
            // 我的页的订单及聊天明确入口同样复用正式Tab，防止绕过底栏监听追加另一套根页。
            val rootMenu = TopLevelNavigator.menuForRoot(destination)
            if (rootMenu != null) TopLevelNavigator.select(navHost.navController, rootMenu)
            else navHost.navController.navigate(destination, arguments)
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

    /** RN 未消费系统返回时恢复首页；只操作原生根导航，不再递归派发给 RN。 */
    override fun invokeDefaultOnBackPressed() { TopLevelNavigator.select(navHost.navController, R.id.homeFragment) }

    /** 活动桥的固定导航表；登录只保存活动根引用，业务写操作不会随登录重放。 */
    fun openActivityDestination(destination: String) {
        when (destination) {
            "login" -> openProtectedDestination(R.id.activityFragment)
            "home" -> TopLevelNavigator.select(navHost.navController, R.id.homeFragment)
            "orders" -> TopLevelNavigator.select(navHost.navController, R.id.ordersFragment)
            else -> throw IllegalArgumentException("不支持的活动导航")
        }
    }

/** 保存公开身份标记和明确导航引用；完整认证令牌与业务正文仍不进入Activity保存树。 */
    override fun onSaveInstanceState(outState: Bundle) {
        pendingProtectedDestination?.let { outState.putInt(KEY_PENDING_DESTINATION, it) }
        pendingProtectedArguments?.let { outState.putBundle("pending_protected_arguments", it) }
        pendingReturnDestination?.let { outState.putInt("pending_return_destination", it) }
        pendingReturnArguments?.let { outState.putBundle("pending_return_arguments", it) }
        outState.putString(KEY_NAVIGATION_OWNER, navigationOwner ?: TopLevelNavigator.ownerLabel(applicationSession().state.value))
        super.onSaveInstanceState(outState)
    }

    private companion object {
        /** 与原薄入口键独立，恢复时只判断公开归属，不提供授权能力。 */
        const val KEY_NAVIGATION_OWNER = "navigation_public_owner"
        const val KEY_PENDING_DESTINATION = "pending_protected_destination"
    }
}
