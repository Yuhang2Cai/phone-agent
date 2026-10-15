// AG04业务效果仓储：按任务和幂等键锁定原效果，禁止换键或异体覆盖。
package com.fandazi.feature.agent;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 所有写入由AgentGoalService持有任务锁后调用。 */
public interface AgentGoalEffectRepository extends JpaRepository<AgentGoalEffectEntity,String> {
    /** 锁定原效果用于同体重试判断。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentGoalEffectEntity e where e.taskId=:task and e.effectKey=:key")
    Optional<AgentGoalEffectEntity> lockByTaskAndKey(@Param("task") String taskId,@Param("key") String effectKey);
}
