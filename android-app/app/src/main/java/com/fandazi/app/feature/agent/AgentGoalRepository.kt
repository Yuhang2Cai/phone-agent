// AG04 目标 HTTP 边界：接口与普通聊天、任务控制分离，恢复失败时不得回退发送自然语言。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 目标仓库只公开当前投影、一次恢复提交和原请求回查。 */
interface AgentGoalRepository {
    /** 读取指定当前任务的公开目标投影。 */
    suspend fun projection(sessionId: String, taskId: String): ApiResult<AgentGoalProjection>

    /** 用户显式点击后提交一次恢复动作；实现不得自动重试。 */
    suspend fun recover(sessionId: String, taskId: String, request: AgentGoalRecoveryRequest): ApiResult<AgentGoalRecoveryResult>

    /** 结果未知时只按原请求 ID 查询回执。 */
    suspend fun byRequest(sessionId: String, taskId: String, requestId: String): ApiResult<AgentGoalRecoveryResult>
}

/** 真实 HTTP 实现复用已认证传输；协议失败明确返回 502，不制造空投影。 */
class HttpAgentGoalRepository(
    private val baseUrl: String,
    private val transport: suspend (Request) -> ApiResult<String>
) : AgentGoalRepository {
    /** 路径逐段编码，opaque 会话和任务 ID 不能改变 API 层级。 */
    private fun url(sessionId: String, taskId: String, vararg path: String) =
        requireNotNull(baseUrl.toHttpUrl().resolve("agent/sessions/"))
            .newBuilder().addPathSegment(sessionId).addPathSegment("tasks").addPathSegment(taskId)
            .apply { path.forEach(::addPathSegment) }.build()

    /** 传输成功后再严格解析；取消信号继续向上传递，格式异常不回退普通消息入口。 */
    private suspend fun <T> read(request: Request, parse: (String) -> T): ApiResult<T> = when (val result = transport(request)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> try { ApiResult.Success(parse(result.value)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError.Http(502, "目标状态格式无效，请只读重查")) }
    }

    /** 当前任务目标投影只读 GET。 */
    override suspend fun projection(sessionId: String, taskId: String): ApiResult<AgentGoalProjection> =
        read(Request.Builder().url(url(sessionId, taskId, "goals")).get().build(), AgentGoalJson::projection)

    /** 恢复 POST 精确发送七个公开字段，没有自动重试和旧消息协议回退。 */
    override suspend fun recover(sessionId: String, taskId: String, request: AgentGoalRecoveryRequest): ApiResult<AgentGoalRecoveryResult> {
        require(request.requestId.isNotBlank() && request.requestId.length <= 100 && request.goalIds.isNotEmpty() && request.goalIds.size <= 8)
        val body = linkedMapOf<String, Any>(
            "schemaVersion" to "2.3", "requestId" to request.requestId, "goalIds" to request.goalIds,
            "action" to request.action, "expectedGoalRevision" to request.expectedGoalRevision,
            "expectedPlanRevision" to request.expectedPlanRevision, "expectedEffectVersion" to request.expectedEffectVersion
        )
        return read(Request.Builder().url(url(sessionId, taskId, "goal-recoveries"))
            .post(Gson().toJson(body).toRequestBody(JSON)).build(), AgentGoalJson::recovery)
    }

    /** 未知结果只用原 requestId 做 GET；调用本方法绝不产生恢复写入。 */
    override suspend fun byRequest(sessionId: String, taskId: String, requestId: String): ApiResult<AgentGoalRecoveryResult> =
        read(Request.Builder().url(url(sessionId, taskId, "goal-recoveries", requestId)).get().build(), AgentGoalJson::recovery)

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

/** 严格 2.3 解析器：拒绝未知字段、宽松数字、重复 ID、未知枚举和跨层版本不一致。 */
internal object AgentGoalJson {
    private val ACTIONS = setOf("retry_reads", "reprepare_remaining", "replan_remaining", "recheck_original", "drop_remaining")
    private val OUTCOME_STATUS = setOf("pending", "running", "satisfied", "waiting_input", "waiting_confirmation", "recoverable", "blocked_dependency", "unknown_outcome", "unsupported", "failed", "cancelled")
    private val AGGREGATE_STATUS = setOf("all_satisfied", "partial", "waiting_input", "waiting_confirmation", "recoverable", "unknown_outcome", "failed", "cancelled")

    /** 精确键集合既保护私有字段，也避免服务端悄然扩展后客户端误解释。 */
    private fun JsonObject.keys(vararg values: String) { require(keySet() == values.toSet()) }
    private fun JsonObject.text(name: String, max: Int = 240): String = get(name).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString.also { value -> require(value.isNotBlank() && value.length <= max) }
    }
    private fun JsonObject.optionalText(name: String, max: Int = 160): String? = get(name).let {
        if (it == null || it.isJsonNull) null else text(name, max)
    }
    private fun JsonObject.number(name: String, minimum: Long = 0): Long = get(name).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("0|[1-9][0-9]*")))
        requireNotNull(it.toString().toLongOrNull()).also { value -> require(value >= minimum) }
    }
    private fun JsonObject.strings(name: String, allowed: Set<String>? = null, maximum: Int): List<String> {
        val rows = get(name).also { require(it != null && it.isJsonArray) }.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString.also { value -> require(value.isNotBlank() && value.length <= 100 && (allowed == null || value in allowed)) }
        }
        require(rows.size <= maximum && rows.distinct().size == rows.size);return rows
    }

    /** 公开资源字段全部可选，但类型、枚举和金额仍严格校验。 */
    private fun resource(raw: JsonElement): AgentGoalResourceSummary? {
        if (raw.isJsonNull) return null
        val value = raw.asJsonObject
        // Schema 中五项均为可选；只拒绝未审核字段，不能强迫后端为缺失信息填 null 或伪造值。
        require(value.keySet().all { it in setOf("resourceType", "resourceId", "title", "status", "amountCents") })
        val type = value.optionalText("resourceType", 20)?.also { require(it in setOf("store", "deal", "order", "voucher", "coupon", "refund", "complaint")) }
        return AgentGoalResourceSummary(type, value.optionalText("resourceId", 100), value.optionalText("title", 160), value.optionalText("status", 60),
            value.get("amountCents")?.takeUnless(JsonElement::isJsonNull)?.let { value.number("amountCents") })
    }

    /** 单目标必须携带完整公开状态和与顶层一致的三版本。 */
    private fun outcome(raw: JsonElement): AgentGoalOutcome {
        val value = raw.asJsonObject;value.keys("goalId", "description", "status", "reasonCode", "resourceSummary",
            "completedConditionLabels", "missingConditionLabels", "allowedActions", "goalRevision", "planRevision", "effectVersion")
        val status = value.text("status", 30).also { require(it in OUTCOME_STATUS) }
        val reason = value.text("reasonCode", 80).also { require(it.matches(Regex("[a-z0-9_]+"))) }
        return AgentGoalOutcome(value.text("goalId", 100), value.text("description", 240), status, reason, resource(value.get("resourceSummary")),
            value.strings("completedConditionLabels", maximum = 16), value.strings("missingConditionLabels", maximum = 16),
            value.strings("allowedActions", ACTIONS, 5).toSet(), value.number("goalRevision", 1), value.number("planRevision", 1), value.number("effectVersion"))
    }

    /** 顶层投影验证目标数量、唯一 ID、聚合枚举以及各行版本一致性。 */
    fun projection(raw: String): AgentGoalProjection {
        val value = JsonParser.parseString(raw).asJsonObject;value.keys("schemaVersion", "conversationTaskId", "taskVersion", "goalRevision",
            "planRevision", "effectVersion", "aggregateStatus", "outcomes", "availableActions")
        require(value.text("schemaVersion", 3) == "2.3")
        val status = value.text("aggregateStatus", 30).also { require(it in AGGREGATE_STATUS) }
        val rows = value.get("outcomes").also { require(it.isJsonArray) }.asJsonArray.map(::outcome)
        require(rows.size in 1..8 && rows.map { it.goalId }.distinct().size == rows.size)
        val result = AgentGoalProjection(value.text("conversationTaskId", 100), value.number("taskVersion", 1), value.number("goalRevision", 1),
            value.number("planRevision", 1), value.number("effectVersion"), status, rows, value.strings("availableActions", ACTIONS, 5).toSet())
        require(rows.all { it.goalRevision == result.goalRevision && it.planRevision == result.planRevision && it.effectVersion == result.effectVersion })
        return result
    }

    /** 回执保持 accepted 与业务成功分离；动作和目标仍须符合公开枚举及唯一性。 */
    fun recovery(raw: String): AgentGoalRecoveryResult {
        val value = JsonParser.parseString(raw).asJsonObject;value.keys("schemaVersion", "requestId", "state", "action", "goalIds",
            "goalRevision", "planRevision", "effectVersion", "allowedActions")
        require(value.text("schemaVersion", 3) == "2.3")
        val state = value.text("state", 30).also { require(it in setOf("accepted", "committed", "conflict", "rejected", "needs_attention")) }
        val action = value.text("action", 30).also { require(it in ACTIONS) }
        val goalIds = value.strings("goalIds", maximum = 8).also { require(it.isNotEmpty()) }
        return AgentGoalRecoveryResult(value.text("requestId", 100), state, action, goalIds, value.number("goalRevision", 1),
            value.number("planRevision", 1), value.number("effectVersion"), value.strings("allowedActions", ACTIONS, 5).toSet())
    }
}
