// 套餐读取模块：后台构造公开DTO，目录嵌套套餐复用节点，依赖ApiClient/Gson。
package com.fandazi.app.feature.deal

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
class HttpGroupBuyingRepository(
    private val apiClient: ApiClient,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GroupBuyingRepository {
    /** 后台转换有序数组，每条检查取消，不在端上重排。 */
    override suspend fun loadStoreDeals(storePublicId: String): ApiResult<List<DealPackage>> =
        apiClient.get("stores/${storePublicId.urlEncoded()}/deals").mapJson { body, checkActive -> DealPackagePayloadParser.parseList(body, checkActive) }

    /** 后台解析单套餐，身份与HTTP错误由原接口保持。 */
    override suspend fun loadDeal(dealPublicId: String): ApiResult<DealPackage> =
        apiClient.get("deals/${dealPublicId.urlEncoded()}").mapJson { body, _ -> DealPackagePayloadParser.parseDetail(body) }

    /** 路径参数必须编码，避免公开 ID 中出现特殊字符时改变请求路径。 */
    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
    /** 后台完成JSON转换与业务校验；取消原样传播，原HTTP和格式错误类别保持。 */
    private suspend fun <T> ApiResult<String>.mapJson(parser: (String, () -> Unit) -> T): ApiResult<T> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> withContext(parsingDispatcher) {
            // 输入已通过网络边界；只在当前作用域解析，trace核对线程且不记录响应正文。
            val context = currentCoroutineContext()
            context.ensureActive()
            Trace.beginSection("fdz.parse.deals")
            try {
                val result = parser(value) { context.ensureActive() }
                // 结果构造前检查取消，旧页面工作不能以空列表或完整成功返回。
                context.ensureActive()
                ApiResult.Success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: Exception) {
                ApiResult.Failure(ApiError.Network("套餐数据格式无效："+invalid.message.orEmpty()))
            } finally {
                Trace.endSection()
            }
        }
    }

}

/** 解析后端套餐 JSON，供仓库和单元测试共同使用。 */
object DealPackagePayloadParser {
    /** 解析门店套餐数组，并保留服务端返回的每个套餐身份。 */
    fun parseList(body: String): List<DealPackage> = parseList(body) {}

    /** 批量入口接受取消检查，纯字符串调用原签名和默认行为保持。 */
    internal fun parseList(body: String, checkActive: () -> Unit): List<DealPackage> =
        JsonParser.parseString(body).asJsonArray.map { checkActive(); parseDetail(it.asJsonObject) }

    /** 解析单个套餐详情，缺失字段由领域模型使用安全默认值承接。 */
    fun parseDetail(body: String): DealPackage =
        parseDetail(JsonParser.parseString(body).asJsonObject)

    /** 嵌套套餐直接复用节点；不重序列化，字段默认值与金额转换规则保持。 */
    internal fun parseDetail(value: JsonObject): DealPackage = value.toDealPackage()

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
        // 保留服务器缺图语义，供目录及套餐页面加载真实原图或明确占位。
        imageUrl = get("imageUrl")?.takeUnless { it.isJsonNull }?.asString,
        imageAlt = get("imageAlt")?.takeUnless { it.isJsonNull }?.asString,
    )

    /** 文本缺失保持原默认值，本次不改变协议。 */
    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    /** 金额数量沿用原转换，调用方业务校验保持。 */
    private fun JsonObject.int(name: String): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
    /** 启用字段缺失保持原默认值，不猜服务器资格。 */
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
}
