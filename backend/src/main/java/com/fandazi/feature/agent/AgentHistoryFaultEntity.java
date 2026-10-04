// 历史记录缺口的最小持久标记：服务重启后继续拒绝把已缺失内容的会话冒称完整恢复。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;

/** 仅私有数据库保存账户/会话及首次故障时间；不含正文、确认凭据或原始异常。 */
@Entity
@Table(name="agent_history_faults",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","session_id"}))
public class AgentHistoryFaultEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100) private String sessionId;
    @Column(name="created_at",nullable=false) private Instant createdAt;

    /** JPA无参入口，不用于客户端创建记录。 */
    protected AgentHistoryFaultEntity() {}

    /** 按服务端已校验归属建立首次缺口标记；修复历史前不能随下一次正常写入自动删除。 */
    public AgentHistoryFaultEntity(Long userId,String sessionId) {
        this.userId=userId;this.sessionId=sessionId;this.createdAt=Instant.now();
    }
}
