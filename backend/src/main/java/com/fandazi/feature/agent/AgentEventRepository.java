// 智能助手模块：封装持久化查询与写入。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {
// 查询by、用户、id、会话、id、订单、by、id、asc。
    List<AgentEventEntity> findByUserIdAndSessionIdOrderByIdAsc(Long userId, String sessionId);
// 查询by、用户、id、会话、id、id、greater、than、订单、by、id、asc。
    List<AgentEventEntity> findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(Long userId, String sessionId, Long id);
    /** 单独冻结该表读取上界；无记录为0，不把事件ID和消息ID混用。 */
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id),0) from AgentEventEntity e where e.userId=:ownerId and e.sessionId=:sessionId")
    long maximumId(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,@org.springframework.data.repository.query.Param("sessionId") String sessionId);

    /** 最多读取请求页大小，不获取全部历史；固定上界使后续新行不混入旧页。 */
    @org.springframework.data.jpa.repository.Query("select e from AgentEventEntity e where e.userId=:ownerId and e.sessionId=:sessionId and e.id>:afterId and e.id<=:upperId order by e.id asc")
    List<AgentEventEntity> snapshotPage(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,@org.springframework.data.repository.query.Param("sessionId") String sessionId,
        @org.springframework.data.repository.query.Param("afterId") long afterId,@org.springframework.data.repository.query.Param("upperId") long upperId,org.springframework.data.domain.Pageable page);
}
