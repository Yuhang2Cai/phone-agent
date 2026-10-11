// 命令仓储模块：作用域内去重/重对账索引，不使用进程内Map替代持久状态。
package com.fandazi.feature.agent;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

/** 修改命令前由服务先锁会话及按ID排序的受影响task，再获取命令锁。 */
public interface AgentTaskCommandRepository extends JpaRepository<AgentTaskCommandEntity,String> {
    /** 同scope同requestId必须回读原决定；不同正文由服务层409拒绝。 */
    Optional<AgentTaskCommandEntity> findByUserIdAndSessionIdAndEpochAndRequestId(Long user,String session,String epoch,String request);
    /** 只允许在正确锁序内调用，不能先锁命令再等待会话。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentTaskCommandEntity c where c.id=:id")
    Optional<AgentTaskCommandEntity> lockById(@Param("id") String id);
    /** 有界读候选，随后在真实行锁下核验租约/退避；列表本身不代表取得派发资格。 */
    @Query("select c from AgentTaskCommandEntity c where c.state in ('prepared','agent_acknowledged','aborting') order by c.createdAt,c.id")
    List<AgentTaskCommandEntity> pending(Pageable page);
}
