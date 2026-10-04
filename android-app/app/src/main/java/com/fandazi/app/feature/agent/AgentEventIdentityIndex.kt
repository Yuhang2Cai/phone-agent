// 会话内事件身份索引：沿用原协议身份去重，数据库行ID仍独立用于SSE续接。
package com.fandazi.app.feature.agent

/** 与所属会话同步创建、恢复和释放；禁止把索引写入SavedState或私人磁盘。 */
class AgentEventIdentityIndex {
    private val eventIds = HashSet<String>()
    private val databaseIds = HashSet<Long>()
    private val sequences = HashSet<SequenceIdentity>()

    /** 返回是否首次接纳；任一已有身份命中即为重放，不以资源ID或文字内容去重。 */
    fun accept(event: AgentEvent): Boolean {
        val sequence = if (event.runId != null && event.traceId != null && event.sequence != null)
            SequenceIdentity(event.runId!!, event.traceId!!, event.sequence!!) else null
        if (event.eventId?.let(eventIds::contains) == true || event.id?.let(databaseIds::contains) == true ||
            sequence?.let(sequences::contains) == true) return false
        // 与原列表any语义一致：只有首次接纳的事件登记身份，重复事件不能扩张别名集合。
        event.eventId?.let(eventIds::add)
        event.id?.let(databaseIds::add)
        sequence?.let(sequences::add)
        return true
    }

    /** 批量先过滤后一次合并，避免每个HTTP事件复制并扫描增长中的完整列表。 */
    fun newEvents(events: List<AgentEvent>): List<AgentEvent> = events.filter(::accept)

    /** 账号切换、重新恢复或新当前轮释放全部旧索引；重新登记必须来自实际内存状态。 */
    fun reset(events: List<AgentEvent> = emptyList()) {
        eventIds.clear(); databaseIds.clear(); sequences.clear()
        events.forEach(::accept)
    }

    /** 同run的新输入可以重新从sequence=0开始，trace必须参与身份。 */
    private data class SequenceIdentity(val run: String, val trace: String, val sequence: Long)
}
