// 助手仓库：只通过公开网关访问助手能力。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

interface AgentRepository {
// 发送消息。
    suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult>
// 处理 `streamEvents` 相关逻辑。
    fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent>
// 处理 `grantThisTaskAuto` 相关逻辑。
    suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit>
// 处理 `revokeThisTaskAuto` 相关逻辑。
    suspend fun revokeThisTaskAuto(sessionId: String): ApiResult<Unit>
}

/**
 * 仅调用后端公开接口；认证令牌由统一拦截器附加，安装包不包含代理服务内部凭据。
 */
class HttpAgentRepository(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val gson: Gson = Gson(),
    client: OkHttpClient? = null,
    private val reconnectDelayMillis: Long = 500L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 仅公开接口真实401通知身份仓库；传递实际请求令牌，防止迟到响应注销新会话。 */
    private val onUnauthorized: suspend (String) -> Unit = {}
) : AgentRepository {
    private val client = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                tokenProvider()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        .build()

// 发送消息。
    override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> =
        executeJson(
            Request.Builder()
                .url(url("agent/sessions/$sessionId/messages"))
                .post(gson.toJson(mapOf("text" to text, "mode" to mode.name)).toRequestBody(JSON))
                .build()
        ) { body -> parseMessage(body) }

// 处理 `streamEvents` 相关逻辑。
    override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> = callbackFlow {
        // 取消页面订阅时同时取消阻塞中的 OkHttp 调用，避免旧连接继续占用线程和推送历史事件。
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        val streamJob = launch(Dispatchers.IO) {
            var cursor = afterEventId
            var consecutiveFailures = 0
            while (currentCoroutineContext().isActive) {
                val request = Request.Builder().url(url("agent/sessions/$sessionId/events")).apply {
                    if (cursor != null) header("Last-Event-ID", cursor.toString())
                    header("Accept", "text/event-stream")
                }.build()
                try {
                    val call = client.newCall(request)
                    activeCall.set(call)
                    call.execute().use { response ->
                        // SSE与普通消息共用失效策略，401不反复重连；403仅表示权限拒绝。
                        invalidateRejectedCredential(response)
                        if (!response.isSuccessful) throw AgentStreamException(response.code, errorMessage(response.body?.string().orEmpty(), response.message))
                        var id: Long? = null
                        val data = StringBuilder()
                        response.body?.charStream()?.buffered()?.useLines { lines ->
                            lines.forEach { line ->
                                when {
                                    line.startsWith("id:") -> id = line.removePrefix("id:").trim().toLongOrNull()
                                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                    line.isBlank() && data.isNotBlank() -> {
                                        parseEvent(id, data.toString())?.let { event ->
                                            consecutiveFailures = 0
                                            cursor = event.id ?: cursor
                                            trySend(event)
                                        }
                                        id = null
                                        data.clear()
                                    }
                                }
                            }
                        }
                        if (data.isNotBlank()) parseEvent(id, data.toString())?.let { event ->
                            cursor = event.id ?: cursor
                            trySend(event)
                        }
                    }
                    if (currentCoroutineContext().isActive) delay(reconnectDelayMillis)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AgentStreamException) {
                    if (failure.code == 401 || failure.code == 403) {
                        close(failure)
                        return@launch
                    }
                    // 会话创建前的 404 可短暂重连；连续失败超过预算后通知状态层，不能永久转圈。
                    if (++consecutiveFailures >= 3) { close(failure); return@launch }
                    if (currentCoroutineContext().isActive) delay(reconnectDelayMillis)
                } catch (failure: IOException) {
                    if (++consecutiveFailures >= 3) { close(failure); return@launch }
                    if (currentCoroutineContext().isActive) delay(reconnectDelayMillis)
                }
            }
        }
        awaitClose { activeCall.getAndSet(null)?.cancel(); streamJob.cancel() }
    }

// 处理 `grantThisTaskAuto` 相关逻辑。
    override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> = executeJson(
        Request.Builder().url(url("automation/task-grants"))
            .post(
                gson.toJson(
                    mapOf(
                        "sessionId" to request.sessionId,
                        "addressId" to request.addressId,
                        "maxAmount" to request.maxAmount,
                        "expiresAt" to request.expiresAt.toString()
                    )
                ).toRequestBody(JSON)
            ).build()
    ) { Unit }

// 处理 `revokeThisTaskAuto` 相关逻辑。
    override suspend fun revokeThisTaskAuto(sessionId: String): ApiResult<Unit> = executeJson(
        Request.Builder().url(url("automation/task-grants?sessionId=$sessionId")).delete().build()
    ) { Unit }

    private suspend fun <T> executeJson(request: Request, parser: (String) -> T): ApiResult<T> = withContext(ioDispatcher) {
        try {
            // 普通 HTTP 请求与长连接分开限制等待；SSE 可长期空闲，消息请求不能永久挂起。
            client.newBuilder().callTimeout(120, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                // 在消费响应前同步全局身份，避免页面仍显示登录、重试继续发送同一过期令牌。
                invalidateRejectedCredential(response)
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) ApiResult.Success(parser(body))
                else ApiResult.Failure(ApiError.Http(response.code, errorMessage(body, response.message)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            ApiResult.Failure(ApiError.Network(error.message ?: "Network request failed"))
        } catch (error: Exception) {
            ApiResult.Failure(ApiError.Network(error.message ?: "Unable to read server response"))
        }
    }

    /** 从实际已发送请求读取凭据版本；不记录令牌，不把503、403或网络故障解释成失效。 */
    private suspend fun invalidateRejectedCredential(response: okhttp3.Response) {
        if (response.code != 401) return
        val authorization = response.request.header("Authorization") ?: return
        if (!authorization.startsWith("Bearer ")) return
        onUnauthorized(authorization.removePrefix("Bearer "))
    }

// 解析消息。
    private fun parseMessage(body: String): AgentMessageResult {
        val value = JsonParser.parseString(body).asJsonObject
        return AgentMessageResult(
            status = runCatching { AgentStatus.valueOf(value.string("status").orEmpty()) }.getOrDefault(AgentStatus.FAILED),
            message = value.string("message").orEmpty(),
            nextAction = value.string("nextAction"),
            candidateCart = value.objectOrNull("candidateCart")?.toCandidateCart(),
            events = value.array("events").mapNotNull { it.asJsonObjectOrNull()?.toEvent() },
            // 售后摘要不是旧外卖购物车；转换为独立确认卡后交给聊天页显式确认。
            cards = value.publicCards(),
            error = value.string("error")
        )
    }

// 解析事件。
    private fun parseEvent(id: Long?, raw: String): AgentEvent? = runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        value.toEvent(id)
    }.getOrNull()

    private fun JsonObject.toEvent(fallbackId: Long? = null) = AgentEvent(
        id = string("id")?.toLongOrNull() ?: fallbackId,
        type = string("type").orEmpty(),
        summary = string("summary").orEmpty(),
        toolName = string("toolName"),
        status = string("status"),
        payload = get("payload")?.asJsonObjectOrNull()?.entrySet()
            ?.filter { isPublicField(it.key) }
            ?.associate { entry -> entry.key to entry.value.publicValue() }.orEmpty()
    )

    /** 解析公开卡片并过滤私有授权字段；字符串正常解码，嵌套对象递归过滤后保留结构。 */
    private fun JsonObject.toCard(): AgentCardPayload? {
        return AgentPublicCards.decode(this)
    }

    /** 已有补全卡优先；仅为未提供的售后类型构造白名单兼容卡，避免重复券/确认按钮。 */
    private fun JsonObject.publicCards(): List<AgentCardPayload> {
        val cards = array("cards").mapNotNull { it.asJsonObjectOrNull()?.toCard() }
        val confirmation = if (string("nextAction") == "confirmation_required")
            objectOrNull("candidateCart")?.let(AgentPublicCards::confirmation) else null
        return cards + (toAfterSaleCards() + listOfNotNull(confirmation)).filter { fallback -> cards.none { it.type == fallback.type } }
    }

    /** 私有字段既不能显示，也不能随聊天状态持久化；大小写和下划线差异不改变边界。 */
    private fun isPublicField(key: String): Boolean {
        return AgentPublicCards.isPublicField(key)
    }

    /** 结构化递归过滤避免私有凭证藏在嵌套负载；不使用字符串替换篡改业务文案。 */
    private fun JsonElement.publicJson(): JsonElement = AgentPublicCards.publicJson(this)

    /** 基本字段直接解码，数组/对象保留过滤后的JSON；空值不产生字面量null。 */
    private fun JsonElement.publicValue(): String = when {
        isJsonNull -> ""
        isJsonPrimitive -> asString
        else -> publicJson().toString()
    }

    /** 从待选券结果提取可点击卡片，并从待确认结果提取安全摘要。 */
    private fun JsonObject.toAfterSaleCards(): List<AgentCardPayload> {
        val action = string("nextAction")
        if (action == "select_voucher") {
            // 券列表来自服务端归属查询；端上只保留公开字段，不把私有状态带入聊天卡。
            return objectOrNull("candidateCart")?.array("vouchers")?.mapNotNull { element ->
                val choice = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val voucherId = choice.string("voucherId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                AgentCardPayload(type = "after_sale_voucher_choice", fields = mapOf(
                    "voucherId" to voucherId,
                    "dealTitle" to choice.string("dealTitle").orEmpty(),
                    "storeName" to choice.string("storeName").orEmpty(),
                    "expiresAt" to choice.string("expiresAt").orEmpty(),
                    "amountCents" to choice.string("amountCents").orEmpty(),
                    "canRefund" to choice.string("canRefund").orEmpty()
                ))
            }.orEmpty()
        }
        val type = when (action) {
            "confirm_refund" -> "after_sale_refund"
            "confirm_complaint" -> "after_sale_complaint"
            else -> return emptyList()
        }
        val summary = objectOrNull("candidateCart") ?: return emptyList()
        // 规则版本随公开摘要透传供当前确认校验；缺失时仍拒绝，绝不在端侧猜测版本。
        val allowed = if (type == "after_sale_refund")
            setOf("resourceId", "voucherId", "orderId", "dealTitle", "storeName", "amountCents", "expiresAt", "reason", "ruleVersion")
        else setOf("orderId", "subject", "description", "expiresAt", "ruleVersion")
        val fields = allowed.mapNotNull { key -> summary.string(key)?.let { key to it } }.toMap()
        return listOf(AgentCardPayload(type = type, fields = fields))
    }

    private fun JsonObject.toCandidateCart(): CandidateCart? {
        val merchant = string("merchantName").orEmpty()
        val addressId = string("addressId")?.toLongOrNull()
            ?: get("addressId")?.takeUnless { it.isJsonNull }?.asLong
            ?: return null
        val items = (array("items").takeIf { it.size() > 0 } ?: array("lines")).mapNotNull { item ->
            item.asJsonObjectOrNull()?.let {
                CandidateCartItem(
                    name = it.string("name") ?: it.string("dishName") ?: return@let null,
                    specification = it.string("specification") ?: it.string("spec") ?: "",
                    price = it.priceText("price").takeUnless { value -> value == "—" } ?: it.priceText("lineTotal"),
                    quantity = it.string("quantity")?.toIntOrNull() ?: 1
                )
            }
        }
        return CandidateCart(
            merchantName = merchant,
            items = items,
            address = string("address").orEmpty(),
            deliveryFee = priceText("deliveryFee"),
            discount = priceText("discount"),
            total = priceText("total"),
            maxAmount = priceText("maxAmount"),
            addressId = addressId,
            authorizationMode = string("authorizationMode")?.let { value ->
                runCatching { AgentMode.valueOf(value) }.getOrNull()
            },
            orderId = string("orderId")?.toLongOrNull()
                ?: get("orderId")?.takeUnless { it.isJsonNull }?.asLong
        )
    }

    private fun JsonObject.priceText(name: String): String {
        val raw = string(name).orEmpty()
        if (raw.isBlank()) return "—"
        return if (raw.startsWith("¥") || raw.startsWith("-¥")) raw else "¥$raw"
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.asJsonObjectOrNull()
    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()
    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
// 处理 `errorMessage` 相关逻辑。
    private fun errorMessage(body: String, fallback: String): String = runCatching {
        JsonParser.parseString(body).asJsonObject.string("error") ?: JsonParser.parseString(body).asJsonObject.string("message")
    }.getOrNull().orEmpty().ifBlank { fallback.ifBlank { "Request failed" } }
// 处理 `url` 相关逻辑。
    private fun url(path: String) = requireNotNull(baseUrl.toHttpUrl().resolve(path)) { "Invalid API base URL" }

    private class AgentStreamException(val code: Int, message: String) : IOException(message)

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
