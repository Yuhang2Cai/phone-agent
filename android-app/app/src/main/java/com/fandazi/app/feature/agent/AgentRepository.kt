// 助手仓库：只通过公开网关访问助手能力。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.CoroutineCallExecutor
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
import kotlinx.coroutines.ensureActive
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
    /** 新任务协议默认关闭；旧仓库与旧替身继续使用原消息契约。 */
    val tasks: AgentTaskRepository? get() = null
    /** 目标恢复默认关闭；旧替身不实现时不能通过普通消息入口模拟目标状态。 */
    val goals: AgentGoalRepository? get() = null
    /** 旧替身默认委托原入口；正式 HTTP 实现发送独立展示引用，不修改原问题或比较候选。 */
    suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?,selectionRef:AgentSelectionRef?):ApiResult<AgentMessageResult> = sendMessage(sessionId,text,mode,clientMessageKey,compareDealIds)
    /** 旧替身兼容入口；正式HTTP实现发送独立公开ID字段，普通null引用沿旧行为。 */
    suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?):ApiResult<AgentMessageResult> = sendMessage(sessionId,text,mode,clientMessageKey)

    /** 旧实现仍支持原三参数发送；展示键只关联用户气泡，不触及模型或交易协议。 */
    suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode, clientMessageKey: String?): ApiResult<AgentMessageResult> =
        sendMessage(sessionId, text, mode)
    /** 只读恢复V1历史；未实现该能力的旧替身明确失败，不能假称空历史。 */
    suspend fun loadHistory(sessionId: String, cursor: String? = null, limit: Int = 50): ApiResult<AgentHistoryPage> =
        ApiResult.Failure(ApiError.Http(503, "当前服务暂不支持聊天历史恢复"))
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
    private val onUnauthorized: suspend (String) -> Unit = {},
    /** 仅受控debug验收显式启用，发布默认保持关闭。 */
    private val taskLifecycleEnabled: Boolean = false,
    /** 目标能力只有在任务协议也开启时才装配；两个发布默认值均为 false。 */
    private val goalRecoveryEnabled: Boolean = false
) : AgentRepository {
    /** 延迟装配以确保共用认证执行器已初始化；默认关闭时完全不创建任务通道。 */
    override val tasks: AgentTaskRepository? by lazy {
        if (taskLifecycleEnabled) HttpAgentTaskRepository(baseUrl, { request -> executeJson(request) { it } }, ::parseMessage) else null
    }
    /** 复用同一认证、401和超时边界；公开目标仓库自身不重试恢复 POST。 */
    override val goals: AgentGoalRepository? by lazy {
        if (taskLifecycleEnabled && goalRecoveryEnabled) HttpAgentGoalRepository(baseUrl) { request -> executeJson(request) { it } } else null
    }
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

    /** 原调用委托新展示关联参数，省略该字段保持旧HTTP契约兼容。 */
    override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> =
        sendMessage(sessionId, text, mode, null)

    /** 普通消息省略对比引用，旧调用与HTTP载荷保持兼容。 */
    override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode, clientMessageKey: String?): ApiResult<AgentMessageResult> = sendMessage(sessionId,text,mode,clientMessageKey,null)

    /** 只发送有序公开ID与原问题，资格/价格由Spring重查；失败不自动重发。 */
    override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?):ApiResult<AgentMessageResult> {
        return sendMessage(sessionId,text,mode,clientMessageKey,compareDealIds,null)
    }

    /** 引用只包含服务端登记身份、版本和可选序号；不上传有序候选数组或自行推导资源 ID。 */
    override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?,selectionRef:AgentSelectionRef?):ApiResult<AgentMessageResult> {
        val fields = mutableMapOf<String,Any>("text" to text, "mode" to mode.name)
        if(compareDealIds!=null) {
            // 发送边界再次核对数量与UUID，损坏恢复状态不能沿默认首项执行。
            val selected=com.fandazi.app.feature.compare.CompareSelection.restore(compareDealIds)
            require(selected.canCompare && selected.ids.size==compareDealIds.size){"对比引用无效"}
            fields["compareDealIds"]=selected.ids
        }
        clientMessageKey?.let { fields["clientMessageKey"] = it }
        selectionRef?.let { reference ->
            require(reference.presentationId.isNotBlank() && reference.presentationId.length<=100 && reference.version>0 && (reference.ordinal==null || reference.ordinal>0)) { "展示引用无效" }
            fields["selectionRef"] = mapOf("presentationId" to reference.presentationId, "version" to reference.version).toMutableMap<String,Any>().apply {
                reference.ordinal?.let { put("ordinal", it) }
            }
        }
        return executeJson(Request.Builder().url(url("agent/sessions/$sessionId/messages"))
            .post(gson.toJson(fields).toRequestBody(JSON)).build(), ::parseMessage)
    }

    /** URL编码opaque游标，校验版本与页身份；正文完整解码，嵌套事件继续走原公开脱敏。 */
    override suspend fun loadHistory(sessionId: String, cursor: String?, limit: Int): ApiResult<AgentHistoryPage> {
        require(limit in 1..100) { "历史limit必须为1至100" }
        val address = url("agent/sessions/$sessionId/history").newBuilder()
            .addQueryParameter("limit", limit.toString()).apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
        return executeJson(Request.Builder().url(address).get().build()) { body ->
            val value = JsonParser.parseString(body).asJsonObject
            require(value.string("schemaVersion") == "1.0" && value.string("sessionId") == sessionId) { "历史版本或会话不匹配" }
            val rows = value.array("messages").map { row ->
                val item = row.asJsonObject
                AgentHistoryMessage(requireNotNull(item.string("publicId")), requireNotNull(item.string("role")),
                    requireNotNull(item.string("content")), item.string("runId"), item.string("traceId"),
                    item.string("clientMessageKey"), item.string("createdAt"))
            }
            val events = value.array("events").map { it.asJsonObject.toEvent() }
            require(rows.size+events.size<=limit) { "历史页超过请求预算" }
            val run = value.objectOrNull("currentRun")
            AgentHistoryPage(sessionId, rows, events, requireNotNull(value.string("throughEventId")?.toLongOrNull()),
                value.string("nextCursor"), requireNotNull(value.string("messageHistoryStatus")),
                run?.string("runId"), run?.string("traceId"), run?.string("status"))
        }
    }

    /**
     * 按数据库SSE游标订阅公开会话事件，返回有界串行Flow；消费慢时暂停读取，不丢文字或关键终态。
     * afterEventId来自状态层已应用位置；连接内只在成功入队后推进游标。取消关闭活动Call，
     * 401/403或三次连续连接故障结束Flow并传播异常，任何取消均不得作为网络故障自动重试。
     */
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
                    // 先登记再检查取消，覆盖awaitClose已执行而新Call尚未开始的竞争；未执行的Call不占网络资源。
                    currentCoroutineContext().ensureActive()
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
                                            // 等待有界队列有容量再交付，不能忽略trySend失败后推进至尚未入队的位置。
                                            // send取消会直接抛取消异常，游标保留旧值；重新订阅仍由状态层已应用游标起步。
                                            send(event)
                                            consecutiveFailures = 0
                                            cursor = event.id ?: cursor
                                        }
                                        id = null
                                        data.clear()
                                    }
                                }
                            }
                        }
                        if (data.isNotBlank()) parseEvent(id, data.toString())?.let { event ->
                            // EOF前没有空行的合法末事件也走同一交付顺序，不能成为不受背压保护的另一条路径。
                            send(event)
                            consecutiveFailures = 0
                            cursor = event.id ?: cursor
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

    /** 普通助手HTTP沿用120秒请求上限和原错误分类；SSE仍使用自身活动Call/续接逻辑。 */
    private val ordinaryExecutor = CoroutineCallExecutor(
        this.client.newBuilder().callTimeout(120, TimeUnit.SECONDS).build(), onUnauthorized, ::errorMessage,
        networkError = { ApiError.Network(it.message ?: "Network request failed") }
    )

    /** 后台解析共享执行器成功文本，取消继续传播；解析异常保留原网络失败映射，不自动重发写消息。 */
    private suspend fun <T> executeJson(request: Request, parser: (String) -> T): ApiResult<T> = withContext(ioDispatcher) {
        try {
            when (val result = ordinaryExecutor.execute(request)) {
                is ApiResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    ApiResult.Success(parser(result.value))
                }
                is ApiResult.Failure -> result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            error = value.string("error"),
            taskConfirmationSummary = if (taskLifecycleEnabled && value.string("status") == "AWAITING_CONFIRMATION")
                value.objectOrNull("candidateCart")?.publicJson()?.asJsonObject else null
        )
    }

    /** 解析并脱敏SSE数据，数据库恢复身份只取行id；JSON的协议身份不能冒充已持久化游标。 */
    private fun parseEvent(id: Long?, raw: String): AgentEvent? = runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        value.toEvent(id, fromSse = true)
    }.getOrNull()

    /** HTTP公开事件仍读其数据库id；SSE即使没有行id也不回退到JSON协议id，序号只作展示/去重负载。 */
    private fun JsonObject.toEvent(databaseCursor: Long? = null, fromSse: Boolean = false) = AgentEvent(
        id = if (fromSse) databaseCursor else string("id")?.toLongOrNull(),
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
