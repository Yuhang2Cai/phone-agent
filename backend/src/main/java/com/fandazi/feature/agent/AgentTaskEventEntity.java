// 公开任务事件持久模块：同scope单调事件ID和投递租约，私有grant/检查点正文不得写入。
package com.fandazi.feature.agent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 事件投递失败只重投同ID；业务/控制状态已经提交，不允许重新应用命令。 */
@Entity @Table(name="agent_task_event_outbox")
class AgentTaskEventEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100,updatable=false) private String sessionId;
    @Column(nullable=false,length=100,updatable=false) private String epoch;
    @Column(name="task_event_id",nullable=false,updatable=false) private long taskEventId;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="event_json",nullable=false,columnDefinition="TEXT") private Map<String,Object> event;
    @Column(name="delivery_state",nullable=false,length=30) private String deliveryState;
    @Column(name="lease_owner",length=100) private String leaseOwner;
    @Column(name="lease_until") private Instant leaseUntil;
    @Column(nullable=false) private int attempts;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    /** 仅供JPA恢复公开投递行。 */
    protected AgentTaskEventEntity() {}
    /** payload必须已经由权威taskEvent Schema验证，写入与目录版本共用事务。 */
    AgentTaskEventEntity(AgentTaskRegistryService.Scope scope,long sequence,Map<String,Object> event) {
        userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();taskEventId=sequence;
        this.event=new LinkedHashMap<>(event);deliveryState="pending";createdAt=Instant.now();
    }
    /** 返回数据库投递身份，不替代公开scope内taskEventId。 */
    Long id() {return id;}
    /** 返回已脱敏事件，所有投递尝试复用同一事件ID和内容。 */
    Map<String,Object> event() {return event;}
    /** 仅未投递且租约到期才可认领，投递成功不因租约到期而重新发送。 */
    boolean claimable(Instant now) {return "pending".equals(deliveryState)&&(leaseUntil==null||!leaseUntil.isAfter(now));}
    /** 同一短事务取得投递资格；实际Consumer调用在事务外。 */
    void claim(String owner,Instant now) {leaseOwner=owner;leaseUntil=now.plusSeconds(10);attempts++;}
    /** 核对租约所有者，旧投递回执不能覆盖下一次认领。 */
    boolean ownedLease(String owner) {return Objects.equals(owner,leaseOwner);}
    /** 成功标记sent；失败释放租约但保留事件，不能撤销业务或控制。 */
    void finish(boolean sent) {if(sent)deliveryState="sent";leaseOwner=null;leaseUntil=null;}
}
