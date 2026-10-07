// 四类投影回归：检查行归属、稳定键、不可变快照和确认失效；不测试ASR或写死生产业务数据。
package com.fandazi.app.feature.agent

import org.junit.Assert.*
import org.junit.Test

/** 合成输入只用于断言投影，不作为界面业务兜底。 */
class AgentRenderRowsTest {
    /** 已完成回复仅展示正文和卡片；相同资源在不同轮仍具有独立子键。 */
    @Test fun preservesRoundOrderAndSeparatesRepeatedResources() {
        val card=AgentCardPayload("store",storePublicId="synthetic-store",fields=mapOf("name" to "夹具门店"))
        val event=AgentEvent(1,"progress","步骤",payload=mapOf("runId" to "run","stepId" to "search","stepStatus" to "completed"))
        val state=AgentUiState(messages=listOf(ChatMessage("问题",true,displayKey="u"),ChatMessage("回答",false,listOf(card),listOf(event),displayKey="a"),ChatMessage("再答",false,listOf(card),displayKey="b")))
        val rows=AgentRenderRows.project(state,true){false}
        assertEquals(listOf(AgentRowType.MESSAGE,AgentRowType.MESSAGE,AgentRowType.CARD,AgentRowType.MESSAGE,AgentRowType.CARD),rows.map{it.type})
        assertEquals(rows.size,rows.map{it.key}.toSet().size)
        assertNotEquals(rows[2].key,rows[4].key)
        assertEquals(rows.map{it.key},AgentRenderRows.project(state.copy(composerText="新草稿"),true){false}.map{it.key})
    }
    /** 卡字段不共享可变输入；待确认失效仍沿用原行键而产生内容差量。 */
    @Test fun freezesFieldsAndChangesActionSnapshot() {
        val fields=mutableMapOf("title" to "原摘要")
        val card=AgentCardPayload("confirmation",fields=fields)
        val state=AgentUiState(messages=listOf(ChatMessage("回答",false,listOf(card),displayKey="a")))
        val active=AgentRenderRows.project(state,true){true}.filterIsInstance<AgentRenderRow.Card>().single()
        fields["title"]="被改动"
        assertEquals("原摘要",active.card.fields["title"])
        val expired=AgentRenderRows.project(state,true){false}.filterIsInstance<AgentRenderRow.Card>().single()
        assertEquals(active.key,expired.key);assertNotEquals(active,expired);assertFalse(expired.current)
        assertThrows(UnsupportedOperationException::class.java){(active.card.fields as MutableMap)["title"]="越界"}
    }
    /** 新一轮只显示当前步骤，历史完成步骤不会再次出现在旧回答下方。 */
    @Test fun replayKeepsLatestStepInItsOwnRound() {
        val one=AgentEvent(1,"progress","搜索",payload=mapOf("runId" to "r","stepId" to "s","stepStatus" to "running"))
        val two=one.copy(id=2,payload=one.payload+ ("stepStatus" to "completed"))
        val state=AgentUiState(messages=listOf(ChatMessage("历史",false,events=listOf(one,two),displayKey="old"),ChatMessage("问题",true,displayKey="now")),events=listOf(one,two),isSending=true)
        val rows=AgentRenderRows.project(state,true){false}.filterIsInstance<AgentRenderRow.Progress>()
        assertEquals(1,rows.size);assertEquals(1,rows.single().steps.size)
        assertEquals("已完成",rows.single().steps.single().status)
        assertTrue(rows.single().key.startsWith("message:now:current:"))
        // 工具步骤可以先完成，只有整轮停止等待才移除进度；保留原事件供历史归属和去重。
        assertTrue(AgentRenderRows.project(state.copy(isSending=false),true){false}.none{it is AgentRenderRow.Progress})
        assertEquals(listOf(one,two),state.messages.first().events)
    }

    /** HTTP/SSE暂停、完成和失败均通过isSending结束进度，恢复旧事件也不能复活进度行。 */
    @Test fun settledAndRecoveredRepliesNeverShowProgress() {
        val event=AgentEvent(1,"progress","提交退款",payload=mapOf("runId" to "r","stepId" to "refund","stepStatus" to "completed"))
        val reply=ChatMessage("服务端回复",false,events=listOf(event),displayKey="reply")
        val settled=AgentUiState(messages=listOf(reply),events=listOf(event),isSending=false)
        listOf(settled,settled.copy(error=AgentError.Network("连接中断")),settled.copy(isRecoveringHistory=true)).forEach { state ->
            assertTrue(AgentRenderRows.project(state,true){false}.none{it is AgentRenderRow.Progress})
            assertEquals(listOf(event),state.messages.single().events)
        }
    }
    /** 未认证仅输出登录引导，私人历史与错误不能残留；空态归同一回收类型。 */
    @Test fun guestAndEmptyUseOnlyOneGuideType() {
        val privateState=AgentUiState(messages=listOf(ChatMessage("私人正文",false)),error=AgentError.Network("错误"))
        val guest=AgentRenderRows.project(privateState,false){true}
        assertEquals(listOf(AgentRenderRow.Guide("guide:login",true)),guest)
        assertEquals(listOf(AgentRenderRow.Guide("guide:empty",false)),AgentRenderRows.project(AgentUiState(),true){false})
        assertEquals(4,AgentRowType.entries.size)
    }
    /** 正文相同仍是两条消息；恢复提示和错误复用消息类型，历史卡不能取得当前动作资格。 */
    @Test fun equalTextIsNotIdentityAndHistoricalCardsStayReadOnly() {
        val card=AgentCardPayload("confirmation",historicalReadOnly=true)
        val rows=AgentRenderRows.project(AgentUiState(messages=listOf(ChatMessage("一样",false,listOf(card),displayKey="one"),ChatMessage("一样",false,displayKey="two")),error=AgentError.Network("断网"),historyNotice="只读恢复失败"),true){true}
        assertEquals(4,rows.size);assertNotEquals(rows[0].key,rows[2].key)
        assertFalse((rows[1] as AgentRenderRow.Card).current)
        assertEquals("只读恢复失败",(rows.last() as AgentRenderRow.Message).body)
    }
}
