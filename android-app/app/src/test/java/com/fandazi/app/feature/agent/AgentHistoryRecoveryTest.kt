// 聊天历史恢复回归：全部快照页应用后再续接，取消/跨账号/离线不得重发用户请求或复活授权。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 使用真实恢复服务与ViewModel，替身仅控制每页网络到达顺序，不造产品业务字段。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentHistoryRecoveryTest {
    /** 50/200长正文全部读取，用户client键和助手trace键稳定，相同文案各轮均保留。 */
    @Test fun longMessagesAndStableKeysRecoverAcrossAllPages() = runTest {
        for(count in listOf(50,200)) {
            val repository=HistoryRepository(count)
            val result=AgentHistoryRecovery(repository).load("session") as ApiResult.Success
            assertEquals(count,result.value.messages.size)
            assertEquals(count,result.value.messages.map { it.displayKey }.toSet().size)
            assertEquals("user:client-0",result.value.messages.first().displayKey)
            assertEquals("assistant:trace-0",result.value.messages[1].displayKey)
            assertTrue(result.value.messages.all { it.text=="相同完整正文".repeat(1600) })
            assertEquals((count+49)/50,repository.loads)
            assertEquals(0,repository.sends)
        }
    }

    /** 第2页等待期间没有SSE；全页应用完成后仅使用快照上界，不使用SavedState旧游标。 */
    @Test fun sseOnlyStartsAfterLastPageAndHistoryCardsNeverExecute() = runTest {
        val gate=CompletableDeferred<Unit>()
        val repository=HistoryRepository(200,gate)
        val vm=AgentViewModel(repository,saved(),StandardTestDispatcher(testScheduler))
        vm.openAssistant();runCurrent()
        assertTrue(vm.uiState.value.isRecoveringHistory)
        assertEquals(null,repository.streamCursor)
        vm.sendText("恢复期间不得写入");assertEquals(0,repository.sends)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(200,vm.uiState.value.messages.size)
        assertEquals(600L,repository.streamCursor)
        assertFalse(vm.uiState.value.authorization.isTaskAuto)
        val card=AgentCardPayload("confirmation",historicalReadOnly=true,
            confirmationIdentity=AgentConfirmationIdentity("run","trace","event",1))
        assertFalse(vm.isCurrentReplyCard(card))
        assertFalse(vm.replyToCard(AgentNavigationRequest.AgentReply("确认",card)))
        assertEquals(0,repository.sends)
    }

    /** 账号切换取消读取，旧页即使就绪也不能回写新会话或清掉新草稿。 */
    @Test fun accountChangeCancelsOldRecoveryAndIndex() = runTest {
        val gate=CompletableDeferred<Unit>()
        val repository=HistoryRepository(200,gate)
        val vm=AgentViewModel(repository,saved(),StandardTestDispatcher(testScheduler))
        vm.openAssistant();runCurrent()
        vm.bindAuthenticatedOwner("new-owner")
        vm.updateComposer("新账号草稿")
        gate.complete(Unit);advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals("新账号草稿",vm.uiState.value.composerText)
        assertEquals(null,repository.streamCursor)
        assertEquals(0,repository.sends)
    }

    /** 无网明确要求联网、保留草稿；重试仅重读历史，空旧表不能伪造用户话语。 */
    @Test fun offlineRetryAndEmptyHistoryNeverResend() = runTest {
        val repository=HistoryRepository(0).apply { offline=true }
        val vm=AgentViewModel(repository,saved(),StandardTestDispatcher(testScheduler))
        vm.openAssistant();advanceUntilIdle()
        assertEquals("完整草稿",vm.uiState.value.composerText)
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.historyNotice!!.contains("不会自动重发"))
        vm.retry();advanceUntilIdle();assertEquals(0,repository.sends)
        repository.offline=false
        vm.retry();advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.historyNotice!!.contains("没有可恢复"))
        assertEquals(0,repository.sends)
    }

    /** 游标循环、快照上界变化和跨会话页必须失败，不能部分应用后冒称恢复完整。 */
    @Test fun malformedSnapshotFailsBeforeApplication() = runTest {
        for(kind in listOf("cycle","boundary","session")) {
            val repository=HistoryRepository(200).apply { invalid=kind }
            assertTrue(AgentHistoryRecovery(repository).load("session") is ApiResult.Failure)
            assertEquals(0,repository.sends)
        }
    }

    /** 未完成任务只拼真实增量，流式气泡持续沿用trace键；空旧表不反造用户输入。 */
    @Test fun unfinishedSnapshotPreservesStreamIdentityAndNeverInventsUser() = runTest {
        val delta=AgentEvent(600,"text_delta","增量",payload=mapOf("delta" to "真实增量", "traceId" to "unfinished", "runId" to "run", "eventId" to "delta"))
        val repository=object:AgentRepository by HistoryRepository(0) {
            override suspend fun loadHistory(sessionId:String,cursor:String?,limit:Int)=ApiResult.Success(
                AgentHistoryPage(sessionId,emptyList(),listOf(delta),600,null,"EMPTY","run","unfinished","running"))
        }
        val result=AgentHistoryRecovery(repository).load("session") as ApiResult.Success
        assertTrue(result.value.emptyMessageHistory)
        assertTrue(result.value.hasUnfinishedRun)
        assertTrue(result.value.messages.none { it.fromUser })
        assertEquals("真实增量",result.value.messages.single().text)
        assertTrue(result.value.messages.single().streaming)
        assertEquals("assistant:unfinished",result.value.messages.single().displayKey)
    }

    /** 历史确认完整公开摘要照常展示，但不绑定任何当前操作身份，不因截止时间有效而复活动作。 */
    @Test fun recoveredConfirmationCardIsExplicitlyReadOnly() = runTest {
        val confirmation=AgentEvent(600,"confirmation_required","确认摘要",payload=mapOf("traceId" to "history-trace",
            "runId" to "run","eventId" to "confirmation","sequence" to "1",
            "summary" to """{"action":"request_refund","resourceId":"synthetic-resource","title":"历史退款摘要","ruleVersion":"v1","amountCents":100,"expiresAt":"2030-01-01T00:00:00Z"}"""))
        val repository=object:AgentRepository by HistoryRepository(0) {
            override suspend fun loadHistory(sessionId:String,cursor:String?,limit:Int)=ApiResult.Success(
                AgentHistoryPage(sessionId,listOf(AgentHistoryMessage("assistant-history","assistant","历史回复","run","history-trace",null,null)),
                    listOf(confirmation),600,null,"AVAILABLE","run","history-trace","waiting_confirmation"))
        }
        val result=AgentHistoryRecovery(repository).load("session") as ApiResult.Success
        val card=result.value.messages.single().cards.single()
        assertEquals("after_sale_refund",card.type)
        assertTrue(card.historicalReadOnly)
        assertEquals(null,card.confirmationIdentity)
    }

    /** 旧完整消息可能没有配套事件；恢复后旧trace的迟到增量与终态只能推进游标，不能追加另一条回复。 */
    @Test fun archivedMessageTraceRejectsLateEventsEvenWithoutStoredEvents() = runTest {
        val repository=HistoryRepository(2)
        val vm=AgentViewModel(repository,saved(),StandardTestDispatcher(testScheduler))
        vm.openAssistant();advanceUntilIdle()
        val original=vm.uiState.value.messages
        // 后端消息记录仍有trace，而事件列表为空；不能从空事件列表误判历史助手轮未归档。
        vm.onAgentEvent(AgentEvent(601,"text_delta","旧增量",payload=mapOf("delta" to "迟到旧正文","traceId" to "trace-0","runId" to "run-0","eventId" to "late-delta")))
        assertEquals("没有配套事件的历史消息也必须阻止旧trace增量追加",original,vm.uiState.value.messages)
        vm.onAgentEvent(AgentEvent(602,"completed","迟到旧终态",status="succeeded",payload=mapOf("traceId" to "trace-0","runId" to "run-0","eventId" to "late-terminal")))
        assertEquals(original,vm.uiState.value.messages)
        assertEquals(602L,vm.uiState.value.lastEventId)
    }

    /** 当前轮在历史快照期间仍运行；旧进度只属于旧助手消息，当前进度和终态不能带入旧trace。 */
    @Test fun restoredUnfinishedRunKeepsProgressAndReplyInItsOwnTrace() = runTest {
        val oldProgress=AgentEvent(9,"progress","旧步骤",status="succeeded",payload=mapOf("traceId" to "old-trace","runId" to "old-run","stepId" to "parsed","label" to "旧步骤"))
        val currentParsed=AgentEvent(10,"parsed","当前已接收",status="running",payload=mapOf("traceId" to "new-trace","runId" to "new-run"))
        val repository=object:AgentRepository by HistoryRepository(0) {
            override suspend fun loadHistory(sessionId:String,cursor:String?,limit:Int)=ApiResult.Success(AgentHistoryPage(sessionId,
                listOf(AgentHistoryMessage("old-answer","assistant","旧回答","old-run","old-trace",null,null),
                    AgentHistoryMessage("new-question","user","新问题","new-run","new-trace","new-key",null)),
                listOf(oldProgress,currentParsed),10,null,"AVAILABLE","new-run","new-trace","running"))
        }
        val vm=AgentViewModel(repository,saved(),StandardTestDispatcher(testScheduler))
        vm.openAssistant();advanceUntilIdle()
        assertEquals("历史旧进度必须留在其所属旧消息",listOf(oldProgress),vm.uiState.value.messages.first().events)
        assertEquals("当前运行进度不得包含旧trace",listOf(currentParsed),vm.uiState.value.events)
        vm.onAgentEvent(AgentEvent(11,"progress","新步骤",status="succeeded",payload=mapOf("traceId" to "new-trace","runId" to "new-run","stepId" to "parsed","label" to "新步骤")))
        vm.onAgentEvent(AgentEvent(12,"completed","新回答",status="succeeded",payload=mapOf("traceId" to "new-trace","runId" to "new-run")))
        assertEquals(3,vm.uiState.value.messages.size)
        assertEquals("新回答",vm.uiState.value.messages.last().text)
        assertTrue(vm.uiState.value.messages.last().events.all { it.traceId=="new-trace" })
        assertEquals(listOf(oldProgress),vm.uiState.value.messages.first().events)
    }

    /** 合成薄状态与真实产品字段一致，不包含正文、卡片或确认凭据。 */
    private fun saved()=SavedStateHandle(mapOf("agent_session_owner" to "owner", "agent_session_id" to "session",
        "agent_session_established" to true,"agent_composer" to "完整草稿","agent_last_event_id" to 19L))

    /** 确定性只读页替身；记录发送/续接事实，页数不截断后端完整历史。 */
    private class HistoryRepository(private val count:Int,private val secondPageGate:CompletableDeferred<Unit>?=null):AgentRepository {
        var loads=0;var sends=0;var streamCursor:Long?=null;var offline=false;var invalid:String?=null
        override suspend fun loadHistory(sessionId:String,cursor:String?,limit:Int):ApiResult<AgentHistoryPage> {
            loads++
            if(offline)return ApiResult.Failure(ApiError.Network("合成离线"))
            if(cursor!=null)secondPageGate?.await()
            val start=cursor?.toInt() ?: 0
            val end=minOf(start+limit,count)
            val messages=(start until end).map { AgentHistoryMessage("message-$it",if(it%2==0)"user" else "assistant",
                "相同完整正文".repeat(1600),"run-${it/2}","trace-${it/2}",if(it%2==0)"client-$it" else null,null) }
            return ApiResult.Success(AgentHistoryPage(if(invalid=="session")"another-session" else sessionId,messages,emptyList(),
                if(invalid=="boundary" && cursor!=null)601 else 600,if(end<count)if(invalid=="cycle")"50" else end.toString() else null,
                if(count==0)"EMPTY" else "AVAILABLE",null,null,"succeeded"))
        }
        override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode):ApiResult<AgentMessageResult> { sends++;error("恢复不得发送") }
        override fun streamEvents(sessionId:String,afterEventId:Long?)=emptyFlow<AgentEvent>().also { streamCursor=afterEventId }
        override suspend fun grantThisTaskAuto(request:ThisTaskAutoRequest)=ApiResult.Success(Unit)
        override suspend fun revokeThisTaskAuto(sessionId:String)=ApiResult.Success(Unit)
    }
}
