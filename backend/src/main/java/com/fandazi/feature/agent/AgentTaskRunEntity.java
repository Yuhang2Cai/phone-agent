// task—run归属持久模块：终态run不可重开，数据库唯一键阻止同task两个在途run。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 只保存调度必要状态；Agent检查点及私有准备结果不复制进Spring公开目录。 */
@Entity @Table(name="agent_task_runs")
public class AgentTaskRunEntity {
    @Id @Column(name="run_id",length=100) private String id;
    @Column(name="conversation_task_id",nullable=false,length=100,updatable=false) private String taskId;
    @Column(nullable=false,length=30) private String status;
    @Column(name="inflight_task_key",length=100,unique=true) private String inflightTaskKey;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="finished_at") private Instant finishedAt;
    /** 仅供JPA恢复持久行。 */
    protected AgentTaskRunEntity() {}
    /** 新run由服务端分配，不能接受客户端指定ID或沿用终态run。 */
    AgentTaskRunEntity(String taskId) {
        id=UUID.randomUUID().toString();this.taskId=taskId;status="running";inflightTaskKey=taskId;createdAt=Instant.now();
    }
    /** 返回不可变运行身份。 */
    public String id() { return id; }
    /** 返回归属任务，防止将另一任务的检查点恢复进当前焦点。 */
    public String taskId() { return taskId; }
    /** 返回执行停点状态，暂停调度本身不会伪装run成功。 */
    public String status() { return status; }
    /** 终态run只能回读原结果，不可再次进入执行。 */
    public boolean terminal() { return Set.of("succeeded","cancelled","failed").contains(status); }
    /** 保存合法停点；终态改变由注册表拒绝，重复相同终态是幂等回读。 */
    void stop(String value) {
        status=value;
        if(terminal()) { inflightTaskKey=null;finishedAt=Instant.now(); }
    }
}
