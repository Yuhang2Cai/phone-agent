// 团购目录网络模块：统一读取 Spring 公开浏览接口，依赖现有 ApiClient/Gson 与套餐解析器。
package com.fandazi.app.feature.store

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.deal.DealPackagePayloadParser
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URLEncoder

/** 独立查询状态；关键词、分类、排序、城市与坐标分别编码，禁止把控件文案当业务关键词。 */
data class CatalogQuery(val keyword: String = "", val category: String = "推荐", val sort: String = "RECOMMENDED",
    val cityCode: String? = null, val latitude: Double? = null, val longitude: Double? = null) {
    /** 只传当前明确条件；无位置不发坐标，无城市不默认 demo 或行政区代码。 */
    fun parameters(): String = listOf("keyword" to keyword.trim(), "category" to category.takeUnless { it == "推荐" },
        "sort" to sort, "cityCode" to cityCode, "latitude" to latitude?.toString(), "longitude" to longitude?.toString())
        .filter { !it.second.isNullOrBlank() }.joinToString("&") { (key, value) -> "$key=${encode(value!!)}" }
}

/** 真实门店页面 DTO；可空字段保留未知语义，不用零评分、假距离或默认套餐冒充。 */
data class CatalogStore(val publicId: String, val name: String, val cityCode: String, val category: String,
    val averageSpendCents: Int, val address: String, val openingHours: String, val rating: Double?,
    val reviewCount: Int, val reviewDistribution: List<Int>, val distanceMeters: Long?, val imageUrl: String?,
    val imageAlt: String?, val featuredDeal: DealPackage?)
/** 服务器返回的城市代码及可用显示名称。 */
data class CatalogCity(val cityCode: String, val name: String)
/** 公开菜单只用于展示，无外卖加购和库存写入能力。 */
data class CatalogDish(val name: String, val description: String, val priceCents: Int, val imageUrl: String?)
/** 评价时间是 UTC 瞬间，视图按上海时区显示；无头像字段时使用明确头像占位。 */
data class CatalogReview(val reviewerName: String, val rating: Double, val content: String, val createdAt: String)

/** 浏览网络边界，公开接口游客可读；网络和协议错误必须传给页面重试。 */
interface CatalogRepository {
    /** 按独立条件获取真实门店顺序，不在 Android 二次排序。 */
    suspend fun stores(query: CatalogQuery): ApiResult<List<CatalogStore>>
    /** 读取指定门店摘要，位置参数只用于服务器距离计算。 */
    suspend fun store(id: String, query: CatalogQuery): ApiResult<CatalogStore>
    /** 城市名单来自目录，失败不能显示硬编码名单。 */
    suspend fun cities(): ApiResult<List<CatalogCity>>
    /** 读取门店关联菜单，没有数据时返回服务器空列表。 */
    suspend fun dishes(id: String): ApiResult<List<CatalogDish>>
    /** 读取服务器最新评价，数量摘要由门店接口的全量统计提供。 */
    suspend fun reviews(id: String): ApiResult<List<CatalogReview>>
}

/** 复用认证、超时和 URL 配置；JSON 非法时显式失败，不把异常变成空业务结果。 */
class HttpCatalogRepository(private val api: ApiClient) : CatalogRepository {
    private val gson = Gson()
    /** 查询公开目录，顺序保持服务端结果。 */
    override suspend fun stores(query: CatalogQuery) = api.get("stores?${query.parameters()}").decode { body ->
        JsonParser.parseString(body).asJsonArray.map { parseStore(it.toString()) }
    }
    /** 指定 UUID 的详情不存在时让 ApiClient 返回错误，禁止替换为首家门店。 */
    override suspend fun store(id: String, query: CatalogQuery) = api.get("stores/${encode(id)}?${query.parameters()}").decode(::parseStore)
    /** 解析实际城市选项。 */
    override suspend fun cities() = api.get("stores/cities").decode { body -> parseList(body, CatalogCity::class.java) }
    /** 菜单仅使用当前门店的公开路径。 */
    override suspend fun dishes(id: String) = api.get("stores/${encode(id)}/dishes").decode { body -> parseList(body, CatalogDish::class.java) }
    /** 评价仅使用当前门店的公开路径。 */
    override suspend fun reviews(id: String) = api.get("stores/${encode(id)}/reviews").decode { body -> parseList(body, CatalogReview::class.java) }

    /** 规范化摘要嵌套套餐使用已存在解析器，避免 Gson 将缺失业务字段默认为零。 */
    private fun parseStore(body: String): CatalogStore {
        val value = JsonParser.parseString(body).asJsonObject
        listOf("publicId", "name", "cityCode", "category", "averageSpendCents", "reviewCount", "reviewDistribution")
            .forEach { require(value.has(it) && !value[it].isJsonNull) { "目录字段缺失：$it" } }
        val base = gson.fromJson(value, CatalogStore::class.java)
        java.util.UUID.fromString(base.publicId)
        val deal = value.get("featuredDeal")?.takeUnless { it.isJsonNull }?.let { DealPackagePayloadParser.parseDetail(it.toString()) }
        require(deal == null || deal.storePublicId == base.publicId) { "套餐不属于当前门店" }
        return base.copy(featuredDeal = deal)
    }
    /** 泛型数组按各 DTO 解析，不制造补充字段。 */
    private fun <T> parseList(body: String, type: Class<T>): List<T> = JsonParser.parseString(body).asJsonArray.map { gson.fromJson(it, type) }
    /** 成功载荷格式错误转换为可重试状态；原 HTTP 错误保持不变。 */
    private fun <T> ApiResult<String>.decode(parse: (String) -> T): ApiResult<T> = when (this) {
        is ApiResult.Success -> runCatching { ApiResult.Success(parse(value)) }.getOrElse { ApiResult.Failure(ApiError.Network("目录数据格式无效")) }
        is ApiResult.Failure -> this
    }
}

/** 编码公开路径和查询值，防止用户关键词中的 & 或 ? 改写其他筛选条件。 */
private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
