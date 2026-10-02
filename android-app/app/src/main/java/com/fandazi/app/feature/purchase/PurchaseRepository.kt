// 团购购买数据边界：依赖统一 ApiClient，严格接收后端报价、订单和券事实，不计算业务金额。
package com.fandazi.app.feature.purchase

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 五分钟报价及展示事实；数量上下限、价格和授权全部来自服务器。 */
data class PurchaseQuote(val quoteId: String, val dealId: String, val quantity: Int, val subtotalCents: Int,
    val discountCents: Int, val totalCents: Int, val expiresAt: String, val confirmationGrant: String,
    val dealVersion: Int, val dealTitle: String, val storeName: String, val usageRules: String,
    val minQuantity: Int, val maxQuantity: Int, val imageUrl: String?, val imageAlt: String?)

/** 真实券身份和期限，成功页不得生成示例券码。 */
data class PurchaseVoucher(val publicId: String, val expiresAt: String)

/** 后端订单快照；支付页与成功页只按身份回读，不接收导航金额。 */
data class GroupBuyOrder(val orderId: String, val dealId: String, val storeId: String, val quantity: Int,
    val totalCents: Int, val status: String, val voucherCount: Int, val dealTitle: String = "",
    val storeName: String = "", val imageUrl: String? = null, val imageAlt: String? = null,
    val vouchers: List<PurchaseVoucher> = emptyList())

/** 购买接口：准备不写订单，提交只引用报价，回读与支付复核认证。 */
interface PurchaseRepository {
    /** 取得本人数量对应的完整报价，失败返回类型化错误。 */
    suspend fun createQuote(dealId: String, quantity: Int, sessionId: String): ApiResult<PurchaseQuote>
    /** 消费已展示报价的凭证，同一报价重试保持相同幂等键。 */
    suspend fun createOrder(quote: PurchaseQuote, sessionId: String, idempotencyKey: String): ApiResult<GroupBuyOrder>
    /** 模拟支付本人订单，不连接真实支付渠道。 */
    suspend fun mockPay(order: GroupBuyOrder, idempotencyKey: String): ApiResult<GroupBuyOrder>
    /** 恢复服务端订单快照，不用导航数据假造状态。 */
    suspend fun getOrder(orderId: String): ApiResult<GroupBuyOrder>
}

/** 使用共享认证客户端访问购买领域，解析异常关闭确认而不补零金额。 */
class HttpPurchaseRepository(private val api: ApiClient, private val gson: Gson = Gson()) : PurchaseRepository {
    /** 发送公开套餐、数量与会话，价格由服务端确定。 */
    override suspend fun createQuote(dealId: String, quantity: Int, sessionId: String): ApiResult<PurchaseQuote> =
        api.postJson("purchases/quotes", gson.toJson(mapOf("dealId" to dealId, "quantity" to quantity, "sessionId" to sessionId))).parse(PurchasePayload::quote)

    /** 只提交报价编号及一次性凭证，防止相同金额替换套餐或数量。 */
    override suspend fun createOrder(quote: PurchaseQuote, sessionId: String, idempotencyKey: String): ApiResult<GroupBuyOrder> =
        api.postJson("group-orders", gson.toJson(mapOf("quoteId" to quote.quoteId, "confirmationGrant" to quote.confirmationGrant,
            "sessionId" to sessionId, "idempotencyKey" to idempotencyKey))).parse(PurchasePayload::order)

    /** 支付使用订单快照，重试不会创建新的订单。 */
    override suspend fun mockPay(order: GroupBuyOrder, idempotencyKey: String): ApiResult<GroupBuyOrder> =
        api.postJson("group-orders/${order.orderId}/mock-pay", gson.toJson(mapOf("idempotencyKey" to idempotencyKey))).parse(PurchasePayload::order)

    /** 查询本人订单和券集合，越权、缺失或认证失败保留 HTTP 错误。 */
    override suspend fun getOrder(orderId: String): ApiResult<GroupBuyOrder> = api.get("group-orders/$orderId").parse(PurchasePayload::order)
}

/** 核心字段缺失、非整数金额或不一致均拒绝确认，不进行安全默认值补齐。 */
object PurchasePayload {
    /** 解析后端报价并检查展示金额守恒；只验证响应，不推断业务优惠资格。 */
    fun quote(body: String): PurchaseQuote {
        val r = JsonParser.parseString(body).asJsonObject
        val q = PurchaseQuote(r.required("quoteId"), r.required("dealId"), r.integer("quantity"), r.integer("subtotalCents"),
            r.integer("discountCents"), r.integer("totalCents"), r.required("expiresAt"), r.required("confirmationGrant"),
            r.integer("dealVersion"), r.required("dealTitle"), r.required("storeName"), r.required("usageRules"),
            r.integer("minQuantity"), r.integer("maxQuantity"), r.optional("imageUrl"), r.optional("imageAlt"))
        // 期限和边界合法才能确认；金额差值使用长整数防止校验自身溢出。
        java.time.Instant.parse(q.expiresAt)
        require(q.totalCents >= 0 && q.discountCents >= 0 && q.subtotalCents.toLong() - q.discountCents == q.totalCents.toLong())
        require(q.minQuantity >= 1 && q.quantity in q.minQuantity..q.maxQuantity)
        return q
    }

    /** 解析完整订单和券身份，券数必须与实际列表一致。 */
    fun order(body: String): GroupBuyOrder {
        val r = JsonParser.parseString(body).asJsonObject
        val vouchers = requireNotNull(r.getAsJsonArray("vouchers")).map { row ->
            row.asJsonObject.let { PurchaseVoucher(it.required("publicId"), it.required("expiresAt")) }
        }
        val result = GroupBuyOrder(r.required("orderId"), r.required("dealId"), r.required("storeId"), r.integer("quantity"),
            r.integer("totalCents"), r.required("status"), r.integer("voucherCount"), r.required("dealTitle"),
            r.required("storeName"), r.optional("imageUrl"), r.optional("imageAlt"), vouchers)
        require(result.quantity > 0 && result.totalCents >= 0 && result.voucherCount == vouchers.size)
        return result
    }

    /** 核心文本不得缺失或空白，避免假成功摘要。 */
    private fun JsonObject.required(key: String): String = requireNotNull(optional(key)?.takeIf(String::isNotBlank))
    /** 非整数或溢出金额拒绝，不让 Gson 截断小数或补零。 */
    private fun JsonObject.integer(key: String): Int = requireNotNull(get(key)).asBigDecimal.intValueExact()
    /** 图片允许缺失，供统一图片组件展示明确缺图状态。 */
    private fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
}

/** 精确格式化服务端整数分，不参与价格或资格计算。 */
fun purchaseMoney(cents: Int): String = "¥${java.math.BigDecimal.valueOf(cents.toLong(), 2).stripTrailingZeros().toPlainString()}"

/** 解析失败返回可重试错误，不冒充零金额或空订单。 */
private inline fun <T> ApiResult<String>.parse(parser: (String) -> T): ApiResult<T> = when (this) {
    is ApiResult.Success -> runCatching { ApiResult.Success(parser(value)) }.getOrElse { ApiResult.Failure(ApiError.Network("购买响应不完整，请重试")) }
    is ApiResult.Failure -> this
}
