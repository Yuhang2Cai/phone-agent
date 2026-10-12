// AG03会话恢复测试：验证生产协调器不会从恢复、失败或核查路径派发第二次业务/控制。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 合成传输记录实际调用边界，策略和归并仍由正式协调器执行。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentTaskSessionTest {
    /** 首次POST等待与失败后的未知结果必须区分，但两种状态都不能重复控制或确认。 */
    @Test fun pendingPostIsMemoryOnlyAndFailureBecomesReadonlyAttention() = runTest {
        val repo = Fake(directory, command);repo.missing = true;repo.controlGate = CompletableDeferred()
        val model = AgentTaskSession(repo, this);model.reset("session", true);model.refresh()
        val job = launch { model.control("pause", task.id) };testScheduler.runCurrent()
        val request = model.state.value.unresolvedRequestId
        assertNotNull(request);assertEquals(request, model.state.value.submittingRequestId)
        assertFalse(AgentTaskControlPolicy.canControl(model.state.value, task.id, "pause"))
        // 请求回包失败后保留原requestId，结束本进程提交态；只读核查不发第二次POST。
        repo.controlGate!!.complete(Unit);job.join()
        assertNull(model.state.value.submittingRequestId);assertEquals(request, model.state.value.unresolvedRequestId)
        model.refresh();assertEquals(1, repo.posts)
        model.reset("session", true, request)
        assertNull("冷启动不能从薄指针恢复提交中的假象", model.state.value.submittingRequestId)
        assertEquals(request, model.state.value.unresolvedRequestId)
    }
    private val task = AgentTaskProjection("task", "transaction", "refund_after_sale", "active", 3, 1, "退款", AgentTaskStopPoint("waiting_input", "reason"), actions = setOf("pause", "cancel"))
    private val directory = AgentTaskDirectory(2, 5, task, emptyList())
    private val command = AgentTaskCommand("command", "pause", "prepared", "needs_attention", "write_unknown", 2, setOf("task"))

    /** 薄指针恢复仅查询原requestId；不保存或重放动作。 */
    @Test fun coldStartLocatesOriginalRequestWithoutPosting() = runTest {
        val repo = Fake(directory, command);val model = AgentTaskSession(repo, this)
        model.reset("session", true, "lost-request");model.refresh()
        assertEquals(listOf("lost-request"), repo.lookups);assertEquals(0, repo.posts)
        assertEquals("needs_attention", model.state.value.commands["command"]!!.reconcile)
        assertFalse(AgentTaskControlPolicy.canControl(model.state.value, task.id, "pause"))
    }
    /** 失败响应后保留原ID，查询404也不能推断控制从未受理或自动重发。 */
    @Test fun failedControlPersistsOneRequestAndOnlyRechecks() = runTest {
        val repo = Fake(directory, command);repo.missing = true
        val pointers = mutableListOf<String?>();val model = AgentTaskSession(repo, this, pointers::add)
        model.reset("session", true);model.refresh();model.control("pause", task.id)
        assertEquals(1, repo.posts);assertNotNull(model.state.value.unresolvedRequestId)
        assertEquals(pointers.first(), repo.lookups.last())
        model.refresh();assertEquals(1, repo.posts)
        repo.missing = false;model.recheck();assertEquals(1, repo.posts)
        assertNull(model.state.value.unresolvedRequestId)
    }
    /** 旧账号目录请求即使无法及时取消，其迟到成功也不能污染新账号。 */
    @Test fun accountChangeDiscardsLateDirectoryResponse() = runTest {
        val repo = Fake(directory, command);val gate = CompletableDeferred<Unit>();repo.gate = gate
        val model = AgentTaskSession(repo, this);model.reset("old", true)
        val old = launch { model.refresh() };testScheduler.runCurrent()
        model.reset("new", false);gate.complete(Unit);old.join()
        assertNull(model.state.value.directory);assertTrue(model.state.value.commands.isEmpty())
    }
    /** 用户核查只使用权威重查接口，不再次派发原控制。 */
    @Test fun attentionRecheckDoesNotDispatchControl() = runTest {
        val repo = Fake(directory, command);repo.showPending = true
        val model = AgentTaskSession(repo, this);model.reset("session", true);model.refresh();model.recheck()
        assertEquals(1, repo.rechecks);assertEquals(0, repo.posts)
    }

    /** 全新会话目录404只代表尚未建立；连续轮询不能查询不存在的命令并反过来锁住首条输入。 */
    @Test fun brandNewMissingSessionRemainsReadyAcrossReadPolls() = runTest {
        val repo = Fake(directory, command);repo.scopeMissing = true
        val model = AgentTaskSession(repo, this);model.reset("new", false)
        model.refresh();model.refresh();model.refresh()
        assertFalse("初始空会话不能误判需要恢复", model.state.value.requiresReload)
        assertEquals(0, repo.pendingReads);assertEquals(0, repo.eventReads)
        assertEquals(3, repo.directoryReads);assertEquals(0, repo.posts)
    }

    /** 已核实为空的新会话继续只读轮询时不能反复进入交接加载，避免欢迎页闪现不存在的任务。 */
    @Test fun knownEmptyScopePollKeepsReadyWhileDirectoryReadIsInFlight() = runTest {
        val repo = Fake(directory, command);repo.scopeMissing = true
        val model = AgentTaskSession(repo, this);model.reset("new", false);model.refresh()
        assertNotNull(model.state.value.directory);assertFalse(model.state.value.loading)
        // 暂挂第二次真实读取边界，观察请求仍在途时的状态，而非只验证404回包后的最终状态。
        val gate = CompletableDeferred<Unit>();repo.gate = gate
        val poll = launch { model.refresh() };testScheduler.runCurrent()
        try {
            assertFalse("已核实空会话的后台读取不能伪装任务交接", model.state.value.loading)
            assertFalse(model.state.value.requiresReload);assertNull(model.state.value.unresolvedRequestId)
        } finally { gate.complete(Unit);poll.join() }
        assertEquals(2, repo.directoryReads);assertEquals(0, repo.pendingReads);assertEquals(0, repo.posts)
    }

    /** 已有会话404或带丢失请求指针时仍必须保守锁定，不能借初始空目录规则抹去未知控制。 */
    @Test fun establishedOrUnresolvedMissingSessionStaysBlocked() = runTest {
        for ((established, pointer) in listOf(true to null, false to "unknown-request")) {
            val repo = Fake(directory, command);repo.scopeMissing = true
            val model = AgentTaskSession(repo, this);model.reset("existing", established, pointer);model.refresh()
            assertTrue(model.state.value.requiresReload);assertEquals(0, repo.posts)
            assertEquals(pointer, model.state.value.unresolvedRequestId)
        }
    }

    /** 网络替身只提供合成权威响应，不包含任务策略的镜像逻辑。 */
    private class Fake(val value: AgentTaskDirectory, val result: AgentTaskCommand) : AgentTaskRepository {
        var posts = 0;var rechecks = 0;var missing = false;var showPending = false
        var scopeMissing = false;var directoryReads = 0;var pendingReads = 0;var eventReads = 0
        var gate: CompletableDeferred<Unit>? = null
        val lookups = mutableListOf<String>()
        override suspend fun directory(sessionId: String): ApiResult<AgentTaskDirectory> {
            directoryReads++;gate?.await()
            return if (scopeMissing) ApiResult.Failure(ApiError.Http(404, "会话不存在")) else ApiResult.Success(value)
        }
        override suspend fun events(sessionId: String, after: Long): ApiResult<List<AgentTaskEvent>> {
            eventReads++;return ApiResult.Success(emptyList())
        }
        override suspend fun pending(sessionId: String): ApiResult<List<AgentTaskCommand>> {
            pendingReads++
            return if (scopeMissing) ApiResult.Failure(ApiError.Http(404, "会话不存在")) else ApiResult.Success(if (showPending) listOf(result) else emptyList())
        }
        override suspend fun byRequest(sessionId: String, requestId: String): ApiResult<AgentTaskCommand> {
            lookups += requestId;return if (missing) ApiResult.Failure(ApiError.Http(404, "未知")) else ApiResult.Success(result)
        }
        override suspend fun command(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> = ApiResult.Success(result)
        override suspend fun recheck(sessionId: String, commandId: String): ApiResult<AgentTaskCommand> { rechecks++;return ApiResult.Success(result) }
        /** 延迟首次POST响应以验证C交接与D核查的切换，控制仍只记一次实际调用。 */
        var controlGate: CompletableDeferred<Unit>? = null
        override suspend fun control(sessionId: String, request: AgentTaskControl): ApiResult<AgentTaskCommand> { posts++;controlGate?.await();return ApiResult.Failure(ApiError.Http(503, "响应丢失")) }
        override suspend fun input(sessionId: String, text: String, requestId: String, selection: AgentSelectionRef?, confirmation: JsonObject?): ApiResult<AgentMessageResult> = error("恢复不能发送业务输入")
        override suspend fun task(sessionId: String, taskId: String): ApiResult<AgentTaskProjection> = ApiResult.Success(value.current!!)
    }
}
