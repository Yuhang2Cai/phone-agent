// 展示引用测试：人工固定服务端列表顺序，验证网络往返及重排后保守降级。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.network.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** 只测试公开展示身份，不把卡片可见性当作用户确认或视觉注意。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPresentationReferenceTest {
    /** 合成卡片由服务端响应解码，预期 A/B/C 与元数据序号人工固定。 */
    private fun card(id: String, ordinal: Int, list: String = "list-abc", version: Int = 1): AgentCardPayload =
        requireNotNull(AgentPublicCards.decode(JsonParser.parseString("""
            {"type":"store","resourceId":"$id","storePublicId":"$id","name":"合成门店$id",
             "presentation":{"presentationId":"$list","version":$version,"ordinal":$ordinal,"resourceType":"store","sourceMessageId":"source-old"}}
        """.trimIndent()).asJsonObject))

    @Test fun serverOrderAndReferenceSurviveProjectionWithoutClaimingUserAttention() {
        // 展示投影不排序或生成身份；第二张仍为 B，普通后续输入关联列表而不提前猜第二项。
        val cards = listOf(card("a",1),card("b",2),card("c",3))
        val rows = AgentRenderRows.project(AgentUiState(messages=listOf(ChatMessage("合成结果",false,cards))),true) { false }
            .filterIsInstance<AgentRenderRow.Card>()
        assertEquals(listOf("a","b","c"),rows.map { it.card.storePublicId })
        assertEquals(AgentSelectionRef("list-abc",1),AgentPresentationReferences.current(rows.map { it.card }))
        assertEquals(AgentSelectionRef("list-abc",1,2),AgentPresentationReferences.card(rows[1].card))
        assertFalse(rows[1].current)
    }

    @Test fun paginationReorderMultipleListsAndMissingMetadataNeverReuseOriginalAssociation() {
        // 页内缺首项、调换顺序、两份列表和旧端无元数据都不能继续声称有唯一完整展示列表。
        assertNull(AgentPresentationReferences.current(listOf(card("b",2),card("c",3))))
        assertNull(AgentPresentationReferences.current(listOf(card("b",2),card("a",1))))
        assertNull(AgentPresentationReferences.current(listOf(card("a",1),card("x",1,"other-list"))))
        assertNull(AgentPresentationReferences.current(listOf(AgentCardPayload("store",storePublicId="a"))))
        assertNull(AgentPresentationReferences.card(card("a",1).copy(fields=mapOf("presentation" to "{}"))))
    }

    @Test fun httpSendsOnlyReferenceAndPreservesServerMetadata() = runBlocking {
        // 实际 OkHttp 请求只提交引用，不上传数组、资源资格或确认布尔；响应引用按原顺序保留。
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""
                {"status":"COMPLETED","message":"合成结果","nextAction":"completed","events":[],"cards":[
                 {"type":"store","resourceId":"a","presentation":{"presentationId":"list-abc","version":1,"ordinal":1,"resourceType":"store","sourceMessageId":"old"}},
                 {"type":"store","resourceId":"b","presentation":{"presentationId":"list-abc","version":1,"ordinal":2,"resourceType":"store","sourceMessageId":"old"}}]}
            """.trimIndent()))
            val repository=HttpAgentRepository(server.url("/api/").toString(),{null})
            val result=repository.sendMessage("synthetic-session","第二个",AgentMode.CONFIRM,"synthetic-key",null,AgentSelectionRef("list-abc",1,2)) as ApiResult.Success
            val request=JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("第二个",request.get("text").asString)
            assertEquals(setOf("presentationId","version","ordinal"),request.getAsJsonObject("selectionRef").keySet())
            assertFalse(request.has("presentationEvidence"))
            assertEquals(AgentSelectionRef("list-abc",1),AgentPresentationReferences.current(result.value.cards))
        } finally {server.shutdown()}
    }

    @Test fun finalEventCardsRestoreRegisteredVersionInsteadOfOldCandidateOrder() {
        // 历史最终事件含登记元数据，读取它不会从旧候选顺序猜测引用。
        val event=AgentEvent(1,"completed","合成结果",payload=mapOf("cards" to """
            [{"type":"store","resourceId":"a","presentation":{"presentationId":"list-final","version":2,"ordinal":1,"resourceType":"store","sourceMessageId":"source"}}]
        """.trimIndent()))
        assertEquals(AgentSelectionRef("list-final",2),AgentPresentationReferences.current(AgentPublicCards.fromEvent(event)))
    }

    @Test fun viewModelFreezesCurrentAssociationAcrossNetworkRetry() = runTest {
        // 第一轮真实状态接收有序卡片，第二轮及手工重试都使用冻结引用；旧界面变化不能换绑定。
        val calls=mutableListOf<AgentSelectionRef?>()
        var count=0
        val repository=object:AgentRepository {
            /** 初次合成响应用于验证状态流转，不作为在线模型或真实目录证据。 */
            override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode):ApiResult<AgentMessageResult> {
                count++
                return ApiResult.Success(AgentMessageResult(AgentStatus.COMPLETED,"合成列表","completed",null,emptyList(),cards=listOf(card("a",1),card("b",2),card("c",3))))
            }
            /** 记录生产六参数入口并模拟响应丢失，不能在客户端自行替换列表或悄悄重发。 */
            override suspend fun sendMessage(sessionId:String,text:String,mode:AgentMode,clientMessageKey:String?,compareDealIds:List<String>?,selectionRef:AgentSelectionRef?):ApiResult<AgentMessageResult> {
                calls.add(selectionRef);return ApiResult.Failure(ApiError.Network("合成连接故障"))
            }
            /** 本测试没有服务端事件，保持空流而不伪造完成事件。 */
            override fun streamEvents(sessionId:String,afterEventId:Long?)=emptyFlow<AgentEvent>()
            /** 不涉及自动授权，任何误调用都应使本测试失败。 */
            override suspend fun grantThisTaskAuto(request:ThisTaskAutoRequest):ApiResult<Unit> = error("不应授权")
            /** 不涉及撤销授权，任何误调用都应使本测试失败。 */
            override suspend fun revokeThisTaskAuto(sessionId:String):ApiResult<Unit> = error("不应撤销授权")
        }
        val vm=AgentViewModel(repository,SavedStateHandle(),StandardTestDispatcher(testScheduler))
        vm.sendText("查询门店");advanceUntilIdle()
        vm.sendText("第二个");advanceUntilIdle()
        vm.retry();advanceUntilIdle()
        assertEquals(1,count)
        assertEquals(listOf(AgentSelectionRef("list-abc",1),AgentSelectionRef("list-abc",1)),calls)
    }
}
