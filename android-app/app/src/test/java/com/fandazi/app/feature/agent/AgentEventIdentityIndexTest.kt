// 内存身份索引回归：保持原协议归属、顺序和数据库游标独立性，不按文字或资源去重。
package com.fandazi.app.feature.agent

import org.junit.Assert.*
import org.junit.Test

/** 2000事件重放与暂停任务新trace覆盖，防止优化吞掉后续输入的真实进度。 */
class AgentEventIdentityIndexTest {
    /** 相同原身份重放仅接纳首次，各轮次新trace即使序号相同仍须接纳。 */
    @Test fun twoThousandReplayEventsKeepOrderAndNewTrace() {
        val index = AgentEventIdentityIndex()
        val original = (1..2000).map { event(it.toLong(),"event-$it","run","trace",it.toLong()) }
        assertEquals(original,index.newEvents(original))
        assertTrue(index.newEvents(original+original.map { it.copy(id=it.id!!+5000) }).isEmpty())
        assertTrue(index.accept(event(9000,"new","run","new-input",1)))
        index.reset()
        assertEquals(original,index.newEvents(original))
    }

    /** 任一身份命中都是重复，但被拒事件的其他新别名不能扩张索引，保持原any扫描语义。 */
    @Test fun duplicatesDoNotRegisterUnacceptedAliases() {
        val index=AgentEventIdentityIndex()
        assertTrue(index.accept(event(1,"first","run","trace",1)))
        assertFalse(index.accept(event(2,"first","run","trace",2)))
        assertTrue(index.accept(event(2,"second","run","trace",2)))
        assertFalse(index.accept(event(2,"third","run","trace",3)))
    }

    /** 构造真实字段组合，数据库ID与原eventId不能互相替代。 */
    private fun event(id:Long, eventId:String, run:String, trace:String, seq:Long)=AgentEvent(id,"progress","相同文案",
        payload=mapOf("eventId" to eventId,"runId" to run,"traceId" to trace,"sequence" to seq.toString()))
}
