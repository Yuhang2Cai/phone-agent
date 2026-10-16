// AG04 目标展示测试：固定六种已确认界面、公开字段边界和恢复动作门控。
package com.fandazi.app.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用纯生产投影器验证 XML 绑定前的数据，不依赖 Android View 或设计稿中的合成业务样例。 */
class AgentGoalPresentationTest {
    /** 八种聚合状态必须收敛到六个已确认界面，未知状态不能靠通用成功页掩盖。 */
    @Test
    fun authoritativeStatesMapToSixConfirmedScreens() {
        val cases = listOf(
            "all_satisfied" to AgentGoalScreen.COMPLETED,
            "partial" to AgentGoalScreen.RECOVERABLE,
            "waiting_input" to AgentGoalScreen.RECOVERABLE,
            "recoverable" to AgentGoalScreen.RECOVERABLE,
            "unknown_outcome" to AgentGoalScreen.UNKNOWN,
            "waiting_confirmation" to AgentGoalScreen.WAITING_CONFIRMATION,
            "failed" to AgentGoalScreen.UNSUPPORTED,
            "cancelled" to AgentGoalScreen.CANCELLED
        )

        assertEquals(cases.map { it.second }, cases.map { (status, _) ->
            AgentGoalPresentation.from(AgentGoalUiState(enabled = true, projection = projection(status)))!!.screen
        })
    }

    /** 可见卡片只使用服务端公开摘要和条件，内部 ID、原因码和三个 revision 不进入界面文案。 */
    @Test
    fun presentationUsesPublicSummaryWithoutPrivateVersionText() {
        val current = projection("partial", setOf("retry_reads", "drop_remaining"), listOf(
            outcome("done", "已完成预订", "satisfied", resource = AgentGoalResourceSummary("order", "order-public", "服务端订单标题", "PAID", 12800)),
            outcome("pending", "读取营业时间", "recoverable", actions = setOf("retry_reads", "drop_remaining"),
                completed = listOf("已选区域"), missing = listOf("营业时间"))
        ))

        val result = AgentGoalPresentation.from(AgentGoalUiState(enabled = true, projection = current))!!
        val visible = buildList {
            add(result.title);add(result.status);add(result.description)
            result.cards.forEach { add(it.title);add(it.status);add(it.description);addAll(it.completedConditions);addAll(it.missingConditions) }
            result.actions.forEach { add(it.label) }
        }.joinToString("|")

        assertTrue(visible.contains("服务端订单标题"));assertTrue(visible.contains("营业时间"))
        assertFalse(visible.contains("pending"));assertFalse(visible.contains("private_reason"))
        assertFalse(visible.contains("goalRevision"));assertFalse(visible.contains("11"))
        assertEquals(listOf("retry_reads", "drop_remaining"), result.actions.map { it.id })
    }

    /** 写结果未知时只保留只读核查，界面仍展示禁用的重新提交以解释安全边界。 */
    @Test
    fun unknownWriteOffersOnlyReadonlyRecheckAndDisabledResubmit() {
        val unknown = outcome("write", "提交订单", "unknown_outcome", actions = setOf("recheck_original"))
        val state = AgentGoalUiState(enabled = true, taskId = "task",
            projection = projection("unknown_outcome", setOf("recheck_original"), listOf(unknown)))

        val result = AgentGoalPresentation.from(state)!!

        assertEquals(AgentGoalScreen.UNKNOWN, result.screen)
        assertEquals(listOf("recheck_original", "resubmit_disabled"), result.actions.map { it.id })
        assertTrue(result.actions.first().enabled);assertFalse(result.actions.last().enabled)
        assertFalse(AgentGoalActionPolicy.canRecover(state, task(), "retry_reads"))
        assertTrue(AgentGoalActionPolicy.canRecover(state, task(), "recheck_original"))
    }

    /** 条件变化页同屏保留失效历史和新的确认；历史卡只展示，实际确认仍返回对话走 AG03 新确认。 */
    @Test
    fun waitingConfirmationShowsInvalidatedHistoryBesideFreshConfirmation() {
        val current = projection("waiting_confirmation", setOf("replan_remaining"), listOf(
            outcome("old", "原价格确认不能继续执行", "failed",
                resource = AgentGoalResourceSummary(title = "历史确认", status = "已失效", amountCents = 14800)),
            outcome("new", "核对调整后的新价格", "waiting_confirmation", actions = setOf("replan_remaining"),
                resource = AgentGoalResourceSummary(title = "新的预订确认", status = "待确认", amountCents = 16800))
        ))

        val result = AgentGoalPresentation.from(AgentGoalUiState(enabled = true, projection = current))!!

        assertEquals(listOf("历史确认", "新的预订确认"), result.cards.map { it.title })
        assertEquals(listOf("已失效", "等待新的确认"), result.cards.map { it.status })
        assertEquals(listOf("return_chat", "replan_remaining"), result.actions.map { it.id })
        assertEquals("查看新的确认", result.actions.first().label)
    }

    /** 恢复资格必须同时匹配当前任务、任务版本、三版本和请求状态，服务端动作集合只是必要条件。 */
    @Test
    fun recoveryPolicyRejectsStaleTaskVersionAndUnknownRequest() {
        val ready = AgentGoalUiState(enabled = true, taskId = "task", projection = projection("recoverable", setOf("retry_reads")))

        assertTrue(AgentGoalActionPolicy.canRecover(ready, task(), "retry_reads"))
        assertFalse(AgentGoalActionPolicy.canRecover(ready, task().copy(version = 8), "retry_reads"))
        assertFalse(AgentGoalActionPolicy.canRecover(ready.copy(unresolvedRequestId = "request", unresolvedTaskId = "task"), task(), "retry_reads"))
        assertFalse(AgentGoalActionPolicy.canRecover(ready.copy(projection = ready.projection!!.copy(effectVersion = 4)), task(), "retry_reads"))
    }

    /** 测试夹具只生成合成公开投影；设计稿餐厅、价格和订单样例不会进入生产兜底。 */
    private fun projection(status: String, actions: Set<String> = emptySet(), rows: List<AgentGoalOutcome>? = null) = AgentGoalProjection(
        conversationTaskId = "task", taskVersion = 7, goalRevision = 11, planRevision = 5, effectVersion = 3,
        aggregateStatus = status,
        outcomes = rows ?: listOf(outcome("goal", "合成目标", when (status) {
            "all_satisfied" -> "satisfied";"unknown_outcome" -> "unknown_outcome";"waiting_confirmation" -> "waiting_confirmation"
            "cancelled" -> "cancelled";"failed" -> "unsupported";else -> "recoverable"
        }, actions = actions)),
        availableActions = actions
    )

    /** 单目标版本与顶层保持一致，便于验证端侧不能从某一层单独放宽动作。 */
    private fun outcome(id: String, description: String, status: String, actions: Set<String> = emptySet(),
        completed: List<String> = emptyList(), missing: List<String> = emptyList(), resource: AgentGoalResourceSummary? = null
    ) = AgentGoalOutcome(id, description, status, "private_reason", resource, completed, missing, actions, 11, 5, 3)

    /** 当前任务夹具只提供 AG03 权威身份和版本，不赋予目标恢复动作。 */
    private fun task() = AgentTaskProjection("task", "composite", "restaurant_recommendation", "active", 7, 1,
        "合成任务", AgentTaskStopPoint("waiting_input"), actions = setOf("pause", "cancel"))
}
