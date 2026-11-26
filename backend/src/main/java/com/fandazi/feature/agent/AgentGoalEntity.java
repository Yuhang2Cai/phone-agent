// AG04公开目标实体：仅保存有限结果投影，私有合同、执行键和完整工具响应不进入Spring表。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 每个task/goalRevision/goalId只有一行，历史版本保留供核查，不能覆盖冒充当前结果。 */
@Entity @Table(name="agent_task_goals",uniqueConstraints=@UniqueConstraint(columnNames={"conversation_task_id","goal_revision","goal_id"}))
public class AgentGoalEntity {
    @Id @Column(length=100) private String id;
    @Column(name="conversation_task_id",nullable=false,length=100,updatable=false) private String taskId;
    @Column(name="goal_id",nullable=false,length=100,updatable=false) private String goalId;
    @Column(name="goal_revision",nullable=false,updatable=false) private long goalRevision;
    @Column(name="plan_revision",nullable=false,updatable=false) private long planRevision;
    // 同一目标修订会经历零到多个已提交业务效果；该列随任务权威效果版本单调前进，不能固定在候选创建时的零值。
    @Column(name="effect_version",nullable=false) private long effectVersion;
    @Column(nullable=false,length=30) private String status;
    @Convert(converter=StringMapJsonConverter.class)
    @Column(name="outcome_json",nullable=false,columnDefinition="TEXT") private Map<String,Object> outcome;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;

    /** 仅供JPA加载，不允许控制器自行构造实体。 */
    protected AgentGoalEntity() {}
    /** 从已通过2.3 Schema的公开结果创建不可变历史行。 */
    AgentGoalEntity(String taskId,long goalRevision,long planRevision,long effectVersion,Map<String,Object> outcome) {
        id=UUID.randomUUID().toString();this.taskId=taskId;this.goalRevision=goalRevision;this.planRevision=planRevision;
        this.effectVersion=effectVersion;goalId=String.valueOf(outcome.get("goalId"));status=String.valueOf(outcome.get("status"));
        this.outcome=new LinkedHashMap<>(outcome);createdAt=Instant.now();
    }
    /** 返回目标身份供同修订结果更新；仓储查询仍先限定task和goalRevision。 */
    String goalId(){return goalId;}
    /** 用同修订的确定性运行结果替换初始pending；目标/计划身份不变，效果版本只能追随已锁定任务单调前进。 */
    void updateOutcome(long planRevision,long effectVersion,Map<String,Object> value){
        if(!goalId.equals(value.get("goalId"))||goalRevision!=((Number)value.get("goalRevision")).longValue()
                // 调用方持有任务行锁并传入当前权威版本；目标行可以从旧效果追上它，但绝不能倒退或超过它。
                ||this.planRevision!=planRevision||planRevision!=((Number)value.get("planRevision")).longValue()
                ||this.effectVersion>effectVersion||effectVersion!=((Number)value.get("effectVersion")).longValue())
            throw new ToolBoundaryException("CONFLICT","目标运行结果版本不一致");
        this.effectVersion=effectVersion;status=String.valueOf(value.get("status"));outcome=new LinkedHashMap<>(value);
    }
}
