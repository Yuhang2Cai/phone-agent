// 门店调度回归：外部读取可控，真实StoreBrowseViewModel负责并行、去重、条件身份与显式刷新。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.*
import com.fandazi.app.feature.deal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 所有门店数据为合成公开测试事实，调度结果不通过调用被测方法的镜像实现推导。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(ExperimentalCoroutinesApi::class)
class StoreQuerySchedulingTest {
    private val dispatcher=StandardTestDispatcher()
    /** 固定主线程与可控摘要延迟。 */
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    /** 结束后恢复环境，后续测试不继承在途延迟。 */
    @After fun cleanup(){Dispatchers.resetMain()}
    /** 摘要1000ms晚到，评价100ms独立完成；重复同条件评价及摘要完成不再读评价。 */
    @Test fun summaryIsParallelAndNeverReloadsCurrentReviews()=runTest(dispatcher) {
        val repo=Reads();val model=StoreBrowseViewModel(repo,repo)
        model.initialize(S,CatalogQuery());model.select(StoreTab.REVIEWS);runCurrent();model.select(StoreTab.REVIEWS);runCurrent()
        advanceTimeBy(100);runCurrent();assertEquals(1,repo.reviewsCalls);assertFalse(model.state.value.loading);assertTrue(model.state.value.summaryLoading)
        advanceTimeBy(900);runCurrent();assertEquals(1,repo.summaryCalls);assertEquals(1,repo.reviewsCalls);assertNotNull(model.state.value.summary)
    }
    /** 同门店同条件的初始化合并，在途页签重选合并；显式重试则强制替换旧请求。 */
    @Test fun sameConditionIsCoalescedButExplicitRetryIsFresh()=runTest(dispatcher) {
        val repo=Reads();val model=StoreBrowseViewModel(repo,repo);val query=CatalogQuery(latitude=31.2,longitude=121.4)
        model.initialize(S,query);runCurrent();model.initialize(S,query);model.select(StoreTab.DEALS);runCurrent();assertEquals(1,repo.dealsCalls);assertEquals(1,repo.summaryCalls)
        model.select(StoreTab.DEALS,true);runCurrent();assertEquals(2,repo.dealsCalls)
        advanceTimeBy(1000);runCurrent();assertFalse(model.state.value.error)
    }
    /** 查询位置变化不能被同门店ID吞掉；实际网络收到各自完整条件，最终摘要属于新请求。 */
    @Test fun changedQueryKeepsDistinctSummaryIdentity()=runTest(dispatcher) {
        val repo=Reads();val model=StoreBrowseViewModel(repo,repo)
        val a=CatalogQuery(latitude=31.2,longitude=121.4);val b=CatalogQuery(latitude=32.0,longitude=122.0)
        model.initialize(S,a);runCurrent();model.initialize(S,b);runCurrent();advanceTimeBy(1000);runCurrent()
        assertEquals(listOf(a,b),repo.queries);assertEquals(b,model.state.value.query);assertEquals(2,repo.summaryCalls)
    }
    /** 业务返回强制重新读取当前摘要/评价，在途或刚成功均不能被普通页签去重吞掉。 */
    @Test fun businessReturnRefreshesBothIndependentCurrentReads()=runTest(dispatcher) {
        val repo=Reads();val model=StoreBrowseViewModel(repo,repo);model.initialize(S,CatalogQuery());model.select(StoreTab.REVIEWS);runCurrent()
        model.refreshAfterBusinessReturn();runCurrent();assertEquals(2,repo.summaryCalls);assertEquals(2,repo.reviewsCalls)
        advanceTimeBy(1000);runCurrent();assertEquals(StoreTab.REVIEWS,model.state.value.tab);assertFalse(model.state.value.error)
    }
    /** 记录外部请求只用于精确计数，所有可观察状态均读取真实模型；不发真实HTTP。 */
    private class Reads:CatalogRepository,GroupBuyingRepository {
        var summaryCalls=0;var reviewsCalls=0;var dealsCalls=0;val queries=mutableListOf<CatalogQuery>()
        override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore>{summaryCalls++;queries.add(query);delay(1000);return ApiResult.Success(CatalogStore(S,"合成门店","fixture","合成分类",100,"合成地址","合成时间",null,0,listOf(0,0,0,0,0),null,null,null,null))}
        override suspend fun reviews(id:String):ApiResult<List<CatalogReview>>{reviewsCalls++;delay(100);return ApiResult.Success(emptyList())}
        override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>>{dealsCalls++;delay(100);return ApiResult.Success(emptyList())}
        override suspend fun loadDeal(id:String):ApiResult<DealPackage> = error("门店调度不得读取单详情")
        override suspend fun stores(query:CatalogQuery):ApiResult<List<CatalogStore>> = error("此测试不得读取首页")
        override suspend fun cities():ApiResult<List<CatalogCity>> = error("此测试不得读取城市")
        override suspend fun dishes(id:String):ApiResult<List<CatalogDish>> = error("此测试不得读取菜单")
    }
    companion object {
        /** 独立合成门店，禁止测试夹具请求别人的业务资源。 */
        private const val S="10000000-0000-0000-0000-000000000001"
    }
}
