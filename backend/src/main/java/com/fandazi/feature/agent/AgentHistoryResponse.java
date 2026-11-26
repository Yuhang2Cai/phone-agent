// V1私有会话历史DTO：消息和脱敏事件分批返回，当前任务只提供只读状态，不恢复任何执行授权。
package com.fandazi.feature.agent;

import java.time.Instant;
import java.util.List;

/** 单页消息与事件合计受limit约束；throughEventId仅在全部页面应用后用于SSE交接。 */
public record AgentHistoryResponse(String schemaVersion,String sessionId,List<Message> messages,
        List<AgentGatewayController.EventResponse> events,long throughEventId,String nextCursor,
        String messageHistoryStatus,CurrentRun currentRun) {
    /** 完整持久消息；公开展示ID与可空客户端键关联气泡，正文不得按分页或列长度截断。 */
    public record Message(String publicId,String role,String content,String runId,String traceId,
            String clientMessageKey,Instant createdAt) {
        /** 从已确认归属的实体构造公开字段，不返回内部记录键或用户实体。 */
        static Message from(AgentMessageEntity row) {
            return new Message(row.getPublicId(),row.getRole(),row.getContent(),row.getRunId(),row.getTraceId(),row.getClientMessageKey(),row.getCreatedAt());
        }
    }
    /** 当前run只读，不携带确认摘要、短时凭证或自动执行授权，旧历史卡不会因此可执行。 */
    public record CurrentRun(String runId,String traceId,String status,boolean readOnly) {}
}
