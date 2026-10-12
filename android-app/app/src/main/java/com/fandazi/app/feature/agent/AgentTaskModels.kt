// AG03公开任务模型：只承载目录、命令及事件水位，不存业务授权、准备令牌或原始工具结果。
package com.fandazi.app.feature.agent

/** 停点由服务端提供；客户端只展示未完成字段，不自行推断原因或补填业务参数。 */
data class AgentTaskStopPoint(val kind: String, val awaitingField: String? = null, val goalId: String? = null)

/** 一个会话任务的公开投影；版本与执行代次分开，任务完成不等于可以复用旧确认。 */
data class AgentTaskProjection(val id: String, val kind: String, val intent: String, val lifecycle: String,
    val version: Long, val generation: Long, val title: String, val stop: AgentTaskStopPoint,
    val activeRunId: String? = null, val lastRunId: String? = null, val actions: Set<String> = emptySet())

/** 目录和水位在服务端同一事务产生；补读从该水位开始，不能拿业务SSE游标替代。 */
data class AgentTaskDirectory(val focusVersion: Long, val watermark: Long, val current: AgentTaskProjection?, val paused: List<AgentTaskProjection>)

/** 控制命令不携带业务正文；终态只能查看，待核查必须显式只读重查。 */
data class AgentTaskCommand(val id: String, val action: String, val state: String, val reconcile: String,
    val reason: String?, val focusVersion: Long, val affected: Set<String>) {
    val terminal: Boolean get() = state in setOf("committed", "aborted", "rejected")
}

/** 业务事件的任务围栏；仅用于归属验证，不是执行授权。 */
data class AgentTaskContext(val taskId: String, val turnId: String, val taskVersion: Long,
    val focusVersion: Long, val generation: Long) : java.io.Serializable

/** 任务事件包含明确会话和epoch；公开用户ID和服务内部数字账号不作错误等值比较。 */
data class AgentTaskEvent(val id: Long, val sessionId: String, val epoch: String, val focusVersion: Long,
    val type: String, val task: AgentTaskProjection? = null, val command: AgentTaskCommand? = null)

/** 界面任务状态仅驻留内存；未知POST结果锁住动作，恢复过程从不重发原控制。 */
data class AgentTaskUiState(val enabled: Boolean = false, val loading: Boolean = false,
    val directory: AgentTaskDirectory? = null, val tasks: Map<String, AgentTaskProjection> = emptyMap(),
    val commands: Map<String, AgentTaskCommand> = emptyMap(), val epoch: String? = null,
    val unresolvedRequestId: String? = null, val notice: String? = null, val requiresReload: Boolean = false,
    /** 仅本进程正在等待首次控制POST回包时存在；冷恢复只有未知请求指针，不能伪装为仍在提交。 */
    val submittingRequestId: String? = null)

/** 服务端控制的乐观版本条件；同一次用户点击只生成一次requestId。 */
data class AgentTaskControl(val requestId: String, val action: String, val targetTaskId: String,
    val expectedTaskVersion: Long, val expectedFocusVersion: Long)

/** 非UI动作策略；实现与验收固定在公开目录和围栏上，不依赖按钮可见性。 */
object AgentTaskControlPolicy {
    /** 校验目录、目标、交接命令和用户动作，拒绝终态与陈旧快照。 */
    fun canControl(state: AgentTaskUiState, taskId: String, action: String): Boolean {
        // 输入校验：恢复不完整或POST结果未知时，必须先只读核查，不能用旧版本发第二条控制。
        if (!state.enabled || state.loading || state.requiresReload || state.unresolvedRequestId != null || state.submittingRequestId != null || state.directory == null) return false
        val task = state.tasks[taskId] ?: return false
        val member = (listOfNotNull(state.directory.current) + state.directory.paused).singleOrNull { it.id == taskId } ?: return false
        if (member.version != task.version || member.generation != task.generation || member.lifecycle != task.lifecycle) return false
        // 规则判断：服务端动作白名单与生命周期同时满足；后台取消只阻塞它实际影响的目标。
        if (task.lifecycle !in setOf("active", "paused") || action !in task.actions) return false
        return state.commands.values.none { !it.terminal && taskId in it.affected }
    }
    /** 只有当前任务同代次、同焦点且同run的新摘要能确认；历史摘要永远无资格。 */
    fun canConfirm(state: AgentTaskUiState, context: AgentTaskContext?, runId: String?): Boolean {
        if (context == null || runId == null || !canControl(state, context.taskId, "pause")) return false
        val directory = state.directory ?: return false
        val current = directory.current ?: return false
        // 受理版本可以小于完成后的投影版本；代次、焦点和run必须精确相等，防止恢复复活历史确认。
        return current.id == context.taskId && current.lifecycle == "active" && current.generation == context.generation &&
            current.version >= context.taskVersion && directory.focusVersion == context.focusVersion &&
            (current.activeRunId ?: current.lastRunId) == runId
    }
}

/** 只读恢复归并器；批次不连续时整批拒绝并要求重载，避免部分应用造成双焦点。 */
object AgentTaskHistoryRecovery {
    /** 用同事务目录替换当前焦点，保留已知终态供历史展示。 */
    fun snapshot(state: AgentTaskUiState, directory: AgentTaskDirectory): AgentTaskUiState {
        val previous = state.directory
        if (previous != null && (directory.focusVersion < previous.focusVersion || directory.watermark < previous.watermark)) return state
        // 规范化：活动目录替换非终态缓存，保留终态历史；目录不能凭本地已见过的卡片恢复任务。
        val tasks = state.tasks.filterValues { it.lifecycle in setOf("completed", "cancelled", "failed") }.toMutableMap()
        if ((listOfNotNull(directory.current) + directory.paused).any { it.id in tasks }) return state.copy(requiresReload = true)
        (listOfNotNull(directory.current) + directory.paused).forEach { tasks[it.id] = it }
        return state.copy(directory = directory, tasks = tasks, loading = false, requiresReload = false, notice = null)
    }
    /** 校验会话、epoch、连续水位和各版本后原子归并；重复事件不回退状态。 */
    fun events(state: AgentTaskUiState, sessionId: String, events: List<AgentTaskEvent>): AgentTaskUiState {
        val directory = state.directory ?: return state.copy(requiresReload = true)
        var watermark = directory.watermark
        var epoch = state.epoch
        var focus = directory.focusVersion
        val tasks = state.tasks.toMutableMap()
        val commands = state.commands.toMutableMap()
        // 先在局部副本校验全批次；任何断档/异源都不能把前半段提交到UI。
        for (event in events) {
            if (event.sessionId != sessionId || (epoch != null && event.epoch != epoch)) return state.copy(requiresReload = true)
            if (event.id <= watermark) continue
            if (event.id != watermark + 1) return state.copy(requiresReload = true)
            epoch = event.epoch
            watermark = event.id
            focus = maxOf(focus, event.focusVersion)
            event.task?.let { projection ->
                val old = tasks[projection.id]
                // 终态没有后继恢复状态；“更高版本”不能凌驾领域不变量，否则整批只读重载。
                if (old?.lifecycle in setOf("completed", "cancelled", "failed") && projection.lifecycle in setOf("active", "paused")) return state.copy(requiresReload = true)
                // 控制预备和安全提交可能共享版本/代次，但生命周期不同；既不能静默丢掉提交，
                // 也不能仅按到达顺序覆盖。整批保持旧水位，下一轮只读目录裁决当前真实状态。
                if (old != null && projection.version == old.version && projection != old) return state.copy(requiresReload = true)
                if (old == null || projection.version > old.version) tasks[projection.id] = projection
            }
            event.command?.let { command ->
                val old = commands[command.id]
                // 冷启动查询可能领先事件水位；终态和控制阶段均不允许被晚到的旧事件回退。
                if (old == null || (!old.terminal && commandRank(command.state) >= commandRank(old.state))) commands[command.id] = command
            }
        }
        val active = tasks.values.filter { it.lifecycle == "active" }
        val paused = tasks.values.filter { it.lifecycle == "paused" }
        if (active.size > 1 || paused.size > 5) return state.copy(requiresReload = true)
        // 结果构造：连续批次只推进任务水位；业务SSE游标不进入此状态。
        return state.copy(directory = AgentTaskDirectory(focus, watermark, active.singleOrNull(), paused), tasks = tasks,
            commands = commands, epoch = epoch, requiresReload = false)
    }

    /** 安全退出阶段高于ACK；aborting不能被迟到agent_acknowledged恢复为可提交。 */
    private fun commandRank(state: String): Int = when (state) {
        "prepared" -> 0; "agent_acknowledged" -> 1; "aborting" -> 2; else -> 3
    }
}
