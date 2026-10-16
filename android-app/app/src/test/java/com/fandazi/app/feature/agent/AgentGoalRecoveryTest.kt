// AG04 目标恢复测试：验证真实 HTTP 契约、原请求只读回查和账号/任务隔离。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生产仓库使用真实 OkHttp 请求，协调器测试只替换远端响应，不复制动作门控规则。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentGoalRecoveryTest {
    /** 开启 AG04 时，业务输入必须显式声明客户端可展示目标恢复；关闭时请求字节保持原 2.2 结构。 */
    @Test
    fun taskInputDeclaresGoalRecoveryCapabilityOnlyWhenEnabled() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(messageJson()))
            server.enqueue(MockResponse().setBody(messageJson()))
            val disabled = HttpAgentRepository(server.url("/api/").toString(), { "token" },
                taskLifecycleEnabled = true, goalRecoveryEnabled = false)
            val enabled = HttpAgentRepository(server.url("/api/").toString(), { "token" },
                taskLifecycleEnabled = true, goalRecoveryEnabled = true)

            assertTrue(disabled.tasks!!.input("session", "查询门店", "request-disabled") is ApiResult.Success)
            assertTrue(enabled.tasks!!.input("session", "查询门店并推荐套餐", "request-enabled") is ApiResult.Success)

            val oldBody = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            val newBody = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals(setOf("schemaVersion", "requestId", "text"), oldBody.keySet())
            assertEquals("2.2", oldBody["schemaVersion"].asString)
            assertEquals(listOf("GOAL_RECOVERY_V1"), newBody["clientCapabilities"].asJsonArray.map { it.asString })
        }
    }

    /** 目标仓库只有 AG03 与 AG04 两个开关同时启用才装配，并复用正式认证传输。 */
    @Test
    fun applicationRepositoryRequiresBothFlagsAndCarriesAuthentication() = runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(projectionJson()))
            assertNull(HttpAgentRepository(server.url("/api/").toString(), { "token" }).goals)
            assertNull(HttpAgentRepository(server.url("/api/").toString(), { "token" }, goalRecoveryEnabled = true).goals)
            val enabled = HttpAgentRepository(server.url("/api/").toString(), { "session-token" },
                taskLifecycleEnabled = true, goalRecoveryEnabled = true)

            assertTrue(enabled.goals!!.projection("session", "task") is ApiResult.Success)
            assertEquals("Bearer session-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    /** 投影必须走任务下的 2.3 公开路径，并严格解析公开字段。 */
    @Test
    fun projectionReadsExactPublicRouteAndSchema() = runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(projectionJson()))

            val result = repository(server).projection("session value", "task/value")

            assertTrue(result is ApiResult.Success)
            assertEquals("服务端目标", (result as ApiResult.Success).value.outcomes.single().description)
            val sent = server.takeRequest();assertEquals("GET", sent.method)
            assertEquals("/api/agent/sessions/session%20value/tasks/task%2Fvalue/goals", sent.path)
        }
    }

    /** 私有额外字段不能被宽松忽略后进入内存或界面。 */
    @Test
    fun privateProjectionFieldFailsClosed() = runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(projectionJson().replaceFirst("{", "{\"confirmationGrantId\":\"secret\",")))

            val result = repository(server).projection("session", "task")

            assertTrue(result is ApiResult.Failure)
        }
    }

    /** 资源摘要的五个字段按协议均可省略；客户端不能因只返回标题而拒绝整份权威投影。 */
    @Test
    fun optionalResourceSummaryFieldsRemainOptional() = runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(projectionJson().replace(
                "{\"resourceType\":\"order\",\"resourceId\":\"order-public\",\"title\":\"服务端订单\",\"status\":\"PAID\",\"amountCents\":12800}",
                "{\"title\":\"服务端订单\"}"
            )))

            val result = repository(server).projection("session", "task")

            assertTrue(result is ApiResult.Success)
            assertEquals("服务端订单", (result as ApiResult.Success).value.outcomes.single().resourceSummary?.title)
        }
    }

    /** 恢复正文只包含协议字段；结果未知后按原 ID GET，不发送第二个 POST。 */
    @Test
    fun recoveryPostsOnceAndOriginalRequestUsesReadonlyGet() = runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(503));server.enqueue(MockResponse().setBody(recoveryJson()))
            val repo = repository(server)
            val request = AgentGoalRecoveryRequest("same-request", listOf("goal"), "retry_reads", 11, 5, 3)

            assertTrue(repo.recover("session", "task", request) is ApiResult.Failure)
            assertTrue(repo.byRequest("session", "task", "same-request") is ApiResult.Success)

            val post = server.takeRequest();assertEquals("POST", post.method)
            assertEquals("/api/agent/sessions/session/tasks/task/goal-recoveries", post.path)
            val body = JsonParser.parseString(post.body.readUtf8()).asJsonObject
            assertEquals(setOf("schemaVersion", "requestId", "goalIds", "action", "expectedGoalRevision", "expectedPlanRevision", "expectedEffectVersion"), body.keySet())
            assertEquals("GET", server.takeRequest().method);assertEquals(2, server.requestCount)
        }
    }

    /** POST 丢回包后协调器保留薄指针；下一轮只查原请求并读取投影，不重放动作。 */
    @Test
    fun unknownPostPersistsPointerAndNeverPostsAgain() = runTest {
        val remote = FakeGoalRepository();remote.postResult = ApiResult.Failure(ApiError.Network("合成断网"))
        val pointers = mutableListOf<Pair<String?, String?>>()
        val session = AgentGoalSession(remote, this) { taskId, requestId -> pointers += taskId to requestId }
        session.reset("session");session.refresh(task())

        session.recover("retry_reads")

        val requestId = session.state.value.unresolvedRequestId
        assertEquals(1, remote.posts);assertTrue(requestId != null);assertEquals("task", session.state.value.unresolvedTaskId)
        assertEquals("task" to requestId, pointers.last())
        remote.lookupResult = ApiResult.Success(recovery(requestId!!))
        session.refresh(task())
        assertEquals(1, remote.posts);assertEquals(listOf(requestId), remote.lookups)
        assertEquals("accepted", session.state.value.recovery?.state)
        assertEquals(requestId, session.state.value.unresolvedRequestId)
    }

    /** 账号重绑清空旧投影和未知请求；新任务读取不能沿用旧任务展示。 */
    @Test
    fun accountResetAndTaskSwitchDiscardOldGoalState() = runTest {
        val remote = FakeGoalRepository();val session = AgentGoalSession(remote, this)
        session.reset("old");session.refresh(task());assertEquals("task", session.state.value.projection?.conversationTaskId)

        session.reset("new")

        assertNull(session.state.value.projection);assertNull(session.state.value.unresolvedRequestId)
        remote.projectionValue = projection("other", taskVersion = 2)
        session.refresh(task("other", 2));assertEquals("other", session.state.value.projection?.conversationTaskId)
        assertFalse(session.state.value.requiresReload)
    }

    /** 迟到的旧目标响应不得覆盖同任务的新三版本；发生回退时必须保持新事实并禁用动作等待重载。 */
    @Test
    fun lateOlderProjectionKeepsNewestFactsAndRequiresReadonlyReload() = runTest {
        val remote = FakeGoalRepository();val session = AgentGoalSession(remote, this)
        session.reset("session")
        remote.projectionValue = projection(goalRevision = 12, planRevision = 6, effectVersion = 4, description = "较新结果")
        session.refresh(task())
        assertEquals("较新结果", session.state.value.projection?.outcomes?.single()?.description)

        remote.projectionValue = projection(goalRevision = 11, planRevision = 5, effectVersion = 3, description = "迟到旧结果")
        session.refresh(task())

        assertEquals(12L, session.state.value.projection?.goalRevision)
        assertEquals("较新结果", session.state.value.projection?.outcomes?.single()?.description)
        assertTrue(session.state.value.requiresReload)
        assertFalse(AgentGoalActionPolicy.canRecover(session.state.value, task(), "retry_reads"))

        // 下一次权威 GET 可以跨过缺失事件直接取得更高版本；它仍不重放恢复 POST。
        remote.projectionValue = projection(goalRevision = 13, planRevision = 7, effectVersion = 5, description = "缺口后的权威结果")
        session.refresh(task())
        assertEquals(13L, session.state.value.projection?.goalRevision)
        assertEquals("缺口后的权威结果", session.state.value.projection?.outcomes?.single()?.description)
        assertFalse(session.state.value.requiresReload)
        assertEquals(0, remote.posts)
    }

    /** HTTP 仓库复用真实传输执行，但测试只返回合成响应且不写用户或交易数据。 */
    private fun repository(server: MockWebServer): HttpAgentGoalRepository {
        val client = OkHttpClient()
        return HttpAgentGoalRepository(server.url("/api/").toString()) { request ->
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) ApiResult.Success(response.body!!.string())
                else ApiResult.Failure(ApiError.Http(response.code, "合成失败"))
            }
        }
    }

    /** 投影夹具完整覆盖 2.3 必填字段，金额和资源均为测试合成值。 */
    private fun projectionJson() = """{"schemaVersion":"2.3","conversationTaskId":"task","taskVersion":7,"goalRevision":11,"planRevision":5,"effectVersion":3,"aggregateStatus":"recoverable","outcomes":[{"goalId":"goal","description":"服务端目标","status":"recoverable","reasonCode":"dependency_timeout","resourceSummary":{"resourceType":"order","resourceId":"order-public","title":"服务端订单","status":"PAID","amountCents":12800},"completedConditionLabels":["已选区域"],"missingConditionLabels":["营业时间"],"allowedActions":["retry_reads"],"goalRevision":11,"planRevision":5,"effectVersion":3}],"availableActions":["retry_reads"]}"""

    /** 任务输入仓只需要一个合法空事件结果即可验证实际发送字节，不伪造目标或业务成功。 */
    private fun messageJson() = """{"status":"COMPLETED","message":"合成回复","nextAction":"task_state","candidateCart":null,"events":[],"cards":[],"error":null,"taskProtocol":true}"""

    /** 回执只证明请求已受理，测试不会把 accepted 当作业务成功。 */
    private fun recoveryJson() = """{"schemaVersion":"2.3","requestId":"same-request","state":"accepted","action":"retry_reads","goalIds":["goal"],"goalRevision":11,"planRevision":5,"effectVersion":3,"allowedActions":["retry_reads"]}"""

    /** 生产协调器使用的合成投影，三版本与当前任务保持一致。 */
    private fun projection(taskId: String = "task", taskVersion: Long = 7, goalRevision: Long = 11,
        planRevision: Long = 5, effectVersion: Long = 3, description: String = "服务端目标"
    ) = AgentGoalProjection(taskId, taskVersion, goalRevision, planRevision, effectVersion,
        "recoverable", listOf(AgentGoalOutcome("goal", description, "recoverable", "dependency_timeout", null,
            emptyList(), listOf("营业时间"), setOf("retry_reads"), goalRevision, planRevision, effectVersion)), setOf("retry_reads"))

    /** 公开任务夹具只提供当前身份和任务版本。 */
    private fun task(id: String = "task", version: Long = 7) = AgentTaskProjection(id, "composite", "restaurant_recommendation", "active", version, 1,
        "合成任务", AgentTaskStopPoint("waiting_input"), actions = setOf("pause", "cancel"))

    /** 原请求查询返回服务端受理态，仍须等待后续权威投影。 */
    private fun recovery(requestId: String = "same-request") = AgentGoalRecoveryResult(requestId, "accepted", "retry_reads", listOf("goal"), 11, 5, 3, setOf("retry_reads"))

    /** 远端替身只记录真实方法边界；资格、请求 ID 和恢复策略全部由生产协调器决定。 */
    private inner class FakeGoalRepository : AgentGoalRepository {
        var projectionValue = projection();var posts = 0
        var postResult: ApiResult<AgentGoalRecoveryResult> = ApiResult.Success(recovery())
        var lookupResult: ApiResult<AgentGoalRecoveryResult> = ApiResult.Failure(ApiError.Http(404, "未找到"))
        val lookups = mutableListOf<String>()
        override suspend fun projection(sessionId: String, taskId: String): ApiResult<AgentGoalProjection> = ApiResult.Success(projectionValue)
        override suspend fun recover(sessionId: String, taskId: String, request: AgentGoalRecoveryRequest): ApiResult<AgentGoalRecoveryResult> {
            posts++;return postResult
        }
        override suspend fun byRequest(sessionId: String, taskId: String, requestId: String): ApiResult<AgentGoalRecoveryResult> {
            lookups += requestId;return lookupResult
        }
    }
}
