// 任务协议组件测试：冻结的合成样例独立于Schema构造，防止拒绝全部输入而假通过。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 仅验证新旧结构边界；不把组件校验通过当作生命周期或业务执行验收。 */
class AgentTaskProtocolContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 读取人工编写的最小合法请求；每次返回新对象，非法字段测试不污染其他场景。 */
    private Map<String, Object> request() throws Exception {
        try (var input = getClass().getResourceAsStream("/task-lifecycle-protocol/request-v22.json")) {
            return mapper.readValue(input, new TypeReference<>() {});
        }
    }

    @Test void validNewRequestMustPass() throws Exception {
        // 未实现2.2时必须在本业务断言失败，而非编译、依赖或样例读取错误。
        var raw = request();
        assertDoesNotThrow(() -> new AgentProtocolValidator(mapper).validateRequest(raw), "合法2.2必须可校验");
    }

    /** 提取深层可变字段仅供负例篡改；原始样例每个测试重新读取。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @Test void everyFenceFieldIsRequiredAndNestedExtrasAreRejected() throws Exception {
        // 缺任一执行栅栏均拒绝，不从旧协议默认值补齐；scope本身也不可注入授权。
        var validator = new AgentProtocolValidator(mapper);
        for (String key : new String[]{"scope", "conversationTaskId", "runId", "turnId", "focusVersion", "taskVersion", "executionGeneration"}) {
            var raw = request();
            child(child(child(raw, "context"), "taskAdmission"), "fence").remove(key);
            assertThrows(AgentServiceUnavailableException.class, () -> validator.validateRequest(raw), key);
        }
        var raw = request();
        child(child(raw, "context"), "taskAdmission").put("confirmationAccepted", true);
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateRequest(raw));
    }

    @Test void oldVersionsCannotReceiveNewTaskPermission() throws Exception {
        // 新字段权限不能通过宽松旧结构绕入原活动run。
        var validator = new AgentProtocolValidator(mapper);
        for (String version : new String[]{"2.0", "2.1"}) {
            var raw = request(); raw.put("schemaVersion", version);
            assertThrows(AgentServiceUnavailableException.class, () -> validator.validateRequest(raw));
        }
    }

    @Test void ordinaryTurnAndControlDirectoryHaveSeparateShapes() throws Exception {
        // 普通确认可以没有生命周期命令；目录必须只读，不含私有凭证。
        var validator = new AgentProtocolValidator(mapper);
        var admission = child(child(request(), "context"), "taskAdmission");
        assertNull(admission.get("commandId"));
        assertDoesNotThrow(() -> validator.validateTaskPayload("turnAdmission", admission));
        var directory = new LinkedHashMap<String, Object>();
        directory.put("schemaVersion", "2.2"); directory.put("focusVersion", 0);
        directory.put("throughTaskEventId", 0); directory.put("currentTask", null);
        directory.put("pausedTasks", java.util.List.of());
        assertDoesNotThrow(() -> validator.validateTaskPayload("taskDirectory", directory));
        directory.put("confirmationGrantId", "forged");
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateTaskPayload("taskDirectory", directory));
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateTaskPayload("confirmationGrant", Map.of()));
    }

    @Test void v2ToolEnvelopeRequiresFenceAndCannotWidenPayload() throws Exception {
        // 只复用原业务白名单；新信封不得走旧校验器，准备支付不是第18个模型工具。
        var validator = new AgentToolSchemaValidator(mapper);
        var source = request();
        var raw = new LinkedHashMap<String, Object>();
        raw.put("schemaVersion", "2.0");
        for (String key : new String[]{"userId", "sessionId", "traceId", "runId"}) raw.put(key, source.get(key));
        raw.put("taskId", "dag-read-node"); raw.put("idempotencyKey", "synthetic-key");
        raw.put("actionVersion", 1); raw.put("payload", Map.of());
        raw.put("taskFence", child(child(source, "context"), "taskAdmission").get("fence"));
        assertDoesNotThrow(() -> validator.validateV2Request("search_stores", raw));
        assertThrows(ToolBoundaryException.class, () -> validator.validateRequest("search_stores", raw));
        for (String key : new String[]{"taskFence", "actionVersion"}) {
            var invalid = new LinkedHashMap<>(raw); invalid.remove(key);
            assertThrows(ToolBoundaryException.class, () -> validator.validateV2Request("search_stores", invalid));
        }
        raw.put("payload", Map.of("priceCents", 1));
        assertThrows(ToolBoundaryException.class, () -> validator.validateV2Request("search_stores", raw));
        assertThrows(ToolBoundaryException.class, () -> validator.validateV2Request("task_payment_preparation", raw));
        assertDoesNotThrow(() -> validator.validateV2Result(Map.of("schemaVersion", "2.0", "ok", false,
                "data", Map.of(), "error", Map.of("code", "CONFLICT", "message", "任务版本冲突", "retryable", false))));
    }

    @Test void controlReplyRequiresTaskMetadataAndForbidsPrivateGrant() {
        // 控制回复与最终run完成不同；不能伪装终态或输出内部授权。
        var validator = new AgentProtocolValidator(mapper);
        var raw = new LinkedHashMap<String, Object>(Map.of("schemaVersion", "2.2", "eventId", "event-1",
                "eventType", "control_reply", "runId", "run-1", "traceId", "trace-1", "sequence", 0,
                "status", "running", "payload", Map.of("message", "已暂停", "sourceMessageId", "source-1")));
        raw.put("taskContext", Map.of("conversationTaskId", "task-1", "turnId", "turn-1", "taskVersion", 1,
                "focusVersion", 0, "executionGeneration", 1));
        assertDoesNotThrow(() -> validator.validateEvent(raw));
        raw.put("payload", Map.of("message", "已暂停", "sourceMessageId", "source-1", "grant", "forged"));
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateEvent(raw));
    }
}
