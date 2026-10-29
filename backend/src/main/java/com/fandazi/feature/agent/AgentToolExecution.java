// 工具执行持久化模块：记录执行器动作键、请求指纹及内部结果，跨重启重试不会重复业务写入。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

/** 仅供内部重放的执行记录；结果可能含确认凭证，公开审计必须另经脱敏器。 */
@Entity
@Table(name = "agent_tool_executions")
class AgentToolExecution {
    @Id @Column(name = "execution_key", length = 255) private String executionKey;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "tool_name", nullable = false, length = 80) private String toolName;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    // 目录型只读工具会返回数十个真实对象，序列化结果可能超过 MySQL TEXT 的 64 KiB 上限。
    // 执行记录承担跨进程幂等重放，必须保存完整成功结果，不能截断或只存摘要。
    @Convert(converter = StringMapJsonConverter.class) @Column(name = "result_json", nullable = false, columnDefinition = "LONGTEXT")
    private Map<String, Object> result;
    // 旧执行记录允许为空，新工具回查必须匹配完整任务范围/原run/turn，当前焦点不替代原归属。
    @Column(name="conversation_task_id",length=100) private String conversationTaskId;
    @Column(name="execution_generation") private Long executionGeneration;
    @Column(name="conversation_epoch",length=100) private String conversationEpoch;
    @Column(name="run_id",length=100) private String runId;
    @Column(name="turn_id",length=100) private String turnId;
    // 仅目标协议的成功工具保存已校验围栏，供交付判断目标是否移除；不向公开审计返回。
    @Convert(converter = StringMapJsonConverter.class) @Column(name="knowledge_goal_fence",columnDefinition="LONGTEXT")
    private Map<String,Object> knowledgeGoalFence;
    // 只保存位置是否缺失，不增加用户位置明文副本；原参数已通过工具Schema校验。
    @Column(name="knowledge_location_absent") private Boolean knowledgeLocationAbsent;
    /** JPA 使用空构造器恢复已经提交的记录。 */
    protected AgentToolExecution() {}
    /** 保存完整成功结果；事务提交前由契约校验，失败动作不占用成功幂等键。 */
    AgentToolExecution(InternalV1ToolRequest request, Long userId, String tool, String fingerprint, Map<String, Object> result) {
        this.executionKey = request.idempotencyKey(); this.userId = userId; this.sessionId = request.sessionId();
        this.toolName = tool; this.fingerprint = fingerprint; this.result = result; this.createdAt = Instant.now();
        // V1同样保存已核验的run身份，知识组合交付只引用本轮成功事实，不能按答案反推归属。
        this.runId = request.runId();
        if("search_stores".equals(tool)) {
            var input=request.payload();
            knowledgeLocationAbsent=(!(input.get("cityCode") instanceof String city) || city.isBlank())
                && !(input.get("latitude") instanceof Number && input.get("longitude") instanceof Number);
        }
    }
    /** 新传输保存原任务证据；领域结果仍仅内部保存，公开receipt只取安全白名单。 */
    AgentToolExecution(InternalV2ToolRequest request,Long user,String tool,String hash,Map<String,Object> data){
        this(request.domainRequest(),user,tool,hash,data);conversationTaskId=(String)request.taskFence().get("conversationTaskId");
        executionGeneration=AgentTaskFingerprint.version(request.taskFence().get("executionGeneration"));
        @SuppressWarnings("unchecked") var scope=(Map<String,Object>)request.taskFence().get("scope");conversationEpoch=(String)scope.get("conversationEpoch");
        runId=request.runId();turnId=(String)request.taskFence().get("turnId");
        if("2.1".equals(request.schemaVersion()))knowledgeGoalFence=new java.util.LinkedHashMap<>(request.goalFence());
    }
    /** 核验原执行归属；已提交事实可在当前任务暂停/终态后回读，不授予任何执行资格。 */
    boolean ownedTask(AgentTaskRegistryService.Scope scope,String task,String run,String turn){
        return userId.equals(scope.userId())&&sessionId.equals(scope.sessionId())&&java.util.Objects.equals(conversationEpoch,scope.epoch())
            &&java.util.Objects.equals(conversationTaskId,task)&&java.util.Objects.equals(runId,run)&&java.util.Objects.equals(turnId,turn);
    }
    /** 已提交工具类型供安全receipt关联，不接受请求方重新指定工具。 */
    String tool(){return toolName;}
    /** 返回原请求摘要供重试核对，任何载荷变化都不能冒充原动作。 */
    String getFingerprint() { return fingerprint; }
    /** 知识缓存只能复用原目标集合；未知旧归属不能被新围栏补填，目标变化必须分配新动作键。 */
    boolean sameKnowledgeGoals(Map<String,Object> fence) {
        if(knowledgeGoalFence==null || !(knowledgeGoalFence.get("goalIds") instanceof java.util.List<?> original)
                || fence==null || !(fence.get("goalIds") instanceof java.util.List<?> current))return false;
        return new java.util.HashSet<>(original).equals(new java.util.HashSet<>(current));
    }
    /** 返回实际已提交结果；重放不再次消费凭证，也不执行领域写入。 */
    Map<String, Object> getResult() { return result; }
}
