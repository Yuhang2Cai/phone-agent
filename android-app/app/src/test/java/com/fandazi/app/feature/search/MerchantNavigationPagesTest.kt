// 路由回归：保留旧merchant页有效生命周期测试；首页/搜索改用现行团购点击路由，不监听退役外卖导航。
package com.fandazi.app.feature.search

import android.os.Looper
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.NavHostFragment
import com.fandazi.app.MainActivity
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.ordering.*
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.store.*
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MerchantNavigationPagesTest {
    /** 首页恢复不能接受旧外卖ViewModel请求；主动卡片点击使用当前团购公开ID且恢复不重复导航。 */
    @Test fun homeIgnoresRetiredOrderingRequestsAndClickKeepsPublicId() = verifyGroupBuyingNavigation(R.id.homeFragment)
    /** 搜索恢复不能被历史外卖导航抢走；替代原已退役消费者测试，验证当前真实NavController。 */
    @Test fun searchIgnoresRetiredOrderingRequestsAndClickKeepsPublicId() = verifyGroupBuyingNavigation(R.id.searchFragment)
    @Test fun merchantResumesWithLatestRequestAndDoesNotRepeat() = verifyResumedNavigation(R.id.merchantFragment)

    // 保留仍实际监听OrderingViewModel的旧merchant入口；最新请求确认及恢复不重复的断言不删除。
    private fun verifyResumedNavigation(source: Int) = runTest {
        val repository = object : OrderingRepository by HttpOrderingRepository(ApiClient("http://localhost/", { null })) {
            override suspend fun loadMerchant(merchantId: Long) = ApiResult.Success(
                Merchant(merchantId, "商家$merchantId", "粥", BigDecimal.TEN, BigDecimal.ONE, 20, true)
            )
            override suspend fun loadMenu(merchantId: Long) = ApiResult.Success(emptyList<Dish>())
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val vm = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            })[OrderingViewModel::class.java]
            controller.start().resume().visible()
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(source, if (source == R.id.merchantFragment) bundleOf("merchantId" to 1L) else null)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            vm.openMerchant(2)
            runCurrent()
            val old = requireNotNull(vm.merchantNavigationRequest.value)
            vm.openMerchant(3)
            runCurrent()
            vm.acknowledgeMerchantNavigation(old.requestId)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(source, host.navController.currentDestination?.id)
            assertEquals(3L, vm.merchantNavigationRequest.value?.merchantId)
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(R.id.merchantFragment, host.navController.currentDestination?.id)
            assertEquals(3L, host.navController.currentBackStackEntry?.arguments?.getLong("merchantId"))
            assertNull(vm.merchantNavigationRequest.value)
            val entry = host.navController.currentBackStackEntry
            controller.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(entry, host.navController.currentBackStackEntry)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** 替代退役搜索外卖lookup前置：当前团购点击先进入对应门店，套餐读取失败不能产生假套餐或二次导航。 */
    @Test fun searchDealLookupFailureKeepsSelectedStoreWithoutFakeDeals() = verifyGroupBuyingNavigation(R.id.searchFragment, failLookup = true)

    // 仅替换网络边界，现有首页/搜索View、点击监听、NavController和StoreFragment都实际运行。
    // 目录替身仅存在测试：返回两个合法公开 UUID，页面必须保留服务端顺序，不能回退演示名单。
    private fun verifyGroupBuyingNavigation(source: Int, failLookup: Boolean = false) = runTest {
        val repository = object : OrderingRepository by HttpOrderingRepository(ApiClient("http://localhost/", { null })) {
            override suspend fun loadMerchant(merchantId: Long) = ApiResult.Success(
                Merchant(merchantId, "旧协议商家", "粥", BigDecimal.TEN, BigDecimal.ONE, 20, true))
        }
        val result = CompletableDeferred<ApiResult<List<DealPackage>>>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val selectedPublicId = "cfb41344-6cc4-4c36-9151-ebdff215f438"
            fun summary(id: String) = CatalogStore(id, "测试门店", "测试城市", "火锅", 1000,
                "测试地址", "测试营业时间", null, 0, listOf(0, 0, 0, 0, 0), null, null, null, null)
            FandaziApplication::class.java.getDeclaredField("catalogRepository").apply { isAccessible = true }
                .set(activity.application, object : CatalogRepository {
                    // 明确不同身份与服务器顺序，防止首页/搜索因本地排序或旧 ID 发生身份漂移。
                    override suspend fun stores(query: CatalogQuery) = ApiResult.Success(listOf(
                        summary("ee6040de-0bf4-4e9a-b0a6-f7e45e3b28b6"), summary(selectedPublicId)))
                    override suspend fun store(id: String, query: CatalogQuery) = ApiResult.Success(summary(id))
                    override suspend fun cities() = ApiResult.Success(emptyList<CatalogCity>())
                    override suspend fun dishes(id: String) = ApiResult.Success(emptyList<CatalogDish>())
                    override suspend fun reviews(id: String) = ApiResult.Success(emptyList<CatalogReview>())
                })
            // 不向生产Application添加测试setter；只在测试反射替换公共仓库网络依赖。
            FandaziApplication::class.java.getDeclaredField("groupBuyingRepository").apply { isAccessible = true }
                .set(activity.application, object : GroupBuyingRepository {
                    override suspend fun loadStoreDeals(storePublicId: String) = result.await()
                    override suspend fun loadDeal(dealPublicId: String): ApiResult<DealPackage> = error("本测试不点击套餐")
                })
            val vm = OrderingViewModel(repository, StandardTestDispatcher(testScheduler))
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            })[OrderingViewModel::class.java]
            controller.start().resume().visible()
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.navigate(source)
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            vm.openMerchant(42)
            runCurrent()
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(source, host.navController.currentDestination?.id)
            assertEquals(42L, vm.merchantNavigationRequest.value?.merchantId)
            val cards = activity.findViewById<android.widget.LinearLayout>(if(source == R.id.homeFragment) R.id.home_store_list else R.id.search_results)
            assertTrue(cards.childCount >= 2)
            // 点击第二张而不是默认第一张，检查现有卡片绑定到不同真实导航参数，不只检查目的地名。
            cards.getChildAt(1).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            // 首页与搜索都使用目录第二项的同一个公开身份，不能擅自按本地价格改动顺序。
            assertEquals(R.id.storeFragment, host.navController.currentDestination?.id)
            assertEquals(selectedPublicId, host.navController.currentBackStackEntry?.arguments?.getString("storePublicId"))
            val entry = host.navController.currentBackStackEntry
            result.complete(if(failLookup) ApiResult.Failure(ApiError.Http(404, "套餐不存在")) else ApiResult.Success(emptyList()))
            shadowOf(Looper.getMainLooper()).idle()
            if(failLookup) {
                val content = activity.findViewById<android.widget.LinearLayout>(R.id.store_content)
                assertEquals(1, content.childCount)
                assertEquals("优惠 加载失败，点击重试", (content.getChildAt(0) as android.widget.TextView).text.toString())
            }
            controller.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(entry, host.navController.currentBackStackEntry)
            assertEquals(selectedPublicId, host.navController.currentBackStackEntry?.arguments?.getString("storePublicId"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
