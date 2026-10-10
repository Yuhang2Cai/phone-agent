// 来源存储模块：查询已签发输入，不依据正文猜测重试身份。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 唯一约束配合会话行锁保证同范围顺序原子提交。 */
interface AgentConversationSourceRepository extends JpaRepository<AgentConversationSource,String> {
    /** 按已校验用户、会话、epoch 和真实重试键读取，不跨会话复用。 */
    Optional<AgentConversationSource> findByUserIdAndSessionIdAndEpochAndInputKey(Long userId,String sessionId,String epoch,String inputKey);
}
