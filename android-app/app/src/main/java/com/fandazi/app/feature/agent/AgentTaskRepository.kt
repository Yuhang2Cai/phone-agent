// AG03任务HTTP契约：复用助手认证传输，只公开2.2任务入口和只读恢复，不回退旧消息写入。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 任务协议与旧消息入口隔离；实现不得在失败时发送旧版业务文字。 */
interface AgentTaskRepository {
    /** 获取带同事务水位的只读目录。 */
    suspend fun directory(sessionId: String): ApiResult<AgentTaskDirectory>
    /** 获取连续任务事件，after不是业务SSE游标。 */
    suspend fun events(sessionId: String, after: Long): ApiResult<List<AgentTaskEvent>>
    /** 冷启动查询所有未终态命令，不派发控制。 */
    suspend fun pending(sessionId: String): ApiResult<List<AgentTaskCommand>>
    /** 按原requestId定位已受理命令；404也不能自动重发POST。 */
    suspend fun byRequest(sessionId: String, requestId: String): ApiResult<AgentTaskCommand>
    /** 读取已知命令状态，不产生业务调用。 */
    suspend fun command(sessionId: String, commandId: String): ApiResult<AgentTaskCommand>
    /** 用户显式要求只读核查权威状态，不再次派发原控制。 */
    suspend fun recheck(sessionId: String, commandId: String): ApiResult<AgentTaskCommand>
    /** 显式控制只发送目标与版本；一次点击只发一次POST。 */
    suspend fun control(sessionId: String, request: AgentTaskControl): ApiResult<AgentTaskCommand>
    /** 精确保留原文；confirmation为当前公开规范摘要，不含本地授权。 */
    suspend fun input(sessionId: String, text: String, requestId: String, selection: AgentSelectionRef? = null, confirmation: JsonObject? = null): ApiResult<AgentMessageResult>
    /** 历史终态任务只读详情，不重新激活。 */
    suspend fun task(sessionId: String, taskId: String): ApiResult<AgentTaskProjection>
}

/** 共享执行器注入已认证HTTP通道；公开DTO解析与普通消息解析分别执行，凭据不进入模型。 */
class HttpAgentTaskRepository(private val baseUrl: String, private val transport: suspend (Request) -> ApiResult<String>,
    private val parseMessage: (String) -> AgentMessageResult) : AgentTaskRepository {
    /** 路径逐段编码，requestId含斜杠时也只能作为标识，不能修改公开接口地址。 */
    private fun url(session: String, vararg path: String) = requireNotNull(baseUrl.toHttpUrl().resolve("agent/sessions/"))
        .newBuilder().addPathSegment(session).apply { path.forEach(::addPathSegment) }.build()

    /** 只解析成功响应；协议异常明确失败，不生成空目录、不透出异常正文且不重发请求。 */
    private suspend fun <T> read(request: Request, parse: (String) -> T): ApiResult<T> = when (val result = transport(request)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> try { ApiResult.Success(parse(result.value)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError.Http(502, "任务状态格式无效，请只读重查")) }
    }

    /** 共用唯一POST构造；调用方负责一次点击唯一ID，这里没有自动重试或协议回退。 */
    private fun post(session: String, path: String, value: Any) = Request.Builder().url(url(session, path))
        .post(Gson().toJson(value).toRequestBody("application/json; charset=utf-8".toMediaType())).build()

    override suspend fun directory(sessionId: String): ApiResult<AgentTaskDirectory> = read(Request.Builder().url(url(sessionId, "tasks")).get().build(), AgentTaskJson::directory)
    override suspend fun events(sessionId: String, after: Long): ApiResult<List<AgentTaskEvent>> {
        require(after >= 0)
        return read(Request.Builder().url(url(sessionId, "task-events").newBuilder().addQueryParameter("after", after.toString()).addQueryParameter("limit", "100").build()).get().build()) {
            JsonParser.parseString(it).asJsonArray.also { rows -> require(rows.size() <= 100) }.map(AgentTaskJson::event)
        }
    }
    override suspend fun pending(sessionId: String): ApiResult<List<AgentTaskCommand>> = read(Request.Builder().url(url(sessionId, "task-commands")).get().build()) {
        JsonParser.parseString(it).asJsonArray.also { rows -> require(rows.size() <= 100) }.map(AgentTaskJson::command)
    }
    override suspend fun byRequest(sessionId: String, requestId: String): ApiResult<AgentTaskCommand> = read(Request.Builder().url(url(sessionId, "task-commands", "by-request", requestId)).get().build()) { AgentTaskJson.command(JsonParser.parseString(it)) }
    override suspend fun command(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> = read(Request.Builder().url(url(sessionId, "task-commands", commandId)).get().build()) { AgentTaskJson.command(JsonParser.parseString(it)) }
    override suspend fun recheck(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> = read(Request.Builder().url(url(sessionId, "task-commands", commandId, "recheck"))
        .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())).build()) { AgentTaskJson.command(JsonParser.parseString(it)) }
    override suspend fun control(sessionId: String, request: AgentTaskControl): ApiResult<AgentTaskCommand> {
        require(request.action in setOf("pause", "cancel", "resume", "switch") && request.requestId.isNotBlank() && request.requestId.length <= 100 && request.expectedTaskVersion > 0 && request.expectedFocusVersion >= 0)
        return read(post(sessionId, "task-commands", mapOf("requestId" to request.requestId, "action" to request.action, "targetTaskId" to request.targetTaskId,
            "expectedTaskVersion" to request.expectedTaskVersion, "expectedFocusVersion" to request.expectedFocusVersion))) { AgentTaskJson.command(JsonParser.parseString(it)) }
    }
    override suspend fun input(sessionId: String, text: String, requestId: String, selection: AgentSelectionRef?, confirmation: JsonObject?): ApiResult<AgentMessageResult> {
        require(text.isNotBlank() && text.length <= 4000 && requestId.isNotBlank() && requestId.length <= 100)
        // 保留原文，引用只发服务端登记身份；禁止旧mode和自定义授权字段混入2.2。
        val body = mutableMapOf<String, Any>("schemaVersion" to "2.2", "requestId" to requestId, "text" to text)
        selection?.let {
            require(it.presentationId.isNotBlank() && it.version > 0 && (it.ordinal == null || it.ordinal > 0))
            body["selectionRef"] = mapOf("presentationId" to it.presentationId, "version" to it.version).toMutableMap<String, Any>().apply { it.ordinal?.let { ordinal -> put("ordinal", ordinal) } }
        }
        confirmation?.let { body["confirmation"] = it }
        return read(post(sessionId, "task-inputs", body), parseMessage)
    }
    override suspend fun task(sessionId: String, taskId: String): ApiResult<AgentTaskProjection> = read(Request.Builder().url(url(sessionId, "tasks", taskId)).get().build()) { AgentTaskJson.task(JsonParser.parseString(it)) }
}

/** 严格公开DTO解析器：不宽松转换字符串数字、未知枚举、重复任务或私有额外字段。 */
internal object AgentTaskJson {
    /** 集合字段严格核对，防止未经审核的新字段进入内存与界面。 */
    private fun JsonObject.keys(vararg fields: String) { require(keySet() == fields.toSet()) }
    private fun JsonObject.text(key: String): String = get(key).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString.also { value -> require(value.isNotBlank()) } }
    private fun JsonObject.optional(key: String): String? = get(key).let { if (it == null || it.isJsonNull) null else text(key) }
    private fun JsonObject.number(key: String): Long = get(key).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("0|[1-9][0-9]*")))
        requireNotNull(it.toString().toLongOrNull())
    }
    /** 目录只含当前和最多五个暂停任务；终态单独从历史入口只读查看。 */
    fun directory(raw: String): AgentTaskDirectory {
        val value = JsonParser.parseString(raw).asJsonObject
        value.keys("schemaVersion", "focusVersion", "throughTaskEventId", "currentTask", "pausedTasks");require(value.text("schemaVersion") == "2.2")
        val current = value["currentTask"].takeUnless { it.isJsonNull }?.let(::task)
        val paused = value["pausedTasks"].asJsonArray.map(::task)
        require(current == null || current.lifecycle == "active");require(paused.size <= 5 && paused.all { it.lifecycle == "paused" })
        val tasks = listOfNotNull(current) + paused;require(tasks.map { it.id }.distinct().size == tasks.size)
        return AgentTaskDirectory(value.number("focusVersion"), value.number("throughTaskEventId"), current, paused)
    }
    /** 公开任务字段只接受固定生命周期；availableActions仍由服务端决定，不自行补恢复按钮。 */
    fun task(raw: JsonElement): AgentTaskProjection {
        val value = raw.asJsonObject
        value.keys("conversationTaskId", "taskKind", "intent", "lifecycle", "taskVersion", "executionGeneration", "title", "stopPoint", "activeRunId", "lastRunId", "availableActions")
        val kind = value.text("taskKind");val lifecycle = value.text("lifecycle")
        require(kind in setOf("selection", "lookup", "transaction", "composite", "chat") && lifecycle in setOf("active", "paused", "completed", "cancelled", "failed"))
        val stop = value["stopPoint"].asJsonObject;stop.keys("kind", "awaitingField", "goalId")
        val actions = value["availableActions"].asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString }
        val expected = when (lifecycle) { "active" -> setOf("pause", "cancel");"paused" -> setOf("resume", "cancel");else -> emptySet() }
        require(actions.toSet() == expected && actions.size == expected.size && value.number("taskVersion") > 0)
        return AgentTaskProjection(value.text("conversationTaskId"), kind, value.text("intent"), lifecycle, value.number("taskVersion"), value.number("executionGeneration"), value.text("title"),
            AgentTaskStopPoint(stop.text("kind"), stop.optional("awaitingField"), stop.optional("goalId")), value.optional("activeRunId"), value.optional("lastRunId"), actions.toSet())
    }
    /** 命令状态与核查状态分别解析；HTTP200本身不能被解释为暂停成功。 */
    fun command(raw: JsonElement): AgentTaskCommand {
        val value = raw.asJsonObject
        value.keys("schemaVersion", "commandId", "action", "state", "reconcileStatus", "reasonCode", "focusVersion", "affectedTaskIds")
        require(value.text("schemaVersion") == "2.2")
        val action = value.text("action");val state = value.text("state");val reconcile = value.text("reconcileStatus")
        require(action in setOf("pause", "cancel", "resume", "switch", "insert") && state in setOf("prepared", "agent_acknowledged", "committed", "aborting", "aborted", "rejected") && reconcile in setOf("idle", "retrying", "needs_attention"))
        val affected = value["affectedTaskIds"].asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank());it.asString }.toSet()
        return AgentTaskCommand(value.text("commandId"), action, state, reconcile, value.optional("reasonCode"), value.number("focusVersion"), affected)
    }
    /** 任务事件围栏保留服务端epoch；控制短答只有水位意义，不伪装业务进度。 */
    fun event(raw: JsonElement): AgentTaskEvent {
        val value = raw.asJsonObject;value.keys("schemaVersion", "taskEventId", "scope", "focusVersion", "eventType", "payload");require(value.text("schemaVersion") == "2.2")
        val scope = value["scope"].asJsonObject;scope.keys("userId", "sessionId", "conversationEpoch");scope.text("userId")
        val type = value.text("eventType");require(type in setOf("task_projection", "command_status", "control_reply"))
        return AgentTaskEvent(value.number("taskEventId"), scope.text("sessionId"), scope.text("conversationEpoch"), value.number("focusVersion"), type,
            if (type == "task_projection") task(value["payload"]) else null, if (type == "command_status") command(value["payload"]) else null)
    }
    /** 业务事件若缺围栏或格式损坏则保持无资格，不以旧run/最近任务猜测归属。 */
    fun context(raw: String?): AgentTaskContext? = runCatching {
        val value = JsonParser.parseString(raw ?: return null).asJsonObject
        value.keys("conversationTaskId", "turnId", "taskVersion", "focusVersion", "executionGeneration")
        AgentTaskContext(value.text("conversationTaskId"), value.text("turnId"), value.number("taskVersion"), value.number("focusVersion"), value.number("executionGeneration"))
    }.getOrNull()
}
