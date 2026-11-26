// Tab设备验收：同条件20轮真实菜单切换，度量Navigation栈身份、存活Fragment、目录调用及PSS。
package com.fandazi.app.core.navigation

import android.annotation.SuppressLint
import android.os.Debug
import androidx.navigation.NavGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.fandazi.app.*
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.auth.*
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.voucher.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 只替换外部目录及合成认证会话，不改变生产导航代码；与真实Spring/Maestro认证链路分别记录。 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi") // 内部栈只用于验收统计，应用业务不依赖该受限接口。
class TopLevelNavigationDeviceTest {
    /** 每轮切订单、我的、AI、首页；完成全20轮再断言，首次失败保留实际增长而非预期数字。 */
    @Test fun twentyMenuRoundsKeepNavigationBounded() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as FandaziApplication
        val originals=mutableMapOf<String,Any>();var reads=0;var orderReads=0
        fun replace(name:String,value:Any) {
            FandaziApplication::class.java.getDeclaredField(name).apply{isAccessible=true;originals[name]=get(app);set(app,value)}
        }
        val storage=object:SessionStorage {
            override val initializationError:Throwable?=null
            override fun read()=Result.success<PersistedSession?>(PersistedSession("synthetic-tab-token",User("tab-device-owner","synthetic@example.invalid")))
            override fun readGuest()=Result.success(false)
            override suspend fun write(value:PersistedSession)=true
            override suspend fun clear()=true
        }
        replace("sessionRepository",SessionRepository(object:AuthGateway {
            override suspend fun login(username:String,password:String):ApiResult<AuthResponse> = error("导航测量不能登录")
            override suspend fun register(username:String,email:String,password:String):ApiResult<AuthResponse> = error("导航测量不能注册")
        },storage))
        replace("catalogRepository",object:CatalogRepository {
            override suspend fun stores(query:CatalogQuery):ApiResult<List<CatalogStore>> {reads++;return ApiResult.Success(emptyList())}
            override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> = ApiResult.Failure(ApiError.Http(404,"测量未打开门店"))
            override suspend fun cities()=ApiResult.Success(emptyList<CatalogCity>())
            override suspend fun dishes(id:String)=ApiResult.Success(emptyList<CatalogDish>())
            override suspend fun reviews(id:String)=ApiResult.Success(emptyList<CatalogReview>())
        })
        replace("voucherRepository",object:VoucherRepository by HttpVoucherRepository(ApiClient("http://localhost/",{null})) {
            override suspend fun listOrders():ApiResult<List<GroupOrderSummary>> {orderReads++;return ApiResult.Success(emptyList())}
        })
        val samples=JSONArray();val homeIds=mutableSetOf<String>();var maxDepth=0;var maxLive=0
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                for(round in 0..20) {
                    // 第0轮仅记录初始页面；随后每步都等待实际事务/生命周期完成，不直接伪造NavOptions。
                    if(round>0)for(menu in listOf(R.id.ordersFragment,R.id.profileFragment,R.id.agentAssistantTab,R.id.homeFragment)) {
                        scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId=menu }
                        instrumentation.waitForIdleSync()
                    }
                    val point=JSONObject().put("round",round)
                    scenario.onActivity { activity ->
                        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                        val entries=host.navController.currentBackStack.value.filter { it.destination !is NavGraph }
                        val live=host.childFragmentManager.fragments.count { it.isAdded }
                        maxDepth=maxOf(maxDepth,entries.size);maxLive=maxOf(maxLive,live)
                        homeIds+=host.navController.currentBackStackEntry!!.id
                        point.put("stackDepth",entries.size).put("liveFragments",live).put("publicRepositoryReads",reads).put("orderRepositoryReads",orderReads)
                        assertEquals("每轮结束应回正式首页",R.id.homeFragment,host.navController.currentDestination!!.id)
                    }
                    // 点采样包括当前实际页面和应用依赖，不能冒称连续峰值或正式arm64性能。
                    val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory);point.put("pssKb",memory.totalPss);samples.put(point)
                }
                val phase=InstrumentationRegistry.getArguments().getString("phase")?:"candidate"
                require(phase.matches(Regex("[a-z0-9-]+")))
                val result=JSONObject().put("rounds",20).put("maxDepth",maxDepth).put("maxLiveFragments",maxLive)
                    .put("uniqueHomeEntries",homeIds.size).put("publicRepositoryReads",reads).put("orderRepositoryReads",orderReads).put("samples",samples)
                    .put("scope","合成已登录身份真实菜单，空目录/本人订单边界；无录音/推理/业务写入；PSS为点样本")
                File(app.getExternalFilesDir(null),"performance/tab-navigation-$phase.json").apply{parentFile!!.mkdirs();writeText(result.toString(2))}
                assertTrue("顶层20轮后返回栈应有界",maxDepth<=2)
                assertEquals("首页恢复应沿用原栈条目",1,homeIds.size)
                assertTrue("实际存活业务Fragment不应累积",maxLive<=2)
            }
        } finally {
            // 场景关闭后还原依赖，真实加密会话未写入；后续Maestro仍使用原服务与身份源。
            originals.forEach{(name,value)->FandaziApplication::class.java.getDeclaredField(name).apply{isAccessible=true;set(app,value)}}
        }
    }
}
