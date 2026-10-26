package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 新事件契约的行为断言：指定缺失能力，随后检查过滤、usage未知及UTF8关联键。 */
class AgentOperationsEventTest {
    /** 反射仅用于红测明确表达功能尚缺，不把编译失败当作有效断言。 */
    static Object invoke(String method, Class<?>[] signature, Object... args) {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsEvent"),"缺少AG07安全事件实现");
        try {return type.getMethod(method,signature).invoke(null,args);}
        catch(InvocationTargetException error) {throw (RuntimeException)error.getCause();}
        catch(ReflectiveOperationException error) {throw new AssertionError(error);}
    }

    @Test void removesSensitiveFieldsAndKeepsUsageUnknown() {
        // 不记录凭据或原始payload；缺usage必须是null而非零计费。
        var row=(Map<?,?>)invoke("make",new Class<?>[]{String.class,Map.class},"stage",
            Map.of("service","spring","stage","spring.history","password","秘密哨兵","payload","正文哨兵"));
        assertEquals("ag07.ops.v1",row.get("schemaVersion"));assertNull(row.get("usage"));
        assertFalse(row.toString().contains("哨兵"));assertTrue(row.containsKey("coverage"));
    }

    @Test void rejectsUntrustedLabelsAndInvalidNumbers() {
        // 字段名合法也不能带动态正文、完整地址或伪造数值。
        for(var fields:java.util.List.of(Map.of("stage","用户正文"),Map.of("durationMs",Double.NaN),
            Map.of("traceKey","手机号"),Map.of("usage",Map.of("prompt_tokens",true)))) {
            assertThrows(IllegalArgumentException.class,()->invoke("make",new Class<?>[]{String.class,Map.class},"stage",fields));
        }
    }

    @Test void identityMatchesUtf8Sha256Contract() {
        // 金标只含内部随机标识，不以手机号等低熵敏感字段进行摘要。
        String identity="97d043b8-9aa2-46e6-9092-9f96b3ca856b";
        String expected=java.util.HexFormat.of().formatHex(assertDoesNotThrow(()->java.security.MessageDigest.getInstance("SHA-256"))
            .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,24);
        assertEquals(expected,invoke("identityKey",new Class<?>[]{String.class},identity));
    }

    @Test void eventTypeCannotClaimUnrelatedUsageOrToolFields() {
        // 与Python同一类型规则：心跳不声明模型/工具执行，工具事件不混模型计费。
        var fields=Map.of("usage",Map.of("prompt_tokens",3),"modelId","decision","toolName","query_faq");
        var heartbeat=AgentOperationsEvent.make("heartbeat",fields);
        var tool=AgentOperationsEvent.make("tool",fields);
        assertNull(heartbeat.get("usage"));assertNull(heartbeat.get("toolName"));
        assertNull(tool.get("usage"));assertNull(tool.get("modelId"));assertEquals("query_faq",tool.get("toolName"));
    }
}
