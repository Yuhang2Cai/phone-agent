package com.fandazi.feature.agent;

import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 请求到run的安全关联及事务提交后事件。只引用原业务身份，不签发许可、不读写领域数据。
 * 回调只捕获安全摘要与当前应用接收器，禁止保存请求载荷、用户、grant或异常正文。
 */
public final class AgentOperationsCorrelation {
    private AgentOperationsCorrelation() {}

    /** 已核权威来源后建立桥；无run仍允许记录request，缺失字段保持null。 */
    public static void bridge(String requestId,String runId,String traceId,String taskId) {
        var runtime=AgentOperationsRuntime.current();if(runtime==null)return;
        try {
            var fields=new LinkedHashMap<String,Object>();fields.put("status","accepted");
            fields.put("requestKey",AgentOperationsEvent.identityKey(requestId));
            fields.put("runKey",AgentOperationsEvent.identityKey(runId));
            fields.put("traceKey",AgentOperationsEvent.identityKey(traceId));
            fields.put("taskKey",AgentOperationsEvent.identityKey(taskId));
            runtime.emit("bridge",fields);
        } catch(RuntimeException invalid) {runtime.emit("invalid",Map.of());}
    }

    /** 真正事务中的写出口登记afterCommit；未事务化的直接调用或回滚不得产生提交声明。 */
    public static void afterToolCommit(String tool,InternalV2ToolRequest request) {
        var runtime=AgentOperationsRuntime.current();
        if(runtime==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())return;
        try {
            var fields=toolFields(tool,request,"committed",null);
            // 在业务返回前仅登记；Spring完成数据库提交后才输出，同键重放仍使用原执行键。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {runtime.emit("tool",fields);}
            });
        } catch(RuntimeException invalid) {runtime.emit("invalid",Map.of());}
    }

    /** 已验证信封的失败出口，未知错误保留unknown，不能把失败当原业务未发生的证明。 */
    public static void failedTool(String tool,InternalV2ToolRequest request,String code) {
        var runtime=AgentOperationsRuntime.current();if(runtime==null || request==null)return;
        try {
            String status=Set.of("FORBIDDEN","UNAUTHORIZED","CONFLICT","VALIDATION_ERROR","EXPIRED","NOT_FOUND","RATE_LIMITED").contains(code)?"denied":"unknown";
            runtime.emit("tool",toolFields(tool,request,status,code));
        } catch(RuntimeException invalid) {runtime.emit("invalid",Map.of());}
    }

    /** 仅摘要原幂等键及内部随机身份，跨服务算法与Python完全相同。 */
    private static Map<String,Object> toolFields(String tool,InternalV2ToolRequest request,String status,String code) {
        var fields=new LinkedHashMap<String,Object>();fields.put("status",status);fields.put("toolName",tool);
        fields.put("protocolVersion",request.schemaVersion());fields.put("errorCode",code);
        fields.put("traceKey",AgentOperationsEvent.identityKey(request.traceId()));
        fields.put("runKey",AgentOperationsEvent.identityKey(request.runId()));
        fields.put("taskKey",AgentOperationsEvent.identityKey(request.taskId()));
        fields.put("executionKeyHash",AgentOperationsEvent.identityKey(request.idempotencyKey()));
        return fields;
    }
}
