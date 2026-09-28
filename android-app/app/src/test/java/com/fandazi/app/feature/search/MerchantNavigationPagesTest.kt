package com.fandazi.app.feature.search

import android.os.Looper
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.NavHostFragment
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.ordering.*
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
    @Test fun homeResumesWithLatestRequestAndDoesNotRepeat() = verifyResumedNavigation(R.id.homeFragment)
    @Test fun searchResumesWithLatestRequestAndDoesNotRepeat() = verifyResumedNavigation(R.id.searchFragment)
    @Test fun merchantResumesWithLatestRequestAndDoesNotRepeat() = verifyResumedNavigation(R.id.merchantFragment)

    // 三个真实入口暂停后连续收到请求，恢复只处理最新 ID；再次恢复不新增返回栈条目。
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

    // 使用真实页面和 NavController，仅替换网络边界，断言跳转时机与真实路由参数。
    @Test fun searchWaitsForLookupAndDoesNotNavigateOnFailure() = runTest {
        val result = CompletableDeferred<ApiResult<Merchant>>()
        val merchant = Merchant(42, "测试商家", "粥", BigDecimal.TEN, BigDecimal.ONE, 20, true)
        val repository = object : OrderingRepository by HttpOrderingRepository(ApiClient("http://localhost/", { null })) {
            override suspend fun searchMerchants(keyword: String) = ApiResult.Success(listOf(merchant))
            override suspend fun loadMerchant(merchantId: Long) = result.await()
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
            host.navController.navigate(R.id.searchFragment)
            shadowOf(Looper.getMainLooper()).idle()
            vm.search("测试")
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<View>(R.id.merchant_card).performClick()
            assertEquals(R.id.searchFragment, host.navController.currentDestination?.id)
            runCurrent()
            result.complete(ApiResult.Failure(ApiError.Http(404, "商家不存在")))
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(R.id.searchFragment, host.navController.currentDestination?.id)
            assertNull(vm.merchantNavigationRequest.value)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
