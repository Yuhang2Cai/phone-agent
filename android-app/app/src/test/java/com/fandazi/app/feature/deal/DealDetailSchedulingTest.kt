// 套餐查询状态回归：只控制外部只读请求，生产模型执行验证、取消、代次和购买门槛。
package com.fandazi.app.feature.deal

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 合成公开DTO与虚拟时钟隔离交易服务，不调用麦克风、音频、报价或下单。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(ExperimentalCoroutinesApi::class)
class DealDetailSchedulingTest {
    private val dispatcher=StandardTestDispatcher()
    /** 将ViewModel主线程确定性地交给本例调度器。 */
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    /** 用例结束恢复全局调度，不能污染其他导航和私有状态测试。 */
    @After fun cleanup(){Dispatchers.resetMain()}

    /** 选项失败重试只再读同店列表；详情/金额保持可信且失败期间禁购。 */
    @Test fun localOptionsRetryDoesNotReloadVerifiedDetail()=runTest(dispatcher) {
        var details=0;var lists=0
        val repo=object:GroupBuyingRepository {
            override suspend fun loadDeal(id:String):ApiResult<DealPackage>{details++;return ApiResult.Success(deal(id))}
            override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>> = if(lists++==0)ApiResult.Failure(ApiError.Network("合成失败"))else ApiResult.Success(listOf(deal(A)))
        }
        val model=DealDetailViewModel(repo);model.initialize(A,S);runCurrent()
        assertEquals(A,model.state.value.detail?.publicId);assertFalse(model.state.value.purchaseReady);assertNotNull(model.state.value.optionsError)
        model.retryOptions();runCurrent()
        assertEquals(1,details);assertEquals(2,lists);assertTrue(model.state.value.purchaseReady)
    }
    /** 切换到新套餐必须新读详情，旧列表非合作取消最后返回也不能更新新标题/价/购买资格。 */
    @Test fun lateOldOptionsCannotAuthorizeNewDeal()=runTest(dispatcher) {
        val old=CompletableDeferred<ApiResult<List<DealPackage>>>();var calls=0
        val repo=object:GroupBuyingRepository {
            override suspend fun loadDeal(id:String)=ApiResult.Success(deal(id))
            override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>> = if(calls++==0)withContext(NonCancellable){old.await()}else ApiResult.Success(listOf(deal(B)))
        }
        val model=DealDetailViewModel(repo);model.initialize(A,S);runCurrent();model.initialize(B,S);runCurrent()
        assertEquals(B,model.state.value.detail?.publicId);assertEquals(2222,model.state.value.detail?.priceCents);assertTrue(model.state.value.purchaseReady)
        old.complete(ApiResult.Success(listOf(deal(A))));runCurrent()
        assertEquals(B,model.state.value.detail?.publicId);assertEquals(listOf(B),model.state.value.options.map{it.publicId});assertTrue(model.state.value.purchaseReady)
    }
    /** 跨门店的旧详情成功与错误均不得覆盖当前对象；每种结果独立启动模型和在途读取。 */
    @Test fun lateOldDetailSuccessAndFailureCannotOverwriteCurrent()=runTest(dispatcher) {
        listOf<ApiResult<DealPackage>>(ApiResult.Success(deal(A)),ApiResult.Failure(ApiError.Network("合成旧错误"))).forEach { late ->
            val old=CompletableDeferred<ApiResult<DealPackage>>()
            val repo=object:GroupBuyingRepository {
                override suspend fun loadDeal(id:String):ApiResult<DealPackage> = if(id==A)withContext(NonCancellable){old.await()}else ApiResult.Success(deal(B).copy(storePublicId=S2))
                override suspend fun loadStoreDeals(id:String)=ApiResult.Success(listOf(deal(B).copy(storePublicId=id)))
            }
            val model=DealDetailViewModel(repo);model.initialize(A,S);runCurrent();model.initialize(B,S2);runCurrent();old.complete(late);runCurrent()
            assertEquals(B,model.state.value.detail?.publicId);assertEquals(S2,model.state.value.detail?.storePublicId);assertNull(model.state.value.detailError);assertTrue(model.state.value.purchaseReady)
        }
    }
    /** 退出视图立即清事实并取消实际可取消请求；旧协程finally被执行，不能继续提供旧购买入口。 */
    @Test fun exitCancelsPendingReadAndDropsEligibility()=runTest(dispatcher) {
        var cancelled=false
        val repo=object:GroupBuyingRepository {
            override suspend fun loadDeal(id:String):ApiResult<DealPackage> = try{awaitCancellation()}finally{cancelled=true}
            override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>> = error("详情未到不得读取列表")
        }
        val model=DealDetailViewModel(repo);model.initialize(A,S);runCurrent();model.cancelReads();runCurrent()
        assertTrue(cancelled);assertNull(model.state.value.detail);assertFalse(model.state.value.purchaseReady)
    }
    /** 正确门店列表不含请求套餐不能默认选首项；下架/无库存/过期详情也不能解锁。 */
    @Test fun missingMembershipAndUnavailableFieldsNeverAuthorizePurchase()=runTest(dispatcher) {
        listOf(deal(A).copy(active=false),deal(A).copy(inventory=0),deal(A).copy(validTo="2026-01-02"),deal(A)).forEach { actual ->
            val repo=object:GroupBuyingRepository {
                override suspend fun loadDeal(id:String)=ApiResult.Success(actual)
                override suspend fun loadStoreDeals(id:String)=ApiResult.Success(listOf(if(actual==deal(A))deal(B)else actual))
            }
            val model=DealDetailViewModel(repo);model.initialize(A,S);runCurrent()
            assertEquals(A,model.state.value.detail?.publicId);assertFalse(model.state.value.purchaseReady)
        }
    }
    /** 进程保存只含当前公开引用，不含DTO、真实金额、购买资格或会话字段；重建重新读取事实。 */
    @Test fun savedStateContainsOnlyPublicSelectionReferences()=runTest(dispatcher) {
        val saved=SavedStateHandle();var calls=0
        val repo=object:GroupBuyingRepository {
            override suspend fun loadDeal(id:String):ApiResult<DealPackage>{calls++;return ApiResult.Success(deal(id))}
            override suspend fun loadStoreDeals(id:String)=ApiResult.Success(listOf(deal(A),deal(B)))
        }
        val model=DealDetailViewModel(repo,saved);model.initialize(A,S);runCurrent();model.initialize(B,S);runCurrent()
        assertEquals(setOf("selectedDealId","storeId"),saved.keys());assertEquals(B,saved.get<String>("selectedDealId"))
        val after=DealDetailViewModel(repo,saved);assertNull(after.state.value.detail);assertFalse(after.state.value.purchaseReady)
        after.initialize(B,S);runCurrent();assertEquals(3,calls);assertEquals(B,after.state.value.detail?.publicId)
    }
    /** 非UUID请求不得发网络；合法请求的坏字段不能成为可信详情或继续读取同店选项。 */
    @Test fun invalidIdentityAndFieldsNeverProduceTrustedContent()=runTest(dispatcher) {
        var details=0;var options=0
        var actual=deal(A)
        val repo=object:GroupBuyingRepository {
            override suspend fun loadDeal(id:String):ApiResult<DealPackage>{details++;return ApiResult.Success(actual)}
            override suspend fun loadStoreDeals(id:String):ApiResult<List<DealPackage>>{options++;return ApiResult.Success(listOf(actual))}
        }
        val model=DealDetailViewModel(repo);model.initialize("不是UUID",S);runCurrent();assertEquals(0,details);assertNotNull(model.state.value.detailError)
        listOf(deal(A).copy(publicId="非法响应"),deal(A).copy(storePublicId="非法门店"),deal(A).copy(title=""),
            deal(A).copy(priceCents=-1),deal(A).copy(inventory=-1),deal(A).copy(purchaseLimit=0),
            deal(A).copy(validFrom="无效日期"),deal(A).copy(validFrom="2031-01-01",validTo="2030-12-31")).forEach { invalid ->
            actual=invalid;model.initialize(A,S,true);runCurrent()
            assertNull(model.state.value.detail);assertNotNull(model.state.value.detailError);assertFalse(model.state.value.purchaseReady)
        }
        assertEquals(8,details);assertEquals(0,options)
    }
    companion object {
        /** 固定UUID和分值可区分两套餐；空图片保留未知，不在测试造图。 */
        private const val S="10000000-0000-0000-0000-000000000001"
        private const val S2="10000000-0000-0000-0000-000000000002"
        private const val A="20000000-0000-0000-0000-000000000001"
        private const val B="20000000-0000-0000-0000-000000000002"
        private fun deal(id:String)=DealPackage(id,S,"合成$id","合成说明",if(id==A)1234 else 2222,2500,10,"2026-01-01","2030-12-31",true,2)
    }
}
