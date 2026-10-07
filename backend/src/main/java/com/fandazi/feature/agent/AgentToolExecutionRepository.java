// 工具执行仓储模块：按执行器动作键查询内部结果，并统计会话准备工具速率。
package com.fandazi.feature.agent;

import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话锁由桥接层持有，唯一执行键再防止跨会话伪造重复动作。 */
interface AgentToolExecutionRepository extends JpaRepository<AgentToolExecution, String> {
    /** 只统计指定时间窗口内的凭证准备动作，拒绝滥用而不影响真实查询。 */
    long countBySessionIdAndToolNameInAndCreatedAtAfter(String sessionId, Collection<String> toolNames, Instant since);
}
