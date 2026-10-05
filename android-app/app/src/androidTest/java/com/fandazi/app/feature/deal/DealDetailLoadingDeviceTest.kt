// 详情设备时序回归：只替换合成游客会话，真实HTTP仓库/导航/页面处理明确的测试边界延迟。
package com.fandazi.app.feature.deal

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.*
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.auth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 运行器必须先启用合成HTTP夹具；结果为此模拟器单次局部时序，不能声称正式arm64性能收益。 */
@RunWith(AndroidJUnit4::class)
class DealDetailLoadingDeviceTest {
    /** 从导航开始记录首个可信标题与完整可购入口时间，100ms详情不能被3秒列表挡住。 */
    @Test fun verifiedDetailAppearsBeforeOptions()=scenario { page,app ->
        val start=SystemClock.elapsedRealtime();open(page,A)
        waitFor(page){it.findViewById<TextView>(R.id.deal_title)?.text?.toString()=="合成套餐001"}
        val first=SystemClock.elapsedRealtime()-start
        page.onActivity { assertFalse("同店选项仍在途时必须禁购",it.findViewById<View>(R.id.deal_purchase_bar).isEnabled) }
        waitFor(page){it.findViewById<View>(R.id.deal_purchase_bar)?.isEnabled==true}
        val full=SystemClock.elapsedRealtime()-start
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: "candidate"
        require(phase.matches(Regex("[a-z0-9-]+")))
        File(app.getExternalFilesDir(null),"performance/detail-loading-$phase.json").apply {
            parentFile!!.mkdirs();writeText(JSONObject().put("firstTrustedContentMs",first).put("fullyOperableMs",full).put("detailDelayMs",100).put("optionsDelayMs",3000)
                .put("scope","正式HTTP/页面，明确公开夹具；单次模拟器局部时序，无音频/交易写入").toString(2))
        }
        assertTrue("可信详情应早于3秒列表延迟",first<3000)
        assertTrue("完整入口必须等待列表",full>=3000 && full>first)
    }
    /** 旧套餐选项在途时切换到另一个详情，然后退出；晚到响应不能恢复旧标题/价或购买入口。 */
    @Test fun switchAndExitCannotReviveOldResource()=scenario { page,_ ->
        open(page,A);waitFor(page){it.findViewById<TextView>(R.id.deal_title)?.text?.toString()=="合成套餐001"}
        open(page,B);waitFor(page){it.findViewById<TextView>(R.id.deal_title)?.text?.toString()=="合成套餐002"}
        page.onActivity {
            assertEquals("deal-price-1236",it.findViewById<TextView>(R.id.deal_price).contentDescription)
            assertFalse(it.findViewById<View>(R.id.deal_purchase_bar).isEnabled)
            host(it).navController.popBackStack(R.id.homeFragment,false)
        }
        // 故意跨过两条旧列表的到达时间；页面退出后原商品事实必须仍不可见。
        SystemClock.sleep(3500);InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        page.onActivity {
            assertEquals(R.id.homeFragment,host(it).navController.currentDestination!!.id)
            assertNull(it.findViewById<View>(R.id.deal_purchase_bar));assertNull(it.findViewById<View>(R.id.deal_title))
        }
    }
    /** 完整公开ID只决定读取目标，不把金额、选项对象或资格通过导航注入。 */
    private fun open(page:ActivityScenario<MainActivity>,id:String){page.onActivity{host(it).navController.navigate(R.id.dealDetailFragment,bundleOf("dealPublicId" to id,"storePublicId" to S))}}
    /** 仅观察实际Android主线程控件，最多15秒；超时是失败，不用延长等待掩盖错误状态。 */
    private fun waitFor(page:ActivityScenario<MainActivity>,condition:(MainActivity)->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<end){var ready=false;page.onActivity{ready=condition(it)};if(ready)return;SystemClock.sleep(40)}
        fail("真实详情界面未在15秒内达到指定状态")
    }
    /** 会话外部边界只驻内存，结束还原；正式目录/套餐HTTP仓库、加密存储和交易接口不替换。 */
    private fun scenario(action:(ActivityScenario<MainActivity>,FandaziApplication)->Unit) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FandaziApplication
        val field=FandaziApplication::class.java.getDeclaredField("sessionRepository").apply{isAccessible=true};val original=field.get(app)
        val storage=object:SessionStorage {
            override val initializationError:Throwable?=null
            override fun read()=Result.success<PersistedSession?>(null)
            override fun readGuest()=Result.success(true)
            override suspend fun write(value:PersistedSession)=true
            override suspend fun clear()=true
        }
        field.set(app,SessionRepository(object:AuthGateway {
            override suspend fun login(username:String,password:String):ApiResult<AuthResponse> = error("详情测量不得认证")
            override suspend fun register(username:String,email:String,password:String):ApiResult<AuthResponse> = error("详情测量不得注册")
        },storage))
        try {ActivityScenario.launch(MainActivity::class.java).use{page->InstrumentationRegistry.getInstrumentation().waitForIdleSync();action(page,app)}}
        finally {field.set(app,original)}
    }
    /** 读取真实NavHost，不为测量新增生产访问器。 */
    private fun host(activity:MainActivity)=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
    companion object {
        /** 与专用合成HTTP边界一致的公开资源，不能用于真实服务交易。 */
        private const val S="00000000-0000-0000-0000-000000000001"
        private const val A="00000000-0000-0000-0000-000000010001"
        private const val B="00000000-0000-0000-0000-000000010002"
    }
}
