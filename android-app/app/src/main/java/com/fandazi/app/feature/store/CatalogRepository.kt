// 团购目录网络模块：统一读取 Spring 公开浏览接口，依赖现有 ApiClient/Gson 与套餐解析器。
package com.fandazi.app.feature.store

import android.os.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.deal.DealPackagePayloadParser
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
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
data class CatalogDish(val name: String, val description: String, val priceCents: Int, val imageUrl: String?, val publicId: String)
/** 评价时间是 UTC 瞬间，视图按上海时区显示；无头像字段时使用明确头像占位。 */
data class CatalogReview(val reviewerName: String, val rating: Double, val content: String, val createdAt: String, val publicId: String, val storePublicId: String)

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
    /** 默认无缓存适配，既有实现及测试替身保持原单次读取；装饰器覆盖为缓存先显流。 */
    fun observeStores(query:CatalogQuery):kotlinx.coroutines.flow.Flow<com.fandazi.app.core.cache.CatalogRead<List<CatalogStore>>> = kotlinx.coroutines.flow.flow {emit(com.fandazi.app.core.cache.CatalogRead.online(stores(query)))}
    /** 摘要来源与事实一起交付，原错误/取消保留。 */
    fun observeStore(id:String,query:CatalogQuery):kotlinx.coroutines.flow.Flow<com.fandazi.app.core.cache.CatalogRead<CatalogStore>> = kotlinx.coroutines.flow.flow {emit(com.fandazi.app.core.cache.CatalogRead.online(store(id,query)))}
    /** 只读菜单来源流不引入交易入口。 */
    fun observeDishes(id:String):kotlinx.coroutines.flow.Flow<com.fandazi.app.core.cache.CatalogRead<List<CatalogDish>>> = kotlinx.coroutines.flow.flow {emit(com.fandazi.app.core.cache.CatalogRead.online(dishes(id)))}
    /** 公开评价来源流与私人反馈分离。 */
    fun observeReviews(id:String):kotlinx.coroutines.flow.Flow<com.fandazi.app.core.cache.CatalogRead<List<CatalogReview>>> = kotlinx.coroutines.flow.flow {emit(com.fandazi.app.core.cache.CatalogRead.online(reviews(id)))}

}

/** 复用认证、超时和 URL 配置；JSON 非法时显式失败，不把异常变成空业务结果。 */
class HttpCatalogRepository(
    private val api: ApiClient,
    // CPU解析独立于网络IO，默认后台；允许测试注入固定线程而不改变生产装配。
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CatalogRepository {
    private val gson = Gson()
    /** 查询公开目录，顺序保持服务端结果。 */
    override suspend fun stores(query: CatalogQuery) = api.get("stores?${query.parameters()}").decode { body, checkActive ->
        CatalogRowIdentity.stores(JsonParser.parseString(body).asJsonArray.map {
            // 每条观察取消，直接复用节点而不转成字符串重解析。
            checkActive(); parseStore(it.asJsonObject)
        })
    }
    /** 指定 UUID 的详情不存在时让 ApiClient 返回错误，禁止替换为首家门店。 */
    override suspend fun store(id: String, query: CatalogQuery) = api.get("stores/${encode(id)}?${query.parameters()}").decode { body, _ ->
        // 详情同时服务于摘要和局部图片刷新；响应身份必须与请求一致，不能展示另一资源的照片。
        parseStore(JsonParser.parseString(body).asJsonObject).also { require(it.publicId == id) { "门店详情身份无效" } }
    }
    /** 解析实际城市选项。 */
    override suspend fun cities() = api.get("stores/cities").decode { body, check -> parseList(body, CatalogCity::class.java, check) }
    /** 菜单仅使用当前门店的公开路径。 */
    override suspend fun dishes(id: String) = api.get("stores/${encode(id)}/dishes").decode { body, check -> CatalogRowIdentity.dishes(parseList(body, CatalogDish::class.java, check)) }
    /** 评价仅使用当前门店的公开路径。 */
    override suspend fun reviews(id: String) = api.get("stores/${encode(id)}/reviews").decode { body, check -> CatalogRowIdentity.reviews(parseList(body, CatalogReview::class.java, check),id) }

    /** 嵌套套餐保持原解析规则并核对门店归属；直接消费节点以去除重复字符串转换。 */
    private fun parseStore(value: JsonObject): CatalogStore {
        listOf("publicId", "name", "cityCode", "category", "averageSpendCents", "reviewCount", "reviewDistribution")
            .forEach { require(value.has(it) && !value[it].isJsonNull) { "目录字段缺失：$it" } }
        val base = gson.fromJson(value, CatalogStore::class.java)
        CatalogRowIdentity.publicId(base.publicId)
        val deal = value.get("featuredDeal")?.takeUnless { it.isJsonNull }?.let { DealPackagePayloadParser.parseDetail(it.asJsonObject) }
        require(deal == null || deal.storePublicId == base.publicId) { "套餐不属于当前门店" }
        return base.copy(featuredDeal = deal)
    }
    /** 泛型数组按各 DTO 解析，不制造补充字段。 */
    private fun <T> parseList(body: String, type: Class<T>, checkActive: () -> Unit): List<T> =
        JsonParser.parseString(body).asJsonArray.map { checkActive(); gson.fromJson(it, type) }

    /** 后台完成JSON转换与业务校验；取消原样传播，原HTTP和格式错误类别保持。 */
    private suspend fun <T> ApiResult<String>.decode(parser: (String, () -> Unit) -> T): ApiResult<T> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> withContext(parsingDispatcher) {
            // 输入已通过网络边界；只在当前作用域解析，trace核对线程且不记录响应正文。
            val context = currentCoroutineContext()
            context.ensureActive()
            Trace.beginSection("fdz.parse.catalog")
            try {
                val result = parser(value) { context.ensureActive() }
                // 结果构造前检查取消，旧页面工作不能以空列表或完整成功返回。
                context.ensureActive()
                ApiResult.Success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: Exception) {
                ApiResult.Failure(ApiError.Network("目录数据格式无效"))
            } finally {
                Trace.endSection()
            }
        }
    }

}

/** 编码公开路径和查询值，防止用户关键词中的 & 或 ? 改写其他筛选条件。 */
private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
