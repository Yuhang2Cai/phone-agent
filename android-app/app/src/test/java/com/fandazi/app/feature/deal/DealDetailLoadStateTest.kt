// 详情时序/归属回归：用真实MainActivity/Fragment验证已存在流程的可信内容和禁购边界，不伪造产品展示数据。
package com.fandazi.app.feature.deal

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import com.fandazi.app.*
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.auth.*
import com.fandazi.app.feature.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 外部目录读取采用可控制虚拟时钟，导航和视图读取为真实实现；所有身份和内容均为合成用例。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DealDetailLoadStateTest {
    /** 100ms详情先到、门店列表3秒仍在途，可信只读内容必须先显示且购买不能提前解锁。 */
    @Test fun trustworthyDetailIsVisibleBeforeThreeSecondOptions()=scenario { f ->
        f.open();f.elapse(100)
        assertEquals("可信详情必须在选项到达前可见","合成当前套餐",f.title())
        assertFalse("选项归属未验证前必须禁购",f.purchase().isEnabled)
        assertEquals(1,f.repository.listCalls)
    }
    /** 后续选项失败不能把已验证详情清空；失败只影响同店选项及购买门槛，不换默认套餐。 */
    @Test fun optionsFailureKeepsVerifiedReadOnlyDetail()=scenario(listFailure=true) { f ->
        f.open();f.elapse(3100)
        assertEquals("选项失败不能清除已校验详情","合成当前套餐",f.title())
        assertFalse(f.purchase().isEnabled)
    }
    /** 导航门店与真实详情门店冲突应早期拒绝，不继续用错误门店查询选项或展示报价入口。 */
    @Test fun requestedStoreConflictRejectsWithoutOptionsRequest()=scenario { f ->
        f.open(STORE_B);f.elapse(100)
        assertEquals("套餐与门店不匹配",f.title());assertEquals(0,f.repository.listCalls)
        assertFalse(f.purchase().isEnabled)
    }
    /** 列表即使含同一个套餐ID，只要对象门店错误仍须拒绝，不能仅凭ID成员关系解锁购买。 */
    @Test fun wrongStoreInOptionsNeverAuthorizesPurchase()=scenario(wrongListStore=true) { f ->
        f.open();f.elapse(3100)
        assertFalse("错误门店列表不能解锁购买",f.purchase().isEnabled)
    }
    /** 单详情响应身份必须等于请求公开ID；服务端误路由到另一套餐不能成为可信首屏。 */
    @Test fun wrongDetailIdNeverDisplaysAnotherDeal()=scenario(wrongDetailId=true) { f ->
        f.open();f.elapse(3100)
        assertNotEquals("错误详情ID不能替代原套餐","合成当前套餐",f.title())
        assertFalse(f.purchase().isEnabled)
    }
    /** 从门店进入业务详情再返回，摘要需要新读；删除返回刷新会让此断言得到旧的单次读取。 */
    @Test fun businessReturnRefreshesStoreFacts()=scenario { f ->
        f.openStore();f.elapse(3100);assertEquals(1,f.summaryCalls)
        f.open();f.elapse(100);f.back();f.elapse(3100)
        assertEquals("业务返回不能吞掉门店刷新",2,f.summaryCalls)
    }
    /** 每例独立主线程时钟，先恢复应用依赖/销毁界面，再解除调度替换，禁止污染后续业务测试。 */
    private fun scenario(listFailure:Boolean=false,wrongListStore:Boolean=false,wrongDetailId:Boolean=false,test:(Fixture)->Unit) {
        val dispatcher=StandardTestDispatcher();Dispatchers.setMain(dispatcher)
        val app=RuntimeEnvironment.getApplication() as FandaziApplication
        val fixture=Fixture(app,dispatcher,listFailure,wrongListStore,wrongDetailId)
        try {fixture.start();test(fixture)}finally{fixture.close();Dispatchers.resetMain()}
    }
    /** 只替换会话/公开仓库外部边界，不增加生产测试setter，也不触发真实认证、写业务或音频。 */
    private class Fixture(val app:FandaziApplication,val dispatcher:TestDispatcher,listFailure:Boolean,wrongListStore:Boolean,wrongDetailId:Boolean) {
        val repository=DelayedBuying(listFailure,wrongListStore,wrongDetailId)
        var summaryCalls=0
        private val originals=mutableMapOf<String,Any>()
        private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
        private val activity get()=controller.get()
        private val nav get()=(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
        fun start() {
            fun replace(name:String,value:Any){FandaziApplication::class.java.getDeclaredField(name).apply{isAccessible=true;originals[name]=get(app);set(app,value)}}
            val storage=object:SessionStorage {
                override val initializationError:Throwable?=null
                override fun read()=Result.success<PersistedSession?>(null)
                override fun readGuest()=Result.success(true)
                override suspend fun write(value:PersistedSession)=true
                override suspend fun clear()=true
            }
            replace("sessionRepository",SessionRepository(object:AuthGateway {
                override suspend fun login(username:String,password:String):ApiResult<AuthResponse> = error("详情时序测试不得登录")
                override suspend fun register(username:String,email:String,password:String):ApiResult<AuthResponse> = error("详情时序测试不得注册")
            },storage))
            replace("groupBuyingRepository",repository)
            replace("catalogRepository",object:CatalogRepository by HttpCatalogRepository(ApiClient("http://127.0.0.1:1/",{null})) {
                override suspend fun stores(query:CatalogQuery)=ApiResult.Success(emptyList<CatalogStore>())
                override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> {
                    summaryCalls++;return ApiResult.Success(CatalogStore(STORE_A,"合成门店","fixture","合成分类",100,"合成地址","合成时间",null,0,listOf(0,0,0,0,0),null,null,null,null))
                }
                override suspend fun cities()=ApiResult.Success(emptyList<CatalogCity>())
            })
            controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible();pump()
        }
        /** 请求原完整ID与可选门店引用；没有从列表选首项或把价格传给页面。 */
        fun open(store:String=STORE_A){nav.navigate(R.id.dealDetailFragment,bundleOf("dealPublicId" to DEAL_A,"storePublicId" to store));pump()}
        /** 返回经过真实NavHost，不直接调用模型的刷新方法；对应商品页关闭后的实际生命周期。 */
        fun openStore(){nav.navigate(R.id.storeFragment,bundleOf("storePublicId" to STORE_A));pump()}
        fun back(){nav.popBackStack();pump()}
        /** 固定推进真实协程和Android事务，100/3000ms由外部请求延迟决定而非现实sleep猜测。 */
        fun elapse(ms:Long){dispatcher.scheduler.advanceTimeBy(ms);pump()}
        fun pump(){repeat(8){dispatcher.scheduler.runCurrent();shadowOf(Looper.getMainLooper()).idle()}}
        fun title()=activity.findViewById<TextView>(R.id.deal_title).text.toString()
        fun purchase()=activity.findViewById<View>(R.id.deal_purchase_bar)
        fun close(){if(::controller.isInitialized){controller.pause().stop().destroy();pump()};originals.forEach{(name,value)->FandaziApplication::class.java.getDeclaredField(name).apply{isAccessible=true;set(app,value)}}}
    }
    /** 每次详情100ms、列表3000ms；错误只在明确参数分支注入，记录请求数用于门店冲突断言。 */
    private class DelayedBuying(val fail:Boolean,val wrongStore:Boolean,val wrongId:Boolean):GroupBuyingRepository {
        var listCalls=0
        override suspend fun loadDeal(id:String):ApiResult<DealPackage>{delay(100);return ApiResult.Success(deal(if(wrongId)DEAL_B else DEAL_A,STORE_A))}
        override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>> {listCalls++;delay(3000);return if(fail)ApiResult.Failure(ApiError.Network("合成选项失败"))else ApiResult.Success(listOf(deal(if(wrongId)DEAL_B else DEAL_A,if(wrongStore)STORE_B else STORE_A)))}
    }
    companion object {
        /** 独立公开UUID及合成事实；合法固定日期用于归属/时序测试，不能把默认字段当实际目录。 */
        private const val STORE_A="10000000-0000-0000-0000-000000000001"
        private const val STORE_B="10000000-0000-0000-0000-000000000002"
        private const val DEAL_A="20000000-0000-0000-0000-000000000001"
        private const val DEAL_B="20000000-0000-0000-0000-000000000002"
        private fun deal(id:String,store:String)=DealPackage(id,store,"合成当前套餐","合成说明",1234,2500,10,"2026-01-01","2030-12-31",true,2)
    }
}
