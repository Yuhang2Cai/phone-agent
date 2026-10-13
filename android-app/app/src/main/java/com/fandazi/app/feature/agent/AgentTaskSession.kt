// AG03端侧会话协调器：只读恢复、控制单次提交和晚响应隔离，不保存或重放业务授权。
package com.fandazi.app.feature.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import java.util.UUID

/** VM提供生命周期作用域；持久回调只能保存一个随机requestId，不含动作或业务正文。 */
class AgentTaskSession(private val repository: AgentTaskRepository?, private val scope: CoroutineScope,
    private val persistRequest: (String?) -> Unit = {}) {
    private val mutable = MutableStateFlow(AgentTaskUiState(enabled = repository != null))
    val state = mutable.asStateFlow()
    private var session = ""
    private var generation = 0L
    private var established = false
    private var polling: Job? = null
    private val reads = Mutex()
    private var requestPointer: String? = null
    // 只保留本会话曾见过、被权威活动目录移除的公开ID及最低版本；不保存动作或确认资格。
    // 网络读取失败时下轮继续只读查询，不能依据“目录里消失了”猜测已经取消或完成。
    private val omittedTaskVersions = linkedMapOf<String, Long>()

    private companion object {
        // 与已有冷历史补读上限一致；过旧的待查引用仍可由聊天历史显式加载，不能无界积累。
        const val MAX_OMITTED_TASKS = 100
    }
    /** 身份变化先清内存状态；同账号进程恢复仅接收薄请求指针。 */
    fun reset(sessionId: String, established: Boolean, requestId: String? = null) {
        close();generation++;session = sessionId;this.established = established;requestPointer = requestId
        // 身份边界同时清待查历史，避免旧账号迟到详情或未完成读取进入新scope。
        omittedTaskVersions.clear()
        mutable.value = AgentTaskUiState(enabled = repository != null, unresolvedRequestId = requestId)
    }
    /** 页面可见时开始只读续接。 */
    fun open() {
        if (repository == null || session.isBlank() || polling?.isActive == true) return
        polling = scope.launch {
            // 只在页面可见时定时读取；退出页面或身份变化取消协程，不向服务器发生命周期动作。
            while (isActive) { refresh();delay(1500) }
        }
    }
    /** 页面离开取消读取，不向服务端发送暂停或取消。 */
    fun close() { polling?.cancel();polling = null }
    /** 只读刷新目录与任务事件；不能在这里调用input或control。 */
    suspend fun refresh() = reads.withLock {
        val repo = repository ?: return@withLock
        val key = session;val token = generation
        if (key.isBlank()) return@withLock
        // 输入校验：尚未建立的会话每轮只检查目录。初始404表示无服务端scope，不能继续读命令/事件再误锁首条输入。
        // 已存在scope或本次业务明确受理后，才进入正常增量读取；未知POST指针从不享受初始空会话例外。
        if (state.value.directory == null || state.value.requiresReload || !established) {
            // 初次恢复或证据失效才锁住交互；已核实为空但尚未建立的scope仍需后台探测，
            // 不能每次探测都把欢迎页改成“任务交接中”，也不能给真实未知请求解除恢复保护。
            if (state.value.directory == null || state.value.requiresReload) mutable.value = state.value.copy(loading = true)
            val result = repo.directory(key)
            if (!current(key, token)) return@withLock
            when (result) {
                is ApiResult.Success -> {
                    // 权威目录成功证明当前账号会话已建立，此后404必须按异常恢复处理，不能降为空目录。
                    established = true
                    val previousTasks = state.value.tasks
                    val recovered = AgentTaskHistoryRecovery.snapshot(state.value, result.value)
                    mutable.value = recovered
                    // 活动目录不包含终态；同版本预备/提交冲突后的重载会移除旧active项。
                    // 仅登记此前已知且本次确实遗漏的对象，再向只读详情取得真实终态，不能本地推断。
                    if (!recovered.requiresReload) {
                        for ((id, known) in previousTasks) {
                            if (id !in recovered.tasks && known.lifecycle in setOf("active", "paused")) {
                                omittedTaskVersions[id] = maxOf(known.version, omittedTaskVersions[id] ?: 0L)
                            }
                        }
                        // 固定待查缓存容量，只淘汰最旧公开引用；不修改当前任务、服务器状态或原业务正文。
                        while (omittedTaskVersions.size > MAX_OMITTED_TASKS) omittedTaskVersions.remove(omittedTaskVersions.keys.first())
                    }
                }
                is ApiResult.Failure -> {
                    val empty = !established && requestPointer == null && (result.error as? ApiError.Http)?.code == 404
                    mutable.value = if (empty) AgentTaskHistoryRecovery.snapshot(state.value, AgentTaskDirectory(0, 0, null, emptyList()))
                    else state.value.copy(loading = false, requiresReload = true, notice = "任务状态尚未恢复，请联网只读重查")
                    return@withLock
                }
            }
        }
        // 冷启动先定位薄请求，再读未终态命令；未知ID保留核查状态，不推测POST从未受理。
        requestPointer?.let { id ->
            val result = repo.byRequest(key, id)
            if (!current(key, token)) return@withLock
            when (result) {
                is ApiResult.Success -> acceptCommand(result.value, located = true)
                is ApiResult.Failure -> mutable.value = state.value.copy(unresolvedRequestId = id, notice = "控制响应尚未定位，请只读重查；不会自动重发")
            }
        }
        val pending = repo.pending(key)
        if (!current(key, token)) return@withLock
        when (pending) {
            is ApiResult.Success -> pending.value.forEach { acceptCommand(it) }
            is ApiResult.Failure -> {
                mutable.value = state.value.copy(requiresReload = true, notice = "交接状态读取失败，请只读重查")
                return@withLock
            }
        }
        // 已知命令逐一只读查询终态；pending列表消失不能单独当作控制成功证据。
        for (command in state.value.commands.values.filterNot { it.terminal }) {
            val result = repo.command(key, command.id)
            if (!current(key, token)) return@withLock
            if (result is ApiResult.Success) acceptCommand(result.value)
        }
        // 目录已裁决焦点后再补终态展示；详情不能新增活动任务或改写当前目录，仍不派发任何控制。
        if (!recoverOmittedHistory(repo, key, token)) return@withLock
        repeat(10) {
            val watermark = state.value.directory?.watermark ?: return@withLock
            val result = repo.events(key, watermark)
            if (!current(key, token)) return@withLock
            when (result) {
                is ApiResult.Success -> {
                    mutable.value = AgentTaskHistoryRecovery.events(state.value, key, result.value)
                    if (state.value.requiresReload || result.value.size < 100) return@withLock
                }
                is ApiResult.Failure -> {
                    // 409/410均重载带水位快照；其他网络故障也锁动作，避免在证据缺失时继续确认。
                    mutable.value = state.value.copy(requiresReload = true, notice = "任务事件未能连续读取，正在等待只读重载")
                    return@withLock
                }
            }
        }
        mutable.value = state.value.copy(requiresReload = true, notice = "任务更新较多，请完成只读重载后继续")
    }
    /** 本次明确输入可能已受理，后续404不得当作初始空目录。 */
    fun markEstablished() { established = true }
    /** 用户显式点击提交一次控制；失败只读定位，不重复POST。 */
    suspend fun control(action: String, taskId: String) {
        val repo = repository ?: return
        if (!AgentTaskControlPolicy.canControl(state.value, taskId, action)) return
        val task = state.value.tasks.getValue(taskId);val directory = state.value.directory ?: return
        val key = session;val token = generation;val requestId = UUID.randomUUID().toString()
        // 同步先保存未知请求状态，第二次快速点击会被策略拒绝；持久层不保存动作和正文，冷启动不能重发。
        requestPointer = requestId;persistRequest(requestId)
        mutable.value = state.value.copy(unresolvedRequestId = requestId, submittingRequestId = requestId, notice = "任务正在交接，请等待状态核实")
        established = true
        val result = repo.control(key, AgentTaskControl(requestId, action, taskId, task.version, directory.focusVersion))
        if (!current(key, token)) return
        // 首次POST已返回后才结束本进程提交态；失败仍保留未知请求指针，切换为只读核查而非重发。
        mutable.value = state.value.copy(submittingRequestId = null)
        if (result is ApiResult.Success) acceptCommand(result.value, located = true)
        // 无论POST是否回包都只读续接，错误不自动重发；原requestId保留至权威终态。
        refresh()
    }
    /** needs_attention只读核查，不重放控制或业务文字。 */
    suspend fun recheck() {
        val repo = repository ?: return
        val key = session;val token = generation
        for (command in state.value.commands.values.filter { !it.terminal && it.reconcile == "needs_attention" }) {
            val result = repo.recheck(key, command.id)
            if (!current(key, token)) return
            if (result is ApiResult.Success) acceptCommand(result.value)
        }
        refresh()
    }
    /** 冷历史任务引用只读详情，最多100个，不恢复旧按钮。 */
    suspend fun historyTasks(ids: Set<String>) {
        val repo = repository ?: return
        val key = session;val token = generation
        for (id in ids.take(100).filterNot { it in state.value.tasks }) {
            val result = repo.task(key, id)
            if (!current(key, token)) return
            if (result is ApiResult.Success) {
                // 历史详情只加入展示集合；不能借历史active投影修改当前焦点目录或赋予按钮资格。
                mutable.value = state.value.copy(tasks = state.value.tasks + (id to result.value))
            }
        }
    }

    /** 补读已知遗漏对象的权威历史；返回false表示身份改变或详情冲突，调用方停止本轮归并。
     *
     * 网络失败保留待查ID供后续轮询；404只放弃不可读引用，不伪造取消/完成。成功必须核对
     * 请求身份、最低版本及终态，再只加入展示集合；目录、焦点和业务确认资格始终不变。
     */
    private suspend fun recoverOmittedHistory(repo: AgentTaskRepository, key: String, token: Long): Boolean {
        if (state.value.requiresReload) return false
        // 复制本轮待查键，挂起期间reset可能清空原集合；每次回包仍复核完整本地身份代次。
        val pending = omittedTaskVersions.entries.take(MAX_OMITTED_TASKS).map { it.key to it.value }
        for ((id, minimumVersion) in pending) {
            if (!current(key, token)) return false
            if (id in state.value.tasks) { omittedTaskVersions.remove(id);continue }
            when (val result = repo.task(key, id)) {
                is ApiResult.Success -> {
                    if (!current(key, token)) return false
                    val task = result.value
                    // 不同对象、陈旧详情或仍活动的对象与刚取得的目录矛盾，必须重载，不能猜一个新焦点。
                    if (task.id != id || task.version < minimumVersion ||
                        task.lifecycle !in setOf("completed", "cancelled", "failed") || task.actions.isNotEmpty()) {
                        mutable.value = state.value.copy(requiresReload = true)
                        return false
                    }
                    mutable.value = state.value.copy(tasks = state.value.tasks + (id to task))
                    omittedTaskVersions.remove(id)
                }
                is ApiResult.Failure -> {
                    if (!current(key, token)) return false
                    // 不能把404解释为控制成功；其它读取失败保留公开引用，下一轮只读重查，不自动重发POST。
                    if ((result.error as? ApiError.Http)?.code == 404) omittedTaskVersions.remove(id)
                }
            }
        }
        return current(key, token) && !state.value.requiresReload
    }

    /** 两个异步读取间身份可能改变；每次回包都核对本地会话代次，不能只依赖取消信号。 */
    private fun current(key: String, token: Long) = key == session && token == generation

    /** 查询结果单调归并；终态后清薄指针，待核查仅显示真实原因，不当作成功。 */
    private fun acceptCommand(command: AgentTaskCommand, located: Boolean = false) {
        val old = state.value.commands[command.id]
        if (old?.terminal == true && !command.terminal) return
        mutable.value = state.value.copy(commands = state.value.commands + (command.id to command),
            unresolvedRequestId = if (located) null else state.value.unresolvedRequestId,
            notice = if (command.reconcile == "needs_attention") "需要核查：${command.reason ?: "状态暂不明确"}；仅可只读重查" else if (!command.terminal) "任务正在交接，请等待状态核实" else null)
        if (command.terminal && located) { requestPointer = null;persistRequest(null) }
    }
}
