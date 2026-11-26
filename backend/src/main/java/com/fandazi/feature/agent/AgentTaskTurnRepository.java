// 普通轮次仓储模块：持久查重与执行占位，不按租约到期自动新建第二轮执行。
package com.fandazi.feature.agent;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 锁序在session/task之后、command之前，保证控制与业务提交竞争相同资源。 */
public interface AgentTaskTurnRepository extends JpaRepository<AgentTaskTurnEntity,String> {
    /** 同scope请求只受理一次，重试回读最初turn及完成回执。 */
    Optional<AgentTaskTurnEntity> findByUserIdAndSessionIdAndEpochAndRequestId(Long user,String session,String epoch,String request);
    /** 查询仍占执行资格的turn；过期占位只能对账，不能盲目重放。 */
    Optional<AgentTaskTurnEntity> findByLeaseTaskKey(String taskId);
    /** 会话与任务锁之后按turnId顺序获取受影响租约，供控制预备及安全释放。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTaskTurnEntity t where t.taskId in :ids and t.leaseTaskKey is not null order by t.id")
    List<AgentTaskTurnEntity> lockLeases(@Param("ids") Collection<String> taskIds);
    /** 完成入口先核验scope/task，再锁此turn；不接受先锁turn再等待session。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTaskTurnEntity t where t.id=:id")
    Optional<AgentTaskTurnEntity> lockById(@Param("id") String id);
}
