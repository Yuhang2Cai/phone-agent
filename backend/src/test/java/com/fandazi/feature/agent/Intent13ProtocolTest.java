// 十三类协议回归：同一消费者分别按权威契约验证旧版和新版，不容忍新版额外复合布尔。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证新版单标签事件与旧版事件都可消费，未知版本和额外字段均被拒绝。 */
class Intent13ProtocolTest {
    /** 同一事件骨架只改变版本和载荷，确保双版支持没有放宽结构约束。 */
    @Test void validatesBothVersionsAndRejectsMixedPayload() {
        AgentProtocolValidator validator = new AgentProtocolValidator(new ObjectMapper());
        Map<String,Object> event = new LinkedHashMap<>(Map.of("schemaVersion","2.0","eventId","e",
                "eventType","intent_decided","traceId","t","runId","r","sequence",1,"status","running",
                "payload",Map.of("intent","composite")));
        assertDoesNotThrow(() -> validator.validateEvent(event));
        event.put("payload",Map.of("intent","composite","isComposite",true));
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateEvent(event));
        event.put("schemaVersion","1.1");
        event.put("payload",Map.of("intent","coupon","isComposite",false));
        assertDoesNotThrow(() -> validator.validateEvent(event));
        event.put("schemaVersion","9.9");
        assertThrows(AgentServiceUnavailableException.class, () -> validator.validateEvent(event));
    }
}
