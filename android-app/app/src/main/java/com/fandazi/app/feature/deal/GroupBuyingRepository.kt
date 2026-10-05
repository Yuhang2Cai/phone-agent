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

    /** 解析单个套餐详情，必需字段缺失或类型错误直接拒绝；不能把缺价冒充零元事实。 */
    fun parseDetail(body: String): DealPackage =
        parseDetail(JsonParser.parseString(body).asJsonObject)

    /** 嵌套套餐直接复用节点；不重序列化，必需字段先做存在性和类型校验。 */
    internal fun parseDetail(value: JsonObject): DealPackage {
        // 输入校验：Spring公开DTO中的必需文字必须存在且为字符串，图片仍保留可空语义。
        listOf("publicId","storePublicId","title","description","validFrom","validTo").forEach { key ->
            val field=value.get(key);require(field!=null && field.isJsonPrimitive && field.asJsonPrimitive.isString){"套餐文字字段无效：$key"}
        }
        // 数字必须是明确整数分/数量，不能让Gson将缺字段、数字字符串或小数截断成默认值。
        listOf("priceCents","originalPriceCents","inventory","purchaseLimit").forEach { key ->
            val field=value.get(key);require(field!=null && field.isJsonPrimitive && field.asJsonPrimitive.isNumber){"套餐数字字段无效：$key"}
            field.asBigDecimal.intValueExact()
        }
        val active=value.get("active");require(active!=null && active.isJsonPrimitive && active.asJsonPrimitive.isBoolean){"套餐启用字段无效"}
        // 新增可空事实先严格校验类型和规模；未知保留null，非法零人数不能被当作可信除数。
        value.get("peopleCount")?.takeUnless{it.isJsonNull}?.let{require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.intValueExact() in 1..10000){"套餐人数字段无效"}}
        listOf("usageRules","storeName").forEach{key->value.get(key)?.takeUnless{it.isJsonNull}?.let{require(it.isJsonPrimitive && it.asJsonPrimitive.isString){"套餐对比文字无效"}}}
        value.get("contents")?.takeUnless{it.isJsonNull}?.let{rows->
            require(rows.isJsonArray && rows.asJsonArray.size()<=100){"套餐内容字段无效"}
            rows.asJsonArray.forEach{item->require(item.isJsonObject){"套餐内容项无效"};val row=item.asJsonObject;val name=row.get("name");val quantity=row.get("quantity");require(name!=null && name.isJsonPrimitive && name.asJsonPrimitive.isString && name.asString.isNotBlank()){"套餐内容名称无效"};require(quantity!=null && quantity.isJsonPrimitive && quantity.asJsonPrimitive.isNumber && quantity.asBigDecimal.intValueExact() in 1..10000){"套餐内容份数无效"}}
        }
        // 结果构造仅映射服务器原字段；UUID和门店归属由各调用流程核对，解析不替换资源。
        return value.toDealPackage()
    }

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
        peopleCount = get("peopleCount")?.takeUnless{it.isJsonNull}?.asInt,
        contents = get("contents")?.takeUnless{it.isJsonNull}?.asJsonArray?.map{row->DealContent(row.asJsonObject.get("name").asString,row.asJsonObject.get("quantity").asInt)},
        usageRules = get("usageRules")?.takeUnless{it.isJsonNull}?.asString,
        storeName = get("storeName")?.takeUnless{it.isJsonNull}?.asString,
    )

    /** 必需文本已通过前置校验；此处读取原值，不补造文案。 */
    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    /** 必需整数已通过前置精确校验；零值保留服务器原义。 */
    private fun JsonObject.int(name: String): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
    /** 布尔字段已通过前置类型校验，不从其他字段猜资格。 */
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
}
