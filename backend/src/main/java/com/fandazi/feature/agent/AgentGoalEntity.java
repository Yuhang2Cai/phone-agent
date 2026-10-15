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
    @Column(name="effect_version",nullable=false,updatable=false) private long effectVersion;
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
    /** 用同修订的确定性运行结果替换初始pending；目标身份和三版本不可借本方法改变。 */
    void updateOutcome(long planRevision,long effectVersion,Map<String,Object> value){
        if(!goalId.equals(value.get("goalId"))||goalRevision!=((Number)value.get("goalRevision")).longValue()
                // 调用方提供的期望版本、数据库当前版本和结果载荷版本必须三方一致。
                ||this.planRevision!=planRevision||planRevision!=((Number)value.get("planRevision")).longValue()
                ||this.effectVersion!=effectVersion||effectVersion!=((Number)value.get("effectVersion")).longValue())
            throw new ToolBoundaryException("CONFLICT","目标运行结果版本不一致");
        status=String.valueOf(value.get("status"));outcome=new LinkedHashMap<>(value);
    }
}
