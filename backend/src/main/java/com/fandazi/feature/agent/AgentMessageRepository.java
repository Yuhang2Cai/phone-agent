// Agent 消息模块：封装按会话读取消息。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Agent 消息仓储。 */
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {
    List<AgentMessageEntity> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);
    /** 服务端固定记录键查重，重复助手终态不得新增或覆盖已有消息。 */
    java.util.Optional<AgentMessageEntity> findByRecordKey(String recordKey);
    /** 单独冻结该表读取上界；无记录为0，不把事件ID和消息ID混用。 */
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id),0) from AgentMessageEntity e where e.user.id=:ownerId and e.sessionId=:sessionId")
    long maximumId(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,@org.springframework.data.repository.query.Param("sessionId") String sessionId);

    /** 最多读取请求页大小，不获取全部历史；固定上界使后续新行不混入旧页。 */
    @org.springframework.data.jpa.repository.Query("select e from AgentMessageEntity e where e.user.id=:ownerId and e.sessionId=:sessionId and e.id>:afterId and e.id<=:upperId order by e.id asc")
    List<AgentMessageEntity> snapshotPage(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,@org.springframework.data.repository.query.Param("sessionId") String sessionId,
        @org.springframework.data.repository.query.Param("afterId") long afterId,@org.springframework.data.repository.query.Param("upperId") long upperId,org.springframework.data.domain.Pageable page);
}
