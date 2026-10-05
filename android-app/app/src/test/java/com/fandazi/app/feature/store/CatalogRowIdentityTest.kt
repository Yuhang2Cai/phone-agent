// 六类列表身份边界测试：真实HTTP及生产解析器拒绝缺失/重复公开身份，避免回收绑定到错误资源。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 缺ID不能按名称补齐，重复ID不能按位置区别；接口错误必须保留为失败。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CatalogRowIdentityTest {
    /** 原Gson默认解析会把缺失身份的条目当成功，这个断言阻止无稳定身份进入回收列表。 */
    @Test fun missingDishAndReviewIdentityFailsClosed()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val repo=HttpCatalogRepository(ApiClient(server.url("/api/").toString(),{null}))
            server.enqueue(MockResponse().setBody("""[{"name":"合成菜","description":"合成说明","priceCents":100,"imageUrl":null}]"""))
            server.enqueue(MockResponse().setBody("""[{"reviewerName":"合成作者","rating":4,"content":"合成评价","createdAt":"2026-10-01T00:00:00Z"}]"""))
            assertTrue("无菜单ID必须失败",repo.dishes(STORE) is ApiResult.Failure)
            assertTrue("无评价ID必须失败",repo.reviews(STORE) is ApiResult.Failure)
        } finally {server.shutdown()}
    }
    /** 同一公开身份重复投影会破坏Diff与点击目标，不能靠去重静默丢掉后端条目。 */
    @Test fun duplicatePublicIdentityIsProtocolFailure()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val repo=HttpCatalogRepository(ApiClient(server.url("/api/").toString(),{null}))
            val row="""{"publicId":"$STORE","name":"合成门店","cityCode":"fixture","category":"火锅","averageSpendCents":100,"address":"合成地址","openingHours":"合成时间","rating":null,"reviewCount":0,"reviewDistribution":[0,0,0,0,0],"distanceMeters":null,"imageUrl":null,"imageAlt":null,"featuredDeal":null}"""
            server.enqueue(MockResponse().setBody("[$row,$row]"))
            assertTrue(repo.stores(CatalogQuery()) is ApiResult.Failure)
        } finally {server.shutdown()}
    }
    /** 同名同价必须保留两个独立公开UUID，服务端倒序不能被客户端重新排列或合并。 */
    @Test fun sameNameRowsRetainIndependentIdentityAndBackendOrder() {
        val first=CatalogDish("同名菜", "同一说明", 100, null, "00000000-0000-0000-0000-000000000002")
        val second=first.copy(publicId=STORE)
        val source=mutableListOf(first,second)
        val actual=CatalogRowIdentity.dishes(source)
        source.reverse()
        assertEquals(listOf("00000000-0000-0000-0000-000000000002",STORE),actual.map{it.publicId})
        assertEquals(2,actual.size)
        try { CatalogRowIdentity.dishes(listOf(first,first));fail("重复菜品UUID应拒绝") } catch(expected:IllegalArgumentException) { assertEquals("重复公开身份",expected.message) }
        val foreign=CatalogReview("合成作者",4.0,"合成内容","2026-10-01T00:00:00Z",STORE,"00000000-0000-0000-0000-000000000002")
        try { CatalogRowIdentity.reviews(listOf(foreign),STORE);fail("错误门店评价应拒绝") } catch(expected:IllegalArgumentException) { assertEquals("评价门店归属无效",expected.message) }
    }
    /** 时间字符串会在真实评价Holder转换，非法时间应在协议边界返回失败而不是进入主线程崩溃。 */
    @Test fun invalidReviewTimestampIsProtocolFailure()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val repo=HttpCatalogRepository(ApiClient(server.url("/api/").toString(),{null}))
            server.enqueue(MockResponse().setBody("""[{"publicId":"$STORE","storePublicId":"$STORE","reviewerName":"合成作者","rating":4,"content":"合成内容","createdAt":"invalid"}]"""))
            assertTrue("非法评价时间必须在协议边界失败",repo.reviews(STORE) is ApiResult.Failure)
        } finally {server.shutdown()}
    }
    /** 请求门店A却收到门店B时必须失败，不能让卡片图片元数据变成另一门店照片。 */
    @Test fun storeDetailMustMatchRequestedPublicIdentity()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val repo=HttpCatalogRepository(ApiClient(server.url("/api/").toString(),{null}))
            server.enqueue(MockResponse().setBody("""{"publicId":"00000000-0000-0000-0000-000000000002","name":"错误门店","cityCode":"fixture","category":"火锅","averageSpendCents":100,"address":"合成地址","openingHours":"合成时间","rating":null,"reviewCount":0,"reviewDistribution":[0,0,0,0,0],"distanceMeters":null,"imageUrl":null,"imageAlt":null,"featuredDeal":null}"""))
            assertTrue("详情公开身份必须匹配请求",repo.store(STORE,CatalogQuery()) is ApiResult.Failure)
        } finally {server.shutdown()}
    }
    companion object {
        /** 合成公开UUID只用于HTTP夹具，不代表任何真实门店或用户。 */
        const val STORE="00000000-0000-0000-0000-000000000001"
    }
}
