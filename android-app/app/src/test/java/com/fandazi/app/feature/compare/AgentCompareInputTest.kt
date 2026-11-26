// 对比助手输入回归：实际HTTP与真实ViewModel，外部网关明确替身，禁止音频/真实交易。
package com.fandazi.app.feature.compare
import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.feature.agent.*
import com.fandazi.app.core.network.*
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
/** 有序公开ID随用户原文发送，草稿不被默认比较问题覆盖，也不自动提交。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCompareInputTest {
    private val ids=listOf("00000000-0000-4000-8000-000000000201","00000000-0000-4000-8000-000000000202")
    /** 网络入口必须携带独立ID数组，禁止把客户端金额/库存/确认授权写进载荷。 */
    @Test fun actualHttpCarriesOnlySelectedIdsAndUnchangedQuestion()=runBlocking {
        val server=MockWebServer();server.start()
        try {server.enqueue(MockResponse().setBody("""{"status":"COMPLETED","message":"合成完成","nextAction":"done","candidateCart":{},"events":[],"cards":[]}"""));val repo=HttpAgentRepository(server.url("/api/").toString(),{null});assertTrue(repo.sendMessage("fixture","原问题不改",AgentMode.CONFIRM,"key",ids) is ApiResult.Success);val request=server.takeRequest(2,TimeUnit.SECONDS) ?: error("发送未发生");val body=JsonParser.parseString(request.body.readUtf8()).asJsonObject;assertEquals("原问题不改",body.get("text").asString);assertTrue("缺少独立compareDealIds",body.has("compareDealIds"));assertEquals(ids,body.getAsJsonArray("compareDealIds").map{it.asString});assertFalse(body.has("priceCents"));assertFalse(body.has("confirmationAccepted"))}finally{server.shutdown()}
    }
    /** 附加对比只更新引用，原草稿完整保留且无请求；手动发送才记录同一用户文本与ID顺序。 */
    @Test fun attachingContextKeepsDraftAndWaitsForExplicitSend()=runTest {
        val repo=Boundary();val vm=AgentViewModel(repo,SavedStateHandle(),StandardTestDispatcher(testScheduler));vm.updateComposer("已有完整草稿");vm.setComparison(ids);runCurrent();assertEquals("已有完整草稿",vm.uiState.value.composerText);assertEquals(0,repo.calls);vm.sendComposer();advanceUntilIdle();assertEquals("已有完整草稿",repo.text);assertEquals("所选公开ID必须实际发送",ids,repo.selected);assertNull(vm.uiState.value.navigation)
    }
    /** 失败轮重试继续原公开资源顺序与原问题，新选择不能静默替换已发出的比较目标。 */
    @Test fun retryUsesFrozenSelectionAfterUserChangesContext()=runTest {
        val repo=Boundary();repo.failuresRemaining=1
        val vm=AgentViewModel(repo,SavedStateHandle(),StandardTestDispatcher(testScheduler))
        vm.setComparison(ids);vm.updateComposer("请比较原来的两个套餐");vm.sendComposer();advanceUntilIdle()
        assertEquals(1,repo.calls);assertNotNull(vm.uiState.value.error)
        vm.setComparison(listOf(ids[1],"00000000-0000-4000-8000-000000000203"))
        vm.retry();advanceUntilIdle()
        assertEquals(listOf(ids,ids),repo.selections);assertEquals("请比较原来的两个套餐",repo.text)
        assertEquals(1,vm.uiState.value.messages.count{it.fromUser});assertNull(vm.uiState.value.navigation)
    }
    /** 新账号清除已选上下文、草稿和失败重试，防止沿用旧账号的会话输入。 */
    @Test fun switchingOwnerClearsComparisonDraftAndRetry()=runTest {
        val repo=Boundary();repo.failuresRemaining=1;val saved=SavedStateHandle()
        val vm=AgentViewModel(repo,saved,StandardTestDispatcher(testScheduler));vm.bindAuthenticatedOwner("合成账号A")
        vm.setComparison(ids);vm.updateComposer("旧账号问题");vm.sendComposer();advanceUntilIdle()
        vm.updateComposer("尚未发送的旧草稿");vm.bindAuthenticatedOwner("合成账号B");vm.retry();advanceUntilIdle()
        assertEquals(1,repo.calls);assertTrue(vm.comparisonSelection().isEmpty())
        assertNull(saved.get<List<String>>("agent_compare_ids"));assertEquals("",vm.uiState.value.composerText)
        assertTrue(vm.uiState.value.messages.isEmpty());assertNull(vm.uiState.value.navigation)
    }

    /** 合成网关只记录发送，不进行SSE、鉴权、授权或交易。 */
    private class Boundary:AgentRepository {
        // 请求记录独立保存公开引用快照，失败次数只控制明确的网络边界。
        var calls=0;var text="";var selected:List<String>?=null
        var failuresRemaining=0;val selections=mutableListOf<List<String>?>()
        /** 旧发送没有对比引用，能让指定断言发现新字段丢失。 */
        override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode):ApiResult<AgentMessageResult>{calls++;this.text=text;if(failuresRemaining>0){failuresRemaining--;return ApiResult.Failure(ApiError.Network("合成网络失败"))};return ApiResult.Success(AgentMessageResult(AgentStatus.COMPLETED,"合成完成","done",null,emptyList()))}
        /** 五参数网络边界记录实际引用，再沿用旧结果构造。 */
        override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?):ApiResult<AgentMessageResult>{selected=compareDealIds;selections.add(compareDealIds?.toList());return sendMessage(sessionId,text,mode)}
        /** 测试不发送实时流，业务结果只由明确消息回包产生。 */
        override fun streamEvents(sessionId:String,afterEventId:Long?)=emptyFlow<AgentEvent>()
        /** 只读比较不允许自动授权。 */
        override suspend fun grantThisTaskAuto(request:ThisTaskAutoRequest):ApiResult<Unit> = error("比较不得授权")
        /** 本测试不进行业务授权撤销。 */
        override suspend fun revokeThisTaskAuto(sessionId:String)=ApiResult.Success(Unit)
    }
}
