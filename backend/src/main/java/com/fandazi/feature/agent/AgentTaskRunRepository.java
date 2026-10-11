// 任务运行仓储：同task历史可读，在途唯一性由数据库约束兜底。
package com.fandazi.feature.agent;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 先核验任务归属，再允许读取/锁定其run；runId本身不是权限。 */
public interface AgentTaskRunRepository extends JpaRepository<AgentTaskRunEntity,String> {
    /** 仅查所属任务的运行历史，用于停点和终态不可变核验。 */
    List<AgentTaskRunEntity> findByTaskId(String taskId);
    /** 锁序必须在session与task之后，不能反向获取会话锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentTaskRunEntity r where r.id=:id")
    Optional<AgentTaskRunEntity> lockById(@Param("id") String id);
}
