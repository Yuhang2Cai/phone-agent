// 顶层导航回归：运行真实MainActivity和菜单，防止Tab栈增长、公开查询丢失及认证保存栈串账号。
package com.fandazi.app.core.navigation

import android.annotation.SuppressLint
import android.os.Looper
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.fandazi.app.*
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.auth.*
import com.fandazi.app.feature.home.CatalogViewModel
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.voucher.*
import com.fandazi.app.feature.purchase.*
import com.fandazi.app.feature.deal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 状态来自真实会话仓库；测试依赖只替换外部读取，不建立产品假业务数据或测试setter。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SuppressLint("RestrictedApi") // 仅验收测试读取Navigation内部栈统计，不进入应用业务代码。
class TopLevelNavigationStateTest {
    /** 同页重选及20轮往返必须有界；断言放在完整测量后，保留原实现的实际增长基线。 */
    @Test fun twentyRoundsKeepBoundedStackAndRetainHomeEntry() = fixture { f ->
        val first=f.nav.currentBackStackEntry!!.id
        f.select(R.id.homeFragment)
        assertEquals(first,f.nav.currentBackStackEntry!!.id)
        val depths=mutableListOf<Int>();val homeKeys=mutableSetOf(first)
        repeat(20) {
            listOf(R.id.ordersFragment,R.id.profileFragment,R.id.agentAssistantTab,R.id.homeFragment).forEach { menu ->
                f.select(menu);depths+=f.nav.currentBackStack.value.count { it.destination !is NavGraph }
            }
            homeKeys+=f.nav.currentBackStackEntry!!.id
        }
        println("NAV_MEASUREMENTS rounds=20 maxDepth=${depths.maxOrNull()} uniqueHomeEntries=${homeKeys.size} publicReads=${f.publicReads}")
        assertTrue("顶层20轮后返回栈应有界",depths.all { it<=2 })
        assertEquals("首页应恢复原栈条目",setOf(first),homeKeys)
    }

    /** 恢复同一生产目录ViewModel和原查询，不能新建首页后偷偷丢掉用户条件。 */
    @Test fun homeQueryAndViewModelSurviveOtherTabs() = fixture { f ->
        val before=f.catalogModel()
        before.load(CatalogQuery(keyword="nav-query",category="火锅",sort="PRICE_DESC"));f.pump()
        f.select(R.id.ordersFragment);f.select(R.id.profileFragment);f.select(R.id.homeFragment)
        val after=f.catalogModel()
        assertSame("恢复应沿用原公开浏览ViewModel",before,after)
        assertEquals("nav-query",after.state.value.query.keyword)
        assertEquals("PRICE_DESC",after.state.value.query.sort)
    }

    /** 菜单AI专用ID只映射正式聊天，重选不能产生第二张聊天或尝试导航菜单资源本身。 */
    @Test fun aiMenuMapsToOneFormalChatDestination() = fixture(authenticated=false) { f ->
        f.select(R.id.agentAssistantTab);val first=f.nav.currentBackStackEntry!!.id
        assertEquals(R.id.agentChatFragment,f.nav.currentDestination!!.id)
        f.select(R.id.agentAssistantTab)
        assertEquals(first,f.nav.currentBackStackEntry!!.id)
    }

    /** AI卡片明确打开公开门店后换Tab，再点AI菜单仍须进入正式聊天，不能恢复成门店页。 */
    @Test fun aiReentryAfterPublicDetailShowsFormalChat() = fixture { f ->
        f.select(R.id.agentAssistantTab)
        f.nav.navigate(R.id.storeFragment,bundleOf("storePublicId" to "10000000-0000-0000-0000-000000000001"));f.pump()
        f.select(R.id.ordersFragment);f.select(R.id.agentAssistantTab)
        assertEquals("AI菜单必须映射正式聊天入口",R.id.agentChatFragment,f.nav.currentDestination!!.id)
    }

    /** 对比输入等业务入口直接进入聊天后，首页恢复不能把聊天叶子当作公开浏览分支。 */
    @Test fun directBusinessChatEntryReturnsHomeWithoutLosingPublicModel() = fixture { f ->
        val original = f.catalogModel()
        original.load(CatalogQuery(keyword="business-entry", category="火锅")); f.pump()
        // 真实对比发送以navigate进入聊天而非点击AI菜单；保留首页根和聊天叶子来复现保存栈归属。
        f.nav.navigate(R.id.agentChatFragment); f.pump()
        f.select(R.id.homeFragment)
        assertEquals("首页菜单不能恢复为跨入口聊天叶子", R.id.homeFragment, f.nav.currentDestination!!.id)
        assertSame("恢复公开首页须保留原查询模型", original, f.catalogModel())
        assertEquals("business-entry", f.catalogModel().state.value.query.keyword)
        // 第一轮纠正之后继续真实四菜单往返，防止后续保存栈又映射到错误根或丢掉首页条目。
        repeat(20) {
            f.select(R.id.ordersFragment); f.select(R.id.profileFragment)
            f.select(R.id.agentAssistantTab); f.select(R.id.homeFragment)
            assertEquals(R.id.homeFragment, f.nav.currentDestination!!.id)
            assertSame(original, f.catalogModel())
        }
    }

    /** 首页所属搜索保存独立查询；完整公开浏览分支恢复，不只恢复默认首页占位。 */
    @Test fun homeSearchBranchRestoresExactQueryAndModel() = fixture { f ->
        f.nav.navigate(R.id.searchFragment,bundleOf("initialKeyword" to "nav-search"));f.pump()
        val before=f.catalogModel();before.load(CatalogQuery(keyword="nav-search",category="火锅"));f.pump()
        f.select(R.id.ordersFragment);f.select(R.id.profileFragment);f.select(R.id.homeFragment)
        assertEquals(R.id.searchFragment,f.nav.currentDestination!!.id)
        assertSame(before,f.catalogModel());assertEquals("nav-search",f.catalogModel().state.value.query.keyword)
    }

    /** 系统返回不逐个回放历史点击的Tab，订单根页返回首页，保留原首页退出链路。 */
    @Test fun systemBackFromTopLevelReturnsHome() = fixture { f ->
        f.select(R.id.profileFragment);f.select(R.id.ordersFragment)
        f.activity.onBackPressedDispatcher.onBackPressed();f.pump()
        assertEquals("顶层返回不应回上次点击的Tab",R.id.homeFragment,f.nav.currentDestination!!.id)
    }

    /** 支付结果的订单入口要退出交易叶子；换Tab回首页不能恢复付款结果或旧报价确认。 */
    @Test fun paymentResultOrderEntryDoesNotReviveTransactionLeaf() = fixture(authenticated=true) { f ->
        f.nav.navigate(R.id.paymentSuccessFragment,bundleOf("orderId" to "10000000-0000-0000-0000-000000000001"));f.pump()
        f.activity.findViewById<View>(R.id.payment_success_orders).performClick();f.pump()
        assertEquals(R.id.ordersFragment,f.nav.currentDestination!!.id)
        f.select(R.id.homeFragment)
        assertEquals(R.id.homeFragment,f.nav.currentDestination!!.id)
        assertThrows("交易叶子不能残留在返回栈",IllegalArgumentException::class.java) { f.nav.getBackStackEntry(R.id.paymentSuccessFragment) }
    }

    /** 已保存订单在退出后必须被销毁；游客重新选择不能恢复旧账户的BackStackEntry。 */
    @Test fun logoutClearsSavedPrivateEntries() = fixture(authenticated=true) { f ->
        f.select(R.id.ordersFragment);val old=f.nav.currentBackStackEntry!!.id
        f.select(R.id.profileFragment)
        runBlocking { f.session.logout() };f.pump()
        assertEquals(R.id.authFragment,f.nav.currentDestination!!.id)
        runBlocking { f.session.continueAsGuest() };f.pump();f.select(R.id.ordersFragment)
        assertNotEquals("游客不能恢复旧账户保存栈",old,f.nav.currentBackStackEntry!!.id)
    }

    /** 同账号新令牌也属于新认证版本，不能恢复旧页面资格；测试只使用合成令牌。 */
    @Test fun replacementTokenClearsSavedPrivateEntries() = fixture(authenticated=true) { f ->
        f.select(R.id.ordersFragment);val old=f.nav.currentBackStackEntry!!.id
        f.select(R.id.profileFragment)
        runBlocking { f.session.login("owner-a","synthetic-password") };f.pump();f.select(R.id.ordersFragment)
        assertNotEquals("新令牌不能恢复旧认证页面",old,f.nav.currentBackStackEntry!!.id)
    }

    /** 真实仓库失效广播后明确登录仍回AI，原暂存401入口修复必须与新栈清理兼容。 */
    @Test fun rejectedChatLoginReturnsToFormalChatWithoutResend() = fixture(authenticated=true) { f ->
        f.select(R.id.agentAssistantTab)
        runBlocking { f.session.invalidateToken(f.session.tokenOrNull()!!) };f.pump()
        assertEquals(R.id.authFragment,f.nav.currentDestination!!.id)
        runBlocking { f.session.login("owner-a","synthetic-password") };f.pump()
        assertEquals(R.id.agentChatFragment,f.nav.currentDestination!!.id)
    }

    /** API拒绝先广播身份变化时仍保留明确订单入口，不能依赖已销毁Fragment随后发出的登录回调。 */
    @Test fun rejectedOrderLoginReturnsToOriginalTab() = fixture(authenticated=true) { f ->
        f.select(R.id.ordersFragment);val old=f.nav.currentBackStackEntry!!.id
        runBlocking { f.session.invalidateToken(f.session.tokenOrNull()!!) };f.pump()
        assertEquals(R.id.authFragment,f.nav.currentDestination!!.id)
        runBlocking { f.session.login("owner-a","synthetic-password") };f.pump()
        assertEquals("订单401登录后须回原明确订单入口",R.id.ordersFragment,f.nav.currentDestination!!.id)
        assertNotEquals("登录必须重新建立当前身份订单栈",old,f.nav.currentBackStackEntry!!.id)
    }

    /** 每例创建独立Activity；关闭时解除视图/导航，不向实际服务器发送登录或写请求。 */
    private fun fixture(authenticated:Boolean=true,verify:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as FandaziApplication
        val f=Fixture(app,authenticated)
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {f.activity=activity.get();f.pump();verify(f)}
        finally {activity.pause().stop().destroy();f.restore()}
    }

    /** 合成输入立即交付，保证所有导航断言运行真实页面，不依赖外网时间或加密库可用性。 */
    private class Fixture(val app:FandaziApplication,authenticated:Boolean) {
        lateinit var activity:MainActivity
        var publicReads=0
        private val originals=mutableMapOf<String,Any>()
        val session=SessionRepository(object:AuthGateway {
            private var revision=0
            override suspend fun login(username:String,password:String)=ApiResult.Success(AuthResponse("synthetic-token-${++revision}",User(username,"synthetic@example.invalid")))
            override suspend fun register(username:String,email:String,password:String)=login(username,password)
        },object:SessionStorage {
            private var value:PersistedSession?=if(authenticated)PersistedSession("synthetic-initial",User("owner-a","synthetic@example.invalid"))else null
            override val initializationError:Throwable?=null
            override fun read()=Result.success(value)
            override fun readGuest()=Result.success(!authenticated)
            override suspend fun write(value:PersistedSession):Boolean {this.value=value;return true}
            override suspend fun clear():Boolean {value=null;return true}
        })
        init {
            replace("sessionRepository",session)
            replace("catalogRepository",object:CatalogRepository {
                override suspend fun stores(query:CatalogQuery):ApiResult<List<CatalogStore>> {publicReads++;return ApiResult.Success(emptyList())}
                override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> = ApiResult.Failure(ApiError.Http(404,"测试未打开门店"))
                override suspend fun cities()=ApiResult.Success(emptyList<CatalogCity>())
                override suspend fun dishes(id:String)=ApiResult.Success(emptyList<CatalogDish>())
                override suspend fun reviews(id:String)=ApiResult.Success(emptyList<CatalogReview>())
            })
            replace("voucherRepository",object:VoucherRepository by HttpVoucherRepository(ApiClient("http://localhost/",{null})) {
                override suspend fun listOrders()=ApiResult.Success(emptyList<GroupOrderSummary>())
            })
            replace("purchaseRepository",object:PurchaseRepository by HttpPurchaseRepository(ApiClient("http://localhost/",{null})) {
                override suspend fun getOrder(orderId:String):ApiResult<GroupBuyOrder> = ApiResult.Failure(ApiError.Http(404,"测试未知订单，不生成假支付成功"))
            })
            replace("groupBuyingRepository",object:GroupBuyingRepository {
                override suspend fun loadStoreDeals(storePublicId:String)=ApiResult.Success(emptyList<DealPackage>())
                override suspend fun loadDeal(dealPublicId:String):ApiResult<DealPackage> = ApiResult.Failure(ApiError.Http(404,"导航测试不提供假套餐"))
            })
        }
        val host get()=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val nav get()=host.navController
        /** 提交真实Fragment事务和主线程收集，随后才读取栈与ViewModel，避免只测导航调用意图。 */
        fun pump() {shadowOf(Looper.getMainLooper()).idle();activity.supportFragmentManager.executePendingTransactions();host.childFragmentManager.executePendingTransactions();shadowOf(Looper.getMainLooper()).idle()}
        /** 触发真实菜单监听而非直接调用测试模拟的导航算法。 */
        fun select(id:Int) {
            val before=nav.currentBackStack.value.map{it.destination.id}
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId=id;pump()
            // 仅输出合成例的资源ID边界，诊断菜单、NavController与实际Fragment是否一致，不包含凭据。
            println("NAV_SELECT menu=$id before=$before after=${nav.currentBackStack.value.map{it.destination.id}} fragment=${fragment()::class.java.simpleName}")
        }
        /** 取得当前实际显示的业务Fragment，不能用新创建的测试Fragment冒充恢复结果。 */
        fun fragment()=host.childFragmentManager.primaryNavigationFragment!!
        /** 取得页面实际Lazy依赖；不把另一个作用域新建的测试ViewModel冒充页面正在使用的模型。 */
        @Suppress("UNCHECKED_CAST")
        fun catalogModel():CatalogViewModel=fragment()::class.java.getDeclaredField("model\$delegate").apply {isAccessible=true}.get(fragment()).let{(it as Lazy<CatalogViewModel>).value}
        /** 反射仅存在测试，记录并恢复Application边界，生产没有可用的测试setter。 */
        private fun replace(name:String,value:Any) {FandaziApplication::class.java.getDeclaredField(name).apply {isAccessible=true;originals[name]=get(app);set(app,value)}}
        /** 避免某例的合成仓库或身份影响后续例，关闭Activity后还原全部依赖。 */
        fun restore(){originals.forEach { (name,value)->FandaziApplication::class.java.getDeclaredField(name).apply{isAccessible=true;set(app,value)}}}
    }
}
