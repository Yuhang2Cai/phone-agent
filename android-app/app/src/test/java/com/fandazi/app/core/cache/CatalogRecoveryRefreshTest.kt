// 当前页面网络恢复回归：真实Fragment生命周期与生产监听，平台替身只控制事件和同步事实的先后。
package com.fandazi.app.core.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 不直接调用刷新函数；通过已注册的平台回调验证500ms防抖和退出注销。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRecoveryRefreshTest {
    /** 仅提供有视图的真实生命周期宿主，页面是否需要服务重试由外部状态决定。 */
    class Page:Fragment() {
        var reads=0
        override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,savedInstanceState:Bundle?):View=FrameLayout(requireContext())
        override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
            CatalogRecoveryRefresh.attach(this,{false},{reads++})
        }
    }
    /** 清理主调度器和宿主，不向其它测试留下网络回调或延时任务。 */
    private fun scenario(action:(Page,ConnectivityManager,TestCoroutineScheduler)->Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity=controller.get()
        try {
            CatalogOnlineTestEnvironment.establish(activity)
            val page=Page()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content,page).commitNow()
            testScheduler.runCurrent();shadowOf(Looper.getMainLooper()).idle();testScheduler.runCurrent()
            val manager=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertEquals(1,shadowOf(manager).networkCallbacks.size)
            action(page,manager,testScheduler)
        } finally {
            controller.pause().stop().destroy();testScheduler.runCurrent();shadowOf(Looper.getMainLooper()).idle();testScheduler.runCurrent()
            Dispatchers.resetMain()
        }
    }
    /** onLost先于同步activeNetwork更新；仍必须按事件刷新，不能被旧true吞掉。 */
    @Test fun lossEventRefreshesDespiteStaleSynchronousNetwork()=scenario {page,manager,clock->
        val callback=shadowOf(manager).networkCallbacks.single()
        callback.onLost(manager.activeNetwork!!)
        assertTrue(CatalogNetworkAccess.connected(managerContext()))
        clock.runCurrent();clock.advanceTimeBy(499);clock.runCurrent();assertEquals(0,page.reads)
        clock.advanceTimeBy(1);clock.runCurrent();assertEquals("断网事件必须在500ms后刷新当前公开查询",1,page.reads)
    }
    /** 能力已失效但同步查询仍返回旧能力；使用回调参数应触发一次离线读取。 */
    @Test fun capabilitiesEventUsesCurrentEventFacts()=scenario {page,manager,clock->
        val empty=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(manager).networkCallbacks.single().onCapabilitiesChanged(manager.activeNetwork!!,empty)
        clock.runCurrent();clock.advanceTimeBy(500);clock.runCurrent()
        assertEquals("不能用同步旧能力覆盖回调事实",1,page.reads)
    }
    /** 初始注册不重复读取；同一能力重复通知与短时间抖动只在最后稳定状态读取一次。 */
    @Test fun duplicateEventsAreDebounced()=scenario {page,manager,clock->
        val callback=shadowOf(manager).networkCallbacks.single();val network=manager.activeNetwork!!
        val online=manager.getNetworkCapabilities(network)!!
        callback.onAvailable(network);callback.onCapabilitiesChanged(network,online)
        clock.runCurrent();clock.advanceTimeBy(600);clock.runCurrent();assertEquals(0,page.reads)
        callback.onLost(network);clock.runCurrent();clock.advanceTimeBy(200)
        callback.onAvailable(network);callback.onCapabilitiesChanged(network,online);clock.runCurrent()
        clock.advanceTimeBy(499);clock.runCurrent();assertEquals(0,page.reads)
        clock.advanceTimeBy(1);clock.runCurrent();assertEquals(1,page.reads)
    }
    /** 断网后当前页面被销毁，回调必须注销，不能刷新旧页面或保留无限轮询。 */
    @Test fun destroyedPageUnregistersCallback()=scenario {page,manager,clock->
        page.parentFragmentManager.beginTransaction().remove(page).commitNow()
        clock.runCurrent();shadowOf(Looper.getMainLooper()).idle();clock.runCurrent()
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
        clock.advanceTimeBy(6000);clock.runCurrent();assertEquals(0,page.reads)
    }
    /** 页面隐藏期间断网；恢复观察首个事实已是false，不能drop首项后一直保留旧在线资格。 */
    @Test fun resumeAlreadyOfflineRefreshesOldOnlinePage()=scenario {page,manager,clock->
        val fragments=page.parentFragmentManager
        fragments.beginTransaction().setMaxLifecycle(page,androidx.lifecycle.Lifecycle.State.STARTED).commitNow()
        clock.runCurrent();assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
        shadowOf(manager).setActiveNetworkInfo(null)
        assertFalse(CatalogNetworkAccess.connected(managerContext()))
        fragments.beginTransaction().setMaxLifecycle(page,androidx.lifecycle.Lifecycle.State.RESUMED).commitNow()
        clock.runCurrent();clock.advanceTimeBy(500);clock.runCurrent()
        assertEquals("回到已断网页面必须重读并标记来源",1,page.reads)
    }
    /** 网络状态来自同一个应用Context，未修改同步网络事实以制造假通过。 */
    private fun managerContext():Context=org.robolectric.RuntimeEnvironment.getApplication()
}
