// 任务请求规范化模块：只对传输对象做稳定JSON摘要，不清洗或改写用户原文。
package com.fandazi.feature.agent;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 摘要用于同键异文冲突检查，不是身份签名、确认凭证或业务执行键。 */
final class AgentTaskFingerprint {
    private AgentTaskFingerprint() {}
    /** 固定对象键顺序，不改变字符串/数组顺序；不在异常中输出实际正文。 */
    static String of(Object value) {
        try {
            byte[] raw=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch(Exception error) {throw new IllegalStateException("无法生成任务请求摘要",error);}
    }
    /** 内部scope结构字段均为受控字符串，与Python权威契约完全一致。 */
    static Map<String,Object> scope(AgentTaskRegistryService.Scope value) {
        return Map.of("userId",String.valueOf(value.userId()),"sessionId",value.sessionId(),"conversationEpoch",value.epoch());
    }
    /** 版本只接受规范Schema已经验证的整数；统一long避免Integer/Long导致重试摘要不同。 */
    static long version(Object value) {return ((Number)value).longValue();}
}
