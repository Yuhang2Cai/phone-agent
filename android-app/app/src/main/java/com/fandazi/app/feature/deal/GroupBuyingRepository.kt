package com.fandazi.app.feature.deal

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 团购目录查询接口，屏蔽页面对 HTTP 路径和 JSON 字段的依赖。 */
interface GroupBuyingRepository {
    /** 查询门店当前可售套餐，返回顺序由后端排序结果决定。 */
    suspend fun loadStoreDeals(storePublicId: String): ApiResult<List<DealPackage>>

    /** 按公开套餐 ID 查询详情，禁止由页面自行拼接默认套餐。 */
    suspend fun loadDeal(dealPublicId: String): ApiResult<DealPackage>
}

/** 通过现有 ApiClient 访问团购目录接口。 */
class HttpGroupBuyingRepository(private val apiClient: ApiClient) : GroupBuyingRepository {
    override suspend fun loadStoreDeals(storePublicId: String): ApiResult<List<DealPackage>> =
        apiClient.get("stores/${storePublicId.urlEncoded()}/deals").mapJson(DealPackagePayloadParser::parseList)

    override suspend fun loadDeal(dealPublicId: String): ApiResult<DealPackage> =
        apiClient.get("deals/${dealPublicId.urlEncoded()}").mapJson(DealPackagePayloadParser::parseDetail)

    /** 路径参数必须编码，避免公开 ID 中出现特殊字符时改变请求路径。 */
    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}

/** 解析后端套餐 JSON，供仓库和单元测试共同使用。 */
object DealPackagePayloadParser {
    /** 解析门店套餐数组，并保留服务端返回的每个套餐身份。 */
    fun parseList(body: String): List<DealPackage> =
        JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.toDealPackage() }

    /** 解析单个套餐详情，缺失字段由领域模型使用安全默认值承接。 */
    fun parseDetail(body: String): DealPackage =
        JsonParser.parseString(body).asJsonObject.toDealPackage()

    /** 将接口对象字段映射为客户端领域模型，金额和库存不在此处修改。 */
    private fun JsonObject.toDealPackage() = DealPackage(
        publicId = string("publicId"),
        storePublicId = string("storePublicId"),
        title = string("title"),
        description = string("description"),
        priceCents = int("priceCents"),
        originalPriceCents = int("originalPriceCents"),
        inventory = int("inventory"),
        validFrom = string("validFrom"),
        validTo = string("validTo"),
        active = bool("active"),
        purchaseLimit = int("purchaseLimit"),
    )

    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.int(name: String): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
}

/** 将 HTTP 字符串结果安全转换成领域对象，解析异常不能伪装成空套餐。 */
private fun <T> ApiResult<String>.mapJson(parser: (String) -> T): ApiResult<T> = when (this) {
    is ApiResult.Success -> runCatching { ApiResult.Success(parser(value)) }.getOrElse {
        ApiResult.Failure(ApiError.Network("套餐数据格式无效：${it.message.orEmpty()}"))
    }
    is ApiResult.Failure -> this
}
