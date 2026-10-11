// 上下文工具证据回归：核对真实入口审计的动作身份，不把发送或重复接收当成重复业务执行。
package com.fandazi.feature.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 同一主Agent按步骤验收；此测试验证证据结构，真实HTTP和数据库增量由CC-10在线用例另证。 */
class AgentContextEvidenceTest {
    /** 成功或失败接收都要保留动作版本和不可逆键摘要，禁止公开一次性确认凭证或完整请求。 */
    @Test void auditCorrelatesVersionAndHashedExecutionIdentity() throws Exception {
        AgentEventService events=mock(AgentEventService.class);
        AgentV1ToolAudit audit=new AgentV1ToolAudit(events);
        String key="synthetic-run:synthetic-task:3";
        var request=new InternalV1ToolRequest("1.1","17","synthetic-session","synthetic-trace",
                "synthetic-run","synthetic-task",key,3,Map.of());
        // 输入明确成功的公开结果；审计不会自行推断其他业务对象或执行次数。
        audit.record(request,"query_faq",Map.of("ok",true,"data",Map.of("faqs",java.util.List.of())));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> payload=ArgumentCaptor.forClass(Map.class);
        verify(events).record(eq(17L),eq("synthetic-session"),eq("tool_result"),eq("工具执行结果"),eq("query_faq"),eq("succeeded"),payload.capture());
        Map<String,Object> value=payload.getValue();
        assertEquals(3,value.get("actionVersion"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))),value.get("executionKeyHash"));
        assertEquals("synthetic-run",value.get("runId"));
        assertEquals("synthetic-task",value.get("taskId"));
        assertFalse(value.containsKey("idempotencyKey"),"公开证据使用摘要，不传播内部幂等值");
        assertFalse(value.containsKey("confirmationGrantId"));
    }
}
