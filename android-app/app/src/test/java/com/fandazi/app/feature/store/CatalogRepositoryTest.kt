// Task4 目录网络回归模块：实际执行 ApiClient/Gson，MockWebServer 只替换外部 HTTP，不测试 XML 先行。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 独立参数、未知事实、错误/空结果与公开身份回归，不向生产目录塞任何测试行。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: CatalogRepository

    /** 每项建立隔离真实 HTTP 端点，避免借用运行中 MySQL 或设备业务数据。 */
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = HttpCatalogRepository(ApiClient(server.url("/api/").toString(), { null }))
    }
    /** 关闭本项 HTTP 服务，不遗留后台线程影响后续 Android 测试。 */
    @After fun cleanup() { server.shutdown() }

    /** 用户关键词里的 &、? 不得改写分类/排序/城市，无位置不得发送默认坐标。 */
    @Test fun independentQueryEscapesKeywordAndDoesNotInventLocation() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        repository.stores(CatalogQuery(keyword = "品牌&sort=DISTANCE?", category = "火锅", sort = "PRICE_DESC", cityCode = "city-from-api"))
        val request = server.takeRequest().requestUrl!!
        assertEquals("品牌&sort=DISTANCE?", request.queryParameter("keyword"))
        assertEquals("火锅", request.queryParameter("category"))
        assertEquals("PRICE_DESC", request.queryParameter("sort"))
        assertEquals("city-from-api", request.queryParameter("cityCode"))
        assertNull(request.queryParameter("latitude")); assertNull(request.queryParameter("longitude"))
    }

    /** 服务器空列表是成功事实，HTTP错误仍是失败，不由客户端补造门店兜底。 */
    @Test fun emptyAndFailureRemainDifferentStates() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val empty = repository.stores(CatalogQuery()) as ApiResult.Success
        assertTrue(empty.value.isEmpty())
        assertTrue(repository.stores(CatalogQuery()) is ApiResult.Failure)
    }

    /** 合法 UUID 和 null 事实完整保留，不能把缺评价/图片/套餐/距离自动变成零值或示例。 */
    @Test fun actualIdentityAndNullableFactsRemainServerOwned() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{
            "publicId":"cfb41344-6cc4-4c36-9151-ebdff215f438","name":"合成测试门店","cityCode":"合成代码",
            "category":"火锅","averageSpendCents":3456,"address":"合成地址","openingHours":"合成时间",
            "rating":null,"reviewCount":0,"reviewDistribution":[0,0,0,0,0],"distanceMeters":null,
            "imageUrl":null,"imageAlt":null,"featuredDeal":null}]"""))
        val row = (repository.stores(CatalogQuery()) as ApiResult.Success).value.single()
        assertEquals("cfb41344-6cc4-4c36-9151-ebdff215f438", row.publicId)
        assertEquals(3456, row.averageSpendCents)
        assertNull(row.rating); assertNull(row.distanceMeters); assertNull(row.featuredDeal); assertNull(row.imageUrl)
    }

    /** 缺必需业务字段或旧演示 ID 必须解析失败，不能靠 Gson 默认零值给页面伪造成功。 */
    @Test fun missingFieldsAreProtocolFailureInsteadOfEmptySuccess() = runBlocking {
        server.enqueue(MockResponse().setBody("[{\"publicId\":\"store-001\",\"name\":\"旧示例\"}]"))
        assertTrue(repository.stores(CatalogQuery()) is ApiResult.Failure)
    }
}
