// 对比目录状态回归：受控仓库验证完整性、顺序与取消代次，数据仅在测试内合成。
package com.fandazi.app.feature.compare
import androidx.lifecycle.*
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.deal.*
import com.fandazi.app.feature.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 只读选择目录不把局部HTTP错误变空成功，也不接受旧代次覆盖新目录。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CompareCatalogViewModelTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    /** 两店结果即使返回顺序不同也按目录原序；第二店失败必须明确不完整。 */
    @Test fun partialFailureKeepsSuccessfulFactsAndMarksIncomplete()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm=CompareCatalogViewModel(Catalog(listOf(store(b),store(a))),Buying{if(it==a)ApiResult.Failure(ApiError.Network("指定店读取失败"))else ApiResult.Success(listOf(deal(b,b)))})
        try {vm.load();runCurrent();assertFalse(vm.state.value.loading);assertTrue(vm.state.value.error);assertEquals(listOf(b),vm.state.value.rows.map{it.deal.publicId});assertEquals("合成门店",vm.state.value.rows.single().storeName)}finally{vm.cancel();Dispatchers.resetMain()}
    }
    /** 当前HTTP在途重复进入不能重复查询；手动取消后新读取不得由旧响应覆盖。 */
    @Test fun cancelsOldGenerationAndMergesInFlightEntry()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val deferred=CompletableDeferred<ApiResult<List<DealPackage>>>();var calls=0
        val vm=CompareCatalogViewModel(Catalog(listOf(store(a))),Buying{calls++;if(calls==1)withContext(NonCancellable){deferred.await()}else ApiResult.Success(listOf(deal(b,a)))})
        try {vm.load();runCurrent();vm.load();assertEquals(1,calls);vm.cancel();vm.load();runCurrent();assertEquals(2,calls);assertEquals(b,vm.state.value.rows.single().deal.publicId);deferred.complete(ApiResult.Success(listOf(deal(a,a))));runCurrent();assertEquals(b,vm.state.value.rows.single().deal.publicId)}finally{deferred.complete(ApiResult.Success(emptyList()));vm.cancel();runCurrent();Dispatchers.resetMain()}
    }
    /** 一个门店的接口返回另一门店套餐时，不能构造看似可信的店名/套餐对。 */
    @Test fun wrongOwnershipNeverBecomesSelectableFacts()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val vm=CompareCatalogViewModel(Catalog(listOf(store(a))),Buying{ApiResult.Success(listOf(deal(b,b)))})
        try {vm.load();runCurrent();assertTrue(vm.state.value.error);assertTrue(vm.state.value.rows.isEmpty())}finally{vm.cancel();Dispatchers.resetMain()}
    }
    /** 公开测试资源全部明确UUID与正整数分，不依赖业务种子或默认行。 */
    private fun store(id:String)=CatalogStore(id,"合成门店","test","测试",1000,"测试地址","测试营业",null,0,emptyList(),null,null,null,null)
    private fun deal(id:String,store:String)=DealPackage(id,store,"合成套餐","测试说明",1000,1200,5,"2026-01-01","2027-01-01",true,5)
    /** 仅stores被测，其余方法若误调用立即失败，防止隐式增加写入或补数据。 */
    private class Catalog(private val rows:List<CatalogStore>):CatalogRepository {
        override suspend fun stores(query:CatalogQuery)=ApiResult.Success(rows)
        override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> = error("不应调用")
        override suspend fun cities():ApiResult<List<CatalogCity>> = error("不应调用")
        override suspend fun dishes(id:String):ApiResult<List<CatalogDish>> = error("不应调用")
        override suspend fun reviews(id:String):ApiResult<List<CatalogReview>> = error("不应调用")
    }
    /** 接口只有公开读取；loadDeal误调用同样失败，确保目录不增加默认详情请求。 */
    private class Buying(private val read:suspend(String)->ApiResult<List<DealPackage>>):GroupBuyingRepository {
        override suspend fun loadStoreDeals(storePublicId:String)=read(storePublicId)
        override suspend fun loadDeal(dealPublicId:String):ApiResult<DealPackage> = error("不应调用")
    }
}
