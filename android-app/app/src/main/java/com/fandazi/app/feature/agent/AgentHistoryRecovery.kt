// 服务端完整快照恢复：有界页读取全部已持久内容，应用完成前不开放SSE或恢复旧确认。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 公开持久消息；客户端展示键只用于视图身份，不是交易幂等键或授权凭据。 */
data class AgentHistoryMessage(val publicId: String, val role: String, val content: String,
    val runId: String?, val traceId: String?, val clientMessageKey: String?, val createdAt: String?)

/** 一页V1历史与固定事件上界；消息和事件合计不能超过请求limit。 */
data class AgentHistoryPage(val sessionId: String, val messages: List<AgentHistoryMessage>,
    val events: List<AgentEvent>, val throughEventId: Long, val nextCursor: String?,
    val messageHistoryStatus: String, val currentRunId: String?, val currentTraceId: String?, val currentRunStatus: String?)

/** 只在全部页收齐后交付，调用方一次应用再以throughEventId订阅SSE。 */
data class AgentRecoveredHistory(val messages: List<ChatMessage>, val events: List<AgentEvent>,
    val throughEventId: Long, val emptyMessageHistory: Boolean, val hasUnfinishedRun: Boolean,
    /** 完整助手记录自己的trace即归档事实，不依赖可空的旧事件表，不包含尚未结束的增量轮。 */
    val archivedTraceIds: Set<String>,
    /** 仅当前运行trace的事件用于活动进度；全历史事件另供身份索引，旧轮步骤仍附着其旧消息。 */
    val currentEvents: List<AgentEvent>)

/** 不重发业务，不使用私人Room；取消直接传播，错误返回原公开网络分类。 */
class AgentHistoryRecovery(private val repository: AgentRepository) {
    /** 有界请求与游标循环检查；不同页必须属于同一会话和事件快照，否则拒绝部分恢复。 */
    suspend fun load(sessionId: String): ApiResult<AgentRecoveredHistory> {
        val messages = ArrayList<AgentHistoryMessage>()
        val events = ArrayList<AgentEvent>()
        val messageIds = HashSet<String>()
        val cursors = HashSet<String>()
        val identities = AgentEventIdentityIndex()
        var cursor: String? = null
        var boundary: Long? = null
        var last: AgentHistoryPage
        do {
            currentCoroutineContext().ensureActive()
            when (val result = repository.loadHistory(sessionId, cursor, 50)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> last = result.value
            }
            // 先校验快照再接纳正文；无效服务端页不能推进已应用游标或显示为完整历史。
            if (last.sessionId != sessionId || last.messages.size+last.events.size>50 || last.throughEventId<0 ||
                (boundary != null && boundary != last.throughEventId) ||
                last.events.any { it.id == null || it.id > last.throughEventId } ||
                last.messages.any { it.publicId.isBlank() || it.role !in setOf("user", "assistant") })
                return invalidHistory()
            boundary = last.throughEventId
            last.messages.forEach { if (messageIds.add(it.publicId)) messages.add(it) }
            events.addAll(identities.newEvents(last.events))
            cursor = last.nextCursor
            if (cursor != null && (!cursors.add(cursor) || (last.messages.isEmpty() && last.events.isEmpty()))) return invalidHistory()
        } while (cursor != null)
        currentCoroutineContext().ensureActive()
        // 按实际输入trace组合历史卡片，不按相同文案或全局资源ID合并不同轮的回复。
        val grouped = events.groupBy { it.traceId }
        val restored = messages.map { message ->
            val own = message.traceId?.let { grouped[it] }.orEmpty()
            ChatMessage(message.content, message.role == "user",
                cards = if (message.role == "assistant") own.flatMap(AgentPublicCards::fromEvent)
                    .distinct().map { it.copy(historicalReadOnly = true, confirmationIdentity = null) } else emptyList(),
                events = if (message.role == "assistant") own else emptyList(),
                displayKey = if (message.role == "user" && message.clientMessageKey != null) "user:${message.clientMessageKey}"
                    else if (message.role == "assistant" && message.traceId != null) "assistant:${message.traceId}"
                    else "history:${message.publicId}")
        }.toMutableList()
        val unfinished = last.currentRunStatus == "running"
        // 未结束助手流只从真实text_delta组装；没有用户记录时明确保持空缺，禁止反造用户气泡。
        if (unfinished && last.currentTraceId != null && messages.none { it.role == "assistant" && it.traceId == last.currentTraceId }) {
            val own = grouped[last.currentTraceId].orEmpty()
            val text = own.filter { it.type == "text_delta" }.joinToString("") { it.payload["delta"] ?: it.payload["text"].orEmpty() }
            if (text.isNotEmpty()) restored.add(ChatMessage(text, false, events=own, streaming=true, displayKey="assistant:${last.currentTraceId}"))
        }
        return ApiResult.Success(AgentRecoveredHistory(restored, events, boundary ?: 0L,
            last.messageHistoryStatus == "EMPTY", unfinished,
            messages.filter { it.role == "assistant" }.mapNotNull { it.traceId }.toSet(),
            if (unfinished && last.currentTraceId != null) grouped[last.currentTraceId].orEmpty() else emptyList()))
    }

    /** 服务端快照无效不能部分宣称恢复成功；显式提示重试读取而非重发原语句。 */
    private fun invalidHistory() = ApiResult.Failure(ApiError.Http(502, "聊天历史快照无效，请重新联网恢复"))
}
