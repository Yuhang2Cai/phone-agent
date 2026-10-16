// AG04 公开目标模型：只承载 2.3 协议允许展示或恢复的字段，不保存确认凭证、执行键或完整工具结果。
package com.fandazi.app.feature.agent

/** 服务端返回的公开资源摘要；任一字段缺失时客户端保持缺失，不补造业务值。 */
data class AgentGoalResourceSummary(
    val resourceType: String? = null,
    val resourceId: String? = null,
    val title: String? = null,
    val status: String? = null,
    val amountCents: Long? = null
)

/** 单个目标的权威结果；版本用于恢复门控，不直接显示在用户界面。 */
data class AgentGoalOutcome(
    val goalId: String,
    val description: String,
    val status: String,
    val reasonCode: String,
    val resourceSummary: AgentGoalResourceSummary?,
    val completedConditionLabels: List<String>,
    val missingConditionLabels: List<String>,
    val allowedActions: Set<String>,
    val goalRevision: Long,
    val planRevision: Long,
    val effectVersion: Long
)

/** 一个任务当前的目标投影；动作必须同时出现在顶层和目标层才可提交。 */
data class AgentGoalProjection(
    val conversationTaskId: String,
    val taskVersion: Long,
    val goalRevision: Long,
    val planRevision: Long,
    val effectVersion: Long,
    val aggregateStatus: String,
    val outcomes: List<AgentGoalOutcome>,
    val availableActions: Set<String>
)

/** 用户一次明确恢复动作；调用方生成唯一请求 ID，并在发送前持久化薄指针。 */
data class AgentGoalRecoveryRequest(
    val requestId: String,
    val goalIds: List<String>,
    val action: String,
    val expectedGoalRevision: Long,
    val expectedPlanRevision: Long,
    val expectedEffectVersion: Long
)

/** 公开受理回执只表示恢复请求状态，不代表订单、退款或其他业务已成功。 */
data class AgentGoalRecoveryResult(
    val requestId: String,
    val state: String,
    val action: String,
    val goalIds: List<String>,
    val goalRevision: Long,
    val planRevision: Long,
    val effectVersion: Long,
    val allowedActions: Set<String>
) {
    /** 只有服务端明确终态才允许清除原请求定位指针。 */
    val terminal: Boolean get() = state in setOf("committed", "conflict", "rejected")
}

/** 目标页只区分已确认的六种视觉状态；未知枚举由解析层拒绝。 */
enum class AgentGoalScreen { COMPLETED, RECOVERABLE, UNKNOWN, WAITING_CONFIRMATION, UNSUPPORTED, CANCELLED }

/** 卡片只使用公开描述、条件和资源摘要；内部版本及原因码不会进入可见文本。 */
data class AgentGoalPresentationCard(
    val title: String,
    val status: String,
    val description: String,
    val completedConditions: List<String>,
    val missingConditions: List<String>,
    val resource: AgentGoalResourceSummary?
)

/** 操作区把恢复动作和本地导航分开，避免把“查看订单”误发成恢复写请求。 */
data class AgentGoalPresentationAction(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val resource: AgentGoalResourceSummary? = null
)

/** Fragment 使用的纯展示结果；所有业务内容均来自公开投影。 */
data class AgentGoalPresentationData(
    val screen: AgentGoalScreen,
    val title: String,
    val status: String,
    val description: String,
    val cards: List<AgentGoalPresentationCard>,
    val actions: List<AgentGoalPresentationAction>,
    val notice: String? = null
)

/** 端侧目标状态；未知写入保留原请求和任务 ID，禁止恢复期间再次提交。 */
data class AgentGoalUiState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val taskId: String? = null,
    val projection: AgentGoalProjection? = null,
    val recovery: AgentGoalRecoveryResult? = null,
    val unresolvedTaskId: String? = null,
    val unresolvedRequestId: String? = null,
    val submittingRequestId: String? = null,
    val notice: String? = null,
    val requiresReload: Boolean = false
)

/** 目标展示投影器；把八种协议聚合状态收敛为用户已确认的六种界面。 */
object AgentGoalPresentation {
    /**
     * 只从严格解析后的公开投影构造文案和动作。
     * 版本、目标 ID、原因码和内部执行信息仅参与门控，不进入可见文本。
     */
    fun from(state: AgentGoalUiState): AgentGoalPresentationData? {
        val projection = state.projection ?: return null
        val screen = when (projection.aggregateStatus) {
            "all_satisfied" -> AgentGoalScreen.COMPLETED
            "partial", "waiting_input", "recoverable" -> AgentGoalScreen.RECOVERABLE
            "unknown_outcome" -> AgentGoalScreen.UNKNOWN
            "waiting_confirmation" -> AgentGoalScreen.WAITING_CONFIRMATION
            "failed" -> AgentGoalScreen.UNSUPPORTED
            "cancelled" -> AgentGoalScreen.CANCELLED
            else -> return null
        }
        val satisfied = projection.outcomes.count { it.status == "satisfied" }
        val cards = projection.outcomes.map { outcome ->
            AgentGoalPresentationCard(
                title = outcome.resourceSummary?.title ?: outcome.description,
                status = outcomeStatus(outcome.status, screen),
                description = outcome.description,
                completedConditions = outcome.completedConditionLabels,
                missingConditions = outcome.missingConditionLabels,
                resource = outcome.resourceSummary
            )
        }
        val actions = when (screen) {
            AgentGoalScreen.COMPLETED -> resourceActions(projection, "查看订单") + localAction("return_chat", "返回对话")
            AgentGoalScreen.CANCELLED -> resourceActions(projection, "查看已完成订单") + localAction("return_chat", "返回对话")
            AgentGoalScreen.UNKNOWN -> buildList {
                if (available(projection, "recheck_original")) add(recoveryAction("recheck_original"))
                // 禁用按钮解释为什么不能再次提交；它没有生产动作映射，也不会进入恢复策略。
                add(AgentGoalPresentationAction("resubmit_disabled", "重新提交", false))
            }
            AgentGoalScreen.WAITING_CONFIRMATION -> buildList {
                add(localAction("return_chat", "查看新的确认"))
                if (available(projection, "replan_remaining")) add(recoveryAction("replan_remaining"))
            }
            AgentGoalScreen.RECOVERABLE, AgentGoalScreen.UNSUPPORTED -> RECOVERY_ORDER
                .filter { available(projection, it) }
                .map(::recoveryAction)
        }
        val copy = when (screen) {
            AgentGoalScreen.COMPLETED -> Triple("目标已完成", "$satisfied/${projection.outcomes.size} 项已完成", "所有目标均已完成，可查看服务端返回的结果。")
            AgentGoalScreen.RECOVERABLE -> Triple("部分目标待继续", "$satisfied/${projection.outcomes.size} 项已完成", "已完成结果保持不变，只处理仍未完成的目标。")
            AgentGoalScreen.UNKNOWN -> Triple("需要核查", "写入结果尚未确认", "只读取原请求的最新状态，不会再次提交。")
            AgentGoalScreen.WAITING_CONFIRMATION -> Triple("条件已变化", "需要新的确认", "历史确认已经失效，请返回对话核对新的确认信息。")
            AgentGoalScreen.UNSUPPORTED -> Triple("当前条件不支持", "$satisfied/${projection.outcomes.size} 项已完成", "请调整缺失条件，或明确放弃剩余目标。")
            AgentGoalScreen.CANCELLED -> Triple("已放弃剩余目标", "$satisfied/${projection.outcomes.size} 项已完成", "已经完成的结果继续有效，未完成部分不再继续。")
        }
        return AgentGoalPresentationData(screen, copy.first, copy.second, copy.third, cards, actions,
            state.notice ?: state.recovery?.takeIf { it.state == "accepted" }?.let { "恢复请求已受理，正在等待最新状态。" })
    }

    /** 只有包含公开订单 ID 的满足结果才展示订单导航；缺少 ID 时保守只保留返回对话。 */
    private fun resourceActions(projection: AgentGoalProjection, label: String): List<AgentGoalPresentationAction> =
        projection.outcomes.asSequence().filter { it.status == "satisfied" }.mapNotNull { it.resourceSummary }
            .firstOrNull { it.resourceType == "order" && !it.resourceId.isNullOrBlank() }
            ?.let { listOf(AgentGoalPresentationAction("view_resource", label, true, it)) }.orEmpty()

    /** 顶层与至少一个目标都声明动作才渲染，不能从顶层白名单推断目标范围。 */
    private fun available(projection: AgentGoalProjection, action: String): Boolean =
        action in projection.availableActions && projection.outcomes.any { action in it.allowedActions }

    /** 恢复动作标签是静态协议说明，不包含任何本地业务对象。 */
    private fun recoveryAction(action: String) = AgentGoalPresentationAction(action, when (action) {
        "retry_reads" -> "重新读取"
        "reprepare_remaining" -> "准备新的确认"
        "replan_remaining" -> "修改条件"
        "recheck_original" -> "核查最新状态"
        "drop_remaining" -> "放弃剩余"
        else -> "继续"
    }, true)

    /** 本地导航动作不会调用恢复 POST。 */
    private fun localAction(id: String, label: String) = AgentGoalPresentationAction(id, label, true)

    /** 服务端枚举转换为用户可读状态；原因码保持内部使用。 */
    private fun outcomeStatus(status: String, screen: AgentGoalScreen): String = when {
        // 条件变化页中的失败/取消行代表已经失效的历史确认；不能误写成业务失败或用户放弃。
        screen == AgentGoalScreen.WAITING_CONFIRMATION && status in setOf("failed", "cancelled") -> "已失效"
        else -> when (status) {
        "satisfied" -> "已完成"
        "pending", "running" -> "处理中"
        "waiting_input" -> "等待补充条件"
        "waiting_confirmation" -> "等待新的确认"
        "recoverable" -> "可以继续"
        "blocked_dependency" -> "依赖暂不可用"
        "unknown_outcome" -> "结果需要核查"
        "unsupported" -> "当前条件不支持"
        "failed" -> "未完成"
        "cancelled" -> "已放弃"
        else -> "状态待核实"
        }
    }

    private val RECOVERY_ORDER = listOf("retry_reads", "reprepare_remaining", "replan_remaining", "recheck_original", "drop_remaining")
}

/** 恢复动作的非 UI 门控；按钮和 ViewModel 都调用同一规则。 */
object AgentGoalActionPolicy {
    /** 同时核对任务、三版本、顶层动作、目标动作和未知写边界。 */
    fun canRecover(state: AgentGoalUiState, task: AgentTaskProjection?, action: String): Boolean {
        if (!state.enabled || state.loading || state.requiresReload || state.unresolvedRequestId != null ||
            state.submittingRequestId != null || task == null || task.lifecycle != "active") return false
        val projection = state.projection ?: return false
        // 当前任务身份和公开任务版本必须完全一致；切换或晚到投影没有恢复资格。
        if (state.taskId != task.id || projection.conversationTaskId != task.id || projection.taskVersion != task.version) return false
        if (action !in projection.availableActions) return false
        // 每个目标自带同一组三版本；任一行不一致说明投影损坏或正在切换，必须重新读取。
        if (projection.outcomes.any { it.goalRevision != projection.goalRevision || it.planRevision != projection.planRevision || it.effectVersion != projection.effectVersion }) return false
        val affected = projection.outcomes.filter { action in it.allowedActions }
        if (affected.isEmpty()) return false
        // 任一未知写存在时整份投影只能查原执行身份，禁止读取重试或重新准备。
        val hasUnknown = projection.outcomes.any { it.status == "unknown_outcome" }
        return !hasUnknown || action == "recheck_original"
    }
}
