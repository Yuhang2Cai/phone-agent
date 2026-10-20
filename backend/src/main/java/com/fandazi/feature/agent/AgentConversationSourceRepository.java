// 来源存储模块：查询已签发输入，不依据正文猜测重试身份。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 唯一约束配合会话行锁保证同范围顺序原子提交。 */
interface AgentConversationSourceRepository extends JpaRepository<AgentConversationSource,String> {
    /** 按已校验用户、会话、epoch 和真实重试键读取，不跨会话复用。 */
    Optional<AgentConversationSource> findByUserIdAndSessionIdAndEpochAndInputKey(Long userId,String sessionId,String epoch,String inputKey);

    /**
     * 把历史消息关联到紧邻输入事务签发的来源，同时由数据库复核用户、会话和当前 epoch。
     * 条件更新避免再次装载会话与来源实体；返回值必须为一，否则调用方回滚本次历史消息。
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true,clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("""
        update AgentConversationSource source set source.messageId=:messageId
        where source.sourceId=:sourceId and source.userId=:userId
          and source.sessionId=:sessionId and source.epoch=:epoch
          and (source.messageId is null or source.messageId=:messageId)
          and exists (select session.id from AgentSession session
              where session.sessionId=:sessionId and session.user.id=:userId
                and session.conversationEpoch=:epoch)
        """)
    int linkOwnedCurrent(
        @org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("sessionId") String sessionId,
        @org.springframework.data.repository.query.Param("epoch") String epoch,
        @org.springframework.data.repository.query.Param("sourceId") String sourceId,
        @org.springframework.data.repository.query.Param("messageId") String messageId);
}
