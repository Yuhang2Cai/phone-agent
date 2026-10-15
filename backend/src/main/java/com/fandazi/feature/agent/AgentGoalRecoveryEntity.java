// AG04目标请求实体：持久绑定scope、正文摘要、候选摘要和原回执，支持断网后同请求回读。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 同requestKey只允许同一指纹；请求行本身不是业务确认或工具执行许可。 */
@Entity @Table(name="agent_goal_recoveries",uniqueConstraints=@UniqueConstraint(columnNames="request_key"))
public class AgentGoalRecoveryEntity {
    @Id @Column(length=100) private String id;
    @Column(name="request_key",nullable=false,length=64,updatable=false) private String requestKey;
    @Column(name="request_id",nullable=false,length=100,updatable=false) private String requestId;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100,updatable=false) private String sessionId;
    @Column(nullable=false,length=100,updatable=false) private String epoch;
    @Column(name="conversation_task_id",nullable=false,length=100,updatable=false) private String taskId;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(name="candidate_hash",nullable=false,length=64,updatable=false) private String candidateHash;
    @Column(nullable=false,length=40,updatable=false) private String action;
    @Column(name="expected_goal_revision",nullable=false,updatable=false) private long expectedGoalRevision;
    @Column(name="expected_plan_revision",nullable=false,updatable=false) private long expectedPlanRevision;
    @Column(name="expected_effect_version",nullable=false,updatable=false) private long expectedEffectVersion;
    @Column(name="committed_goal_revision",nullable=false,updatable=false) private long committedGoalRevision;
    @Column(name="committed_plan_revision",nullable=false,updatable=false) private long committedPlanRevision;
    @Column(name="committed_effect_version",nullable=false,updatable=false) private long committedEffectVersion;
    @Column(nullable=false,length=30) private String state;
    @Convert(converter=StringMapJsonConverter.class)
    @Column(name="receipt_json",nullable=false,columnDefinition="TEXT") private Map<String,Object> receipt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;

    /** 仅供JPA加载。 */
    protected AgentGoalRecoveryEntity() {}
    /** 在版本CAS成功后一次性建立committed原回执。 */
    AgentGoalRecoveryEntity(String requestKey,String requestId,AgentTaskRegistryService.Scope scope,String taskId,
            String fingerprint,String candidateHash,long expectedGoal,long expectedPlan,long expectedEffect,
            long committedGoal,long committedPlan,long committedEffect,Map<String,Object> receipt) {
        id=UUID.randomUUID().toString();this.requestKey=requestKey;this.requestId=requestId;userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();
        this.taskId=taskId;this.fingerprint=fingerprint;this.candidateHash=candidateHash;action="revision";
        expectedGoalRevision=expectedGoal;expectedPlanRevision=expectedPlan;expectedEffectVersion=expectedEffect;
        committedGoalRevision=committedGoal;committedPlanRevision=committedPlan;committedEffectVersion=committedEffect;
        state="committed";this.receipt=new LinkedHashMap<>(receipt);createdAt=Instant.now();
    }
    /** 返回唯一scope请求键，供仓储比较。 */ String requestKey(){return requestKey;}
    /** 返回原请求指纹，同ID异体必须冲突。 */ String fingerprint(){return fingerprint;}
    /** 返回所属任务，不能借同requestId跨任务读取。 */ String taskId(){return taskId;}
    /** 返回不可变原回执副本。 */ Map<String,Object> receipt(){return new LinkedHashMap<>(receipt);}
}
