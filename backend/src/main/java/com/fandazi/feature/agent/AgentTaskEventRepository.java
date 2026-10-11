// 任务outbox仓储：公开事件可按scope/水位补读，租约锁只保护投递行而不持有业务锁。
package com.fandazi.feature.agent;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 公开事件读取不调用模型或准备业务资格；命令失败不删除历史事件。 */
interface AgentTaskEventRepository extends JpaRepository<AgentTaskEventEntity,Long> {
    /** 只返回本scope游标之后的有界事件，不能跨epoch复用客户端水位。 */
    @Query("select e from AgentTaskEventEntity e where e.userId=:user and e.sessionId=:session and e.epoch=:epoch and e.taskEventId>:after order by e.taskEventId")
    List<AgentTaskEventEntity> after(@Param("user") Long user,@Param("session") String session,@Param("epoch") String epoch,@Param("after") long after,Pageable page);
    /** 查询保留边界，过期游标必须重读目录而不是默默漏事件。 */
    @Query("select min(e.taskEventId) from AgentTaskEventEntity e where e.userId=:user and e.sessionId=:session and e.epoch=:epoch")
    Long first(@Param("user") Long user,@Param("session") String session,@Param("epoch") String epoch);
    /** 列出有界投递候选，认领仍须行锁重新检查租约。 */
    List<AgentTaskEventEntity> findByDeliveryStateOrderByIdAsc(String state,Pageable page);
    /** 只有投递租约服务调用，禁止在此锁内等待网络/界面消费者。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from AgentTaskEventEntity e where e.id=:id")
    Optional<AgentTaskEventEntity> lockById(@Param("id") Long id);
}
