// V1 内部工具传输模块：仅在完整 Draft 7 校验后转成 DTO，避免反序列化时丢弃未知字段。
package com.fandazi.feature.agent;

import java.util.Map;

/** 执行器传入的动作身份与业务载荷；userId 必须来自内部 JWT，版本缺省为 1，不接受模型自造动作键。 */
public record InternalV1ToolRequest(String schemaVersion, String userId, String sessionId, String traceId,
                                    String runId, String taskId, String idempotencyKey, Integer actionVersion,
                                    Map<String, Object> payload) {
    /** 规范化执行器初始版本；结构校验已经拒绝零、负数和非整数。 */
    public int version() { return actionVersion == null ? 1 : actionVersion; }
}
