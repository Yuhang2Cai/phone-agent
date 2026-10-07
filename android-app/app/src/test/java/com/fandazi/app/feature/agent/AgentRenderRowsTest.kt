// 四类投影回归：检查行归属、稳定键、不可变快照和确认失效；不测试ASR或写死生产业务数据。
package com.fandazi.app.feature.agent

import org.junit.Assert.*
import org.junit.Test

/** 合成输入只用于断言投影，不作为界面业务兜底。 */
class AgentRenderRowsTest {
    /** 用户、助手、所属步骤和卡片按原轮次排布，相同资源不同轮具有不同子键。 */
    @Test fun preservesRoundOrderAndSeparatesRepeatedResources() {
        val card=AgentCardPayload("store",storePublicId="synthetic-store",fields=mapOf("name" to "夹具门店"))
        val event=AgentEvent(1,"progress","步骤",payload=mapOf("runId" to "run","stepId" to "search","stepStatus" to "completed"))
        val state=AgentUiState(messages=listOf(ChatMessage("问题",true,displayKey="u"),ChatMessage("回答",false,listOf(card),listOf(event),displayKey="a"),ChatMessage("再答",false,listOf(card),displayKey="b")))
        val rows=AgentRenderRows.project(state,true){false}
        assertEquals(listOf(AgentRowType.MESSAGE,AgentRowType.MESSAGE,AgentRowType.PROGRESS,AgentRowType.CARD,AgentRowType.MESSAGE,AgentRowType.CARD),rows.map{it.type})
        assertEquals(rows.size,rows.map{it.key}.toSet().size)
        assertNotEquals(rows[3].key,rows[5].key)
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
    /** 重放步骤仅保留最后状态，历史步骤和当前步骤不会混入同一个行。 */
    @Test fun replayKeepsLatestStepInItsOwnRound() {
        val one=AgentEvent(1,"progress","搜索",payload=mapOf("runId" to "r","stepId" to "s","stepStatus" to "running"))
        val two=one.copy(id=2,payload=one.payload+ ("stepStatus" to "completed"))
        val state=AgentUiState(messages=listOf(ChatMessage("历史",false,events=listOf(one,two),displayKey="old"),ChatMessage("问题",true,displayKey="now")),isSending=true)
        val rows=AgentRenderRows.project(state,true){false}.filterIsInstance<AgentRenderRow.Progress>()
        assertEquals(2,rows.size);assertEquals(1,rows.first().steps.size)
        assertEquals("已完成",rows.first().steps.single().status);assertEquals("正在连接助手",rows.last().steps.single().label)
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
