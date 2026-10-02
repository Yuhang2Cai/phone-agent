// 团购券模块：封装订单、团购券和演示核销接口，页面只消费真实后端数据。
package com.fandazi.app.feature.voucher

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonParser

/** 团购券数据访问协议，便于后续替换为缓存或分页实现。 */
interface VoucherRepository {
    /** 只读本人订单集合，失败保留认证/网络错误；不缓存资格或创建订单。 */
    suspend fun listOrders(): ApiResult<List<GroupOrderSummary>>
    /** 输入后端公开订单号，返回本人详情；无权限和未知资源由服务端统一拒绝。 */
    suspend fun getOrder(orderId: String): ApiResult<GroupOrderSummary>
    /** 输入公开券号，读取该券的分摊金额和资格，不申请核销令牌。 */
    suspend fun getVoucher(voucherId: String): ApiResult<VoucherSummary>
    /** 查询本人券已有申请的公开入口，404保留无申请语义；不依赖未来售后写仓库。 */
    suspend fun getRefundLink(voucherId: String): ApiResult<VoucherRefundLink>
    /** 原延期设计接口保留兼容，本期只读页面不会调用。 */
    suspend fun createDisplayToken(voucherId: String): ApiResult<VoucherDisplayToken>
    /** 原延期核销接口保留兼容，本期无页面调用入口。 */
    suspend fun redeem(token: String, storeId: String): ApiResult<VoucherSummary>
}

/** 基于统一 ApiClient 的真实 HTTP 实现。 */
class HttpVoucherRepository(private val api: ApiClient, private val gson: Gson = Gson()) : VoucherRepository {
    /** 解析全部本人订单；同一公开身份重复意味着协议无效，不能展示两个同资源入口。 */
    override suspend fun listOrders(): ApiResult<List<GroupOrderSummary>> = api.get("group-orders").mapJson { body ->
        JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.toGroupOrder() }.also { orders ->
            require(orders.map { it.orderId }.distinct().size == orders.size) { "重复订单身份" }
        }
    }

    /** 详情必须返回请求的同一公开订单号，错误响应不能静默替换成其他资源。 */
    override suspend fun getOrder(orderId: String): ApiResult<GroupOrderSummary> = api.get("group-orders/${orderId.urlEncoded()}").mapJson {
        JsonParser.parseString(it).asJsonObject.toGroupOrder().also { order -> require(order.orderId == orderId) { "订单身份不匹配" } }
    }

    /** 本人券详情同样核对请求身份；页面失败可重试，不拿第一张券兜底。 */
    override suspend fun getVoucher(voucherId: String): ApiResult<VoucherSummary> = api.get("vouchers/${voucherId.urlEncoded()}").mapJson {
        JsonParser.parseString(it).asJsonObject.toVoucher().also { voucher -> require(voucher.publicId == voucherId) { "团购券身份不匹配" } }
    }

    /** 只读取申请身份；绑定原券并拒绝空 ID，防止错误申请被展示为另一个券的进度入口。 */
    override suspend fun getRefundLink(voucherId: String): ApiResult<VoucherRefundLink> =
        api.get("refunds/by-voucher/${voucherId.urlEncoded()}").mapJson { body ->
            val value = JsonParser.parseString(body).asJsonObject
            val link = VoucherRefundLink(value.get("requestId").asString, value.get("voucherId").asString)
            // 服务端仍负责归属；客户端只验证响应与请求一致，不猜状态或申请编号。
            require(link.requestId.isNotBlank() && link.voucherId == voucherId) { "退款申请身份不匹配" }
            link
        }

    override suspend fun createDisplayToken(voucherId: String): ApiResult<VoucherDisplayToken> =
        api.postJson("vouchers/${voucherId.urlEncoded()}/display-token", "{}").mapJson {
            val value = JsonParser.parseString(it).asJsonObject
            VoucherDisplayToken(value["voucherId"].asString, value["token"].asString, value["expiresAt"]?.asString)
        }

    override suspend fun redeem(token: String, storeId: String): ApiResult<VoucherSummary> = api.postJson(
        "vouchers/demo-redemption", gson.toJson(mapOf("token" to token, "storeId" to storeId))
    ).mapJson { JsonParser.parseString(it).asJsonObject.toVoucher() }

    /** 对公开身份编码，防止资源值改变路径结构；不截断或生成替代 ID。 */
    private fun String.urlEncoded() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    /** 解析券公开投影；核心金额和资格缺失时抛出解析异常，由统一边界转为可重试失败。 */
    private fun com.google.gson.JsonObject.toVoucher() = VoucherSummary(
        get("publicId").asString, get("orderId").asString, get("dealId").asString, get("dealTitle").asString,
        get("storeId").asString, get("storeName").asString, get("status").asString,
        getNullable("expiresAt"), getNullable("redeemedAt"), requiredBoolean("canRefund"),
        // 新摘要字段直接消费接口，null 金额保留原意，不按订单数量重新分摊。
        cents("amountCents"), getNullable("createdAt"),
        getNullable("imageUrl"), getNullable("imageAlt"),
        // 所属订单数量/时间来自页面响应；没有值时保留缺失，不用设备时间或固定数量补造。
        get("orderQuantity")?.takeUnless { it.isJsonNull }?.asInt,
        getNullable("orderStatus"), getNullable("orderCreatedAt"), getNullable("dealDescription")
    ).also { voucher ->
        // 空资源或业务名称不允许形成可点样例卡；状态按后端协议枚举，资格不由端上重算。
        require(listOf(voucher.publicId, voucher.orderId, voucher.dealId, voucher.storeId, voucher.dealTitle, voucher.storeName).all(String::isNotBlank))
        require(voucher.status in setOf("UNUSED", "REDEEMED", "REFUNDED", "EXPIRED"))
    }

    /** 解析订单和所属券；核对数量、身份与分摊守恒，非法输出不能继续导航或展示资格。 */
    private fun com.google.gson.JsonObject.toGroupOrder() = GroupOrderSummary(
        get("orderId").asString, get("dealId").asString, get("storeId").asString,
        get("quantity").asBigDecimal.intValueExact(), cents("totalCents") ?: error("缺少订单金额"), get("status").asString, get("voucherCount").asBigDecimal.intValueExact(),
        getAsJsonArray("vouchers").map { it.asJsonObject.toVoucher() }, requiredBoolean("canReview"),
        getNullable("createdAt"), getNullable("storeName").orEmpty(), getNullable("dealTitle").orEmpty(),
        getNullable("imageUrl"), getNullable("imageAlt"),
        // 页面金额保持整数分且实付允许 null，避免 totalCents 被误当支付事实。
        cents("paidTotalCents"),
        getNullable("paidAt"), getNullable("expiresAt"), getNullable("dealDescription")
    ).also { order ->
        require(order.quantity > 0 && order.voucherCount == order.vouchers.size && order.quantity == order.voucherCount)
        require(listOf(order.orderId, order.dealId, order.storeId, order.storeName, order.dealTitle).all(String::isNotBlank))
        require(order.status in setOf("PENDING_PAYMENT", "PAID", "PARTIALLY_USED", "USED", "REFUNDED", "EXPIRED", "CANCELLED"))
        require(order.vouchers.map { it.publicId }.distinct().size == order.vouchers.size)
        require(order.vouchers.all { it.orderId == order.orderId && it.storeId == order.storeId && it.dealId == order.dealId })
        // 实付快照存在时每张券必须有准确分摊，合计不能依赖当前套餐价格。
        order.paidTotalCents?.let { paid -> require(order.vouchers.all { it.amountCents != null } && order.vouchers.sumOf { it.amountCents!!.toLong() } == paid.toLong()) }
    }

    /** 布尔资格必须由后端明确给出，缺字段或字符串替代布尔都拒绝。 */
    private fun com.google.gson.JsonObject.requiredBoolean(name: String): Boolean {
        val field = get(name) ?: error("资格字段缺失")
        require(field.isJsonPrimitive && field.asJsonPrimitive.isBoolean)
        return field.asBoolean
    }

    /** 金额只接受非负整数分；null 保持未付款语义，不将小数截断成另一笔金额。 */
    private fun com.google.gson.JsonObject.cents(name: String): Int? {
        val field = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(field.isJsonPrimitive && field.asJsonPrimitive.isNumber)
        return field.asBigDecimal.intValueExact().also { require(it >= 0) }
    }

    /** 合法空值保留缺失，不把 JSON null 展示为业务文字。 */
    private fun com.google.gson.JsonObject.getNullable(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}

/** 将 ApiClient 的文本响应统一转换为业务对象，同时保留原始错误结果。 */
private suspend inline fun <T> ApiResult<String>.mapJson(crossinline parser: (String) -> T): ApiResult<T> = when (this) {
    is ApiResult.Success -> runCatching { ApiResult.Success(parser(value)) }.getOrElse { exception ->
        // 解析失败时保留异常类型，便于区分后端协议变更和网络返回空内容；不记录响应正文，避免泄漏券数据。
        ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("响应格式无效：${exception::class.java.simpleName}"))
    }
    is ApiResult.Failure -> this
}
