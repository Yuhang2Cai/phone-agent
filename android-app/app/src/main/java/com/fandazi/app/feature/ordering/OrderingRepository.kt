// 下单流程模块：封装持久化查询与写入。
package com.fandazi.app.feature.ordering

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface OrderingRepository {
// 搜索merchants。
    suspend fun searchMerchants(keyword: String): ApiResult<List<Merchant>>
// 加载merchants。
    suspend fun loadMerchants(page: Int, size: Int = 4, openOnly: Boolean = true): ApiResult<PagedResult<Merchant>> =
        ApiResult.Failure(ApiError.Network("商家列表暂不可用"))
// 加载商家。
    suspend fun loadMerchant(merchantId: Long): ApiResult<Merchant> =
        ApiResult.Failure(ApiError.Network("商家详情暂不可用"))
// 加载reviews。
    suspend fun loadReviews(merchantId: Long, page: Int, size: Int = 10): ApiResult<PagedResult<MerchantReview>> =
        ApiResult.Failure(ApiError.Network("商家评价暂不可用"))
// 加载菜单。
    suspend fun loadMenu(merchantId: Long): ApiResult<List<Dish>>
// 创建购物车、draft。
    suspend fun createCartDraft(merchantId: Long): ApiResult<CartDraft>
// 更新购物车。
    suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>): ApiResult<CartDraft>
// 校验购物车。
    suspend fun validateCart(cartDraftId: Long): ApiResult<CartQuote>
// 加载addresses。
    suspend fun loadAddresses(): ApiResult<List<Address>>
// 创建订单。
    suspend fun createOrder(cartDraftId: Long, addressId: Long, idempotencyKey: String): ApiResult<Order>
// 支付订单。
    suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order>
// 加载orders。
    suspend fun loadOrders(): ApiResult<List<Order>>
// 加载订单。
    suspend fun loadOrder(orderId: Long): ApiResult<Order>
// 创建地址。
    suspend fun createAddress(address: Address): ApiResult<Address> = ApiResult.Failure(ApiError.Network("地址功能暂不可用"))
// 更新地址。
    suspend fun updateAddress(address: Address): ApiResult<Address> = ApiResult.Failure(ApiError.Network("地址功能暂不可用"))
// 删除地址。
    suspend fun deleteAddress(addressId: Long): ApiResult<Unit> = ApiResult.Failure(ApiError.Network("地址功能暂不可用"))
// 处理 `setDefaultAddress` 相关逻辑。
    suspend fun setDefaultAddress(addressId: Long): ApiResult<Address> = ApiResult.Failure(ApiError.Network("地址功能暂不可用"))
}

class HttpOrderingRepository(
    private val apiClient: ApiClient,
    private val gson: Gson = Gson()
) : OrderingRepository {
// 搜索merchants。
    override suspend fun searchMerchants(keyword: String): ApiResult<List<Merchant>> =
        apiClient.get("merchants?keyword=${keyword.urlEncoded()}").mapJson { body ->
            JsonParser.parseString(body).asJsonObject.toPage { it.toMerchant() }.content
        }

// 加载merchants。
    override suspend fun loadMerchants(page: Int, size: Int, openOnly: Boolean): ApiResult<PagedResult<Merchant>> =
        apiClient.get("merchants?page=$page&size=$size&openOnly=$openOnly")
            .mapJson { JsonParser.parseString(it).asJsonObject.toPage { item -> item.toMerchant() } }

// 加载商家。
    override suspend fun loadMerchant(merchantId: Long): ApiResult<Merchant> =
        apiClient.get("merchants/$merchantId")
            .mapJson { JsonParser.parseString(it).asJsonObject.toMerchant() }

// 加载reviews。
    override suspend fun loadReviews(merchantId: Long, page: Int, size: Int): ApiResult<PagedResult<MerchantReview>> =
        apiClient.get("merchants/$merchantId/reviews?page=$page&size=$size")
            .mapJson { JsonParser.parseString(it).asJsonObject.toPage { item -> item.toMerchantReview() } }

// 加载菜单。
    override suspend fun loadMenu(merchantId: Long): ApiResult<List<Dish>> =
        apiClient.get("merchants/$merchantId/dishes").mapJson { body ->
            JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.toDish() }
        }

// 创建购物车、draft。
    override suspend fun createCartDraft(merchantId: Long): ApiResult<CartDraft> =
        apiClient.postJson("cart/drafts", gson.toJson(mapOf("merchantId" to merchantId))).mapJson { it.toCartDraft() }

// 更新购物车。
    override suspend fun updateCart(cartDraftId: Long, items: List<CartItemInput>): ApiResult<CartDraft> =
        apiClient.putJson("cart/drafts/$cartDraftId/items", gson.toJson(items)).mapJson { it.toCartDraft() }

// 校验购物车。
    override suspend fun validateCart(cartDraftId: Long): ApiResult<CartQuote> =
        apiClient.postJson("cart/drafts/$cartDraftId/validate", "{}").mapJson { body ->
            JsonParser.parseString(body).asJsonObject.toCartQuote(cartDraftId)
        }

// 加载addresses。
    override suspend fun loadAddresses(): ApiResult<List<Address>> =
        apiClient.get("addresses").mapJson { body -> JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.toAddress() } }

// 创建订单。
    override suspend fun createOrder(cartDraftId: Long, addressId: Long, idempotencyKey: String): ApiResult<Order> =
        apiClient.postJson(
            "orders",
            gson.toJson(mapOf("cartDraftId" to cartDraftId, "addressId" to addressId)),
            mapOf("Idempotency-Key" to idempotencyKey)
        ).mapJson { it.toOrder() }

// 支付订单。
    override suspend fun payOrder(orderId: Long, idempotencyKey: String): ApiResult<Order> =
        apiClient.postJson("orders/$orderId/pay", "{}", mapOf("Idempotency-Key" to idempotencyKey)).mapJson { it.toOrder() }

// 加载orders。
    override suspend fun loadOrders(): ApiResult<List<Order>> =
        apiClient.get("orders").mapJson { body -> JsonParser.parseString(body).asJsonArray.map { it.asJsonObject.toOrder() } }

// 加载订单。
    override suspend fun loadOrder(orderId: Long): ApiResult<Order> =
        apiClient.get("orders/$orderId").mapJson { it.toOrder() }

// 创建地址。
    override suspend fun createAddress(address: Address): ApiResult<Address> =
        apiClient.postJson("addresses", gson.toJson(address.toCommand())).mapJson { JsonParser.parseString(it).asJsonObject.toAddress() }

// 更新地址。
    override suspend fun updateAddress(address: Address): ApiResult<Address> =
        apiClient.putJson("addresses/${address.id}", gson.toJson(address.toCommand())).mapJson { JsonParser.parseString(it).asJsonObject.toAddress() }

// 删除地址。
    override suspend fun deleteAddress(addressId: Long): ApiResult<Unit> =
        when (val result = apiClient.delete("addresses/$addressId")) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

// 处理 `setDefaultAddress` 相关逻辑。
    override suspend fun setDefaultAddress(addressId: Long): ApiResult<Address> =
        apiClient.patch("addresses/$addressId/default", "{}").mapJson { JsonParser.parseString(it).asJsonObject.toAddress() }

    private fun String.toCartDraft(): CartDraft = JsonParser.parseString(this).asJsonObject.let { root ->
        CartDraft(root.long("id"), root.long("merchantId"), root.array("items").map { it.asJsonObject.toCartLine() })
    }

    private fun String.toOrder(): Order = JsonParser.parseString(this).asJsonObject.toOrder()

    private fun <T> JsonObject.toPage(parser: (JsonObject) -> T) = PagedResult(
        content = array("content").map { parser(it.asJsonObject) },
        number = int("number"),
        last = bool("last"),
        totalElements = long("totalElements")
    )

    private fun JsonObject.toMerchant() = Merchant(
        long("id"), string("name"), string("category"), decimal("minimumOrder"), decimal("deliveryFee"),
        int("deliveryMinutes"), bool("open"), nullableString("imageUrl")
    )

    private fun JsonObject.toMerchantReview() = MerchantReview(
        long("id"), long("merchantId"), string("reviewerName"), decimal("rating"),
        string("content"), string("createdAt")
    )

    private fun JsonObject.toDish() = Dish(
        long("id"), long("merchantId"), string("category"), string("name"), string("description"),
        decimal("price"), int("stock"), bool("listed"), nullableString("imageUrl"),
        array("options").map { it.asJsonObject.toOption() }
    )

    private fun JsonObject.toOption() = DishOption(
        long("id"), string("groupName"), string("optionName"), decimal("priceDelta"), bool("available")
    )

    private fun JsonObject.toAddress() = Address(
        long("id"), string("recipient"), string("phone"), string("detail"), bool("defaultAddress"), nullableBool("valid") ?: true
    )

    private fun Address.toCommand() = mapOf("recipient" to recipient, "phone" to phone, "detail" to detail, "defaultAddress" to defaultAddress)

    private fun JsonObject.toCartQuote(fallbackCartId: Long) = CartQuote(
        nullableLong("cartDraftId") ?: fallbackCartId,
        decimal("subtotal"), decimal("deliveryFee"), decimal("discount"), decimal("total"),
        array("lines").map { it.asJsonObject.toCartLine() }
    )

    private fun JsonObject.toOrder() = Order(
        long("id"), enumValue(string("status")), string("recipient"), string("phone"), string("detail"),
        decimal("subtotal"), decimal("deliveryFee"), decimal("discount"), decimal("total"),
        array("items").map { it.asJsonObject.toCartLine() },
        array("timeline").map { enumValue(it.asString) },
        nullableString("merchantName"),
        nullableString("createdAt")
    )

    private fun JsonObject.toCartLine() = CartLine(
        long("dishId"), nullableString("dishName").orEmpty(), int("quantity"),
        array("optionIds").map { it.asLong }, nullableString("optionSummary").orEmpty(), decimal("unitPrice")
    )

// 处理 `enumValue` 相关逻辑。
    private fun enumValue(value: String) = OrderStatus.valueOf(value)
    private fun String.urlEncoded() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    private fun <T> ApiResult<String>.mapJson(parser: (String) -> T): ApiResult<T> = when (this) {
        is ApiResult.Success -> runCatching { ApiResult.Success(parser(value)) }.getOrElse {
            ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network(it.message ?: "Unable to parse response"))
        }
        is ApiResult.Failure -> this
    }
    private fun JsonObject.string(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.nullableString(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.long(name: String) = get(name).asLong
    private fun JsonObject.nullableLong(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asLong
    private fun JsonObject.int(name: String) = get(name).asInt
    private fun JsonObject.bool(name: String) = get(name).asBoolean
    private fun JsonObject.nullableBool(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asBoolean
    private fun JsonObject.decimal(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asBigDecimal ?: BigDecimal.ZERO
    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()
}
