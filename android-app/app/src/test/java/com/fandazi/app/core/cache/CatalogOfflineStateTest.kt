// 目录页面离线状态回归：真实ViewModel消费受控来源流，证明可读快照、禁购和恢复状态保持。
package com.fandazi.app.core.cache
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.home.CatalogViewModel
import com.fandazi.app.feature.deal.*
import com.fandazi.app.feature.compare.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28]) @OptIn(ExperimentalCoroutinesApi::class)
class CatalogOfflineStateTest {
    private val dispatcher=StandardTestDispatcher()
    /** 固定主线程便于精确推进缓存与网络两阶段。 */
    @Before fun setUp(){Dispatchers.setMain(dispatcher)}
    /** 测试结束恢复协程环境，不留下轮询或设备状态。 */
    @After fun tearDown(){Dispatchers.resetMain()}
    private val storeId="00000000-0000-4000-8000-000000000001";private val dealId="00000000-0000-4000-8000-000000000002"
    private fun deal(price:Int=100)=DealPackage(dealId,storeId,"合成套餐","公开内容",price,200,10,"2020-01-01","2099-01-01",true,2,storeName="合成门店")
    /** 受控仓库只公开查询：旧一次读取返回断网，来源流提供真正需要的新离线行为。 */
    private inner class Reads:CatalogRepository,GroupBuyingRepository,CatalogSourceRepository {
        var online=false;var calls=0;var pauseDeals:CompletableDeferred<Unit>?=null
        val meta get()=CatalogReadMetadata(if(online)CatalogReadStatus.ONLINE else CatalogReadStatus.OFFLINE,100L)
        val store=CatalogStore(storeId,"合成门店","110000","火锅",100,"公开地址","10:00",null,0,emptyList(),null,null,null,null)
        private fun <T> offline():ApiResult<T> = ApiResult.Failure(ApiError.Network("断网"))
        override suspend fun stores(query:CatalogQuery):ApiResult<List<CatalogStore>> = offline()
        override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> = offline()
        override suspend fun cities():ApiResult<List<CatalogCity>> = offline()
        override suspend fun dishes(id:String):ApiResult<List<CatalogDish>> = offline()
        override suspend fun reviews(id:String):ApiResult<List<CatalogReview>> = offline()
        override suspend fun loadDeal(dealPublicId:String):ApiResult<DealPackage> = offline()
        override suspend fun loadStoreDeals(storePublicId:String):ApiResult<List<DealPackage>> = offline()
        override fun observeStores(query:CatalogQuery):Flow<CatalogRead<List<CatalogStore>>> = flow{calls++;emit(CatalogRead(ApiResult.Success(listOf(store)),meta))}
        override fun observeStore(id:String,query:CatalogQuery):Flow<CatalogRead<CatalogStore>> = flow{emit(CatalogRead(ApiResult.Success(store),meta))}
        override fun observeDeal(id:String):Flow<CatalogRead<DealPackage>> = flow{emit(CatalogRead(ApiResult.Success(deal(if(online)150 else 100)),meta))}
        override fun observeStoreDeals(id:String):Flow<CatalogRead<List<DealPackage>>> = flow{pauseDeals?.await();emit(CatalogRead(ApiResult.Success(listOf(deal(if(online)150 else 100))),meta))}
    }
    /** 首页快照可读，恢复不重置独立筛选、排序和薄锚点。 */
    @Test fun homeOfflineAndRecoveryPreserveQueryAndAnchor()=runTest(dispatcher){val repo=Reads();val model=CatalogViewModel(repo);val query=CatalogQuery(keyword="套餐",category="火锅",sort="PRICE_ASC",cityCode="110000");model.initialize(query);runCurrent();assertEquals("离线必须读到原公开缓存",listOf(storeId),model.state.value.stores.map{it.publicId});model.rememberAnchor(ListAnchor("store:$storeId",-20));repo.online=true;model.load();runCurrent();assertEquals(query,model.state.value.query);assertEquals(-20,model.anchor.offset);assertEquals(2,repo.calls)}
    /** 门店摘要和套餐页签都可离线浏览，不能丢掉来源流变成全页错误。 */
    @Test fun storeOfflineReadsBothIndependentSections()=runTest(dispatcher){val repo=Reads();val model=StoreBrowseViewModel(repo,repo);model.initialize(storeId,CatalogQuery());runCurrent();assertNotNull("离线门店摘要应可浏览",model.state.value.summary);assertEquals(1,model.state.value.rows.size);assertFalse(model.state.value.error)}
    /** 恢复同页签时延迟新读取，原完整公开行和薄锚点必须保留，不能闪空后再恢复。 */
    @Test fun storeSameTabRefreshKeepsSnapshotUntilNewRead()=runTest(dispatcher) {
        val repo=Reads();val model=StoreBrowseViewModel(repo,repo)
        model.initialize(storeId,CatalogQuery());runCurrent()
        val before=model.state.value.rows;model.rememberAnchor(ListAnchor("deal:$dealId",-24))
        val gate=CompletableDeferred<Unit>();repo.pauseDeals=gate
        try {
            model.refreshAfterBusinessReturn();runCurrent()
            assertEquals("相同页签刷新不得清掉完整快照",before,model.state.value.rows)
            assertEquals(-24,model.anchor.offset)
            assertFalse(model.state.value.metadata.canTransact)
        } finally {gate.complete(Unit);runCurrent()}
    }
    /** 详情价格可读但禁购，恢复读新价格后才开放原重新报价入口。 */
    @Test fun detailOfflineCannotBuyRecoveryReadsChangedPrice()=runTest(dispatcher){val repo=Reads();val model=DealDetailViewModel(repo);model.initialize(dealId,storeId);runCurrent();assertNotNull("离线公开详情应可读",model.state.value.detail);assertEquals(100,model.state.value.detail!!.priceCents);assertFalse(model.state.value.purchaseReady);repo.online=true;model.initialize(dealId,storeId,true);runCurrent();assertEquals(150,model.state.value.detail!!.priceCents);assertTrue(model.state.value.purchaseReady)}
    /** 对比每列可读快照，但来源为离线时不能被现有有效日期/库存判成可买。 */
    @Test fun comparisonSnapshotDoesNotGrantPurchase(){val facts=CompareDealFacts(deal(),"合成门店",metadata=CatalogReadMetadata(CatalogReadStatus.OFFLINE,100));assertFalse("离线对比不能开放购买",CompareColumn(dealId,facts,loading=false).canPurchase)}
}
