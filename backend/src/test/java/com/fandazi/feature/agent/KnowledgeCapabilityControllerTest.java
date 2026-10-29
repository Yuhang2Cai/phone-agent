package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 新任务两版信封的能力声明测试；领域桥是显式替身，只验证HTTP包装，真实围栏由既有集成覆盖。 */
class KnowledgeCapabilityControllerTest {
    /** 相同17工具数据保持不变，只有成功知识响应携带能力；业务错误不能宣称已获权威来源。 */
    @ParameterizedTest @ValueSource(strings={"2.0","2.1"})
    void task_tool_versions_declare_capability_only_on_faq_success(String version) {
        var bridge=mock(AgentTaskToolBridge.class);
        var controller=new InternalV2ToolController(mock(InternalV1JwtValidator.class),mock(AgentToolSchemaValidator.class),bridge,mock(AgentV1ToolAudit.class),new ObjectMapper(),true,true);
        var data=Map.<String,Object>of("faqs",List.of());
        when(bridge.execute(eq("query_faq"),any())).thenReturn(data);
        when(bridge.executeWithGoalContext(eq("query_faq"),any())).thenReturn(new AgentTaskToolBridge.GoalToolResult(data,Map.of()));
        var request=Map.<String,Object>of("schemaVersion",version,"userId","1","sessionId","fixture","runId","run","taskId","faq","payload",Map.of("question","规则"));
        var response=controller.call("query_faq","fixture",request);
        assertEquals(true,response.getBody().get("ok"));
        assertEquals("delivery-v1",response.getHeaders().getFirst("X-Knowledge-Authority"));
        // 原业务异常传播到标准失败信封，不允许附带相同能力头误导Agent继续回答。
        when(bridge.execute(eq("query_faq"),any())).thenThrow(new IllegalStateException("合成目录不可用"));
        when(bridge.executeWithGoalContext(eq("query_faq"),any())).thenThrow(new IllegalStateException("合成目录不可用"));
        var failure=controller.call("query_faq","fixture",request);
        assertEquals(false,failure.getBody().get("ok"));
        assertNull(failure.getHeaders().getFirst("X-Knowledge-Authority"));
    }
}
