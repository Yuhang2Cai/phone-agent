// AG04公开目标仓储：写入由AgentGoalService持有任务锁后完成，查询始终限定task和版本。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 只提供当前服务所需的版本查询；不能按裸goalId跨任务搜索。 */
public interface AgentGoalRepository extends JpaRepository<AgentGoalEntity,String> {
    /** 返回指定任务和目标版本的公开结果，用于重启后复核投影组成。 */
    List<AgentGoalEntity> findByTaskIdAndGoalRevision(String taskId,long goalRevision);
}
