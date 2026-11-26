// 业务列表状态回归：使用真实ViewModel，替身仅模拟外部读取迟到，不断言模拟器本身。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.home.CatalogViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.User
import com.fandazi.app.feature.order.OrdersViewModel
import com.fandazi.app.feature.voucher.*
import com.fandazi.app.feature.deal.*
import kotlinx.coroutines.flow.MutableStateFlow

/** 检测取消后仍交付旧成功、旧失败以及可变集合影响已经呈现结果的回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BusinessListStateTest {
    private val dispatcher=StandardTestDispatcher()
    /** 所有调度由测试控制，先启动旧请求，再启动新请求，最后确定性释放旧回调。 */
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    /** 恢复全局主线程调度，避免其他页面用例继承本项测试时钟。 */
    @After fun cleanup() { Dispatchers.resetMain() }

    /** 删除请求代次校验会让旧查询成功覆盖新筛选；取消不合作的外部读取是必要边界。 */
    @Test fun lateOldSuccessCannotOverwriteCurrentQuery()=runTest(dispatcher) {
        val old=CompletableDeferred<ApiResult<List<CatalogStore>>>()
        val repo=DelayedCatalog(old)
        val model=CatalogViewModel(repo)
        model.load(CatalogQuery(keyword="旧条件")); runCurrent()
        model.load(CatalogQuery(keyword="新条件")); runCurrent()
        assertEquals("新店",model.state.value.stores.single().name)
        old.complete(ApiResult.Success(listOf(store("旧店",1)))); runCurrent()
        assertEquals("新条件",model.state.value.query.keyword)
        assertEquals("新店",model.state.value.stores.single().name)
        assertNull(model.state.value.error)
    }

    /** 已提交集合不应持有外部可变列表或评分分布引用，否则Diff旧值会被悄悄改写。 */
    @Test fun successfulStateOwnsImmutableOrderedSnapshot()=runTest(dispatcher) {
        val distribution=mutableListOf(0,0,0,0,1)
        val rows=mutableListOf(store("先返回",2).copy(reviewDistribution=distribution),store("后返回",1))
        val repo=DelayedCatalog(CompletableDeferred(),rows)
        val model=CatalogViewModel(repo)
        model.load(CatalogQuery(keyword="新条件")); runCurrent()
        rows.reverse();distribution[4]=99
        assertEquals(listOf("先返回","后返回"),model.state.value.stores.map{it.name})
        assertEquals(listOf(0,0,0,0,1),model.state.value.stores.first().reviewDistribution)
    }

    /** 旧错误晚到不能清空当前正确结果或让新筛选显示另一查询的重试文案。 */
    @Test fun lateOldFailureCannotReplaceCurrentSuccess()=runTest(dispatcher) {
        val old=CompletableDeferred<ApiResult<List<CatalogStore>>>()
        val model=CatalogViewModel(DelayedCatalog(old))
        model.load(CatalogQuery(keyword="旧条件"));runCurrent()
        model.load(CatalogQuery(keyword="新条件"));runCurrent()
        old.complete(ApiResult.Failure(ApiError.Network("合成旧失败")));runCurrent()
        assertEquals("新店",model.state.value.stores.single().name);assertNull(model.state.value.error)
    }
    /** 进程恢复只接收薄查询与公开行键；事实必须通过重新读取获得。 */
    @Test fun thinSavedStateRestoresQueryAndPublicAnchorWithoutBusinessRows()=runTest(dispatcher) {
        val saved=SavedStateHandle()
        val before=CatalogViewModel(DelayedCatalog(CompletableDeferred()),saved)
        before.load(CatalogQuery(keyword="新条件",category="火锅",sort="PRICE_DESC",cityCode="fixture",latitude=31.2,longitude=121.4));runCurrent()
        before.rememberAnchor(ListAnchor("store:00000000-0000-0000-0000-000000000002",-17))
        val after=CatalogViewModel(DelayedCatalog(CompletableDeferred()),saved)
        assertTrue(after.state.value.stores.isEmpty())
        after.initialize(CatalogQuery());runCurrent()
        assertEquals("新条件",after.state.value.query.keyword);assertEquals("PRICE_DESC",after.state.value.query.sort)
        assertEquals(ListAnchor("store:00000000-0000-0000-0000-000000000002",-17),after.anchor)
        assertEquals("新店",after.state.value.stores.single().name)
        assertTrue(saved.keys().none{it.contains("stores")})
    }
    /** 退出立即清私有集合；取消不合作的旧账号读取随后返回也不能重建旧订单。 */
    @Test fun logoutAndNewTokenRejectLatePrivateOrderRows()=runTest(dispatcher) {
        val session=MutableStateFlow<SessionState>(SessionState.Authenticated("synthetic-token-a",User("合成用户","synthetic@example.test")))
        val old=CompletableDeferred<ApiResult<List<GroupOrderSummary>>>()
        val repository=object:VoucherRepository by HttpVoucherRepository(ApiClient("http://127.0.0.1:1/",{null})) {
            override suspend fun listOrders():ApiResult<List<GroupOrderSummary>> = withContext(NonCancellable){old.await()}
        }
        val model=OrdersViewModel(repository,session);model.refresh();runCurrent()
        session.value=SessionState.Unauthenticated;runCurrent()
        assertTrue(model.state.value.orders.isEmpty());assertTrue(model.state.value.authRequired)
        session.value=SessionState.Authenticated("synthetic-token-b",User("合成用户","synthetic@example.test"));runCurrent()
        old.complete(ApiResult.Success(listOf(order("旧订单",1))));runCurrent()
        assertTrue(model.state.value.orders.isEmpty());assertFalse(model.state.value.loading)
        assertFalse(model.state.value.authRequired)
    }
    /** 交易返回显式刷新必须替换进入详情前的在途读取，旧资格最后到达仍不能覆盖更新。 */
    @Test fun transactionReturnRefreshWinsAgainstPreTransactionRead()=runTest(dispatcher) {
        val session=MutableStateFlow<SessionState>(SessionState.Authenticated("synthetic-token",User("合成用户","synthetic@example.test")))
        val old=CompletableDeferred<ApiResult<List<GroupOrderSummary>>>()
        var calls=0
        val repository=object:VoucherRepository by HttpVoucherRepository(ApiClient("http://127.0.0.1:1/",{null})) {
            override suspend fun listOrders():ApiResult<List<GroupOrderSummary>> = if(calls++==0)withContext(NonCancellable){old.await()}else ApiResult.Success(listOf(order("交易后",1).copy(status="PAID",canReview=true)))
        }
        val model=OrdersViewModel(repository,session);model.refresh();runCurrent()
        model.refresh();runCurrent()
        assertEquals(listOf("交易后"),model.state.value.orders.map{it.dealTitle})
        assertTrue(model.state.value.orders.single().canReview)
        old.complete(ApiResult.Success(listOf(order("交易前",1))));runCurrent()
        assertEquals("PAID",model.state.value.orders.single().status)
        assertTrue(model.state.value.orders.single().canReview)
    }
    /** 旧评价读取不配合取消仍须被代次挡住，不能覆盖刚切到的菜品和页签。 */
    @Test fun oldStoreTabCannotOverwriteNewDishRows()=runTest(dispatcher) {
        val old=CompletableDeferred<ApiResult<List<CatalogReview>>>()
        val repository=object:CatalogRepository by DelayedCatalog(CompletableDeferred()) {
            override suspend fun store(id:String,query:CatalogQuery)=ApiResult.Success(store("合成门店",1))
            override suspend fun reviews(id:String):ApiResult<List<CatalogReview>> = withContext(NonCancellable){old.await()}
            override suspend fun dishes(id:String)=ApiResult.Success(listOf(CatalogDish("当前菜品","合成说明",100,null,"00000000-0000-0000-0000-000000000003")))
        }
        val buying=object:GroupBuyingRepository by HttpGroupBuyingRepository(ApiClient("http://127.0.0.1:1/",{null})) {
            override suspend fun loadStoreDeals(storeId:String)=ApiResult.Success(emptyList<DealPackage>())
        }
        val model=StoreBrowseViewModel(repository,buying)
        model.initialize("00000000-0000-0000-0000-000000000001",CatalogQuery());runCurrent()
        model.select(StoreTab.REVIEWS);runCurrent()
        model.select(StoreTab.DISHES);runCurrent()
        old.complete(ApiResult.Success(listOf(CatalogReview("旧作者",4.0,"旧评价","2026-10-01T00:00:00Z","00000000-0000-0000-0000-000000000004","00000000-0000-0000-0000-000000000001"))));runCurrent()
        assertEquals(StoreTab.DISHES,model.state.value.tab)
        assertEquals("当前菜品",(model.state.value.rows.single() as StoreDetailRow.Dish).value.name)
        assertFalse(model.state.value.error)
    }
    /** 固定合成金额及独立公开订单号，测试变化不能改成静态示例事实交付生产。 */
    private fun order(title:String,n:Int)=GroupOrderSummary("00000000-0000-0000-0000-%012d".format(n),"00000000-0000-0000-0000-000000000099","00000000-0000-0000-0000-000000000098",1,100,"CREATED",0,emptyList(),dealTitle=title)

    /** 提供完整合成公开门店，未知事实为null；仅用于测试，不写入生产目录。 */
    private fun store(name:String,n:Int)=CatalogStore("00000000-0000-0000-0000-%012d".format(n),name,"fixture","火锅",100,"合成地址","合成时间",null,0,listOf(0,0,0,0,0),null,null,null,null)

    /** 非合作取消仅模拟外部依赖；当前查询成功值仍由真实ViewModel构造、验证及保存。 */
    private inner class DelayedCatalog(val old:CompletableDeferred<ApiResult<List<CatalogStore>>>,val current:List<CatalogStore> = listOf(store("新店",2))):CatalogRepository {
        override suspend fun stores(query:CatalogQuery):ApiResult<List<CatalogStore>> = if(query.keyword=="旧条件") withContext(NonCancellable){old.await()} else ApiResult.Success(current)
        override suspend fun store(id:String,query:CatalogQuery):ApiResult<CatalogStore> = error("此场景不得读详情")
        override suspend fun cities():ApiResult<List<CatalogCity>> = error("此场景不得读城市")
        override suspend fun dishes(id:String):ApiResult<List<CatalogDish>> = error("此场景不得读菜品")
        override suspend fun reviews(id:String):ApiResult<List<CatalogReview>> = error("此场景不得读评价")
    }
}
