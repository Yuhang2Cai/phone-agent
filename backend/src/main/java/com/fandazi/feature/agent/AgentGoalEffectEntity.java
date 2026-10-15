// AG04业务效果实体：记录已提交效果的安全回执摘要，并与effectVersion递增同事务。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** effectKey在单任务内唯一；重复同体回读，换正文不能覆盖已提交业务事实。 */
@Entity @Table(name="agent_goal_effects",uniqueConstraints=@UniqueConstraint(columnNames={"conversation_task_id","effect_key"}))
public class AgentGoalEffectEntity {
    @Id @Column(length=100) private String id;
    @Column(name="effect_key",nullable=false,length=255,updatable=false) private String effectKey;
    @Column(name="conversation_task_id",nullable=false,length=100,updatable=false) private String taskId;
    @Column(name="goal_revision",nullable=false,updatable=false) private long goalRevision;
    @Column(name="plan_revision",nullable=false,updatable=false) private long planRevision;
    @Column(name="effect_version",nullable=false,updatable=false) private long effectVersion;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Convert(converter=StringMapJsonConverter.class)
    @Column(name="receipt_json",nullable=false,columnDefinition="TEXT",updatable=false) private Map<String,Object> receipt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    /** 仅供JPA加载。 */ protected AgentGoalEffectEntity() {}
    /** 从已提交业务的安全回执创建不可变效果行。 */
    AgentGoalEffectEntity(String taskId,String effectKey,long goalRevision,long planRevision,long effectVersion,String fingerprint,Map<String,Object> receipt) {
        id=UUID.randomUUID().toString();this.taskId=taskId;this.effectKey=effectKey;this.goalRevision=goalRevision;this.planRevision=planRevision;
        this.effectVersion=effectVersion;this.fingerprint=fingerprint;this.receipt=new LinkedHashMap<>(receipt);createdAt=Instant.now();
    }
    /** 返回原请求指纹供幂等比较。 */ String fingerprint(){return fingerprint;}
    /** 返回原效果版本。 */ long effectVersion(){return effectVersion;}
}
