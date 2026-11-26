// AG03恢复归并测试：目录水位与业务SSE分离，跨账号、断档和晚响应不能回写当前焦点。
package com.fandazi.app.feature.agent

import org.junit.Assert.*
import org.junit.Test

/** 针对真实归并器验证原子批次与版本单调性，无网络镜像实现。 */
class AgentTaskHistoryRecoveryTest {
    private val task = AgentTaskProjection("task", "selection", "recommend", "active", 3, 1, "选套餐", AgentTaskStopPoint("input", "quantity"), actions = setOf("pause", "cancel"))
    private val directory = AgentTaskDirectory(2, 8, task, emptyList())
    private val state = AgentTaskUiState(true, directory = directory, tasks = mapOf(task.id to task), epoch = "epoch")

    /** 同一目录快照重复加载无副作用，陈旧目录不得覆盖新焦点。 */
    @Test fun snapshotSetsWatermarkAndRejectsOlderSnapshot() {
        val loaded = AgentTaskHistoryRecovery.snapshot(AgentTaskUiState(true), directory)
        assertEquals(directory, loaded.directory)
        assertEquals(task, loaded.tasks[task.id])
        assertEquals(directory, AgentTaskHistoryRecovery.snapshot(loaded, directory.copy(focusVersion = 1, watermark = 7)).directory)
    }
    /** 先收到新任务投影，再收到旧版本，只推进事件水位，不回退任务版本。 */
    @Test fun contiguousEventsAdvanceWatermarkWithoutTaskRegression() {
        val newer = task.copy(version = 5)
        val updated = AgentTaskHistoryRecovery.events(state, "session", listOf(
            AgentTaskEvent(9, "session", "epoch", 2, "task_projection", newer),
            AgentTaskEvent(10, "session", "epoch", 2, "task_projection", task)))
        assertEquals(10L, updated.directory!!.watermark)
        assertEquals(5L, updated.tasks[task.id]!!.version)
        assertEquals(updated, AgentTaskHistoryRecovery.events(updated, "session", listOf(AgentTaskEvent(9, "session", "epoch", 2, "task_projection", task))))
    }
    /** 批次包含断档或越权来源时不能先应用前半段。 */
    @Test fun gapsAndForeignEpochRejectTheWholeBatch() {
        for (bad in listOf(AgentTaskEvent(11, "session", "epoch", 2, "task_projection", task.copy(version = 7)),
            AgentTaskEvent(10, "other", "epoch", 2, "task_projection", task),
            AgentTaskEvent(10, "session", "other-epoch", 2, "task_projection", task))) {
            val updated = AgentTaskHistoryRecovery.events(state, "session", listOf(AgentTaskEvent(9, "session", "epoch", 2, "task_projection", task.copy(version = 4)), bad))
            assertTrue(updated.requiresReload)
            assertEquals(8L, updated.directory!!.watermark)
            assertEquals(task, updated.tasks[task.id])
        }
    }
    /** 冷启动先读到终态命令，再补到旧prepared事件，不能重新显示交接中。 */
    @Test fun terminalCommandCannotBeRegressedByOldEvent() {
        val terminal = AgentTaskCommand("command", "pause", "committed", "idle", null, 3, setOf(task.id))
        val updated = AgentTaskHistoryRecovery.events(state.copy(commands = mapOf(terminal.id to terminal)), "session",
            listOf(AgentTaskEvent(9, "session", "epoch", 3, "command_status", command = terminal.copy(state = "prepared", reconcile = "retrying"))))
        assertEquals("committed", updated.commands[terminal.id]!!.state)
        assertEquals(9L, updated.directory!!.watermark)
    }
    /** 终态不可重开；即使非法新投影声称更高版本，也必须只读重载而非恢复动作。 */
    @Test fun higherVersionCannotReopenTerminalTask() {
        val terminal = task.copy(version = 4, lifecycle = "completed", actions = emptySet())
        val ended = state.copy(directory = directory.copy(current = null), tasks = mapOf(task.id to terminal))
        val updated = AgentTaskHistoryRecovery.events(ended, "session", listOf(AgentTaskEvent(9, "session", "epoch", 3, "task_projection", task.copy(version = 5))))
        assertTrue(updated.requiresReload);assertEquals(terminal, updated.tasks[task.id])
    }

    /** 预备和提交共享taskVersion时，状态差异不能静默丢弃；保持旧投影直到只读目录重载。 */
    @Test fun sameVersionLifecycleChangeRequiresAuthoritativeReload() {
        val paused = task.copy(lifecycle = "paused", version = 5, generation = 3, actions = setOf("resume", "cancel"))
        val before = state.copy(directory = directory.copy(current = null, paused = listOf(paused)), tasks = mapOf(task.id to paused))
        val committed = paused.copy(lifecycle = "active", actions = setOf("pause", "cancel"))
        val conflict = AgentTaskHistoryRecovery.events(before, "session", listOf(
            AgentTaskEvent(9, "session", "epoch", 4, "task_projection", committed)))
        assertTrue("同版本恢复提交必须触发只读重载", conflict.requiresReload)
        assertEquals(paused, conflict.tasks[task.id])
        assertEquals(8L, conflict.directory!!.watermark)
        val reloaded = AgentTaskHistoryRecovery.snapshot(conflict, AgentTaskDirectory(4, 9, committed, emptyList()))
        assertFalse(reloaded.requiresReload)
        assertEquals("active", reloaded.directory!!.current!!.lifecycle)
    }

    /** 完全相同投影可以推进水位；其他同版本差异仍整批拒绝，防止后半批冲突留下半份焦点。 */
    @Test fun identicalVersionIsIdempotentButConflictingBatchIsAtomic() {
        val identical = AgentTaskHistoryRecovery.events(state, "session", listOf(AgentTaskEvent(9, "session", "epoch", 2, "task_projection", task)))
        assertFalse(identical.requiresReload);assertEquals(9L, identical.directory!!.watermark)
        val changed = task.copy(stop = AgentTaskStopPoint("waiting_input", "reason"))
        val conflict = AgentTaskHistoryRecovery.events(state, "session", listOf(
            AgentTaskEvent(9, "session", "epoch", 2, "task_projection", task),
            AgentTaskEvent(10, "session", "epoch", 2, "task_projection", changed)))
        assertTrue("同版本停点差异不能静默忽略", conflict.requiresReload)
        assertEquals(8L, conflict.directory!!.watermark)
        assertEquals(task, conflict.tasks[task.id])
    }
}
