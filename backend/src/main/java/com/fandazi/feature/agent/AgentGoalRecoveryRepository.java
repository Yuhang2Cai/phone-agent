// AG04目标请求仓储：请求身份由可信scope摘要唯一，写入前已由会话行锁串行化同一scope。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 裸requestId不唯一，必须先结合可信scope生成requestKey。 */
public interface AgentGoalRecoveryRepository extends JpaRepository<AgentGoalRecoveryEntity,String> {
    /**
     * 按完整scope摘要键读取原请求。
     * 写事务进入这里前已经锁定所属session；同一requestKey必然属于同一session，因此无需再对不存在的唯一索引键加悲观范围锁。
     * 保持普通一致性读取可避免不同会话并发首写时在MySQL request_key空隙上互相死锁，数据库唯一约束仍是最终重复写保护。
     */
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
