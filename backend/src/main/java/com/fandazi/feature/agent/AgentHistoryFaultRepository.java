// 历史恢复缺口仓库：只按账户和会话查故障事实，不读取正文或返回执行授权。
package com.fandazi.feature.agent;

import org.springframework.data.jpa.repository.JpaRepository;

/** 数据库唯一归属约束保证并发故障标记仍只有一条。 */
public interface AgentHistoryFaultRepository extends JpaRepository<AgentHistoryFaultEntity,Long> {
    /** 读取首次记录缺口事实，Spring重建服务对象后仍可定位不可完整恢复的会话。 */
    boolean existsByUserIdAndSessionId(Long userId,String sessionId);
}
