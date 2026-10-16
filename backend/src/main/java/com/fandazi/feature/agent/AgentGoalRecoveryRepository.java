// AG04目标请求仓储：唯一请求读取使用数据库行锁，跨进程重复提交只能回原结果或冲突。
package com.fandazi.feature.agent;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 裸requestId不唯一，必须先结合可信scope生成requestKey。 */
public interface AgentGoalRecoveryRepository extends JpaRepository<AgentGoalRecoveryEntity,String> {
    /** 在session/task锁后锁请求行，保持统一锁序并处理同体重试。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentGoalRecoveryEntity r where r.requestKey=:key")
    Optional<AgentGoalRecoveryEntity> lockByRequestKey(@Param("key") String requestKey);
    /** 只读回查仍使用完整scope摘要键，不暴露其他账号请求存在性。 */
    Optional<AgentGoalRecoveryEntity> findByRequestKey(String requestKey);
    /**
     * 任务行锁内查询同一三版本尚未消费的公开恢复动作。
     * 该查询阻止客户端通过不断更换requestId并行建立多轮恢复；真正执行完成后由后续协调器推进版本或状态。
     */
    @Query("select r from AgentGoalRecoveryEntity r where r.taskId=:taskId and r.expectedGoalRevision=:goal " +
        "and r.expectedPlanRevision=:plan and r.expectedEffectVersion=:effect and r.state='accepted'")
    java.util.List<AgentGoalRecoveryEntity> findAccepted(@Param("taskId") String taskId,@Param("goal") long goal,
        @Param("plan") long plan,@Param("effect") long effect);
}
