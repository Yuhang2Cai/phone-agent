// 后台解析回归：真实HTTP载荷与Gson转换记录实际线程，防止只移动网络等待却仍在调用线程批量解析。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import com.fandazi.app.feature.voucher.HttpVoucherRepository
import com.fandazi.app.feature.deal.HttpGroupBuyingRepository
import kotlin.coroutines.CoroutineContext

/** 单线程调用方模拟UI，记录真正DTO转换所在的线程；所有载荷为隔离合成数据，不接业务数据库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class RepositoryParsingDispatcherTest {
    /** 30条目录必须保持顺序和未知字段，并在后台线程解析。 */
    @Test fun thirtyRowsParseOffCaller() = verifyRows(30)
    /** 100条目录覆盖中等载荷，不能仅少量条目被切换到后台。 */
    @Test fun hundredRowsParseOffCaller() = verifyRows(100)
    /** 500条目录覆盖最长夹具，全部转换离开调用线程。 */
    @Test fun fiveHundredRowsParseOffCaller() = verifyRows(500)

    /** 券订单与套餐同样消费可注入后台dispatcher；三种载荷的身份、金额和顺序均保持。 */
    @Test fun voucherAndDealArraysUseInjectedWorkerAndKeepFacts() = runBlocking {
        val server=MockWebServer().also { it.start() }
        try { newSingleThreadContext("fixture-parser").use { worker ->
            val dispatch=RecordingDispatcher(worker)
            val api=ApiClient(server.url("/api/").toString(),{null})
            val vouchers=HttpVoucherRepository(api,parsingDispatcher=dispatch)
            val deals=HttpGroupBuyingRepository(api,parsingDispatcher=dispatch)
            for(count in listOf(30,100,500)) {
                server.enqueue(MockResponse().setBody((0 until count).joinToString(prefix="[",postfix="]") { i -> order(i) }))
                val orders=(vouchers.listOrders() as ApiResult.Success).value
                assertEquals(count,orders.size)
                assertEquals((0 until count).map { "o$it" },orders.map { it.orderId })
                assertTrue(orders.all { it.paidTotalCents==123 && it.vouchers.single().amountCents==123 && !it.canReview })
                // 使用后端完整必需DTO，不能用缺字段默认值绕过新可信详情边界；原顺序/金额/线程断言保持。
                server.enqueue(MockResponse().setBody((0 until count).joinToString(prefix="[",postfix="]") { i ->
                    """{"publicId":"d$i","storePublicId":"s","title":"合成套餐$i","description":"合成说明","priceCents":123,"originalPriceCents":200,"inventory":10,"validFrom":"2026-01-01","validTo":"2030-12-31","active":true,"purchaseLimit":2}"""
                }))
                val rows=(deals.loadStoreDeals("s") as ApiResult.Success).value
                assertEquals(count,rows.size);assertEquals("d${count-1}",rows.last().publicId)
                assertTrue(rows.all { it.priceCents==123 })
            }
            assertEquals(6,dispatch.names.size)
            assertTrue(dispatch.names.all { it.startsWith("fixture-parser") })
        } } finally { server.shutdown() }
    }

    /** 只观察注入解析调度器实际执行线程，网络IO不会使用该调度器，避免只检查配置值。 */
    private class RecordingDispatcher(private val worker:CoroutineDispatcher):CoroutineDispatcher() {
        val names=CopyOnWriteArrayList<String>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {
            worker.dispatch(context,Runnable { names+=Thread.currentThread().name;block.run() })
        }
    }

    /** 一个合法订单对应一张券，实付整数分守恒且所属身份一致；仅用于HTTP解析。 */
    private fun order(i:Int)="""{"orderId":"o$i","dealId":"d","storeId":"s","storeName":"合成门店","dealTitle":"合成套餐","quantity":1,"totalCents":123,"paidTotalCents":123,"status":"PAID","voucherCount":1,"canReview":false,"vouchers":[{"publicId":"v$i","orderId":"o$i","dealId":"d","storeId":"s","storeName":"合成门店","dealTitle":"合成套餐","status":"UNUSED","canRefund":false,"amountCents":123}]}"""

    /** 第一条转换触发取消后，后续499条不得继续消耗CPU或形成空成功。 */
    @Test fun cancellationStopsLongArrayConversion() = runBlocking {
        val server=MockWebServer().also { it.start() };val rows=AtomicInteger();var task:Job?=null
        try {
            server.enqueue(MockResponse().setBody(payload(500)))
            val repository=HttpCatalogRepository(ApiClient(server.url("/api/").toString(), {null}))
            installRecorder(repository) { if(rows.incrementAndGet()==1) task!!.cancel() }
            newSingleThreadContext("fixture-ui").use { caller ->
                task=launch(caller, start=CoroutineStart.LAZY) { repository.stores(CatalogQuery()) }
                task!!.start();task!!.join()
            }
            assertEquals("取消后仍批量转换后续行",1,rows.get())
            assertTrue(task!!.isCancelled)
        } finally { server.shutdown() }
    }

    /** 真实Gson转换拦截仅记录线程并交给原Gson，不用假仓库替代协议解析。 */
    private fun verifyRows(count:Int)=runBlocking {
        val server=MockWebServer().also { it.start() };val names=CopyOnWriteArrayList<String>()
        try {
            server.enqueue(MockResponse().setBody(payload(count)))
            val repository=HttpCatalogRepository(ApiClient(server.url("/api/").toString(), {null}))
            installRecorder(repository) { names+=Thread.currentThread().name }
            val result=newSingleThreadContext("fixture-ui").use { caller -> withContext(caller) { repository.stores(CatalogQuery()) } }
            val rows=(result as ApiResult.Success).value
            assertEquals(count,rows.size);assertEquals(count,names.size)
            assertEquals((0 until count).map { "合成门店$it" },rows.map { it.name })
            assertTrue(rows.all { it.featuredDeal==null && it.rating==null && it.distanceMeters==null })
            // 协程调试可能给线程追加@coroutine编号，按固定调用线程前缀识别，不能误判为后台线程。
            assertTrue("批量DTO解析仍在调用线程：${names.first()}",names.none { it.startsWith("fixture-ui") })
        } finally { server.shutdown() }
    }

    /** 仅替换已有私有Gson字段的观察器，原实现无需先改构造函数即可建立真实失败断言。 */
    private fun installRecorder(repository:HttpCatalogRepository, record:()->Unit) {
        val original=Gson()
        val gson=GsonBuilder().registerTypeAdapter(CatalogStore::class.java, JsonDeserializer<CatalogStore> { value,_,_ ->
            record();original.fromJson(value,CatalogStore::class.java)
        }).create()
        repository.javaClass.getDeclaredField("gson").apply { isAccessible=true }.set(repository,gson)
    }

    /** 确定性UUID和文本形成30/100/500相同形状载荷，无图片/评分/位置等伪事实。 */
    private fun payload(count:Int)=(0 until count).joinToString(prefix="[",postfix="]") { index ->
        """{"publicId":"00000000-0000-0000-0000-${index.toString().padStart(12,'0')}","name":"合成门店$index","cityCode":"fixture","category":"火锅","averageSpendCents":3456,"reviewCount":0,"reviewDistribution":[0,0,0,0,0],"rating":null,"distanceMeters":null,"featuredDeal":null,"address":"合成地址","openingHours":"合成时间"}"""
    }
}
