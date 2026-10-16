// AG04 目标会话协调器：负责只读恢复和一次性请求身份，不能执行普通消息或业务确认。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** VM 提供生命周期作用域；持久回调只保存任务 ID 与随机请求 ID。 */
class AgentGoalSession(
    private val repository: AgentGoalRepository? = null,
    private val scope: CoroutineScope? = null,
    private val persistRequest: (String?, String?) -> Unit = { _, _ -> }
) {
    private val mutable = MutableStateFlow(AgentGoalUiState(enabled = repository != null))
    val state = mutable.asStateFlow()
    private val reads = Mutex()
    private var session = ""
    private var generation = 0L
    private var requestTask: String? = null
    private var requestId: String? = null

    /** 身份变化先递增本地代次并清投影；只接收无法重放动作的任务/请求薄指针。 */
    fun reset(sessionId: String, taskId: String? = null, requestId: String? = null) {
        generation++;session = sessionId;requestTask = taskId;this.requestId = requestId
        mutable.value = AgentGoalUiState(enabled = repository != null, unresolvedTaskId = taskId, unresolvedRequestId = requestId)
    }

    /**
     * 冷启动先 GET 原请求回执，再读取当前任务投影。
     * 无当前任务、任务切换或迟到回包都只改变内存展示，不产生恢复 POST 或业务确认。
     */
    suspend fun refresh(task: AgentTaskProjection?) = reads.withLock {
        val repo = repository ?: return@withLock
        val key = session;val token = generation
        if (key.isBlank()) return@withLock
        if (task == null || task.lifecycle != "active") {
            mutable.value = state.value.copy(loading = false, taskId = null, projection = null, recovery = null,
                requiresReload = requestId != null, notice = requestId?.let { "原恢复请求等待对应任务只读核查" })
            return@withLock
        }
        // 当前任务变化先清旧投影，网络失败时也不能继续显示上一任务的业务摘要。
        if (state.value.taskId != null && state.value.taskId != task.id) {
            mutable.value = state.value.copy(taskId = task.id, projection = null, recovery = null, loading = true, requiresReload = false, notice = null)
        } else mutable.value = state.value.copy(taskId = task.id, loading = true)

        // 薄指针与当前任务匹配时只查原 ID；accepted 仍保持锁定，终态才清除指针。
        if (requestId != null && requestTask == task.id) {
            when (val located = repo.byRequest(key, task.id, requestId!!)) {
                is ApiResult.Success -> {
                    if (!current(key, token)) return@withLock
                    accept(located.value)
                }
                is ApiResult.Failure -> if (current(key, token)) {
                    mutable.value = state.value.copy(notice = "恢复结果尚未定位，请联网只读核查；不会自动重发")
                }
            }
        }
        when (val result = repo.projection(key, task.id)) {
            is ApiResult.Success -> {
                if (!current(key, token)) return@withLock
                val projection = result.value
                // 服务端投影必须仍属于当前任务和同一任务版本；乱序结果不能覆盖新焦点。
                val previous = state.value.projection?.takeIf { it.conversationTaskId == task.id }
                mutable.value = when {
                    projection.conversationTaskId != task.id || projection.taskVersion != task.version ->
                        state.value.copy(projection = null, loading = false, requiresReload = true,
                            notice = "目标版本已变化，请读取最新任务状态")
                    previous != null && projection.isOlderThan(previous) ->
                        // 同一任务的目标、计划、效果或任务版本都只能单调前进；迟到旧响应保留新事实并关闭动作。
                        state.value.copy(loading = false, requiresReload = true,
                            notice = "收到迟到的旧目标状态，已保留当前结果并等待重新读取")
                    previous != null && projection.sameVersionAs(previous) && projection != previous ->
                        // 完全相同版本却返回不同正文表示服务端副本尚未收敛，不能任选一份覆盖当前展示。
                        state.value.copy(loading = false, requiresReload = true,
                            notice = "同一目标版本内容不一致，已保留当前结果并等待重新读取")
                    else -> state.value.copy(taskId = task.id, projection = projection, loading = false, requiresReload = false,
                        notice = state.value.notice?.takeIf { requestId != null })
                }
            }
            is ApiResult.Failure -> {
                if (!current(key, token)) return@withLock
                val code = (result.error as? ApiError.Http)?.code
                // 旧 2.2 任务或尚未建立投影不是伪造的空成功；无未知请求时仅隐藏目标页。
                mutable.value = if (code in setOf(404, 409) && requestId == null)
                    state.value.copy(taskId = task.id, projection = null, loading = false, requiresReload = false, notice = null)
                else state.value.copy(projection = null, loading = false, requiresReload = true,
                    notice = state.value.notice ?: "目标状态读取失败，请联网重试")
            }
        }
    }

    /** 用户显式点击只提交一次；发包前同步保存薄指针，未知结果后只能由 refresh 回查。 */
    suspend fun recover(action: String) {
        val repo = repository ?: return
        val taskId = state.value.taskId ?: return
        val projection = state.value.projection ?: return
        // 以投影构造最小当前任务，只为复用同一策略；真实 ViewModel 入口会再用 AG03 当前任务复核。
        val task = AgentTaskProjection(taskId, "composite", "goal_recovery", "active", projection.taskVersion, 1,
            "目标任务", AgentTaskStopPoint("waiting_input"), actions = setOf("pause", "cancel"))
        if (!AgentGoalActionPolicy.canRecover(state.value, task, action)) return
        val goals = projection.outcomes.filter { action in it.allowedActions }.map { it.goalId }
        if (goals.isEmpty()) return
        val key = session;val token = generation;val id = UUID.randomUUID().toString()
        // 同步写入后再发包；持久层不保存 action、goalIds 或任何业务/授权正文，因此冷启动无法重放。
        requestTask = taskId;requestId = id;persistRequest(taskId, id)
        mutable.value = state.value.copy(unresolvedTaskId = taskId, unresolvedRequestId = id, submittingRequestId = id,
            notice = "恢复请求正在提交；结果未知时只会查询原请求")
        val request = AgentGoalRecoveryRequest(id, goals, action, projection.goalRevision, projection.planRevision, projection.effectVersion)
        val result = repo.recover(key, taskId, request)
        if (!current(key, token)) return
        mutable.value = state.value.copy(submittingRequestId = null)
        when (result) {
            is ApiResult.Success -> accept(result.value)
            is ApiResult.Failure -> {
                val code = (result.error as? ApiError.Http)?.code
                // 明确的客户端拒绝证明服务端未受理；连接、超时、429和服务端错误都保留原 ID。
                if (code != null && code in 400..499 && code !in setOf(408, 429)) clearRequest("恢复条件已变化，请读取最新状态")
                else mutable.value = state.value.copy(notice = "恢复响应尚未确认，请只读核查；不会自动重发")
            }
        }
    }

    /** 迟到回包必须同时匹配会话和本地身份代次。 */
    private fun current(key: String, token: Long): Boolean = key == session && token == generation

    /** 任一公开版本回退都判为旧响应；版本不能靠另一字段前进来抵消。 */
    private fun AgentGoalProjection.isOlderThan(other: AgentGoalProjection): Boolean =
        taskVersion < other.taskVersion || goalRevision < other.goalRevision ||
            planRevision < other.planRevision || effectVersion < other.effectVersion

    /** 完整四版本相同才允许把重复 GET 视为同一快照，正文不同则进入只读重载。 */
    private fun AgentGoalProjection.sameVersionAs(other: AgentGoalProjection): Boolean =
        taskVersion == other.taskVersion && goalRevision == other.goalRevision &&
            planRevision == other.planRevision && effectVersion == other.effectVersion

    /** 回执只更新恢复状态；accepted/needs_attention 继续保留薄指针和动作锁。 */
    private fun accept(result: AgentGoalRecoveryResult) {
        if (result.requestId != requestId) return
        mutable.value = state.value.copy(recovery = result, unresolvedTaskId = requestTask, unresolvedRequestId = requestId,
            notice = when (result.state) {
                "accepted" -> "恢复请求已受理，正在等待最新状态。"
                "needs_attention" -> "恢复结果需要核查；不会自动重新提交。"
                "committed" -> "恢复状态已更新。"
                else -> "恢复请求未继续执行，请读取最新目标状态。"
            })
        if (result.terminal) clearRequest(state.value.notice)
    }

    /** 只在已知终态或明确 4xx 拒绝后删除持久指针。 */
    private fun clearRequest(notice: String?) {
        requestTask = null;requestId = null;persistRequest(null, null)
        mutable.value = state.value.copy(unresolvedTaskId = null, unresolvedRequestId = null, submittingRequestId = null, notice = notice)
    }
}
