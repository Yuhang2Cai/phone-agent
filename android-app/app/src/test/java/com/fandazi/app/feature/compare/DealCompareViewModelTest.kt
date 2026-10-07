// 对比状态需求回归：真实只读事实及整数分人均，不涉及XML或音频。
package com.fandazi.app.feature.compare
import com.fandazi.app.feature.deal.DealPackage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
/** 独立合成业务事实固定未知、下架、有效期与舍入预期，后续补充真实ViewModel恢复/迟到场景。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DealCompareViewModelTest {
    /** 套餐标题即使写双人，权威人数未提供也不能计算人均。 */
    @Test fun unknownPeopleNeverUsesTitle(){assertEquals("未提供",CompareDealFacts(deal(),"合成门店",null).personPriceText())}
    /** 0人数不是合法除数，不能崩溃或显示零价人均。 */
    @Test fun zeroPeopleNeverDivides(){assertEquals("未提供",CompareDealFacts(deal(),"合成门店",0).personPriceText())}
    /** 100分分给3人，四舍五入到整数分后为0.33元，不改变交易总价。 */
    @Test fun averageRoundsToTwoDecimals(){assertEquals("¥0.33/人",CompareDealFacts(deal(price=100),"合成门店",3).personPriceText());assertEquals(100,deal(price=100).priceCents)}
    /** 下架快照只能只读展示，不能导航购买。 */
    @Test fun inactiveDealDisablesPurchase(){assertFalse("下架必须关闭购买",CompareDealFacts(deal(active=false),"合成门店",2).purchaseAvailable())}
    /** 库存清零或适用期结束同样不能借旧快照解锁入口。 */
    @Test fun soldOutAndExpiredDisablePurchase(){assertFalse(CompareDealFacts(deal(inventory=0),"合成门店",2).purchaseAvailable());assertFalse(CompareDealFacts(deal(to="2020-01-01",from="2019-01-01"),"合成门店",2).purchaseAvailable())}

    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    /** 保存仅含公开引用；模拟重新创建ViewModel后顺序恢复，再读取新事实而不恢复旧金额。 */
    @Test fun thinRestoreReloadsCurrentFacts()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val saved=SavedStateHandle();var price=8800;var reads=0
        val repo=DealCompareRepository{id->reads++;ApiResult.Success(CompareDealFacts(deal(price=price).copy(publicId=id),"合成门店",2))}
        val first=DealCompareViewModel(repo,saved)
        try {first.initialize(listOf(b,a));runCurrent();assertEquals(listOf(b,a),first.state.value.ids);assertEquals(listOf(DealCompareViewModel.KEY),saved.keys().toList());first.cancelReads();price=9100
            val rebuilt=DealCompareViewModel(repo,SavedStateHandle(mapOf(DealCompareViewModel.KEY to saved.get<List<String>>(DealCompareViewModel.KEY))))
            try {assertEquals(listOf(b,a),rebuilt.state.value.ids);rebuilt.initialize(rebuilt.state.value.ids);runCurrent();assertEquals(4,reads);assertEquals(9100,rebuilt.state.value.columns[0].facts!!.deal.priceCents)}finally{rebuilt.cancelReads()}
        }finally{first.cancelReads();Dispatchers.resetMain()}
    }
    /** 返回对比重新读新价格与下架状态，旧可售快照不能解锁最新购买。 */
    @Test fun refreshPublishesPriceChangeAndInactiveGate()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var price=8800;var active=true
        val vm=DealCompareViewModel(DealCompareRepository{id->ApiResult.Success(CompareDealFacts(deal(price=price,active=active).copy(publicId=id),"合成门店",2))})
        try {vm.initialize(listOf(a,b));runCurrent();assertTrue(vm.state.value.columns.all{it.canPurchase});price=9900;active=false;vm.initialize(listOf(a,b));assertTrue(vm.state.value.columns.all{!it.canPurchase});runCurrent();assertEquals(9900,vm.state.value.columns[0].facts!!.deal.priceCents);assertTrue(vm.state.value.columns.all{!it.canPurchase})}finally{vm.cancelReads();Dispatchers.resetMain()}
    }
    /** 一列失败重试不得重查另一列或清掉其可信事实，列顺序也不随返回时序改变。 */
    @Test fun retriesOnlyTheFailedColumn()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val calls=mutableMapOf<String,Int>();var fail=true
        val vm=DealCompareViewModel(DealCompareRepository{id->calls[id]=(calls[id]?:0)+1;if(id==b && fail)ApiResult.Failure(ApiError.Network("明确注入的读取失败"))else ApiResult.Success(CompareDealFacts(deal().copy(publicId=id),"合成门店",2))})
        try {vm.initialize(listOf(a,b));runCurrent();assertTrue(vm.state.value.columns[0].canPurchase);assertFalse(vm.state.value.columns[1].canPurchase);fail=false;vm.retry(b);runCurrent();assertEquals(1,calls[a]);assertEquals(2,calls[b]);assertEquals(listOf(a,b),vm.state.value.columns.map{it.id});assertTrue(vm.state.value.columns.all{it.canPurchase})}finally{vm.cancelReads();Dispatchers.resetMain()}
    }
    /** 已取消的旧代次即使外部边界不合作并迟到，也不能覆盖新选择/价格。 */
    @Test fun lateOldReadNeverChangesCurrentFacts()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val old=CompletableDeferred<ApiResult<CompareDealFacts>>();var delayed=true
        val vm=DealCompareViewModel(DealCompareRepository{id->if(id==a && delayed)withContext(NonCancellable){old.await()}else ApiResult.Success(CompareDealFacts(deal(price=9900).copy(publicId=id),"最新合成门店",2))})
        try {vm.initialize(listOf(a,b));runCurrent();delayed=false;vm.initialize(listOf(b,a));runCurrent();old.complete(ApiResult.Success(CompareDealFacts(deal(price=100),"旧合成门店",2)));runCurrent();assertEquals(listOf(b,a),vm.state.value.ids);assertTrue(vm.state.value.columns.all{it.facts?.deal?.priceCents==9900})}finally{old.complete(ApiResult.Failure(ApiError.Network("清理取消")));vm.cancelReads();Dispatchers.resetMain()}
    }
    /** 服务端身份错误是单列失败，不借其他套餐解锁入口。 */
    @Test fun wrongResponseIdClosesPurchase()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val vm=DealCompareViewModel(DealCompareRepository{ApiResult.Success(CompareDealFacts(deal(),"合成门店",2))})
        try {vm.initialize(listOf(a,b));runCurrent();assertTrue(vm.state.value.columns[0].canPurchase);assertFalse(vm.state.value.columns[1].canPurchase);assertNotNull(vm.state.value.columns[1].error)}finally{vm.cancelReads();Dispatchers.resetMain()}
    }
    /** 合成公开资源与长期日期仅为单测夹具，不作为运行期默认套餐。 */
    private fun deal(price:Int=8800,active:Boolean=true,inventory:Int=10,from:String="2020-01-01",to:String="2030-01-01")=DealPackage("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000101","合成双人餐","合成说明",price,10800,inventory,from,to,active,5)
}
