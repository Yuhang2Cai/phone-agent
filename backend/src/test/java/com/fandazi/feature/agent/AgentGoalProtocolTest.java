// AG04目标协议组件测试：使用人工2.3载荷验证完整Schema选择，不从生产Schema生成预期。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证2.3请求和目标DTO的公开边界；不把结构通过当作恢复业务已实现。 */
class AgentGoalProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 在人工2.2样例上显式增加目标版本，避免复制生产Schema构造答案。 */
    @SuppressWarnings("unchecked")
    private Map<String,Object> request() throws Exception {
        try (var input=getClass().getResourceAsStream("/task-lifecycle-protocol/request-v22.json")) {
            var value=mapper.readValue(input,new TypeReference<Map<String,Object>>(){});
            value.put("schemaVersion","2.3");
            var context=(Map<String,Object>)value.get("context");
            var admission=(Map<String,Object>)context.get("taskAdmission");
            admission.put("protocolVersion","2.3");
            context.put("goalContext",new LinkedHashMap<>(Map.of(
                    "goalRevision",2,"planRevision",3,"effectVersion",1,
                    "goalIds",List.of("goal-store","goal-deal"))));
            return value;
        }
    }

    @Test void validV23RequestMustPassWithoutChangingV22() throws Exception {
        // 红测必须命中新版本不受支持；文件读取、编译或依赖错误都不算有效红灯。
        var validator=new AgentProtocolValidator(mapper);
        assertDoesNotThrow(()->validator.validateRequest(request()),"合法2.3必须按独立Schema校验");
        try (var input=getClass().getResourceAsStream("/task-lifecycle-protocol/request-v22.json")) {
            var old=mapper.readValue(input,new TypeReference<Map<String,Object>>(){});
            assertDoesNotThrow(()->validator.validateRequest(old),"原2.2行为必须保持");
        }
    }

    @Test void oldVersionCannotCarryGoalContext() throws Exception {
        /** 旧版本即使字段内容合法也必须拒绝，不能按“最新Schema”宽松解析。 */
        var raw=request();raw.put("schemaVersion","2.2");
        var admission=(Map<String,Object>)((Map<String,Object>)raw.get("context")).get("taskAdmission");
        admission.put("protocolVersion","2.2");
        assertThrows(AgentServiceUnavailableException.class,
                ()->new AgentProtocolValidator(mapper).validateRequest(raw));
    }

    @Test void goalOutcomeAndRecoveryRequestRejectPrivateOrUnknownFields() {
        /** 公开DTO只保留有限摘要；伪成功、授权和未知状态均应被完整Schema拒绝。 */
        var validator=new AgentProtocolValidator(mapper);
        var outcome=new LinkedHashMap<String,Object>();
        outcome.put("goalId","goal-store");outcome.put("description","找到符合预算的门店");
        outcome.put("status","recoverable");outcome.put("reasonCode","read_temporarily_unavailable");
        outcome.put("resourceSummary",null);outcome.put("completedConditionLabels",List.of());
        outcome.put("missingConditionLabels",List.of("store_candidates"));
        outcome.put("allowedActions",List.of("retry_reads","drop_remaining"));
        outcome.put("goalRevision",2);outcome.put("planRevision",3);outcome.put("effectVersion",1);
        assertDoesNotThrow(()->validator.validateGoalPayload("goalOutcome",outcome));
        var privateOutcome=new LinkedHashMap<>(outcome);privateOutcome.put("confirmationGrantId","forged");
        assertThrows(AgentServiceUnavailableException.class,
                ()->validator.validateGoalPayload("goalOutcome",privateOutcome));
        var unknown=new LinkedHashMap<>(outcome);unknown.put("status","almost_done");
        assertThrows(AgentServiceUnavailableException.class,
                ()->validator.validateGoalPayload("goalOutcome",unknown));

        var recovery=new LinkedHashMap<String,Object>();recovery.put("schemaVersion","2.3");
        recovery.put("requestId","recovery-1");recovery.put("goalIds",List.of("goal-store"));
        recovery.put("action","retry_reads");recovery.put("expectedGoalRevision",2);
        recovery.put("expectedPlanRevision",3);recovery.put("expectedEffectVersion",1);
        assertDoesNotThrow(()->validator.validateGoalPayload("goalRecoveryRequest",recovery));
        recovery.put("succeeded",true);
        assertThrows(AgentServiceUnavailableException.class,
                ()->validator.validateGoalPayload("goalRecoveryRequest",recovery));
    }

    @Test void v21ToolEnvelopeRequiresGoalFenceAndKeepsBusinessCatalog() throws Exception {
        /** 新工具信封同时携带任务与目标围栏；旧2.0不能夹带目标版本。 */
        var validator=new AgentToolSchemaValidator(mapper);var source=request();
        var raw=new LinkedHashMap<String,Object>();raw.put("schemaVersion","2.1");
        for(String key:new String[]{"userId","sessionId","traceId","runId"})raw.put(key,source.get(key));
        raw.put("taskId","dag-read-node");raw.put("idempotencyKey","synthetic-key");
        raw.put("actionVersion",1);raw.put("payload",Map.of());
        raw.put("taskFence",((Map<?,?>)((Map<?,?>)source.get("context")).get("taskAdmission")).get("fence"));
        raw.put("goalFence",Map.of("goalRevision",2,"planRevision",3,"effectVersion",1,
                "goalIds",List.of("goal-store")));
        assertDoesNotThrow(()->validator.validateV21Request("search_stores",raw));
        var missing=new LinkedHashMap<>(raw);missing.remove("goalFence");
        assertThrows(ToolBoundaryException.class,()->validator.validateV21Request("search_stores",missing));
        assertThrows(ToolBoundaryException.class,()->validator.validateV2Request("search_stores",raw));
    }
}
