// AG03非UI动作策略测试：拒绝旧确认和控制竞争，不通过View可见性推断授权。
package com.fandazi.app.feature.agent

import org.junit.Assert.*
import org.junit.Test

/** 合成任务无真实业务数据，所有断言调用正式策略。 */
class AgentTaskControlPolicyTest {
    private val task = AgentTaskProjection("refund", "transaction", "refund", "active", 4, 2, "退款", AgentTaskStopPoint("confirmation"), "run-2", "run-2", setOf("pause", "cancel"))
    private val state = AgentTaskUiState(true, directory = AgentTaskDirectory(3, 10, task, emptyList()), tasks = mapOf(task.id to task))
    private val context = AgentTaskContext("refund", "turn", 3, 3, 2)

    /** 完成回调可以递增taskVersion，不能因此误禁同代次的有效确认。 */
    @Test fun currentGenerationConfirmationAllowsOlderAdmissionTaskVersion() {
        assertTrue(AgentTaskControlPolicy.canConfirm(state, context, "run-2"))
        assertTrue(AgentTaskControlPolicy.canControl(state, "refund", "pause"))
    }
    /** 恢复后焦点和代次变化使旧卡失效；终态没有恢复动作。 */
    @Test fun staleOrTerminalConfirmationIsRejected() {
        assertFalse(AgentTaskControlPolicy.canConfirm(state, context.copy(generation = 1), "run-2"))
        assertFalse(AgentTaskControlPolicy.canConfirm(state, context.copy(focusVersion = 2), "run-2"))
        assertFalse(AgentTaskControlPolicy.canConfirm(state, context, "old-run"))
        val terminal = task.copy(lifecycle = "completed", actions = emptySet())
        assertFalse(AgentTaskControlPolicy.canControl(state.copy(tasks = mapOf(task.id to terminal)), task.id, "resume"))
        assertFalse(AgentTaskControlPolicy.canConfirm(state.copy(directory = state.directory!!.copy(current = null)), context, "run-2"))
    }
    /** 取消后台任务不剥夺前台确认；未知控制响应则必须先只读定位。 */
    @Test fun pendingControlOnlyBlocksAffectedTasksUnlessResponseIsUnknown() {
        val command = AgentTaskCommand("command", "cancel", "prepared", "retrying", null, 3, setOf("background"))
        assertTrue(AgentTaskControlPolicy.canConfirm(state.copy(commands = mapOf(command.id to command)), context, "run-2"))
        assertFalse(AgentTaskControlPolicy.canConfirm(state.copy(commands = mapOf(command.id to command.copy(affected = setOf(task.id)))), context, "run-2"))
        assertFalse(AgentTaskControlPolicy.canControl(state.copy(unresolvedRequestId = "lost"), task.id, "pause"))
        assertFalse(AgentTaskControlPolicy.canControl(state.copy(loading = true), task.id, "pause"))
    }
    /** 历史详情不是当前目录成员，不能靠一个旧active投影重获控制资格。 */
    @Test fun historicalLiveDetailOutsideDirectoryCannotBeControlled() {
        val old = task.copy(id = "historical")
        assertFalse(AgentTaskControlPolicy.canControl(state.copy(tasks = state.tasks + (old.id to old)), old.id, "pause"))
    }
}
