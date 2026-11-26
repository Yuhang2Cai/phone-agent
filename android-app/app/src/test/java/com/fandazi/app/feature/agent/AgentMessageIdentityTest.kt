// 消息身份契约：创建时分配、copy和增量沿用，禁止用相同正文合并不同轮。
package com.fandazi.app.feature.agent
import org.junit.Assert.*
import org.junit.Test
/** 既有15.5身份的定向回归，HTTP及历史实际路径由恢复和ViewModel组合测试共同验证。 */
class AgentMessageIdentityTest {
    /** 增量和最终答复替换必须保持同一气泡键；另一次创建即使同正文也不同。 */
    @Test fun copyDeltaAndFinalKeepOriginalKey() {
        val first=ChatMessage("",false,streaming=true)
        val delta=first.copy(text="增量")
        val final=delta.copy(text="完整回答",streaming=false)
        assertEquals(first.displayKey,delta.displayKey);assertEquals(first.displayKey,final.displayKey)
        assertNotEquals(first.displayKey,ChatMessage("",false).displayKey)
    }
    /** 子行键继承所属消息：插入另一轮或修改正文不使原资源重新创建。 */
    @Test fun childKeySurvivesTextChangeAndEarlierInsertion() {
        val card=AgentCardPayload("deal",storePublicId="s",dealPublicId="d")
        val msg=ChatMessage("回答",false,listOf(card),displayKey="persisted:trace")
        val before=AgentRenderRows.project(AgentUiState(messages=listOf(msg)),true){false}.filterIsInstance<AgentRenderRow.Card>().single().key
        val after=AgentRenderRows.project(AgentUiState(messages=listOf(ChatMessage("新前缀",true),msg.copy(text="新的完整正文"))),true){false}.filterIsInstance<AgentRenderRow.Card>().single().key
        assertEquals(before,after)
    }
}
